package com.community.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统用户实体（对应表 sys_user），承载社区居民/便民服务人员/物业管理员三级角色账号。
 * <p>password 字段存储 BCryptPasswordEncoder 生成的密文，严禁明文入库；
 * role 取值限定为 ROLE_RESIDENT / ROLE_WORKER / ROLE_ADMIN，
 * 与 Spring Security 角色约定一致。</p>
 *
 * @author community-booking
 */
@Data
// 表名锁定为 sys_user
@TableName("sys_user")
public class SysUser implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 用户主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 登录用户名，唯一约束 uk_username，Spring Security 认证主体 */
    @TableField("username")
    private String username;

    /** BCrypt 密文密码（明文统一 123456），出参时通过 VO 剔除，严禁回传前端 */
    @TableField("password")
    private String password;

    /** 真实姓名，用于订单联系人展示与评价轨迹展示 */
    @TableField("real_name")
    private String realName;

    /** 手机号（下单时默认带入订单联系电话） */
    @TableField("phone")
    private String phone;

    /** 邮箱（必填），派单/完成/取消通知与次日上门提醒邮件的收件地址 */
    @TableField("email")
    private String email;

    /** 居住地址（居民下单时默认带入服务地址） */
    @TableField("address")
    private String address;

    /** 角色字符串：ROLE_RESIDENT / ROLE_WORKER / ROLE_ADMIN */
    @TableField("role")
    private String role;

    /** 账号状态：1 正常 / 0 禁用（禁用账号登录被拦截，且不得作为派单候选） */
    @TableField("status")
    private Integer status;

    /** 创建时间，由 Service 层显式赋值 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
