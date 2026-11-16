package com.community.booking.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * 改派入参 DTO（POST /api/admin/reassign，更换"已派单未开工"订单的承接师傅）。
 * <p>改派方式由接口场景固化为 REASSIGN（前端无法伪造 dispatch_type），
 * 与派单接口的 AUTO/MANUAL 分离，审计轨迹更清晰；新旧师傅资格校验沿用
 * Service 层统一口径（账号启用、技能覆盖、非休息、数据库原子占忙）。</p>
 *
 * @author community-booking
 */
@Data
public class ReassignDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 订单ID（须为状态1已派单未开工） */
    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    /** 新承接师傅用户ID（须与当前承接人不同，Service 层校验） */
    @NotNull(message = "新承接师傅不能为空")
    private Long newWorkerId;
}
