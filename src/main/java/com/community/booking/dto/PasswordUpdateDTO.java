package com.community.booking.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 修改密码入参 DTO（PUT /api/user/password，三类角色共用）。
 * <p>与个人资料表单分离：密码不出现在资料表单中，避免明文密码随
 * 资料往返被浏览器自动填充或记录；旧密码由 Service 层用 BCrypt
 * 比对当前密文后才允许提交新密码，防止"会话被盗后直接改密码锁人"。</p>
 *
 * @author community-booking
 */
@Data
public class PasswordUpdateDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 旧密码明文（须与当前 BCrypt 密文匹配） */
    @NotBlank(message = "旧密码不能为空")
    @Size(max = 32, message = "旧密码最长32个字符")
    private String oldPassword;

    /** 新密码明文（6-32位，入库前 BCrypt 加密） */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 32, message = "新密码长度须为6-32位")
    private String newPassword;
}
