package com.community.booking.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 服务订单出参 VO（三视角订单列表与订单详情共用，一条联表 SQL 直出全部展示字段）。
 * <p>serviceItemName/categoryName/residentName/workerName 由 Mapper JOIN 直出
 * （避免 N+1）；canCancel/canEvaluate 为派生动作标记，由 SQL CASE WHEN 按
 * 订单状态机口径直出（可取消=待派单且本人视角，可评价=已完成未归档且完成
 * 未超7天且本人视角），viewerId 传 null（管理员视角）时两标记恒为 false，
 * 由 Service 层置 null 表达"非本人视角"。orderStatusText 由 Service 层
 * 按状态映射填充中文，供表格直接渲染状态徽章。</p>
 *
 * @author community-booking
 */
@Data
public class OrderVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 订单ID */
    private Long id;

    /** 订单号 */
    private String orderNo;

    /** 下单居民ID */
    private Long residentId;

    /** 下单居民姓名（联表直出，师傅/管理员视角展示） */
    private String residentName;

    /** 服务项目ID */
    private Long serviceId;

    /** 服务项目名称（联表直出） */
    private String serviceItemName;

    /** 服务项目所属分类名称（二级联表直出，派单中心按类展示） */
    private String categoryName;

    /** 承接师傅ID（待派单为 null） */
    private Long workerId;

    /** 承接师傅姓名（联表直出，待派单为 null） */
    private String workerName;

    /** 预约上门时间 */
    private LocalDateTime appointTime;

    /** 服务地址 */
    private String serviceAddress;

    /** 联系电话（居民与派单师傅可见；Service 层对无关视角做脱敏置 null） */
    private String contactPhone;

    /** 下单备注（取消单追记的取消原因随此字段回传） */
    private String remark;

    /** 下单价格快照（元） */
    private BigDecimal price;

    /** 订单状态：0待派单/1已派单/2服务中/3已完成/4已取消 */
    private Integer orderStatus;

    /** 订单状态中文描述（Service 层按状态映射填充） */
    private String orderStatusText;

    /** 派单方式：AUTO/MANUAL/REASSIGN（未派单为 null） */
    private String dispatchType;

    /** 派单方式中文描述（Service 层映射：智能派单/手动派单/改派） */
    private String dispatchTypeText;

    /** 服务完成时间 */
    private LocalDateTime finishTime;

    /** 归档标记：0正常/1已归档 */
    private Integer isArchived;

    /** 是否可取消（本人视角派生：待派单且为下单本人；管理员视角 null） */
    private Boolean canCancel;

    /** 是否可评价（本人视角派生：已完成未归档且完成未超7天；管理员视角 null） */
    private Boolean canEvaluate;

    /** 是否已评价（联表 EXISTS 直出，列表页据此隐藏评价入口） */
    private Boolean evaluated;

    /** 该订单的评价记录ID（已评价时回传，供查看自己评价内容；未评价为 null） */
    private Long evaluationId;

    /** 下单时间 */
    private LocalDateTime createTime;

    /** 状态更新时间 */
    private LocalDateTime updateTime;
}
