package com.community.booking.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 服务评价出参 VO（师傅端"收到的评价"、管理员评价管理与居民"我的评价"共用）。
 * <p>residentName/workerName/serviceItemName 由 Mapper JOIN 直出（避免 N+1）；
 * orderNo 联表直出便于追溯评价来源订单。</p>
 *
 * @author community-booking
 */
@Data
public class EvaluationVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 评价ID */
    private Long id;

    /** 订单ID */
    private Long orderId;

    /** 订单号（联表直出，追溯评价来源） */
    private String orderNo;

    /** 评价居民ID */
    private Long residentId;

    /** 评价居民姓名（联表直出；师傅视角无需隐去，评价本就署名） */
    private String residentName;

    /** 被评价师傅ID */
    private Long workerId;

    /** 被评价师傅姓名（联表直出） */
    private String workerName;

    /** 被评价服务项目ID */
    private Long serviceId;

    /** 被评价服务项目名称（联表直出） */
    private String serviceItemName;

    /** 星级：1~5星 */
    private Integer star;

    /** 文字评价内容 */
    private String comment;

    /** 评价时间 */
    private LocalDateTime createTime;
}
