package com.community.booking.service;

import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.PageQueryDTO;
import com.community.booking.dto.UserProfileSaveDTO;
import com.community.booking.dto.UserSaveDTO;
import com.community.booking.entity.SysUser;
import com.community.booking.vo.PageVO;
import com.community.booking.vo.UserVO;

/**
 * 系统用户业务接口：个人中心资料维护 + 管理员账号管理 + 认证数据源支撑。
 * <p>分层铁律：本接口全部业务逻辑（查重、BCrypt 加密、越权校验、默认密码）
 * 在 ServiceImpl 完成，Controller 不得直接注入 SysUserMapper。
 * 密码密文只在认证链路内部流转，任何方法都不回传含密码的实体。</p>
 *
 * @author community-booking
 */
public interface SysUserService {

    /**
     * 按用户ID查询用户实体（含密文密码），仅供认证链路与内部业务组装使用，
     * 严禁直接作为 Controller 出参。
     *
     * @param id 用户ID
     * @return 用户实体；不存在时返回 null
     */
    SysUser getUserById(Long id);

    /**
     * 按登录用户名查询用户实体（含密文密码），Spring Security 认证数据源。
     *
     * @param username 登录用户名
     * @return 用户实体；不存在时返回 null
     */
    SysUser getUserByUsername(String username);

    /**
     * 查询当前登录用户的信息（VO，不含密码）。
     *
     * @param currentUser 当前登录用户上下文（IDOR 归属锚点）
     * @return 当前用户 VO
     */
    UserVO getCurrentUserInfo(LoginUserDTO currentUser);

    /**
     * 修改当前登录用户的个人资料（姓名/手机号/邮箱/地址）。
     * <p>越权防线：本方法不接受 userId 参数，只能改自己的行；role 与 status
     * 不接受普通用户提交，杜绝自我提权与自我禁用。</p>
     *
     * @param currentUser 当前登录用户上下文
     * @param dto         资料修改入参
     */
    void updateProfile(LoginUserDTO currentUser, UserProfileSaveDTO dto);

    /**
     * 修改当前登录用户的密码（校验旧密码后 BCrypt 加密保存新密码）。
     *
     * @param currentUser 当前登录用户上下文
     * @param oldPassword 旧密码明文
     * @param newPassword 新密码明文（6-32位）
     */
    void updatePassword(LoginUserDTO currentUser, String oldPassword, String newPassword);

    /**
     * 管理员分页查询账号列表（可按角色/状态筛选、关键字模糊）。
     *
     * @param query  分页查询入参（keyword 模糊匹配用户名/姓名/手机号）
     * @param role   角色筛选 ROLE_RESIDENT/ROLE_WORKER/ROLE_ADMIN（可空=不限）
     * @param status 账号状态筛选 1正常/0禁用（可空=不限）
     * @return 用户 VO 分页
     */
    PageVO<UserVO> pageUsers(PageQueryDTO query, String role, Integer status);

    /**
     * 管理员新增或修改账号（id 为空新增，否则修改）。
     * <p>业务规则：用户名排除自身查重；新增密码留空取初始 123456；
     * 修改留空表示不变更；修改时禁止禁用当前登录的管理员本人；
     * 角色为 ROLE_WORKER 的新增/改角色用户同步初始化 worker_profile 档案。</p>
     *
     * @param dto         账号保存入参
     * @param currentUser 当前登录管理员（用于"不得禁用自己"校验）
     */
    void saveUser(UserSaveDTO dto, LoginUserDTO currentUser);

    /**
     * 管理员删除账号（物理删除前校验业务引用：有订单、评价或师傅在途工单
     * 的账号禁止删除，保证无悬挂外键）。
     *
     * @param id          目标用户ID
     * @param currentUser 当前登录管理员（不得删除自己）
     */
    void removeUser(Long id, LoginUserDTO currentUser);
}
