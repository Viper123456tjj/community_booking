package com.community.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.community.booking.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 系统用户 Mapper：继承 BaseMapper 获得单表 CRUD，仅补充认证链路所需的
 * 按用户名精确查询一个自定义方法。
 * <p>selectByUsername 是 Spring Security 认证数据源（UserDetailsService）与
 * 管理员账号保存查重的唯一专用查询：唯一索引 uk_username 直接命中；
 * 全部 SQL 100% #{} 预编译，无 ${} 拼接注入风险。</p>
 *
 * @author community-booking
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按登录用户名精确查询用户（含 BCrypt 密文密码，仅供认证与查重内部消费，
     * 不经过任何 Controller 出参，密文天然不会外泄）。
     *
     * @param username 登录用户名
     * @return 用户实体（含密文密码）；不存在时返回 null
     */
    @Select("SELECT id, username, password, real_name, phone, email, address, role, status, create_time "
            + "FROM sys_user WHERE username = #{username} LIMIT 1")
    SysUser selectByUsername(@Param("username") String username);
}
