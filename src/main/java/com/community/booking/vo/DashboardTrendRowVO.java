package com.community.booking.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * 近 7 日趋势聚合行（DashboardMapper.selectTrend7Days 的映射目标）。
 * <p>数据库返回的是"有单日期"的稀疏集合，Service 层再按连续 7 天对齐补 0，
 * 保证 ECharts 的 X 轴不会因为缺日期而错位。</p>
 *
 * @author community-booking
 */
@Data
public class DashboardTrendRowVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 统计日期（下单日） */
    private LocalDate statDate;

    /** 当日新建订单数（含已取消） */
    private Long total;

    /** 当日完成订单数 */
    private Long completed;
}
