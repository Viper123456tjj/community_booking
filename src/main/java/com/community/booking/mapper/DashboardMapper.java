package com.community.booking.mapper;

import com.community.booking.vo.DashboardCountRowVO;
import com.community.booking.vo.DashboardKpiVO;
import com.community.booking.vo.DashboardTrendRowVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 运营看板聚合查询 Mapper（Batch 4）。
 * <p>为什么单独成类而不塞进 ServiceOrderMapper：看板的六组指标全部是
 * <b>跨表聚合</b>（订单 + 项目 + 分类 + 用户 + 评价 + 师傅档案），既不属于
 * 订单领域的常规 CRUD，也不该让订单 Mapper 承担报表职责；独立 Mapper 让
 * "交易路径"与"分析路径"在索引与锁的视角上彻底分离——看板慢查询不会影响下单。</p>
 * <p>全部查询走索引覆盖或小结果集聚合：</p>
 * <ul>
 *   <li>KPI 用多条标量子查询一次取回（每条的 WHERE 都命中 idx_order_status /
 *       idx_order_create_time 等索引，代价可控）；</li>
 *   <li>趋势按天 GROUP BY，只扫最近 7 天分区（create_time 索引范围扫描）；</li>
 *   <li>分类/师傅排行在聚合后排序取前 5，避免把全量明细拉回应用层。</li>
 * </ul>
 * <p>SQL 100% 使用 #{}（本类无参，全部为常量条件），无任何字符串拼接注入面。</p>
 *
 * @author community-booking
 */
@Mapper
public interface DashboardMapper {

    /**
     * 运营核心指标（一次查询取回 8 个标量，避免 8 次往返）。
     * <p>口径说明：</p>
     * <ul>
     *   <li>todayOrders：今日新建订单数（含已取消，反映真实下单量）；</li>
     *   <li>pendingOrders：当前待派单且未归档（派单中心的实时待办量）；</li>
     *   <li>activeOrders：当前已派单 + 服务中（在途工单量）；</li>
     *   <li>todayCompleted：今日完成数（按 finish_time 落在今日）；</li>
     *   <li>avgStar30d：近 30 天评价平均星级（服务水平趋势，无评价时为 0）；</li>
     *   <li>idleWorkers / totalWorkers：空闲师傅数 / 师傅总数（运力水位）；</li>
     *   <li>weekCancelled：近 7 天取消单数（含超时自动取消，服务质量预警）。</li>
     * </ul>
     *
     * @return 指标行（字段与 DashboardKpiVO 一一对应）
     */
    @Select("SELECT "
            + "(SELECT COUNT(*) FROM service_order WHERE DATE(create_time) = CURDATE()) AS today_orders, "
            + "(SELECT COUNT(*) FROM service_order WHERE order_status = 0 AND is_archived = 0) AS pending_orders, "
            + "(SELECT COUNT(*) FROM service_order WHERE order_status IN (1, 2) AND is_archived = 0) AS active_orders, "
            + "(SELECT COUNT(*) FROM service_order WHERE order_status = 3 "
            + " AND finish_time IS NOT NULL AND DATE(finish_time) = CURDATE()) AS today_completed, "
            + "(SELECT IFNULL(ROUND(AVG(star), 2), 0) FROM service_evaluation "
            + " WHERE create_time >= DATE_SUB(NOW(), INTERVAL 30 DAY)) AS avg_star30d, "
            + "(SELECT COUNT(*) FROM worker_profile WHERE work_status = 1) AS idle_workers, "
            + "(SELECT COUNT(*) FROM worker_profile) AS total_workers, "
            + "(SELECT COUNT(*) FROM service_order WHERE order_status = 4 "
            + " AND create_time >= DATE_SUB(CURDATE(), INTERVAL 7 DAY)) AS week_cancelled")
    DashboardKpiVO selectKpi();

    /**
     * 近 7 日订单趋势（按下单日期聚合，缺失日期由 Service 层补 0）。
     * <p>只返回库内实际有单的日期行：SQL 不生成日期序列（MySQL 无内置序列，
     * 用递归 CTE 会显著增加可读性负担），由 Service 用 7 天循环对齐日期，
     * 既保证图表 X 轴连续，也让"某天无单"明确显示为 0 而不是断点。</p>
     *
     * @return 每日聚合行（stat_date / total / completed）
     */
    @Select("SELECT DATE(create_time) AS stat_date, COUNT(*) AS total, "
            + "SUM(CASE WHEN order_status = 3 THEN 1 ELSE 0 END) AS completed "
            + "FROM service_order "
            + "WHERE create_time >= DATE_SUB(CURDATE(), INTERVAL 6 DAY) "
            + "GROUP BY DATE(create_time) "
            + "ORDER BY stat_date")
    List<DashboardTrendRowVO> selectTrend7Days();

    /**
     * 订单状态分布（未归档订单，用于环形图）。
     * <p>归档单（完成超 7 天）已退出常规运营视野，纳入占比会让"历史单"
     * 长期压制近期状态分布，故此处按 is_archived = 0 过滤。</p>
     *
     * @return 状态行（stat_name 为状态码字符串，stat_value 为数量）
     */
    @Select("SELECT CAST(order_status AS CHAR) AS stat_name, COUNT(*) AS stat_value "
            + "FROM service_order WHERE is_archived = 0 "
            + "GROUP BY order_status ORDER BY order_status")
    List<DashboardCountRowVO> selectStatusDistribution();

    /**
     * 服务分类占比（按订单数，用于饼图）。
     * <p>以订单行的 service_id 关联项目再关联分类——即便项目后来被改分类，
     * 历史订单也会归到"当前分类"，与前端服务列表的口径保持一致。</p>
     *
     * @return 分类行（stat_name 为分类名，stat_value 为订单数）
     */
    @Select("SELECT IFNULL(sc.category_name, '未分类') AS stat_name, COUNT(o.id) AS stat_value "
            + "FROM service_order o "
            + "JOIN service_item si ON si.id = o.service_id "
            + "LEFT JOIN service_category sc ON sc.id = si.category_id "
            + "GROUP BY sc.category_name ORDER BY stat_value DESC")
    List<DashboardCountRowVO> selectCategoryDistribution();

    /**
     * 师傅接单排行 TOP5（按已完成工单数，用于横向柱状图）。
     * <p>统计口径为"已完成"而非"被派单"：派单量可能因改派而虚高，
     * 完成量才真实反映服务产出。</p>
     *
     * @return 排行行（stat_name 为师傅姓名，stat_value 为完成单数）
     */
    @Select("SELECT u.real_name AS stat_name, COUNT(o.id) AS stat_value "
            + "FROM service_order o JOIN sys_user u ON u.id = o.worker_id "
            + "WHERE o.order_status = 3 "
            + "GROUP BY o.worker_id, u.real_name "
            + "ORDER BY stat_value DESC, o.worker_id ASC LIMIT 5")
    List<DashboardCountRowVO> selectWorkerRankTop5();
}
