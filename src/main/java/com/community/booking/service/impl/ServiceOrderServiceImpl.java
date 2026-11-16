package com.community.booking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.OrderCreateDTO;
import com.community.booking.dto.OrderPageQueryDTO;
import com.community.booking.entity.ServiceItem;
import com.community.booking.entity.ServiceOrder;
import com.community.booking.entity.SysUser;
import com.community.booking.entity.WorkerProfile;
import com.community.booking.exception.BusinessException;
import com.community.booking.exception.ForbiddenException;
import com.community.booking.exception.NotFoundException;
import com.community.booking.mapper.ServiceItemMapper;
import com.community.booking.mapper.ServiceOrderMapper;
import com.community.booking.mapper.SysUserMapper;
import com.community.booking.mapper.WorkerProfileMapper;
import com.community.booking.mq.AppointMessageSender;
import com.community.booking.mq.message.OrderNotifyMessage;
import com.community.booking.mq.message.OrderTimeoutMessage;
import com.community.booking.service.ServiceOrderService;
import com.community.booking.service.WorkerProfileService;
import com.community.booking.utils.Constants;
import com.community.booking.utils.RedisCacheUtils;
import com.community.booking.vo.OrderVO;
import com.community.booking.vo.PageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 服务订单业务实现：本系统最核心的状态机与越权防线所在。
 * <p>状态机推进统一采用"条件 UPDATE"（UPDATE ... SET 新态 WHERE id=? AND
 * order_status=期望前态），受影响行数为 0 即说明并发已抢态或状态不满足，
 * 直接抛业务异常回滚——这一数据库层原子判定天然幂等，即使同一请求被重复提交
 * 也只有一次能成功推进。</p>
 * <p><b>Batch 3 叠加 Redis 分布式锁（community:order:lock:{orderNo}）构成双防线</b>：
 * 所有状态迁移写入口先抢订单锁，抢不到直接以 400"正在被其他操作处理"快速拒绝，
 * 让绝大多数并发冲突在应用层就被挡掉、不打数据库；条件 UPDATE 仍是最终幂等保证
 * （Redis 故障时 tryLock 降级放行，仅靠条件 UPDATE 兜底，正确性不降级）。
 * 锁的释放挂在事务 afterCompletion 上——提交与回滚都释放：若挂在 afterCommit 上，
 * 回滚时锁会滞留到 TTL 到期，把正常用户挡在门外；若在读之后就释放，则并发请求
 * 可能读到未提交数据后误判状态，故必须活到事务结束。</p>
 * <p>越权铁律：查询归属（resident_id/worker_id）与写操作的发起者一律取当前
 * 登录用户，任何方法都不接受前端传入的 userId；管理员的跨归属操作必须以
 * ROLE_ADMIN 为前置条件。</p>
 *
 * @author community-booking
 */
@Slf4j
@Service
public class ServiceOrderServiceImpl implements ServiceOrderService {

    /** 订单状态中文映射（下标即状态值 0~4） */
    private static final String[] ORDER_STATUS_TEXT = {
            "待派单", "已派单", "服务中", "已完成", "已取消"
    };

    /** 预约时间允许的最远天数（防止居民误填一年后的日期长期占用排期） */
    private static final int MAX_APPOINT_DAYS = 30;

    /** 订单号当日流水位数（4 位，配合日期前缀保证唯一且可读） */
    private static final int ORDER_NO_SEQ_WIDTH = 4;

    /** 订单号日期段格式（WO + yyyyMMdd + 4位流水） */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 消息体内的时间展示格式（仅用于日志追踪，不参与超时裁决） */
    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 订单号生成重试上限（并发撞唯一索引时换号重试，超过即视为系统异常） */
    private static final int ORDER_NO_RETRY = 8;

    /** 死锁重试的基础退避毫秒数（按轮次线性放大，让并发事务错开时间窗） */
    private static final long RETRY_BACKOFF_MILLIS = 15L;

    /** 死锁重试的随机抖动上限（毫秒）：打散同一时刻集中重试的惊群效应 */
    private static final long RETRY_JITTER_MILLIS = 20L;

    /** 订单号序列冷启动播种锁的持有秒数（只需覆盖一次 count 查询，给足余量防抖动） */
    private static final long SEED_LOCK_SECONDS = 5L;

    /** 订单 Mapper（主表与三视角联表分页数据源） */
    private final ServiceOrderMapper orderMapper;
    /** 服务项目 Mapper（下单价格快照与技能校验） */
    private final ServiceItemMapper itemMapper;
    /** 用户 Mapper（派单目标身份与状态校验） */
    private final SysUserMapper userMapper;
    /** 师傅档案 Mapper（技能覆盖与空闲判定） */
    private final WorkerProfileMapper workerProfileMapper;
    /** 师傅档案业务（状态联动刷新统一收口） */
    private final WorkerProfileService workerProfileService;
    /** Redis 缓存与锁原语（订单状态机分布式锁 + 热门榜 ZINCRBY 双轨） */
    private final RedisCacheUtils redisCacheUtils;
    /** MQ 消息发送器（超时延迟消息 + 订单通知 + 评价绩效重算） */
    private final AppointMessageSender messageSender;
    /** 订单锁持有超时秒数（配置 community.order.lock-seconds，契约 30 秒） */
    private final long lockSeconds;

    /**
     * 编程式事务模板：下单需要"事务整体重试"能力。
     * <p>并发 INSERT 同一张表时 InnoDB 可能判定死锁并牺牲其中一个事务，
     * 这是数据库层面的可重试故障（被牺牲者已整体回滚、无脏数据）。
     * 但异常一旦穿过 {@code @Transactional} 边界，事务就被标记 rollback-only，
     * 在方法内部重试是无效的——必须让整个事务重新开启。</p>
     */
    private final TransactionTemplate transactionTemplate;

    /**
     * 构造注入依赖。
     *
     * @param orderMapper           订单 Mapper
     * @param itemMapper            项目 Mapper
     * @param userMapper            用户 Mapper
     * @param workerProfileMapper   师傅档案 Mapper
     * @param workerProfileService  师傅档案业务
     * @param redisCacheUtils       Redis 锁与热度原语
     * @param messageSender         MQ 消息发送器
     * @param lockSeconds           订单锁持有秒数（配置注入，契约 30 秒）
     */
    public ServiceOrderServiceImpl(ServiceOrderMapper orderMapper, ServiceItemMapper itemMapper,
                                   SysUserMapper userMapper, WorkerProfileMapper workerProfileMapper,
                                   WorkerProfileService workerProfileService, RedisCacheUtils redisCacheUtils,
                                   AppointMessageSender messageSender,
                                   @Value("${community.order.lock-seconds:30}") long lockSeconds,
                                   TransactionTemplate transactionTemplate) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.userMapper = userMapper;
        this.workerProfileMapper = workerProfileMapper;
        this.workerProfileService = workerProfileService;
        this.redisCacheUtils = redisCacheUtils;
        this.messageSender = messageSender;
        this.lockSeconds = lockSeconds;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 三视角订单分页：归属条件由登录角色决定，严禁前端直传。
     *
     * @param query       查询入参
     * @param currentUser 当前登录用户
     * @return 订单 VO 分页
     */
    @Override
    public PageVO<OrderVO> pageOrders(OrderPageQueryDTO query, LoginUserDTO currentUser) {
        if (currentUser == null || currentUser.getUserId() == null) {
            throw new ForbiddenException("请先登录后再查看订单");
        }
        long pageNum = query == null || query.getPageNum() == null
                ? Constants.DEFAULT_PAGE_NUM : query.getPageNum();
        long pageSize = query == null || query.getPageSize() == null
                ? Constants.DEFAULT_PAGE_SIZE : Math.min(query.getPageSize(), Constants.MAX_PAGE_SIZE);
        Integer statusFilter = validateStatus(query == null ? null : query.getOrderStatus());
        Page<OrderVO> page = new Page<>(pageNum, pageSize);
        String kw = normalizeKw(query == null ? null : query.getKeyword());
        Long residentId = null;
        Long workerId = null;
        Long viewerId = currentUser.getUserId();
        // 归属注入：居民看自己下的单，师傅看指派给自己的单，管理员全量且可跨类筛选
        if (Constants.ROLE_RESIDENT.equals(currentUser.getRole())) {
            residentId = viewerId;
        } else if (Constants.ROLE_WORKER.equals(currentUser.getRole())) {
            workerId = viewerId;
        } else if (!Constants.ROLE_ADMIN.equals(currentUser.getRole())) {
            throw new ForbiddenException("当前角色无权查看订单列表");
        } else {
            // 管理员视角：动作标记（可取消/可评价）无意义，SQL 里 viewerId 传 null 恒为 0，
            // 再由本方法置 null 表达"非本人操作视角"，前端据此不渲染居民动作按钮
            viewerId = null;
        }
        IPage<OrderVO> result = orderMapper.selectOrderPage(page, kw, statusFilter,
                query == null ? null : query.getCategoryId(), query == null ? null : query.getServiceId(),
                residentId, workerId, viewerId);
        decorate(result.getRecords(), Constants.ROLE_ADMIN.equals(currentUser.getRole()));
        return PageVO.of(result);
    }

    /**
     * 订单详情（IDOR 测试重点：非归属且非管理员一律 403）。
     *
     * @param id          订单ID
     * @param currentUser 当前登录用户
     * @return 订单 VO
     */
    @Override
    public OrderVO getOrderDetail(Long id, LoginUserDTO currentUser) {
        ServiceOrder order = requireOrder(id);
        checkViewPermission(order, currentUser);
        Long viewerId = Constants.ROLE_ADMIN.equals(currentUser.getRole()) ? null : currentUser.getUserId();
        OrderVO vo = orderMapper.selectOrderDetailById(id, viewerId);
        if (vo == null) {
            throw new NotFoundException("服务订单不存在或已被清理");
        }
        decorate(List.of(vo), Constants.ROLE_ADMIN.equals(currentUser.getRole()));
        return vo;
    }

    /**
     * 居民下单：状态 0、价格快照、订单号服务端生成、热度 +1，单事务。
     * <p><b>为什么用编程式事务</b>：高并发下单时多个事务同时向 service_order
     * 插入新行，InnoDB 在唯一索引间隙上仍可能判定死锁并牺牲其中一方。这个死锁
     * 是数据库层的瞬时竞争（被牺牲的事务已整体回滚），重做一遍即可成功。但若
     * 沿用 {@code @Transactional} 注解，异常穿过事务边界后当前事务已标记
     * rollback-only，在方法体内循环重试没有任何意义；因此这里改用
     * {@link TransactionTemplate}，每轮重试都是独立的全新事务。</p>
     *
     * @param dto         下单入参
     * @param currentUser 当前登录居民
     * @return 新订单ID
     */
    @Override
    public Long createOrder(OrderCreateDTO dto, LoginUserDTO currentUser) {
        if (currentUser == null || !Constants.ROLE_RESIDENT.equals(currentUser.getRole())) {
            throw new ForbiddenException("只有社区居民可以提交预约单");
        }
        // 项目必须真实存在且处于上架状态（下架项目不可下单，与列表可见性口径一致）
        ServiceItem item = itemMapper.selectById(dto.getServiceId());
        if (item == null) {
            throw new NotFoundException("所选服务项目不存在");
        }
        if (Constants.ITEM_OFF == item.getStatus()) {
            throw new BusinessException(400, "该服务已下架，暂不可预约");
        }
        // 预约时间二次校验：@Future 只保证晚于当前，这里再限制最长 30 天
        LocalDateTime appoint = dto.getAppointTime();
        if (appoint.isBefore(LocalDateTime.now())) {
            throw new BusinessException(400, "预约上门时间必须晚于当前时间");
        }
        if (appoint.isAfter(LocalDateTime.now().plusDays(MAX_APPOINT_DAYS))) {
            throw new BusinessException(400, "预约上门时间最多可选 " + MAX_APPOINT_DAYS + " 天内");
        }
        // 参数校验全部前置到事务外：非法入参不该占用一次事务与数据库连接
        Long orderId = null;
        for (int attempt = 1; attempt <= ORDER_NO_RETRY; attempt++) {
            try {
                orderId = transactionTemplate.execute(status -> insertOrder(dto, item, appoint, currentUser));
                break;
            } catch (DeadlockLoserDataAccessException | CannotAcquireLockException e) {
                // 死锁败者已被整体回滚，无脏数据；短暂错峰后退让重试即可
                log.warn("下单事务发生死锁（第{}/{}次，事务已回滚，重试）: {}",
                        attempt, ORDER_NO_RETRY, e.getMessage());
                if (attempt == ORDER_NO_RETRY) {
                    throw new BusinessException(409, "当前下单人数较多，请稍后重试");
                }
                sleepBeforeRetry(attempt);
            }
        }
        if (orderId == null) {
            throw new BusinessException(500, "系统繁忙，请稍后重试");
        }
        log.info("居民[{}]下单成功 orderId={} serviceId={}", currentUser.getUsername(), orderId, item.getId());
        return orderId;
    }

    /**
     * 下单事务体：插入订单 + 项目热度递增 + 事务提交后投递超时延迟消息。
     *
     * @param dto         下单入参
     * @param item        已校验的服务项目
     * @param appoint     已校验的预约时间
     * @param currentUser 当前登录居民
     * @return 新订单ID
     */
    private Long insertOrder(OrderCreateDTO dto, ServiceItem item, LocalDateTime appoint, LoginUserDTO currentUser) {
        ServiceOrder order = new ServiceOrder();
        order.setResidentId(currentUser.getUserId());
        order.setServiceId(item.getId());
        // 待派单态无承接师傅，由管理员派单中心后续指派
        order.setWorkerId(null);
        order.setAppointTime(appoint);
        order.setServiceAddress(dto.getServiceAddress().trim());
        order.setContactPhone(dto.getContactPhone().trim());
        order.setRemark(trimOrNull(dto.getRemark()));
        // 价格快照：以服务端现查的项目挂牌价为准，前端传价一律无效（防篡改）
        order.setPrice(item.getPrice());
        order.setOrderStatus(Constants.ORDER_PENDING);
        order.setDispatchType(null);
        order.setFinishTime(null);
        order.setIsArchived(Constants.NOT_ARCHIVED);
        order.setCreateTime(LocalDateTime.now());
        // 订单号并发撞唯一索引时重取流水重试，最终以 uk_order_no 兜底
        insertWithGeneratedOrderNo(order);
        // 热度 +1（下单行为驱动热门榜）：DB 列原子 +1 与 Redis ZINCRBY 双轨，
        // ZINCRBY 挂 afterCommit——事务回滚不会虚增榜单热度，Redis 故障仅 WARN
        itemMapper.addHotScore(item.getId());
        redisCacheUtils.afterCommit(() ->
                redisCacheUtils.zincrby(Constants.CACHE_HOT_SERVICES, String.valueOf(item.getId()), 1.0));
        // Batch 4：事务提交后投递 30 分钟延迟消息，TTL 到期经延迟 DLX 进入
        // appoint.cancel.queue；若期间管理员已派单，消费者会因"状态已不是待派单"
        // 而幂等跳过——延迟消息是"提醒系统该看一眼"而非"强制取消"
        OrderTimeoutMessage delayMessage = new OrderTimeoutMessage();
        delayMessage.setOrderId(order.getId());
        delayMessage.setOrderNo(order.getOrderNo());
        delayMessage.setCreateTime(order.getCreateTime().format(DATE_TIME_FMT));
        messageSender.sendTimeoutDelay(delayMessage);
        return order.getId();
    }

    /**
     * 死锁重试前的错峰等待：退避时长随重试轮次线性增长并叠加随机抖动，
     * 避免多个失败事务在同一时刻再次相撞（惊群）。
     *
     * @param attempt 当前重试轮次（从 1 开始）
     */
    private void sleepBeforeRetry(int attempt) {
        long backoff = RETRY_BACKOFF_MILLIS * attempt + ThreadLocalRandom.current().nextLong(RETRY_JITTER_MILLIS);
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException ie) {
            // 保留中断语义：线程被要求停止时不再继续重试
            Thread.currentThread().interrupt();
            throw new BusinessException(500, "系统繁忙，请稍后重试");
        }
    }

    /**
     * 居民取消订单：仅本人、仅待派单态，取消后联动释放师傅（此态尚无师傅，
     * 保留联动调用以统一收口）。
     *
     * @param id          订单ID
     * @param reason      取消原因
     * @param currentUser 当前登录用户
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long id, String reason, LoginUserDTO currentUser) {
        ServiceOrder order = requireOrder(id);
        // 水平越权防线：只能取消自己下的单
        if (!order.getResidentId().equals(currentUser == null ? null : currentUser.getUserId())) {
            throw new ForbiddenException("只能取消自己提交的预约订单");
        }
        // 角色防线：师傅与管理员不走本接口（管理员用运营取消接口）
        if (!Constants.ROLE_RESIDENT.equals(currentUser.getRole())) {
            throw new ForbiddenException("只有下单居民可以自助取消");
        }
        // 分布式锁第一道防线：与改派/开工/管理员取消互斥，抢不到即快速拒绝
        acquireOrderLock(order, "取消");
        boolean success = cancelPendingOrder(id, buildCancelRemark(order.getRemark(), reason));
        if (!success) {
            throw new BusinessException(400, "只有待派单的订单可以取消（当前状态: "
                    + statusText(order.getOrderStatus()) + "），已派单请联系物业管理员处置");
        }
        // 事务提交后异步通知（居民取消无承接师傅，只发居民一封）
        messageSender.sendOrderNotify(buildNotify(order.getId(), OrderNotifyMessage.TYPE_CANCEL, "居民自助取消"));
        log.info("居民[{}]取消订单[{}]", currentUser.getUsername(), order.getOrderNo());
    }

    /**
     * 管理员派单：校验候选师傅后条件 UPDATE 置已派单 + 原子占忙，单事务全成或全回滚。
     *
     * @param orderId      订单ID
     * @param workerId     承接师傅ID
     * @param dispatchType 派单方式
     * @param currentUser  当前登录管理员
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void dispatchOrder(Long orderId, Long workerId, String dispatchType, LoginUserDTO currentUser) {
        requireAdmin(currentUser, "派单");
        ServiceOrder order = requireOrder(orderId);
        // 分布式锁第一道防线：与居民取消/改派/开工/超时死信取消互斥同一订单
        acquireOrderLock(order, "派单");
        // 状态前置校验（给出友好提示），真正的并发幂等由下面的条件 UPDATE 保证
        if (Constants.ORDER_PENDING != order.getOrderStatus()) {
            throw new BusinessException(400, "只有待派单的订单可以派单（当前状态: "
                    + statusText(order.getOrderStatus()) + "）");
        }
        String type = normalizeDispatchType(dispatchType);
        WorkerQualification qual = validateWorkerQualification(workerId, order.getServiceId(), null);
        boolean success = orderMapper.update(null,
                new UpdateWrapper<ServiceOrder>()
                        .eq("id", orderId).eq("order_status", Constants.ORDER_PENDING)
                        .set("worker_id", workerId)
                        .set("dispatch_type", type)
                        .set("order_status", Constants.ORDER_DISPATCHED)
                        .set("update_time", LocalDateTime.now())) > 0;
        if (!success) {
            throw new BusinessException(400, "订单状态已被并发变更，请刷新后重试派单");
        }
        // 数据库层原子占忙：WHERE work_status=1 保证并发派单不会双中同一师傅
        if (workerProfileMapper.trySetBusy(workerId) == 0) {
            // 占忙失败 => 抛异常回滚上面的订单更新，避免出现"订单已派但师傅状态未置忙"
            throw new BusinessException(400, "师傅[" + qual.realName() + "]刚被并发派单抢占或已转休息，请改选他人");
        }
        // 事务提交后异步通知：师傅收"新工单"，居民收"已安排师傅"
        messageSender.sendOrderNotify(buildNotify(order.getId(), OrderNotifyMessage.TYPE_DISPATCH, "管理员派单"));
        log.info("管理员[{}]派单成功 orderNo={} -> workerId={} type={}",
                currentUser.getUsername(), order.getOrderNo(), workerId, type);
    }

    /**
     * 管理员改派：更换已派单未开工订单的师傅，旧师傅释放、新师傅占忙。
     *
     * @param orderId     订单ID
     * @param newWorkerId 新师傅ID
     * @param currentUser 当前登录管理员
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reassignOrder(Long orderId, Long newWorkerId, LoginUserDTO currentUser) {
        requireAdmin(currentUser, "改派");
        ServiceOrder order = requireOrder(orderId);
        // 分布式锁第一道防线：改派涉及"新师傅占忙 + 旧师傅释放"两步写，必须与
        // 开工、居民取消、超时取消互斥，避免旧师傅被释放后又被开工
        acquireOrderLock(order, "改派");
        if (Constants.ORDER_DISPATCHED != order.getOrderStatus()) {
            throw new BusinessException(400, "只有已派单未开工的订单可以改派（当前状态: "
                    + statusText(order.getOrderStatus()) + "）");
        }
        // 改派必须真的换人：重复指派同一师傅无业务意义
        if (newWorkerId.equals(order.getWorkerId())) {
            throw new BusinessException(400, "该订单已由目标师傅承接，无需改派");
        }
        // excludeOrderId 仅用于智能候选计算，改派校验沿用同一资格口径
        WorkerQualification qual = validateWorkerQualification(newWorkerId, order.getServiceId(), orderId);
        boolean success = orderMapper.update(null,
                new UpdateWrapper<ServiceOrder>()
                        .eq("id", orderId).eq("order_status", Constants.ORDER_DISPATCHED)
                        .set("worker_id", newWorkerId)
                        .set("dispatch_type", Constants.DISPATCH_REASSIGN)
                        .set("update_time", LocalDateTime.now())) > 0;
        if (!success) {
            throw new BusinessException(400, "订单状态已被并发变更（师傅可能已开始服务），改派失败");
        }
        if (workerProfileMapper.trySetBusy(newWorkerId) == 0) {
            throw new BusinessException(400, "师傅[" + qual.realName() + "]当前不可接单（忙碌或休息），请改选他人");
        }
        // 旧师傅在事务内联动释放：其若无其他在手工单则回置空闲
        Long oldWorkerId = order.getWorkerId();
        if (oldWorkerId != null) {
            workerProfileService.refreshWorkStatus(oldWorkerId);
        }
        // 事务提交后通知：新师傅收"新工单"，居民收更新后的上门师傅信息
        messageSender.sendOrderNotify(buildNotify(order.getId(), OrderNotifyMessage.TYPE_DISPATCH, "管理员改派"));
        log.info("管理员[{}]改派订单[{}] {} -> {}", currentUser.getUsername(), order.getOrderNo(),
                oldWorkerId, newWorkerId);
    }

    /**
     * 师傅开始服务：仅承接本人、1 -> 2 条件推进。
     *
     * @param id          订单ID
     * @param currentUser 当前登录师傅
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startService(Long id, LoginUserDTO currentUser) {
        ServiceOrder order = requireOrder(id);
        checkWorkerOwnership(order, currentUser, "推进");
        // 分布式锁第一道防线：与改派/管理员取消互斥（防止"改派进行中师傅已开工"）
        acquireOrderLock(order, "开始服务");
        boolean success = orderMapper.update(null,
                new UpdateWrapper<ServiceOrder>()
                        .eq("id", id).eq("order_status", Constants.ORDER_DISPATCHED)
                        .set("order_status", Constants.ORDER_IN_SERVICE)
                        .set("update_time", LocalDateTime.now())) > 0;
        if (!success) {
            throw new BusinessException(400, "只有已派单的工单可以开始服务（当前状态: "
                    + statusText(order.getOrderStatus()) + "），请勿重复提交");
        }
        // 开工后仍为忙碌（本就是忙碌态），调用联动收口保持口径统一
        workerProfileService.refreshWorkStatus(order.getWorkerId());
        log.info("师傅[{}]开始服务 orderNo={}", currentUser.getUsername(), order.getOrderNo());
    }

    /**
     * 师傅完成服务：仅承接本人、2 -> 3 条件推进，写完成时间 + 累计完成数 +1。
     *
     * @param id          订单ID
     * @param currentUser 当前登录师傅
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeService(Long id, LoginUserDTO currentUser) {
        ServiceOrder order = requireOrder(id);
        checkWorkerOwnership(order, currentUser, "完成");
        // 分布式锁第一道防线：完成与改派/取消互斥（完成写 finish_time + 累计完成数，
        // 与后续的评价窗口、绩效重算强相关，不允许并发交叉）
        acquireOrderLock(order, "完成服务");
        LocalDateTime finishTime = LocalDateTime.now();
        boolean success = orderMapper.update(null,
                new UpdateWrapper<ServiceOrder>()
                        .eq("id", id).eq("order_status", Constants.ORDER_IN_SERVICE)
                        .set("order_status", Constants.ORDER_COMPLETED)
                        .set("finish_time", finishTime)
                        .set("update_time", finishTime)) > 0;
        if (!success) {
            throw new BusinessException(400, "只有服务中的工单可以完成（当前状态: "
                    + statusText(order.getOrderStatus()) + "），请勿重复提交");
        }
        // 同事务内原子累计完成数（智能派单负载均衡因子的实时来源）
        workerProfileService.incrementTotalOrders(order.getWorkerId());
        // 完成即无在手工单，联动回置空闲，重新进入派单候选池
        workerProfileService.refreshWorkStatus(order.getWorkerId());
        // 事务提交后通知居民"服务已完成，请在 7 天内评价"
        messageSender.sendOrderNotify(buildNotify(order.getId(), OrderNotifyMessage.TYPE_COMPLETE, "师傅完成服务"));
        log.info("师傅[{}]完成服务 orderNo={} price={}", currentUser.getUsername(),
                order.getOrderNo(), order.getPrice());
    }

    /**
     * 管理员运营取消：待派单/已派单两态可取消，服务中禁止单方面取消。
     *
     * @param id          订单ID
     * @param reason      取消原因
     * @param currentUser 当前登录管理员
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void adminCancelOrder(Long id, String reason, LoginUserDTO currentUser) {
        requireAdmin(currentUser, "取消订单");
        ServiceOrder order = requireOrder(id);
        // 分布式锁第一道防线：管理员运营取消与开工、改派、居民取消互斥
        acquireOrderLock(order, "取消订单");
        String remark = buildCancelRemark(order.getRemark(), reason);
        // 优先尝试取消待派单态（无师傅占用，无需释放）
        if (Constants.ORDER_PENDING == order.getOrderStatus()) {
            if (!cancelPendingOrder(id, remark)) {
                throw new BusinessException(400, "订单状态已被并发变更，取消失败，请刷新后重试");
            }
            messageSender.sendOrderNotify(buildNotify(order.getId(), OrderNotifyMessage.TYPE_CANCEL, "物业运营取消"));
            log.info("管理员[{}]取消待派单订单[{}]", currentUser.getUsername(), order.getOrderNo());
            return;
        }
        // 已派单态：取消同时清空承接师傅，避免残留"已派单但无师傅"
        if (Constants.ORDER_DISPATCHED == order.getOrderStatus()) {
            boolean success = orderMapper.update(null,
                    new UpdateWrapper<ServiceOrder>()
                            .eq("id", id).eq("order_status", Constants.ORDER_DISPATCHED)
                            .set("order_status", Constants.ORDER_CANCELLED)
                            .set("remark", remark)
                            .set("worker_id", null)
                            .set("dispatch_type", null)
                            .set("update_time", LocalDateTime.now())) > 0;
            if (!success) {
                throw new BusinessException(400, "订单状态已被并发变更（师傅可能已开始服务），管理员取消失败");
            }
            // 释放被取消订单占用的师傅（无其他在手工单则回置空闲）
            workerProfileService.refreshWorkStatus(order.getWorkerId());
            // 已派单取消：居民与承接师傅双方都收到撤销通知（师傅最容易白跑一趟）
            messageSender.sendOrderNotify(buildNotify(order.getId(), OrderNotifyMessage.TYPE_CANCEL, "物业运营取消"));
            log.info("管理员[{}]取消已派单订单[{}]，释放师傅[{}]",
                    currentUser.getUsername(), order.getOrderNo(), order.getWorkerId());
            return;
        }
        throw new BusinessException(400, "服务中/已完成/已取消的订单不能由管理员取消（当前状态: "
                + statusText(order.getOrderStatus()) + "），服务中订单须由师傅完成或先改派处理");
    }

    /**
     * 超时未派单自动取消（MQ 消费者调用，无登录态）。
     * <p>四步走，每一步都为"重复投递/并发派单"留了退路：</p>
     * <ol>
     *   <li>取订单：不存在直接返回 false（消息无处理价值，消费者确认即可）；</li>
     *   <li>状态预判：非待派单直接返回 false——这是 30 分钟内管理员已派单的
     *       正常业务结果，<b>不是异常</b>，绝不能抛异常让消息重投；</li>
     *   <li>抢分布式锁：与"管理员派单/居民取消/改派"互斥，抢不到说明订单
     *       正在被别的操作处理，同样返回 false 由消费者确认（后续重投也不会
     *       改变结果，重试没有意义）；</li>
     *   <li>条件 UPDATE：WHERE order_status=待派单 原子置为已取消并追记原因，
     *       影响 0 行说明状态在毫秒级内被抢改，返回 false 幂等结束。</li>
     * </ol>
     * <p>取消成功后投递通知消息（事务提交后发出），居民收到"超时未派单已取消"邮件。</p>
     *
     * @param orderId 订单ID
     * @return true=本次真的取消了订单；false=已流转或不存在
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean cancelTimeoutOrder(Long orderId) {
        if (orderId == null) {
            return false;
        }
        ServiceOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            log.warn("超时取消：订单[{}]不存在（可能已归档清理）", orderId);
            return false;
        }
        if (Constants.ORDER_PENDING != order.getOrderStatus()) {
            // 30 分钟内已派单/已取消：设计内的幂等命中，不告警不重投
            log.info("超时取消：订单[{}]当前状态[{}]，无需取消", order.getOrderNo(),
                    statusText(order.getOrderStatus()));
            return false;
        }
        // 分布式锁：与派单/改派/居民取消互斥；抢不到说明他人正在处理，本次放弃
        String lockKey = Constants.ORDER_LOCK_PREFIX + order.getOrderNo();
        String owner = Constants.ORDER_LOCK_OWNER_PREFIX + UUID.randomUUID();
        if (!redisCacheUtils.tryLock(lockKey, owner, lockSeconds)) {
            log.info("超时取消：订单[{}]正在被其他操作处理，本次跳过", order.getOrderNo());
            return false;
        }
        redisCacheUtils.afterRelease(() -> redisCacheUtils.unlock(lockKey, owner));
        boolean cancelled = cancelPendingOrder(orderId,
                buildCancelRemark(order.getRemark(), "超过30分钟未派单，系统自动取消"));
        if (!cancelled) {
            log.info("超时取消：订单[{}]状态被并发变更，本次未取消", order.getOrderNo());
            return false;
        }
        // 事务提交后通知居民：超时取消也要给出明确原因，避免"订单莫名消失"
        messageSender.sendOrderNotify(buildNotify(order.getId(), OrderNotifyMessage.TYPE_CANCEL,
                "超过30分钟未派单，系统自动取消"));
        log.info("超时取消成功：orderId={} orderNo={}", orderId, order.getOrderNo());
        return true;
    }

    /**
     * 内部工具：构造订单通知消息（只带定位键与事件类型，正文由消费者查库组装）。
     *
     * @param orderId 订单ID
     * @param type    事件类型（DISPATCH/COMPLETE/CANCEL/REMIND）
     * @param scene   触发场景描述（用于邮件措辞与日志）
     * @return 通知消息
     */
    private OrderNotifyMessage buildNotify(Long orderId, String type, String scene) {
        OrderNotifyMessage message = new OrderNotifyMessage();
        message.setOrderId(orderId);
        message.setType(type);
        message.setScene(scene);
        return message;
    }

    /**
     * 内部工具：抢订单状态机分布式锁（Batch 3 双防线的第一道）。
     * <p>调用约定与实现要点：</p>
     * <ul>
     *   <li>必须在 {@code @Transactional} 方法内、requireOrder 取到 order 之后调用；
     *       本方法把"事务结束时释放"注册到 TransactionSynchronization 的
     *       afterCompletion——提交与回滚都会执行，锁不会滞留到 TTL 到期；</li>
     *   <li>锁值为一次性 UUID（由 RedisCacheUtils.unlock 的 Lua GET 比对保证
     *       "只释放自己的锁"，不会误删他人已重新获取的同名锁）；</li>
     *   <li>抢锁失败即抛 400"该订单正在被其他操作处理"：绝大多数并发冲突在
     *       应用层被快速挡掉，不消耗数据库；条件 UPDATE 仍是正确性的最终保证
     *       （Redis 故障时 tryLock 降级放行，正确性不降级）。</li>
     * </ul>
     *
     * @param order  已取到的订单实体（order_no 为锁键后缀）
     * @param action 动作词（用于失败提示与日志定位）
     */
    private void acquireOrderLock(ServiceOrder order, String action) {
        String lockKey = Constants.ORDER_LOCK_PREFIX + order.getOrderNo();
        // 持有者标识：UUID 保证 unlock 只释放本次持有的锁（Lua 脚本内 GET 比对）
        String owner = Constants.ORDER_LOCK_OWNER_PREFIX + UUID.randomUUID();
        if (!redisCacheUtils.tryLock(lockKey, owner, lockSeconds)) {
            log.warn("订单状态机加锁失败（并发{}）: orderNo={}", action, order.getOrderNo());
            throw new BusinessException(400, "该订单正在被其他操作处理，请稍后刷新重试");
        }
        // 锁必须活到事务结束（提交或回滚都释放）：提交前释放会让并发请求
        // 读到未提交数据误判状态；回滚时不释放则会挡人到 TTL 到期
        redisCacheUtils.afterRelease(() -> redisCacheUtils.unlock(lockKey, owner));
    }

    /**
     * 内部工具：取消待派单订单的条件 UPDATE（居民自助取消与超时死信消费者共用同一
     * 原子口径，保证"已派单不得被取消"这一不变量在任何路径下都不被绕过）。
     *
     * @param id     订单ID
     * @param remark 追记取消原因后的备注
     * @return true=本次真的完成了 0 -> 4 迁移；false=状态已被并发变更
     */
    private boolean cancelPendingOrder(Long id, String remark) {
        return orderMapper.update(null,
                new UpdateWrapper<ServiceOrder>()
                        .eq("id", id).eq("order_status", Constants.ORDER_PENDING)
                        .set("order_status", Constants.ORDER_CANCELLED)
                        .set("remark", remark)
                        .set("update_time", LocalDateTime.now())) > 0;
    }

    /**
     * 内部工具：按ID取订单，不存在即 404。
     *
     * @param id 订单ID
     * @return 订单实体
     */
    private ServiceOrder requireOrder(Long id) {
        if (id == null) {
            throw new BusinessException(400, "订单ID不能为空");
        }
        ServiceOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new NotFoundException("服务订单不存在或已被清理");
        }
        return order;
    }

    /**
     * 内部工具：订单查看权限校验（IDOR 核心）。
     *
     * @param order       订单实体
     * @param currentUser 当前登录用户
     */
    private void checkViewPermission(ServiceOrder order, LoginUserDTO currentUser) {
        if (currentUser == null || currentUser.getUserId() == null) {
            throw new ForbiddenException("请先登录后再查看订单");
        }
        String role = currentUser.getRole();
        boolean allowed = Constants.ROLE_ADMIN.equals(role)
                || (Constants.ROLE_RESIDENT.equals(role) && order.getResidentId().equals(currentUser.getUserId()))
                || (Constants.ROLE_WORKER.equals(role) && currentUser.getUserId().equals(order.getWorkerId()));
        if (!allowed) {
            // 统一提示不泄露"该订单是否存在/属于谁"，降低越权探测的信息增益
            throw new ForbiddenException("无权查看该订单");
        }
    }

    /**
     * 内部工具：师傅工单归属校验（推进工单动作的越权防线）。
     *
     * @param order       订单实体
     * @param currentUser 当前登录用户
     * @param action      动作词（用于提示语）
     */
    private void checkWorkerOwnership(ServiceOrder order, LoginUserDTO currentUser, String action) {
        if (currentUser == null || !Constants.ROLE_WORKER.equals(currentUser.getRole())) {
            throw new ForbiddenException("只有便民服务人员可以" + action + "工单");
        }
        if (!currentUser.getUserId().equals(order.getWorkerId())) {
            throw new ForbiddenException("只能" + action + "指派给自己的工单");
        }
    }

    /**
     * 内部工具：管理员角色校验（垂直越权防线）。
     *
     * @param currentUser 当前登录用户
     * @param action      动作词
     */
    private void requireAdmin(LoginUserDTO currentUser, String action) {
        if (currentUser == null || !Constants.ROLE_ADMIN.equals(currentUser.getRole())) {
            throw new ForbiddenException("只有物业管理员可以" + action);
        }
    }

    /**
     * 内部工具：派单目标师傅资格校验（账号启用 + 师傅角色 + 非休息 + 技能覆盖）。
     * <p>技能覆盖以标签集合精确比对：| 分隔串拆分后 required 必须是 worker 技能
     * 集合的子集；用 LIKE 会把"水电维修"误配成子串"电维修"，故必须集合比对。</p>
     *
     * @param workerId         候选师傅用户ID
     * @param serviceId        服务项目ID
     * @param excludeOrderId   改派场景排除的订单ID（可为 null，仅作日志上下文）
     * @return 资格校验通过的师傅信息（含姓名，用于失败提示）
     */
    private WorkerQualification validateWorkerQualification(Long workerId, Long serviceId, Long excludeOrderId) {
        if (workerId == null) {
            throw new BusinessException(400, "请选择承接师傅");
        }
        SysUser worker = userMapper.selectById(workerId);
        if (worker == null || !Constants.ROLE_WORKER.equals(worker.getRole())) {
            throw new BusinessException(400, "承接人不是便民服务人员，无法派单");
        }
        if (Constants.USER_STATUS_DISABLED == worker.getStatus()) {
            throw new BusinessException(400, "师傅[" + worker.getRealName() + "]账号已禁用，无法派单");
        }
        WorkerProfile profile = workerProfileMapper.selectOne(
                new QueryWrapper<WorkerProfile>().eq("user_id", workerId));
        if (profile == null) {
            throw new BusinessException(400, "该师傅档案缺失，请先联系物业管理员初始化档案");
        }
        if (Constants.WORK_REST == profile.getWorkStatus()) {
            throw new BusinessException(400, "师傅[" + worker.getRealName() + "]当前休息中，不接受派单");
        }
        ServiceItem item = itemMapper.selectById(serviceId);
        if (item == null) {
            throw new NotFoundException("订单关联的服务项目不存在");
        }
        Set<String> have = new HashSet<>(splitTags(profile.getSkillTags()));
        List<String> required = splitTags(item.getSkillRequired());
        List<String> missing = required.stream().filter(t -> !have.contains(t)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(400, "师傅[" + worker.getRealName() + "]缺少所需技能: "
                    + String.join("、", missing) + "，无法承接该服务");
        }
        // 已在途的师傅仍可被改派/追加派单（忙碌状态由 trySetBusy 原子把关并回滚），
        // 这里只做资格与技能校验，忙碌并发交由 UPDATE 条件判定，避免"校验-派单"竞态
        return new WorkerQualification(workerId, worker.getRealName());
    }

    /**
     * 内部工具：订单列表/详情的派生字段补全（状态中文、派单方式中文、
     * 管理员视角的动作标记置 null）。纯内存映射，零额外 SQL。
     *
     * @param records 订单 VO 列表
     * @param isAdmin 是否管理员视角
     */
    private void decorate(List<OrderVO> records, boolean isAdmin) {
        for (OrderVO vo : records) {
            vo.setOrderStatusText(statusText(vo.getOrderStatus()));
            vo.setDispatchTypeText(dispatchText(vo.getDispatchType()));
            if (isAdmin) {
                // 管理员视角：居民侧动作标记无意义，置 null 让前端不渲染按钮
                vo.setCanCancel(null);
                vo.setCanEvaluate(null);
            }
        }
    }

    /**
     * 订单号生成 + 插入（Redis 原子自增，DB baseline 仅在冷启动校正一次）。
     * <p><b>为什么不能只用 COUNT(*)+1</b>：当日流水取"已有订单数 + 1"，在并发下单时
     * 多个请求会同时读到同一个 count，算出的订单号完全相同，只能靠 uk_order_no
     * 唯一索引报 DuplicateKeyException 后重取。实测 6 个居民同时下单时 3 次重试
     * 窗口内连环撞号，最终把并发下单误报成"下单过于频繁"，成功率只有 1/6。</p>
     * <p><b>更要命的是死锁</b>：{@code LIKE 'WO20261114%'} 走 uk_order_no 的范围扫描，
     * InnoDB 会给这段区间加**间隙锁**；并发的 INSERT 需要在同一唯一索引上加插入
     * 意向锁，两者互斥即触发 "Deadlock found when trying to get lock"。因此正常
     * 路径必须完全不碰这个查询。</p>
     * <p><b>修复策略</b>：</p>
     * <ol>
     *   <li>当日序列全部由 Redis INCR 产生——单点原子操作，无并发冲突、无 DB 锁；</li>
     *   <li>仅当 INCR 返回 1（键刚创建或已过 TTL）时才查一次 DB 当日订单数做基线
     *       校正，把序列抬到 DB 实际值之上，避免 Redis 被清空后流水倒退撞已有单号；
     *       正常运行时这一步不执行，间隙锁不可能出现；</li>
     *   <li>万一仍撞号（极端并发或 DB 基线偏差），重试时追加 4 位随机十六进制后缀，
     *       而不是简单重复同一条流水。</li>
     * </ol>
     *
     * @param order 待插入订单实体（order_no 由本方法写入）
     */
    private void insertWithGeneratedOrderNo(ServiceOrder order) {
        String prefix = Constants.ORDER_NO_PREFIX + LocalDate.now().format(DATE_FMT);
        String seqKey = Constants.ORDER_SEQ_KEY_PREFIX + prefix;
        String readyKey = Constants.ORDER_SEQ_READY_PREFIX + prefix;
        String seedLockKey = Constants.ORDER_SEQ_SEED_LOCK_PREFIX + prefix;
        for (int attempt = 0; attempt < ORDER_NO_RETRY; attempt++) {
            // 第一优先：只有序列已就绪才发号（就绪=已完成 DB 基线校正）
            long seq = redisCacheUtils.incrSeqIfReady(seqKey, readyKey, Constants.ORDER_SEQ_TTL_SECONDS);
            if (seq < 1) {
                // 未就绪：说明当天首个请求还没播种，或 Redis 被清空/重启过。
                // 谁来播种由 SETNX 决定，只有一个赢家去查库；其余线程等待后重试，
                // 保证任何线程都不会在基线抬升前拿到 1、2、3 这类小流水。
                if (!redisCacheUtils.tryMutex(seedLockKey, SEED_LOCK_SECONDS)) {
                    sleepBeforeRetry(attempt + 1);
                    continue;
                }
                try {
                    // 再确认一次：可能已被前一个赢家播完并放锁，避免重复查库
                    long recheck = redisCacheUtils.incrSeqIfReady(seqKey, readyKey,
                            Constants.ORDER_SEQ_TTL_SECONDS);
                    if (recheck >= 1) {
                        seq = recheck;
                    } else {
                        // 当日 DB 已有订单数作为基线，序列从基线+1 起发，
                        // 保证与历史订单号（WOyyyyMMddNNNN）绝不重叠
                        long dbCount = orderMapper.countOrdersWithPrefix(prefix);
                        if (!redisCacheUtils.seedSeq(seqKey, readyKey, dbCount, Constants.ORDER_SEQ_TTL_SECONDS)) {
                            throw new BusinessException(500, "系统繁忙，请稍后重试");
                        }
                        seq = redisCacheUtils.incrSeqIfReady(seqKey, readyKey, Constants.ORDER_SEQ_TTL_SECONDS);
                        if (seq < 1) {
                            throw new BusinessException(500, "系统繁忙，请稍后重试");
                        }
                    }
                } finally {
                    redisCacheUtils.releaseMutex(seedLockKey);
                }
            }
            order.setOrderNo(prefix + String.format("%0" + ORDER_NO_SEQ_WIDTH + "d", seq));
            try {
                orderMapper.insert(order);
                return;
            } catch (DuplicateKeyException e) {
                // 极端兜底（如库里存在非本系统写入的同号数据）：继续取下一个流水，
                // 绝不生成带随机后缀的畸形单号，保持单号格式恒为 WO+日期+流水
                log.warn("订单号[{}]已被占用，第{}/{}次重取流水", order.getOrderNo(), attempt + 1, ORDER_NO_RETRY);
            }
        }
        // 走到这里说明唯一索引连续冲突，属于系统异常而非用户操作过快，
        // 用 500 语义提示重试，不再伪装成限流（避免掩盖真实故障）
        log.error("订单号生成连续 {} 次冲突，放弃本次下单: prefix={}", ORDER_NO_RETRY, prefix);
        throw new BusinessException(500, "系统繁忙，请稍后重试");
    }

    /**
     * 订单状态中文（越界返回"未知"，杜绝数组越界）。
     *
     * @param status 状态码
     * @return 中文描述
     */
    private String statusText(Integer status) {
        return status != null && status >= 0 && status < ORDER_STATUS_TEXT.length
                ? ORDER_STATUS_TEXT[status] : "未知";
    }

    /**
     * 派单方式中文。
     *
     * @param dispatchType 派单方式码
     * @return 中文描述（未派单返回"—"）
     */
    private String dispatchText(String dispatchType) {
        if (dispatchType == null || dispatchType.isEmpty()) {
            return "—";
        }
        return switch (dispatchType) {
            case Constants.DISPATCH_AUTO -> "智能派单";
            case Constants.DISPATCH_MANUAL -> "手动派单";
            case Constants.DISPATCH_REASSIGN -> "改派";
            default -> dispatchType;
        };
    }

    /**
     * 派单方式归一与校验（智能候选确认传 AUTO，管理员手工指派传 MANUAL）。
     *
     * @param dispatchType 提交的派单方式
     * @return 合法的方式码
     */
    private String normalizeDispatchType(String dispatchType) {
        if (dispatchType == null || dispatchType.isEmpty()) {
            return Constants.DISPATCH_MANUAL;
        }
        String type = dispatchType.trim().toUpperCase();
        if (!Constants.DISPATCH_AUTO.equals(type) && !Constants.DISPATCH_MANUAL.equals(type)) {
            throw new BusinessException(400, "派单方式只能为 AUTO(智能派单) 或 MANUAL(手动派单)，改派走改派接口");
        }
        return type;
    }

    /**
     * 取消原因追记：以【居民主动取消】/【物业取消】标记前缀写入 remark，
     * 保留居民原始备注（信息不丢失，运营可追溯取消语境）。
     *
     * @param original 原始备注
     * @param reason   取消原因（可空则用默认文案）
     * @return 追记后的备注（截断到 255 字符以适配列宽）
     */
    private String buildCancelRemark(String original, String reason) {
        String reasonText = (reason == null || reason.trim().isEmpty()) ? "未说明原因" : reason.trim();
        String appended = "【取消原因】" + reasonText;
        String merged = (original == null || original.trim().isEmpty()) ? appended : original.trim() + " | " + appended;
        return merged.length() > 255 ? merged.substring(0, 255) : merged;
    }

    /**
     * 状态筛选合法性校验（0~4 之外直接 400）。
     *
     * @param status 提交的状态值
     * @return 原值（null 放行）
     */
    private Integer validateStatus(Integer status) {
        if (status == null) {
            return null;
        }
        if (status < 0 || status > 4) {
            throw new BusinessException(400, "订单状态筛选值只能为 0~4");
        }
        return status;
    }

    /**
     | 分隔标签串拆分为去空白去重列表。
     *
     * @param raw 原始串
     * @return 标签列表
     */
    private List<String> splitTags(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * 关键字规范化（空串归一 null）。
     *
     * @param kw 原始关键字
     * @return 规范化关键字
     */
    private String normalizeKw(String kw) {
        if (kw == null) {
            return null;
        }
        String trimmed = kw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 空串归一 null（备注字段允许置空）。
     *
     * @param value 原始值
     * @return null 或去空白值
     */
    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 派单资格校验结果（师傅ID与姓名，失败提示需要姓名定位具体师傅）。
     *
     * @param userId   师傅用户ID
     * @param realName 师傅真实姓名
     */
    private record WorkerQualification(Long userId, String realName) {
    }
}
