package com.community.booking.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 运营看板聚合出参（一次请求返回 6 组数据，ECharts 无需二次请求）。
 * <p>为什么聚合而非拆分接口：看板是"一屏六图"的强关联场景，拆成 6 个接口
 * 会让首屏出现 6 次 RTT 与 6 段独立加载状态；聚合后前端只需一次
 * `/api/admin/dashboard/overview`，六张图同时渲染，体验与实现都更简单。</p>
 * <p>结构说明：kpi 为顶部指标卡，其余五个列表分别对应折线/饼图/环形/
 * 横向柱/热门榜；NameValue 的 name-value 形状可直接喂给 ECharts 的
 * dataset，前端无需再做字段映射。</p>
 *
 * @author community-booking
 */
@Data
public class DashboardVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 核心指标卡（今日单量、待派单、在途、平均星级、运力水位等） */
    private Kpi kpi;

    /** 近 7 日订单趋势（折线图：新建 vs 完成） */
    private List<TrendPoint> trend;

    /** 订单状态分布（环形图） */
    private List<NameValue> statusDistribution;

    /** 服务分类占比（饼图） */
    private List<NameValue> categoryDistribution;

    /** 师傅接单排行 TOP5（横向柱状图） */
    private List<NameValue> workerRank;

    /** 热门服务 TOP5（条形图，数据源为 Redis 热度榜，Redis 不可用时回退库内 hot_score） */
    private List<NameValue> hotItems;

    /**
     * 指标卡视图（与 DashboardKpiVO 字段同构，单独定义是为了让 VO 层
     * 与 Mapper 映射对象解耦：Mapper 改列不影响前端契约）。
     */
    @Data
    public static class Kpi implements Serializable {

        /** 序列化版本号 */
        private static final long serialVersionUID = 1L;

        /** 今日新建订单数 */
        private Long todayOrders;

        /** 当前待派单量 */
        private Long pendingOrders;

        /** 当前在途工单量 */
        private Long activeOrders;

        /** 今日完成量 */
        private Long todayCompleted;

        /** 近 30 天平均星级 */
        private BigDecimal avgStar30d;

        /** 空闲师傅数 */
        private Long idleWorkers;

        /** 师傅总数 */
        private Long totalWorkers;

        /** 近 7 天取消单数 */
        private Long weekCancelled;
    }

    /**
     * 趋势点（X 轴日期 + 两个系列值）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrendPoint implements Serializable {

        /** 序列化版本号 */
        private static final long serialVersionUID = 1L;

        /** X 轴标签（MM-dd） */
        private String date;

        /** 当日新建订单数 */
        private Long total;

        /** 当日完成订单数 */
        private Long completed;
    }

    /**
     * 通用名称-数值对（饼图/柱图/榜单共用）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NameValue implements Serializable {

        /** 序列化版本号 */
        private static final long serialVersionUID = 1L;

        /** 名称（分类名/状态名/师傅姓名/服务名称） */
        private String name;

        /** 数值 */
        private Long value;
    }
}
