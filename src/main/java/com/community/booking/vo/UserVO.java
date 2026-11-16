package com.community.booking.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户账号出参 VO（管理员账号管理、师傅名单、评价轨迹与个人中心共用）。
 * <p>安全红线：绝不包含 password 字段——BCrypt 密文严禁回传前端，
 * 这是 Entity 与 VO 分离的最典型场景。</p>
 *
 * @author community-booking
 */
@Data
public class UserVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long id;

    /** 登录用户名 */
    private String username;

    /** 真实姓名 */
    private String realName;

    /** 手机号 */
    private String phone;

    /** 邮箱（派单/完成/取消通知与次日提醒邮件的收件地址） */
    private String email;

    /** 居住地址 */
    private String address;

    /** 角色字符串：ROLE_RESIDENT / ROLE_WORKER / ROLE_ADMIN */
    private String role;

    /** 状态：1正常/0禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;
}
