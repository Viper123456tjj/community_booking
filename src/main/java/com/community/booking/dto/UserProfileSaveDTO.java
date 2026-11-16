package com.community.booking.dto;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 个人中心资料修改入参 DTO（三类角色共用，PUT /api/user/profile）。
 * <p>与管理员账号管理的 UserSaveDTO 区分：本 DTO 严禁携带 role 与 status
 * 字段——普通用户不得提权或自我禁用；密码修改走独立的修改密码接口，
 * 本表单不含密码字段，避免明文密码随资料表单往返。</p>
 *
 * @author community-booking
 */
@Data
public class UserProfileSaveDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 真实姓名（1-32字符） */
    @NotBlank(message = "真实姓名不能为空")
    @Size(max = 32, message = "真实姓名最长32字符")
    private String realName;

    /** 手机号（可空；非空须为11位手机号） */
    @Pattern(regexp = "^$|1[3-9]\\d{9}", message = "手机号须为11位手机号")
    private String phone;

    /** 邮箱（必填，邮件通知收件地址） */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱最长64字符")
    private String email;

    /** 居住地址（居民下单默认地址来源，可空，最长128字符） */
    @Size(max = 128, message = "地址最长128字符")
    private String address;
}
