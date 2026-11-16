package com.community.booking.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

/**
 * 订单分页查询入参 DTO（居民"我的预约"、师傅"我的工单"、管理员派单中心三视角共用）。
 * <p>在通用分页参数之上叠加状态与归属筛选：orderStatus 精确筛状态机各态；
 * categoryId/serviceId 用于派单中心按服务维度过滤。三视角的归属条件
 * （residentId/workerId/不限）一律由 Service 层根据登录角色注入，
 * 严禁前端直传，杜绝越权窥探他人订单。</p>
 *
 * @author community-booking
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderPageQueryDTO extends PageQueryDTO {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 订单状态筛选：0待派单/1已派单/2服务中/3已完成/4已取消（可空=不限） */
    @Min(value = 0, message = "订单状态最小为0")
    @Max(value = 4, message = "订单状态最大为4")
    private Integer orderStatus;

    /** 服务分类筛选（可空=不限；管理员派单中心按品类过滤） */
    private Long categoryId;

    /** 服务项目筛选（可空=不限） */
    private Long serviceId;
}
