package com.community.booking.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 登录表单入参 DTO（POST /api/auth/login）。
 * <p>仅做非空与长度校验；真正的凭据比对由 AuthenticationManager 完成
 * （DaoAuthenticationProvider + BCryptPasswordEncoder），失败时由
 * AuthController 统一转换为 401 的 Result，避免泄露"用户名不存在"
 * 这类账号枚举信息。</p>
 * <p>本系统三类角色（居民/师傅/管理员）共用同一登录入口，登录后由
 * 认证主体携带的 role 决定可访问的接口前缀与页面首页，不需要
 * "选择身份"这类额外参数——身份始终以库内账号为准，前端不可伪造。</p>
 *
 * @author community-booking
 */
@Data
public class LoginDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 登录用户名 */
    @NotBlank(message = "用户名不能为空")
    @Size(max = 32, message = "用户名最长32个字符")
    private String username;

    /** 登录密码（明文，仅用于比对，不落库不回显） */
    @NotBlank(message = "密码不能为空")
    @Size(max = 32, message = "密码最长32个字符")
    private String password;
}
