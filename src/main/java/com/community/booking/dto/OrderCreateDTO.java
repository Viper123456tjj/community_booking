package com.community.booking.dto;

import lombok.Data;

import javax.validation.constraints.Future;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 居民下单入参 DTO（POST /api/order 请求体）。
 * <p>安全设计：下单人身份一律取当前登录用户，严禁前端传 residentId；
 * 价格由服务端按 service_item 现查快照，前端传价无效。JSR-303 校验
 * 在 Controller 层经 @Valid 触发，业务校验（服务上下架、时间合理区间）
 * 在 ServiceImpl 完成。</p>
 *
 * @author community-booking
 */
@Data
public class OrderCreateDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 服务项目ID（须为上架状态，服务端校验） */
    @NotNull(message = "请选择服务项目")
    private Long serviceId;

    /** 预约上门时间（必须晚于当前时间且不超过 30 天，服务端二次校验） */
    @NotNull(message = "预约上门时间不能为空")
    @Future(message = "预约上门时间必须晚于当前时间")
    private LocalDateTime appointTime;

    /** 服务地址（默认带入居民档案地址，可修改） */
    @NotBlank(message = "服务地址不能为空")
    @Size(max = 128, message = "服务地址最长128字符")
    private String serviceAddress;

    /** 联系电话（默认带入居民手机号，可修改） */
    @NotBlank(message = "联系电话不能为空")
    @Pattern(regexp = "1[3-9]\\d{9}", message = "联系电话须为11位手机号")
    private String contactPhone;

    /** 下单备注（可空，最长255字符，师傅接单时可见） */
    @Size(max = 255, message = "备注最长255字符")
    private String remark;
}
