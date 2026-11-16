package com.community.booking.dto;

import lombok.Data;

import javax.validation.constraints.Email;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 管理员账号保存入参 DTO（仅 ROLE_ADMIN；id 为空新增，否则修改）。
 * <p>密码规则与社团项目一致：新增留空取初始密码 123456，修改留空表示
 * 不变更；入库前一律 BCrypt 加密。role/status 由管理员显式指定，
 * Service 层校验角色合法值与"不得禁用自己"约束。</p>
 *
 * @author community-booking
 */
@Data
public class UserSaveDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 用户ID（为空=新增，非空=修改） */
    private Long id;

    /** 登录用户名（唯一，Service 层排除自身查重） */
    @NotBlank(message = "登录用户名不能为空")
    @Pattern(regexp = "[a-zA-Z][a-zA-Z0-9_]{2,31}", message = "用户名为3-32位字母开头的字母数字下划线")
    private String username;

    /** 密码明文（可空：新增留空取默认 123456，修改留空不变更；严禁回传） */
    @Size(min = 6, max = 32, message = "密码长度须为6-32位")
    private String password;

    /** 真实姓名 */
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

    /** 居住地址（可空；师傅为常驻小区，居民为门牌地址） */
    @Size(max = 128, message = "地址最长128字符")
    private String address;

    /** 角色：ROLE_RESIDENT/ROLE_WORKER/ROLE_ADMIN（新增必填；修改可空=不变更） */
    @Pattern(regexp = "^$|ROLE_(RESIDENT|WORKER|ADMIN)", message = "角色须为三级角色之一")
    private String role;

    /** 账号状态：1正常/0禁用（可空默认1） */
    @Min(value = 0, message = "状态最小为0")
    @Max(value = 1, message = "状态最大为1")
    private Integer status;
}
