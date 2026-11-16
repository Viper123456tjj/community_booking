package com.community.booking.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 运营看板核心指标行（DashboardMapper.selectKpi 的映射目标）。
 * <p>字段与 SQL 别名按驼峰规则一一对应（map-underscore-to-camel-case 生效），
 * 全部使用包装类型：COUNT 在极端情况下返回 null 时不会因拆箱抛 NPE。</p>
 *
 * @author community-booking
 */
@Data
public class DashboardKpiVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 今日新建订单数（含已取消，反映真实下单量） */
    private Long todayOrders;

    /** 当前待派单且未归档（派单中心实时待办量） */
    private Long pendingOrders;

    /** 当前在途工单数（已派单 + 服务中） */
    private Long activeOrders;

    /** 今日完成订单数（按 finish_time 落在今日） */
    private Long todayCompleted;

    /** 近 30 天评价平均星级（无评价为 0） */
    private BigDecimal avgStar30d;

    /** 当前空闲师傅数（运力水位） */
    private Long idleWorkers;

    /** 师傅总数 */
    private Long totalWorkers;

    /** 近 7 天取消单数（含超时自动取消） */
    private Long weekCancelled;
}
