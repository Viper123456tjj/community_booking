package com.community.booking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.PageQueryDTO;
import com.community.booking.dto.UserProfileSaveDTO;
import com.community.booking.dto.UserSaveDTO;
import com.community.booking.entity.ServiceEvaluation;
import com.community.booking.entity.ServiceOrder;
import com.community.booking.entity.SysUser;
import com.community.booking.entity.WorkerProfile;
import com.community.booking.exception.BusinessException;
import com.community.booking.exception.ForbiddenException;
import com.community.booking.exception.NotFoundException;
import com.community.booking.mapper.ServiceEvaluationMapper;
import com.community.booking.mapper.ServiceOrderMapper;
import com.community.booking.mapper.SysUserMapper;
import com.community.booking.mapper.WorkerProfileMapper;
import com.community.booking.service.SysUserService;
import com.community.booking.service.WorkerProfileService;
import com.community.booking.utils.Constants;
import com.community.booking.vo.PageVO;
import com.community.booking.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 系统用户业务实现：个人中心资料/密码维护 + 管理员账号管理 + 认证数据源。
 * <p>安全要点：密文密码只在认证链路内部流转，出参一律转 UserVO（无 password
 * 字段）；个人资料下载一律以登录用户ID为准，不接受外部传入的 userId；
 * 管理员写操作全部带业务校验（查重、不得禁用/删除自己、角色合法值、
 * 删除前引用校验防悬挂外键）。</p>
 *
 * @author community-booking
 */
@Slf4j
@Service
public class SysUserServiceImpl implements SysUserService {

    /** 初始密码明文（管理员新增账号留空密码时使用，与 init.sql 测试数据一致） */
    private static final String DEFAULT_PASSWORD = "123456";

    /** 合法角色集合（与 Constants 三个角色常量一致，用于提交值白名单校验） */
    private static final Set<String> VALID_ROLES = Set.of(
            Constants.ROLE_RESIDENT, Constants.ROLE_WORKER, Constants.ROLE_ADMIN);

    /** 系统用户 Mapper（认证与账号管理数据源） */
    private final SysUserMapper sysUserMapper;
    /** 师傅档案 Mapper（角色变更为师傅时联动建档案） */
    private final WorkerProfileMapper workerProfileMapper;
    /** 订单 Mapper（删除账号前校验订单引用） */
    private final ServiceOrderMapper serviceOrderMapper;
    /** 评价 Mapper（删除账号前校验评价引用） */
    private final ServiceEvaluationMapper serviceEvaluationMapper;
    /** BCrypt 编码器（与 SecurityConfig 共用同一 Bean） */
    private final PasswordEncoder passwordEncoder;
    /** 师傅档案业务（跨表联动统一走 Service，不直连别的领域的 Mapper 写操作） */
    private final WorkerProfileService workerProfileService;

    /**
     * 构造注入全部依赖。
     *
     * @param sysUserMapper             用户 Mapper
     * @param workerProfileMapper       师傅档案 Mapper
     * @param serviceOrderMapper        订单 Mapper
     * @param serviceEvaluationMapper   评价 Mapper
     * @param passwordEncoder           BCrypt 编码器
     * @param workerProfileService      师傅档案业务（档案初始化联动）
     */
    public SysUserServiceImpl(SysUserMapper sysUserMapper, WorkerProfileMapper workerProfileMapper,
                              ServiceOrderMapper serviceOrderMapper,
                              ServiceEvaluationMapper serviceEvaluationMapper,
                              PasswordEncoder passwordEncoder, WorkerProfileService workerProfileService) {
        this.sysUserMapper = sysUserMapper;
        this.workerProfileMapper = workerProfileMapper;
        this.serviceOrderMapper = serviceOrderMapper;
        this.serviceEvaluationMapper = serviceEvaluationMapper;
        this.passwordEncoder = passwordEncoder;
        this.workerProfileService = workerProfileService;
    }

    /**
     * 按ID查用户实体（含密文密码，仅内部使用）。
     *
     * @param id 用户ID
     * @return 用户实体或 null
     */
    @Override
    public SysUser getUserById(Long id) {
        if (id == null) {
            return null;
        }
        // 主键直查（认证后 Controller 传入的 userId 已由 SecurityContext 保证真实性）
        return sysUserMapper.selectById(id);
    }

    /**
     * 按用户名查用户实体（认证数据源）。
     *
     * @param username 登录用户名
     * @return 用户实体或 null
     */
    @Override
    public SysUser getUserByUsername(String username) {
        if (username == null || username.isEmpty()) {
            return null;
        }
        // 唯一索引 uk_username 精确命中，返回含密文密码的完整实体供认证比对
        return sysUserMapper.selectByUsername(username);
    }

    /**
     * 查询当前登录用户信息（VO，无密码字段）。
     *
     * @param currentUser 当前登录用户上下文
     * @return 当前用户 VO
     */
    @Override
    public UserVO getCurrentUserInfo(LoginUserDTO currentUser) {
        SysUser user = requireUser(currentUser == null ? null : currentUser.getUserId());
        return toVO(user);
    }

    /**
     * 修改当前登录用户资料：仅改本人、仅改 4 个白名单字段，
     * role/status 完全不接受提交（防自我提权）。
     *
     * @param currentUser 当前登录用户
     * @param dto         资料入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateProfile(LoginUserDTO currentUser, UserProfileSaveDTO dto) {
        Long myId = currentUser == null ? null : currentUser.getUserId();
        SysUser user = requireUser(myId);
        // 手机号若填写则查重：排除本人后不得与他人重复（空值不参与查重）
        checkPhoneDuplicated(dto.getPhone(), user.getId());
        // 仅赋值允许修改的字段，其余字段保持库内原值，天然杜绝越权改角色
        user.setRealName(dto.getRealName());
        user.setPhone(emptyToNull(dto.getPhone()));
        user.setEmail(dto.getEmail());
        user.setAddress(emptyToNull(dto.getAddress()));
        sysUserMapper.updateById(user);
        log.info("用户[{}]修改个人资料成功", user.getUsername());
    }

    /**
     * 修改当前登录用户密码：先比对旧密码密文，再写入新密码密文。
     *
     * @param currentUser 当前登录用户
     * @param oldPassword 旧密码明文
     * @param newPassword 新密码明文
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePassword(LoginUserDTO currentUser, String oldPassword, String newPassword) {
        SysUser user = requireUser(currentUser == null ? null : currentUser.getUserId());
        // 旧密码错误一律给出同一提示，不泄露"账号是否存在/密码是否接近"等侧信道信息
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException(400, "原密码不正确");
        }
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 32) {
            throw new BusinessException(400, "新密码长度须为6-32位");
        }
        if (oldPassword.equals(newPassword)) {
            throw new BusinessException(400, "新密码不能与原密码相同");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        sysUserMapper.updateById(user);
        log.info("用户[{}]修改密码成功", user.getUsername());
    }

    /**
     * 管理员分页查询账号列表（QueryWrapper 在 Service 层拼装，符合分层铁律）。
     *
     * @param query  分页入参
     * @param role   角色筛选（可空）
     * @param status 状态筛选（可空）
     * @return 用户 VO 分页
     */
    @Override
    public PageVO<UserVO> pageUsers(PageQueryDTO query, String role, Integer status) {
        Page<SysUser> page = new Page<>(resolvePageNum(query), resolvePageSize(query));
        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        // 关键字三字段模糊：全部经 #{} 传参（QueryWrapper 内部预编译），无注入风险
        String kw = trimKw(query == null ? null : query.getKeyword());
        if (kw != null) {
            wrapper.and(w -> w.like("username", kw).or().like("real_name", kw).or().like("phone", kw));
        }
        // 角色白名单校验后才参与筛选，防止前端传入任意字符串影响 SQL 语义
        if (role != null && !role.isEmpty()) {
            if (!VALID_ROLES.contains(role)) {
                throw new BusinessException(400, "非法的角色筛选值: " + role);
            }
            wrapper.eq("role", role);
        }
        if (status != null) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("id");
        IPage<SysUser> result = sysUserMapper.selectPage(page, wrapper);
        // Entity 转 VO：剔除 BCrypt 密文，这是 Entity/VO 分离的核心目的
        IPage<UserVO> voPage = result.convert(this::toVO);
        return PageVO.of(voPage);
    }

    /**
     * 管理员新增/修改账号：查重、默认密码、"不得禁用/删除自己"、
     * 师傅角色联动建档案，全程单事务。
     *
     * @param dto         账号保存入参
     * @param currentUser 当前登录管理员
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveUser(UserSaveDTO dto, LoginUserDTO currentUser) {
        boolean isCreate = dto.getId() == null;
        // 用户名排除自身查重（修改时同名同自己放行）
        QueryWrapper<SysUser> nameWrapper = new QueryWrapper<SysUser>().eq("username", dto.getUsername());
        if (!isCreate) {
            nameWrapper.ne("id", dto.getId());
        }
        if (sysUserMapper.selectCount(nameWrapper) > 0) {
            throw new BusinessException(400, "登录用户名已存在: " + dto.getUsername());
        }
        // 手机号查重（空值不校验）
        checkPhoneDuplicated(dto.getPhone(), dto.getId());
        // 角色合法性：修改可空（不变更），新增必填
        String role = dto.getRole();
        SysUser user;
        if (isCreate) {
            if (role == null || role.isEmpty() || !VALID_ROLES.contains(role)) {
                throw new BusinessException(400, "新增账号必须指定合法角色");
            }
            user = new SysUser();
            BeanUtils.copyProperties(dto, user, "id", "password", "role");
            user.setRole(role);
            user.setPhone(emptyToNull(dto.getPhone()));
            user.setAddress(emptyToNull(dto.getAddress()));
            // 密码留空取初始密码 123456，否则用提交明文，入库前一律 BCrypt
            String plain = (dto.getPassword() == null || dto.getPassword().isEmpty())
                    ? DEFAULT_PASSWORD : dto.getPassword();
            user.setPassword(passwordEncoder.encode(plain));
            user.setStatus(dto.getStatus() == null ? Constants.USER_STATUS_NORMAL : dto.getStatus());
            user.setCreateTime(LocalDateTime.now());
            sysUserMapper.insert(user);
            // 师傅角色联动建档案（技能词表内默认技能，保证派单候选池数据可用）
            if (Constants.ROLE_WORKER.equals(role)) {
                workerProfileService.initProfileIfAbsent(user.getId());
            }
            log.info("管理员[{}]新增账号[{}] role={}", currentUser.getUsername(), user.getUsername(), role);
        } else {
            user = requireUser(dto.getId());
            BeanUtils.copyProperties(dto, user, "id", "password", "role", "status", "createTime");
            user.setPhone(emptyToNull(dto.getPhone()));
            user.setAddress(emptyToNull(dto.getAddress()));
            // 密码留空=不变更；留空以外一律重新加密
            if (dto.getPassword() != null && !dto.getPassword().isEmpty()) {
                user.setPassword(passwordEncoder.encode(dto.getPassword()));
            }
            if (role != null && !role.isEmpty()) {
                if (!VALID_ROLES.contains(role)) {
                    throw new BusinessException(400, "非法角色值: " + role);
                }
                // 角色变更护栏：账号已有业务轨迹时禁止改角色，避免订单归属语义漂移
                if (!role.equals(user.getRole())) {
                    checkRoleChangeAllowed(user.getId(), user.getRole(), role);
                    user.setRole(role);
                    // 升为师傅则建档案；降为居民/管理员则删除遗留档案（无在途工单才允许，已在上一校验拦截）
                    if (Constants.ROLE_WORKER.equals(role)) {
                        workerProfileService.initProfileIfAbsent(user.getId());
                    } else {
                        workerProfileMapper.delete(new QueryWrapper<WorkerProfile>().eq("user_id", user.getId()));
                    }
                }
            }
            Integer status = dto.getStatus() == null ? user.getStatus() : dto.getStatus();
            // 禁用护栏：管理员不得禁用自己（防止把自己关在系统外）
            if (Constants.USER_STATUS_DISABLED == status && user.getId().equals(currentUser.getUserId())) {
                throw new BusinessException(400, "不能禁用当前登录的管理员账号");
            }
            // 禁用师傅前须无在途工单，否则订单状态机将出现"承接人已禁用"的悬空态
            if (Constants.USER_STATUS_DISABLED == status && Constants.ROLE_WORKER.equals(user.getRole())) {
                checkWorkerNoActiveOrder(user.getId());
            }
            user.setStatus(status);
            sysUserMapper.updateById(user);
            log.info("管理员[{}]修改账号[{}]", currentUser.getUsername(), user.getUsername());
        }
    }

    /**
     * 管理员删除账号：自我删除拦截 + 业务引用校验（订单/评价/在途工单）+
     * 师傅档案联动删除，单事务保证不产生悬挂外键。
     *
     * @param id          目标用户ID
     * @param currentUser 当前登录管理员
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeUser(Long id, LoginUserDTO currentUser) {
        SysUser user = requireUser(id);
        if (id.equals(currentUser.getUserId())) {
            throw new BusinessException(400, "不能删除当前登录的管理员账号");
        }
        // 下单引用：有下单记录的居民不允许删除（订单 resident_id 将悬挂）
        long orderRefs = serviceOrderMapper.selectCount(
                new QueryWrapper<ServiceOrder>().eq("resident_id", id));
        if (orderRefs > 0) {
            throw new BusinessException(400, "该账号已产生 " + orderRefs + " 条预约订单，不能删除，建议改为禁用");
        }
        // 师傅引用：有承接订单（含已完成）都不允许删除（订单与评价的 worker_id 将悬挂）
        long workerRefs = serviceOrderMapper.selectCount(
                new QueryWrapper<ServiceOrder>().eq("worker_id", id));
        if (workerRefs > 0) {
            throw new BusinessException(400, "该师傅已承接 " + workerRefs + " 条订单，不能删除，建议改为禁用");
        }
        // 评价引用：已发出或已收到评价均禁止删除（评价的 resident_id/worker_id 将悬挂）
        long evalRefs = serviceEvaluationMapper.selectCount(
                new QueryWrapper<ServiceEvaluation>().eq("resident_id", id))
                + serviceEvaluationMapper.selectCount(
                new QueryWrapper<ServiceEvaluation>().eq("worker_id", id));
        if (evalRefs > 0) {
            throw new BusinessException(400, "该账号关联 " + evalRefs + " 条评价记录，不能删除，建议改为禁用");
        }
        // 无引用的师傅先删档案再删账号，两表同事务保证 1 对 1 关系不残留
        workerProfileMapper.delete(new QueryWrapper<WorkerProfile>().eq("user_id", id));
        sysUserMapper.deleteById(id);
        log.info("管理员[{}]删除账号[{}]", currentUser.getUsername(), user.getUsername());
    }

    /**
     * 内部工具：按ID取用户，不存在即 404。
     *
     * @param id 用户ID
     * @return 用户实体
     */
    private SysUser requireUser(Long id) {
        if (id == null) {
            throw new BusinessException(400, "当前登录用户信息缺失，请重新登录");
        }
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new NotFoundException("账号不存在或已被删除");
        }
        return user;
    }

    /**
     * 手机号查重（排除自身ID；空/空串不参与查重）。
     *
     * @param phone      待校验手机号
     * @param excludeId  需排除的用户ID（可空）
     */
    private void checkPhoneDuplicated(String phone, Long excludeId) {
        if (phone == null || phone.isEmpty()) {
            return;
        }
        QueryWrapper<SysUser> wrapper = new QueryWrapper<SysUser>().eq("phone", phone);
        if (excludeId != null) {
            wrapper.ne("id", excludeId);
        }
        if (sysUserMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(400, "手机号已被其他账号使用: " + phone);
        }
    }

    /**
     * 角色变更护栏：师傅有在途/历史订单、居民有下单、管理员有管理痕迹时
     * 不允许改角色（简化为"有订单/评价引用即禁止"，与删除校验口径一致）。
     *
     * @param userId 用户ID
     * @param oldRole 原角色
     * @param newRole 新角色
     */
    private void checkRoleChangeAllowed(Long userId, String oldRole, String newRole) {
        long orderRefs = serviceOrderMapper.selectCount(
                new QueryWrapper<ServiceOrder>().eq("resident_id", userId))
                + serviceOrderMapper.selectCount(new QueryWrapper<ServiceOrder>().eq("worker_id", userId));
        long evalRefs = serviceEvaluationMapper.selectCount(
                new QueryWrapper<ServiceEvaluation>().eq("resident_id", userId));
        if (orderRefs > 0 || evalRefs > 0) {
            throw new BusinessException(400, "该账号已产生订单或评价记录，不能变更角色（"
                    + oldRole + " -> " + newRole + "），如需处置请禁用账号");
        }
    }

    /**
     * 师傅在途工单校验：存在"已派单/服务中"订单时禁止禁用或删除该师傅。
     *
     * @param userId 师傅用户ID
     */
    private void checkWorkerNoActiveOrder(Long userId) {
        long active = serviceOrderMapper.selectCount(new QueryWrapper<ServiceOrder>()
                .eq("worker_id", userId).in("order_status",
                        Constants.ORDER_DISPATCHED, Constants.ORDER_IN_SERVICE));
        if (active > 0) {
            throw new BusinessException(400, "该师傅仍有 " + active + " 条在手工单，请先派单改派或由师傅完成后再处置");
        }
    }

    /**
     * Entity -> VO 转换（剔除 password 字段）。
     *
     * @param user 用户实体
     * @return 用户 VO
     */
    private UserVO toVO(SysUser user) {
        UserVO vo = new UserVO();
        BeanUtils.copyProperties(user, vo, "password");
        return vo;
    }

    /**
     * 关键字规范化：去首尾空白，空串归一为 null（不参与筛选）。
     *
     * @param kw 原始关键字
     * @return 规范化后的关键字或 null
     */
    private String trimKw(String kw) {
        if (kw == null) {
            return null;
        }
        String trimmed = kw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 空串归一为 null（手机号/地址字段允许置空，前端常传空串）。
     *
     * @param value 原始字符串
     * @return null（空/空白）或原值
     */
    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 页码兜底解析（缺省 1）。
     *
     * @param query 分页入参
     * @return 有效页码
     */
    private long resolvePageNum(PageQueryDTO query) {
        return query == null || query.getPageNum() == null ? Constants.DEFAULT_PAGE_NUM : query.getPageNum();
    }

    /**
     * 页容量兜底解析（缺省 10，上限 100）。
     *
     * @param query 分页入参
     * @return 有效页容量
     */
    private long resolvePageSize(PageQueryDTO query) {
        if (query == null || query.getPageSize() == null) {
            return Constants.DEFAULT_PAGE_SIZE;
        }
        return Math.min(query.getPageSize(), Constants.MAX_PAGE_SIZE);
    }
}
