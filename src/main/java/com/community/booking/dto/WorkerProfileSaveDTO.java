package com.community.booking.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 师傅档案维护入参 DTO（PUT /api/worker/profile 与管理员端师傅档案修改共用）。
 * <p>仅承载可编辑字段：技能标签与接单状态诉求。rating_score/total_orders
 * 是系统派生指标，严禁前端直传；busy(2) 状态由系统按在手工单自动置位，
 * 师傅本人只能提交 1空闲/0休息，管理员可代为强制休息（Service 层区分）。</p>
 *
 * @author community-booking
 */
@Data
public class WorkerProfileSaveDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 目标师傅用户ID（师傅本人维护时由 Service 置为登录用户，管理员代管时显式传入） */
    @NotNull(message = "师傅用户ID不能为空")
    private Long userId;

    /** 技能标签（| 分隔，标签须落在服务技能词表内保证派单可达；可空=不变更） */
    @Pattern(regexp = "[\\u4e00-\\u9fa5A-Za-z0-9]+(\\|[\\u4e00-\\u9fa5A-Za-z0-9]+)*",
            message = "技能标签须为竖线分隔的标签串，标签内不允许空白与特殊字符")
    @Size(max = 255, message = "技能标签最长255字符")
    private String skillTags;

    /** 期望接单状态：1空闲/0休息（2忙碌由系统在派单/开工时自动置位，不接受提交） */
    @Min(value = 0, message = "接单状态最小为0")
    @Max(value = 1, message = "师傅只能自选空闲(1)或休息(0)")
    private Integer workStatus;
}
