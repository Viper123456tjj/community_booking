package com.community.booking.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 通用"名称-数值"聚合行（状态分布 / 分类占比 / 师傅排行三张图共用）。
 * <p>三张图的数据形状完全一致（分类名 + 计数），共用同一个映射目标可以少写
 * 两个只差类名的 VO；SQL 里统一用 {@code AS stat_name / AS stat_value} 别名，
 * 让 Mapper 的目标类型与查询解耦——换图表只需改 SQL，不必新增实体。</p>
 *
 * @author community-booking
 */
@Data
public class DashboardCountRowVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 名称（分类名 / 师傅姓名 / 状态码字符串） */
    private String statName;

    /** 数值（订单数 / 完成单数） */
    private Long statValue;
}
