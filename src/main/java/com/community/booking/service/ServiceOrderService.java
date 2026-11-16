package com.community.booking.service;

import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.OrderCreateDTO;
import com.community.booking.dto.OrderPageQueryDTO;
import com.community.booking.vo.OrderVO;
import com.community.booking.vo.PageVO;

/**
 * 服务订单业务接口：居民下单/取消、师傅推进工单、管理员派单/改派与查询。
 * <p>状态机（0待派单→1已派单→2服务中→3已完成，0/1 可取消→4已取消）的全部
 * 写入口集中在本接口；Batch 2 先落地"状态前置校验 + 条件 UPDATE 原子推进"
 * （UPDATE ... WHERE order_status = 期望前态）保证并发幂等，Batch 3 起再叠加
 * Redis 分布式锁 community:order:lock:{orderNo} 与 MQ 超时取消，方法签名不变。</p>
 * <p>越权铁律：查询归属（residentId/workerId）与状态迁移的发起者身份一律取
 * 当前登录用户注入，严禁信任前端直传的 userId；管理员视角才允许跨归属操作。</p>
 *
 * @author community-booking
 */
public interface ServiceOrderService {

    /**
     * 三视角订单分页：按当前登录角色自动注入归属条件。
     * <p>ROLE_RESIDENT 只看自己下单的订单；ROLE_WORKER 只看指派给自己的工单；
     * ROLE_ADMIN 全量并可按分类/项目过滤（派单中心）。返回的 canCancel/
     * canEvaluate 对管理员视角置 null（表达"非本人操作视角"）。</p>
     *
     * @param query       分页查询入参（状态/分类/项目/关键字）
     * @param currentUser 当前登录用户（决定归属过滤与动作标记视角）
     * @return 订单 VO 分页
     */
    PageVO<OrderVO> pageOrders(OrderPageQueryDTO query, LoginUserDTO currentUser);

    /**
     * 订单详情：管理员可看全部；居民仅可看自己下单的订单；师傅仅可看指派给
     * 自己的工单；其余一律按 403 越权拦截（IDOR 测试重点）。
     *
     * @param id          订单ID
     * @param currentUser 当前登录用户
     * @return 订单 VO
     */
    OrderVO getOrderDetail(Long id, LoginUserDTO currentUser);

    /**
     * 居民下单：价格由服务端现查 service_item 快照、订单号服务端生成、
     * 初始状态待派单；下单成功后热度 +1 并（Batch 4 起）投递 30 分钟延迟
     * 消息用于超时未派单自动取消。
     *
     * @param dto         下单入参（项目/预约时间/地址/电话/备注）
     * @param currentUser 当前登录居民
     * @return 新订单ID
     */
    Long createOrder(OrderCreateDTO dto, LoginUserDTO currentUser);

    /**
     * 居民取消订单：仅本人且仅待派单(0)态可取消（已派单后需走管理员改派/取消，
     * 避免师傅已上门却单方面取消）。取消后联动释放被占用师傅的忙碌状态。
     *
     * @param id          订单ID
     * @param reason      取消原因（追记到 remark，可空）
     * @param currentUser 当前登录居民
     */
    void cancelOrder(Long id, String reason, LoginUserDTO currentUser);

    /**
     * 管理员派单（智能或手动）：把待派单订单指派给指定师傅，写入 dispatch_type
     * 并把该师傅置忙。Batch 4 落地智能算法后本方法承接"候选确认"入口。
     *
     * @param orderId     订单ID（须为待派单态）
     * @param workerId    承接师傅用户ID（须启用、非休息、技能覆盖服务所需）
     * @param dispatchType 派单方式 AUTO/MANUAL/REASSIGN（由调用场景决定）
     * @param currentUser 当前登录管理员
     */
    void dispatchOrder(Long orderId, Long workerId, String dispatchType, LoginUserDTO currentUser);

    /**
     * 管理员改派：更换"已派单未开工"订单的承接师傅；旧师傅释放忙碌、
     * 新师傅置忙，dispatch_type 记为 REASSIGN。
     *
     * @param orderId     订单ID（须为已派单态）
     * @param newWorkerId 新承接师傅用户ID
     * @param currentUser 当前登录管理员
     */
    void reassignOrder(Long orderId, Long newWorkerId, LoginUserDTO currentUser);

    /**
     * 师傅开始服务：仅承接本人、仅已派单(1)态可推进为服务中(2)。
     *
     * @param id          订单ID
     * @param currentUser 当前登录师傅
     */
    void startService(Long id, LoginUserDTO currentUser);

    /**
     * 师傅完成服务：仅承接本人、仅服务中(2)态可推进为已完成(3)，写入
     * finish_time 并在同一事务内累计该师傅完成数 +1、联动刷新接单状态。
     *
     * @param id          订单ID
     * @param currentUser 当前登录师傅
     */
    void completeService(Long id, LoginUserDTO currentUser);

    /**
     * 管理员取消订单（运营兜底：待派单/已派单两态可由管理员取消，
     * 服务中订单不允许单方面取消，须由师傅完成或改派处置）。
     *
     * @param id     订单ID
     * @param reason 取消原因（追记 remark）
     * @param currentUser 当前登录管理员
     */
    void adminCancelOrder(Long id, String reason, LoginUserDTO currentUser);

    /**
     * 超时未派单自动取消（Batch 4：appoint.cancel.queue 消费者调用）。
     * <p>无登录态：由 MQ 消费者触发，取消主体是"系统"。与居民自助取消共用
     * 同一原子口径（条件 UPDATE WHERE order_status=待派单），因此并发下
     * 只有一方生效，另一方得到 false 并正常确认消息，不会重复取消。</p>
     * <p>方法内部会抢订单分布式锁（与派单/改派/开工互斥），并在事务提交后
     * 投递取消通知邮件——避免出现"管理员刚派单成功，取消消息同时把单撤了"。</p>
     *
     * @param orderId 订单ID
     * @return true=本次真的完成了 待派单 -> 已取消 迁移；
     *         false=订单已流转（已派单/已完成/已取消）或不存在，属于幂等命中
     */
    boolean cancelTimeoutOrder(Long orderId);
}
