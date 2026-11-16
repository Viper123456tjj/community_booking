package com.community.booking.config;

import com.community.booking.entity.SysUser;
import com.community.booking.utils.Constants;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

/**
 * Spring Security 认证主体：包装 SysUser，承载登录校验与三级鉴权所需信息。
 * <p>设计要点：</p>
 * <ul>
 *   <li>sys_user.role 本身就是 ROLE_RESIDENT/ROLE_WORKER/ROLE_ADMIN 的完整形式，
 *       与 GrantedAuthority 约定一致，无需二次拼接 ROLE_ 前缀；</li>
 *   <li>status=0 时 isEnabled() 返回 false，DaoAuthenticationProvider 自动抛
 *       DisabledException 拦截禁用账号（init.sql 中的 worker8/resident8 即此样本）；</li>
 *   <li>保留 user 实体，供 SecurityUtils 从 SecurityContextHolder 组装
 *       LoginUserDTO——Service 层 IDOR 越权校验的操作者身份唯一来源。</li>
 * </ul>
 *
 * @author community-booking
 */
@Getter
public class LoginUserDetails implements UserDetails {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 被包装的用户实体（含 BCrypt 密文，仅参与认证，绝不回传前端） */
    private final SysUser user;

    /**
     * 构造认证主体。
     *
     * @param user 已通过存在性校验的用户实体
     */
    public LoginUserDetails(SysUser user) {
        this.user = user;
    }

    /**
     * 权限集合：role 字段已是完整 ROLE_* 形式，直接映射为单一权限。
     *
     * @return 仅含一个 ROLE_* 权限的集合
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority(user.getRole()));
    }

    /**
     * BCrypt 密文密码（由 PasswordEncoder 在认证时比对）。
     *
     * @return 密文密码
     */
    @Override
    public String getPassword() {
        return user.getPassword();
    }

    /**
     * 登录用户名。
     *
     * @return username
     */
    @Override
    public String getUsername() {
        return user.getUsername();
    }

    /**
     * 账号未过期（本系统无账号有效期设计，恒为 true）。
     *
     * @return true
     */
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * 账号未锁定（本系统无登录失败锁定策略，恒为 true）。
     *
     * @return true
     */
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /**
     * 凭证未过期（BCrypt 密文无过期概念，恒为 true）。
     *
     * @return true
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * 账号可用性：status=1 正常可用，status=0 禁用（登录被框架拦截）。
     *
     * @return 是否启用
     */
    @Override
    public boolean isEnabled() {
        return user.getStatus() != null && user.getStatus() == Constants.USER_STATUS_NORMAL;
    }
}
