package com.community.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.community.booking.entity.ServiceOrder;
import com.community.booking.vo.DispatchCandidateVO;
import com.community.booking.vo.OrderVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

/**
 * 服务订单 Mapper：继承 BaseMapper 获得单表 CRUD，补充三视角订单联表分页、
 * 订单详情联表单查、当日订单号流水统计与智能派单候选行查询四类自定义方法。
 * <p>residentName/serviceItemName/categoryName/workerName 为 VO 联表派生字段，
 * 由本 Mapper JOIN sys_user / service_item / service_category / sys_user(师傅)
 * 一条 SQL 直出（全部 JOIN 命中主键或唯一索引，不放大行数，分页 COUNT 口径准确）；
 * evaluated/evaluationId 经 LEFT JOIN service_evaluation（uk_eval_order 唯一，
 * 最多 1 行）直出；canCancel/canEvaluate 两个动作标记由 SQL CASE WHEN 按状态机
 * 口径直出，viewerId 传 null（管理员视角）时恒为 0，Service 层再置 null 表达
 * "非本人视角"。全部 SQL 100% #{} 预编译，无 ${} 拼接。</p>
 *
 * @author community-booking
 */
@Mapper
public interface ServiceOrderMapper extends BaseMapper<ServiceOrder> {

    /**
     * 订单联表分页查询（居民"我的预约"、师傅"我的工单"、管理员派单中心三视角共用）。
     * <p>归属条件由 Service 层按登录角色注入且严禁前端直传：居民视角传
     * residentId、师傅视角传 workerId、管理员两者皆 null（全量）。管理员额外
     * 支持按分类/项目过滤，师傅与居民的筛选仅开放状态与关键字。
     * 排序统一按"下单时间倒序 + id 倒序"，最新单据靠前。</p>
     *
     * @param page        分页对象（分页拦截器自动补 COUNT）
     * @param kw          关键字（可空=不筛选，模糊匹配订单号/项目名称/居民姓名）
     * @param orderStatus 订单状态筛选 0~4（可空=不限）
     * @param categoryId  服务分类筛选（可空=不限，仅管理员派单中心使用）
     * @param serviceId   服务项目筛选（可空=不限）
     * @param residentId  归属居民ID（居民视角由 Service 注入；其余视角 null）
     * @param workerId    归属师傅ID（师傅视角由 Service 注入；其余视角 null）
     * @param viewerId    当前登录用户ID（用于 canCancel/canEvaluate 本人判定；
     *                    管理员视角传 null，两个动作标记恒为 0）
     * @return 订单 VO 分页（联表字段与派生标记一条 SQL 直出）
     */
    @Select("<script>"
            + "SELECT o.id, o.order_no, o.resident_id, u.real_name AS resident_name, "
            + "o.service_id, si.item_name AS service_item_name, sc.category_name, "
            + "o.worker_id, wu.real_name AS worker_name, o.appoint_time, o.service_address, "
            + "o.contact_phone, o.remark, o.price, o.order_status, o.dispatch_type, "
            + "o.finish_time, o.is_archived, "
            + "(ev.id IS NOT NULL) AS evaluated, ev.id AS evaluation_id, "
            + "CASE WHEN o.order_status = 0 AND o.resident_id = #{viewerId} THEN 1 ELSE 0 END AS can_cancel, "
            + "CASE WHEN o.order_status = 3 AND o.is_archived = 0 AND o.resident_id = #{viewerId} "
            + "AND o.finish_time IS NOT NULL AND o.finish_time >= DATE_SUB(NOW(), INTERVAL 7 DAY) "
            + "AND ev.id IS NULL THEN 1 ELSE 0 END AS can_evaluate, "
            + "o.create_time, o.update_time "
            + "FROM service_order o "
            + "JOIN sys_user u ON u.id = o.resident_id "
            + "JOIN service_item si ON si.id = o.service_id "
            + "LEFT JOIN service_category sc ON sc.id = si.category_id "
            + "LEFT JOIN sys_user wu ON wu.id = o.worker_id "
            + "LEFT JOIN service_evaluation ev ON ev.order_id = o.id "
            + "<where> "
            + "<if test=\"kw != null and kw != ''\">AND (o.order_no LIKE CONCAT('%', #{kw}, '%') "
            + "OR si.item_name LIKE CONCAT('%', #{kw}, '%') OR u.real_name LIKE CONCAT('%', #{kw}, '%')) </if> "
            + "<if test=\"orderStatus != null\">AND o.order_status = #{orderStatus} </if> "
            + "<if test=\"categoryId != null\">AND si.category_id = #{categoryId} </if> "
            + "<if test=\"serviceId != null\">AND o.service_id = #{serviceId} </if> "
            + "<if test=\"residentId != null\">AND o.resident_id = #{residentId} </if> "
            + "<if test=\"workerId != null\">AND o.worker_id = #{workerId} </if> "
            + "</where> "
            + "ORDER BY o.create_time DESC, o.id DESC"
            + "</script>")
    IPage<OrderVO> selectOrderPage(IPage<OrderVO> page, @Param("kw") String kw,
                                   @Param("orderStatus") Integer orderStatus,
                                   @Param("categoryId") Long categoryId, @Param("serviceId") Long serviceId,
                                   @Param("residentId") Long residentId, @Param("workerId") Long workerId,
                                   @Param("viewerId") Long viewerId);

    /**
     * 订单详情联表单查（展示字段与列表口径完全一致，一条 SQL 直出）。
     *
     * @param id       订单ID
     * @param viewerId 当前登录用户ID（本人动作标记判定用；管理员传 null）
     * @return 订单 VO；不存在时返回 null
     */
    @Select("SELECT o.id, o.order_no, o.resident_id, u.real_name AS resident_name, "
            + "o.service_id, si.item_name AS service_item_name, sc.category_name, "
            + "o.worker_id, wu.real_name AS worker_name, o.appoint_time, o.service_address, "
            + "o.contact_phone, o.remark, o.price, o.order_status, o.dispatch_type, "
            + "o.finish_time, o.is_archived, "
            + "(ev.id IS NOT NULL) AS evaluated, ev.id AS evaluation_id, "
            + "CASE WHEN o.order_status = 0 AND o.resident_id = #{viewerId} THEN 1 ELSE 0 END AS can_cancel, "
            + "CASE WHEN o.order_status = 3 AND o.is_archived = 0 AND o.resident_id = #{viewerId} "
            + "AND o.finish_time IS NOT NULL AND o.finish_time >= DATE_SUB(NOW(), INTERVAL 7 DAY) "
            + "AND ev.id IS NULL THEN 1 ELSE 0 END AS can_evaluate, "
            + "o.create_time, o.update_time "
            + "FROM service_order o "
            + "JOIN sys_user u ON u.id = o.resident_id "
            + "JOIN service_item si ON si.id = o.service_id "
            + "LEFT JOIN service_category sc ON sc.id = si.category_id "
            + "LEFT JOIN sys_user wu ON wu.id = o.worker_id "
            + "LEFT JOIN service_evaluation ev ON ev.order_id = o.id "
            + "WHERE o.id = #{id} LIMIT 1")
    OrderVO selectOrderDetailById(@Param("id") Long id, @Param("viewerId") Long viewerId);

    /**
     * 统计指定订单号前缀（WO+yyyyMMdd）的当日订单数，用于生成当日 4 位流水号。
     * <p>前缀由 Service 层按当天日期拼装后整体作为 #{} 参数传入，不含拼接注入；
     * 并发下单时最终以 uk_order_no 唯一索引兜底，冲突由全局异常处理器提示重试。</p>
     *
     * @param prefix 订单号前缀（如 WO20261111）
     * @return 当日已生成的订单数量
     */
    @Select("SELECT COUNT(*) FROM service_order WHERE order_no LIKE CONCAT(#{prefix}, '%')")
    int countOrdersWithPrefix(@Param("prefix") String prefix);

    /**
     * 智能派单候选行查询（Batch 4 多维权重评分算法的数据源）。
     * <p>候选池口径：账号正常(status=1) 的 ROLE_WORKER 且存在档案、当前非休息
     * (work_status &lt;&gt; 0) 的师傅；休息中师傅完全不进入候选。
     * active_orders 与 conflict_orders 两条 COUNT 子查询在 SQL 内一次算完：
     * 冲突判定以预约时间前后各 windowHours 小时为窗口，与在手工单（已派单/服务中）
     * 的预约时间重叠即计数，改派场景通过 excludeOrderId 排除本单自身。
     * 技能覆盖匹配须在内存中做（| 分隔串按标签精确比对，like 会把"水电维修"
     * 误配成"电维修"类子串），因此本方法不返回 matchScore，由 Service 计算。</p>
     *
     * @param appointTime     订单预约上门时间（冲突窗口中心）
     * @param windowHours     冲突窗口半径小时数（计划书口径 2 小时）
     * @param excludeOrderId  改派时排除的订单ID（可为 null）
     * @return 候选师傅原始行（matchScore/reason 等评分字段由 Service 层填充）
     */
    @Select("<script>"
            + "SELECT u.id AS user_id, u.username, u.real_name, u.phone, "
            + "wp.skill_tags, wp.work_status, wp.rating_score, wp.total_orders, "
            + "(SELECT COUNT(*) FROM service_order o WHERE o.worker_id = u.id AND o.order_status IN (1, 2)) AS active_orders, "
            + "(SELECT COUNT(*) FROM service_order o WHERE o.worker_id = u.id AND o.order_status IN (1, 2) "
            + "AND o.appoint_time BETWEEN DATE_SUB(#{appointTime}, INTERVAL #{windowHours} HOUR) "
            + "AND DATE_ADD(#{appointTime}, INTERVAL #{windowHours} HOUR) "
            + "<if test=\"excludeOrderId != null\">AND o.id &lt;&gt; #{excludeOrderId} </if> "
            + ") AS conflict_orders "
            + "FROM sys_user u JOIN worker_profile wp ON wp.user_id = u.id "
            + "WHERE u.status = 1 AND u.role = 'ROLE_WORKER' AND wp.work_status &lt;&gt; 0"
            + "</script>")
    java.util.List<DispatchCandidateVO> selectDispatchCandidates(@Param("appointTime") LocalDateTime appointTime,
                                                                @Param("windowHours") int windowHours,
                                                                @Param("excludeOrderId") Long excludeOrderId);

    /**
     * 归档完成超期的订单（每日 03:00 定时任务调用）。
     * <p>只置 is_archived=1，不删除数据：归档的语义是"退出常规列表与可评价窗口"，
     * 订单行仍需保留供对账与历史查询。条件里同时限定 order_status=3 与
     * is_archived=0，保证重复执行不会重复计数（幂等）。</p>
     *
     * @param days 完成后保留天数（契约 7 天，由调用方按配置传入）
     * @return 本次归档的订单行数
     */
    @org.apache.ibatis.annotations.Update("UPDATE service_order SET is_archived = 1 "
            + "WHERE order_status = 3 AND is_archived = 0 "
            + "AND finish_time IS NOT NULL AND finish_time < DATE_SUB(NOW(), INTERVAL #{days} DAY)")
    int archiveCompletedOrders(@Param("days") int days);

    /**
     * 查询"次日上门"的已派单订单ID（每日 18:00 提醒任务的数据源）。
     * <p>时间窗口为 [明日 00:00:00, 后日 00:00:00)，用半开区间避免 23:59:59.999
     * 这类边界值漏单；只取已派单(1)态——待派单还没师傅、服务中已是当天的事。</p>
     *
     * @return 次日待上门的订单ID列表（可能为空）
     */
    @Select("SELECT id FROM service_order WHERE order_status = 1 AND is_archived = 0 "
            + "AND appoint_time >= DATE_ADD(CURDATE(), INTERVAL 1 DAY) "
            + "AND appoint_time < DATE_ADD(CURDATE(), INTERVAL 2 DAY) "
            + "ORDER BY appoint_time")
    java.util.List<Long> selectTomorrowDispatchedOrderIds();
}
