package com.community.booking.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.io.Serializable;

/**
 * 派单入参 DTO（POST /api/admin/order/dispatch，管理员派单中心"候选确认"或手工指派）。
 * <p>设计说明：派单方式随请求提交（AUTO=确认智能候选结果、MANUAL=管理员手工指派），
 * 改派走独立的改派接口并固定记为 REASSIGN，不允许前端伪造改派标记；
 * 承接师傅的资格（账号启用、师傅角色、非休息、技能覆盖）全部由 Service 层
 * 现查现校验并在数据库层原子占忙，前端提交候选结果不构成信任前提。</p>
 *
 * @author community-booking
 */
@Data
public class DispatchDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 待派单订单ID（须为状态0待派单，Service 层条件 UPDATE 校验） */
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /** 承接师傅用户ID（sys_user.id，须为 ROLE_WORKER 且具备所需技能） */
    @NotNull(message = "承接师傅不能为空")
    private Long workerId;

    /** 派单方式：AUTO智能候选确认 / MANUAL管理员手工指派（留空默认 MANUAL） */
    @Pattern(regexp = "^$|AUTO|MANUAL", message = "派单方式只能为 AUTO 或 MANUAL")
    private String dispatchType;
}
