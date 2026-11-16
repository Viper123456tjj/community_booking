package com.community.booking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.PageQueryDTO;
import com.community.booking.dto.WorkerProfileSaveDTO;
import com.community.booking.entity.ServiceItem;
import com.community.booking.entity.ServiceOrder;
import com.community.booking.entity.SysUser;
import com.community.booking.entity.WorkerProfile;
import com.community.booking.exception.BusinessException;
import com.community.booking.exception.ForbiddenException;
import com.community.booking.exception.NotFoundException;
import com.community.booking.mapper.ServiceItemMapper;
import com.community.booking.mapper.ServiceOrderMapper;
import com.community.booking.mapper.SysUserMapper;
import com.community.booking.mapper.WorkerProfileMapper;
import com.community.booking.service.WorkerProfileService;
import com.community.booking.utils.Constants;
import com.community.booking.vo.PageVO;
import com.community.booking.vo.WorkerProfileVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 服务人员档案业务实现：师傅自助维护 + 管理员师傅管理 + 接单状态联动。
 * <p>关键设计：接单状态是"派单候选池"的准入闸门，因此状态变更全部收口到
 * refreshWorkStatus 一处——忙判定以库内在手工单（已派单+服务中）COUNT 为准，
 * 不依赖任何缓存或前端申报，保证智能派单读到的状态永远与订单表一致；
 * 休息(0) 是师傅主动意愿，系统联动时绝不覆盖。</p>
 *
 * @author community-booking
 */
@Slf4j
@Service
public class WorkerProfileServiceImpl implements WorkerProfileService {

    /** 接单状态中文映射（下标即状态值：0休息/1空闲/2忙碌） */
    private static final String[] WORK_STATUS_TEXT = {"休息中", "空闲", "忙碌"};

    /** 用户 Mapper（校验目标账号确为启用师傅，不注入 SysUserService 避免循环依赖） */
    private final SysUserMapper sysUserMapper;
    /** 师傅档案 Mapper（本领域主表） */
    private final WorkerProfileMapper workerProfileMapper;
    /** 订单 Mapper（在手工单与历史工单引用校验） */
    private final ServiceOrderMapper serviceOrderMapper;
    /** 服务项目 Mapper（技能词表来源） */
    private final ServiceItemMapper serviceItemMapper;

    /**
     * 构造注入全部 Mapper 依赖。
     *
     * @param sysUserMapper         用户 Mapper
     * @param workerProfileMapper   档案 Mapper
     * @param serviceOrderMapper    订单 Mapper
     * @param serviceItemMapper     服务项目 Mapper
     */
    public WorkerProfileServiceImpl(SysUserMapper sysUserMapper, WorkerProfileMapper workerProfileMapper,
                                    ServiceOrderMapper serviceOrderMapper, ServiceItemMapper serviceItemMapper) {
        this.sysUserMapper = sysUserMapper;
        this.workerProfileMapper = workerProfileMapper;
        this.serviceOrderMapper = serviceOrderMapper;
        this.serviceItemMapper = serviceItemMapper;
    }

    /**
     * 查询师傅当前档案（技能列表与状态中文已补全）。
     *
     * @param userId 师傅用户ID
     * @return 档案 VO
     */
    @Override
    public WorkerProfileVO getMyProfile(Long userId) {
        WorkerProfileVO vo = workerProfileMapper.selectWorkerDetailByUserId(userId);
        if (vo == null) {
            throw new NotFoundException("师傅档案不存在，请联系物业管理员初始化");
        }
        enrich(vo);
        return vo;
    }

    /**
     * 维护师傅档案（技能与期望状态），含越权、技能词表与休息护栏校验。
     *
     * @param dto         维护入参
     * @param currentUser 当前登录用户
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveProfile(WorkerProfileSaveDTO dto, LoginUserDTO currentUser) {
        Long targetId = dto.getUserId();
        boolean isAdmin = currentUser != null && Constants.ROLE_ADMIN.equals(currentUser.getRole());
        // 越权防线：非管理员只能维护自己的档案（水平越权拦截）
        if (!isAdmin && !targetId.equals(currentUser == null ? null : currentUser.getUserId())) {
            throw new ForbiddenException("只能维护自己的师傅档案");
        }
        // 目标账号必须存在且为启用状态的师傅（垂直越权与悬挂引用双防）
        SysUser target = sysUserMapper.selectById(targetId);
        if (target == null) {
            throw new NotFoundException("师傅账号不存在");
        }
        if (!Constants.ROLE_WORKER.equals(target.getRole())) {
            throw new BusinessException(400, "该账号不是便民服务人员，无法维护师傅档案");
        }
        WorkerProfile profile = workerProfileMapper.selectOne(
                new QueryWrapper<WorkerProfile>().eq("user_id", targetId));
        if (profile == null) {
            throw new NotFoundException("师傅档案不存在，请联系物业管理员初始化");
        }
        // 技能标签校验：非空时逐个落在服务技能词表内（管理员代管可自由录入）
        if (dto.getSkillTags() != null && !dto.getSkillTags().isEmpty()) {
            List<String> tags = splitTags(dto.getSkillTags());
            if (tags.isEmpty()) {
                throw new BusinessException(400, "技能标签不能为空");
            }
            // 去重后入库，保持提交顺序（前端展示与派单匹配均以集合语义，顺序无副作用）
            Set<String> unique = new LinkedHashSet<>(tags);
            if (!isAdmin) {
                Set<String> vocab = listSkillVocabulary();
                List<String> unknown = unique.stream().filter(t -> !vocab.contains(t)).toList();
                if (!unknown.isEmpty()) {
                    throw new BusinessException(400, "以下技能不在服务技能词表中，录入后无法被派单: "
                            + String.join("、", unknown));
                }
            }
            profile.setSkillTags(String.join("|", unique));
        }
        // 期望状态校验：只接受 空闲(1)/休息(0)，忙碌(2) 由系统联动维护
        if (dto.getWorkStatus() != null) {
            int ws = dto.getWorkStatus();
            if (ws != Constants.WORK_IDLE && ws != Constants.WORK_REST) {
                throw new BusinessException(400, "接单状态只能自选空闲(1)或休息(0)，忙碌由系统自动置位");
            }
            // 休息护栏：仍有在手工单时不允许切休息，防止"休息师傅持单"的矛盾态
            if (ws == Constants.WORK_REST) {
                long active = countActiveOrders(targetId);
                if (active > 0) {
                    throw new BusinessException(400, "当前仍有 " + active + " 条在手工单，请先完成或联系管理员改派后再休息");
                }
            }
            // 从休息切空闲的护栏：账号被禁用不得接单（禁用与可派单互斥）
            if (ws == Constants.WORK_IDLE && Constants.USER_STATUS_DISABLED == target.getStatus()) {
                throw new BusinessException(400, "账号已被禁用，无法切换为接单状态");
            }
            profile.setWorkStatus(ws);
        }
        workerProfileMapper.updateById(profile);
        log.info("师傅档案[{}]已更新（操作者={}）", target.getUsername(),
                currentUser == null ? "system" : currentUser.getUsername());
    }

    /**
     * 管理员分页查询师傅列表（联表一条 SQL 直出全部展示字段）。
     *
     * @param query      分页入参
     * @param workStatus 接单状态筛选（可空）
     * @return 档案 VO 分页
     */
    @Override
    public PageVO<WorkerProfileVO> pageWorkers(PageQueryDTO query, Integer workStatus) {
        long pageNum = query == null || query.getPageNum() == null
                ? Constants.DEFAULT_PAGE_NUM : query.getPageNum();
        long pageSize = query == null || query.getPageSize() == null
                ? Constants.DEFAULT_PAGE_SIZE : Math.min(query.getPageSize(), Constants.MAX_PAGE_SIZE);
        Page<WorkerProfileVO> page = new Page<>(pageNum, pageSize);
        String kw = normalizeKw(query == null ? null : query.getKeyword());
        if (workStatus != null && workStatus != Constants.WORK_REST
                && workStatus != Constants.WORK_IDLE && workStatus != Constants.WORK_BUSY) {
            throw new BusinessException(400, "非法的接单状态筛选值: " + workStatus);
        }
        IPage<WorkerProfileVO> result = workerProfileMapper.selectWorkerPage(page, kw, workStatus);
        // 逐行补全技能列表与状态中文（内存映射，不再发起查询）
        result.getRecords().forEach(this::enrich);
        return PageVO.of(result);
    }

    /**
     * 管理员查看单个师傅档案详情。
     *
     * @param userId 师傅用户ID
     * @return 档案 VO
     */
    @Override
    public WorkerProfileVO getWorkerDetail(Long userId) {
        return getMyProfile(userId);
    }

    /**
     * 按在手工单联动刷新接单状态（所有状态迁移后的统一收口）。
     *
     * @param userId 师傅用户ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void refreshWorkStatus(Long userId) {
        if (userId == null) {
            return;
        }
        WorkerProfile profile = workerProfileMapper.selectOne(
                new QueryWrapper<WorkerProfile>().eq("user_id", userId));
        if (profile == null) {
            return;
        }
        long active = countActiveOrders(userId);
        int target;
        if (active > 0) {
            // 有在手工单 => 忙碌（无论此前是空闲还是休息，持单即忙碌）
            target = Constants.WORK_BUSY;
        } else if (Constants.WORK_REST == profile.getWorkStatus()) {
            // 休息是主动意愿且已无单：保持休息，绝不擅自改为空闲
            return;
        } else {
            // 无单且当前忙碌 => 回置空闲，重新进入派单候选池
            target = Constants.WORK_IDLE;
        }
        if (target == profile.getWorkStatus()) {
            return;
        }
        WorkerProfile update = new WorkerProfile();
        update.setId(profile.getId());
        update.setWorkStatus(target);
        workerProfileMapper.updateById(update);
        log.info("师傅[{}]接单状态联动刷新: {} -> {}", userId, profile.getWorkStatus(), target);
    }

    /**
     * 重算平均星级（一条 SQL 读算写，天然幂等）。
     *
     * @param userId 师傅用户ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recalcRatingScore(Long userId) {
        workerProfileMapper.recalcRatingScore(userId);
    }

    /**
     * 累计完成数 +1（原子自增）。
     *
     * @param userId 师傅用户ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void incrementTotalOrders(Long userId) {
        workerProfileMapper.addTotalOrders(userId);
    }

    /**
     * 幂等初始化师傅档案。
     *
     * @param userId 师傅用户ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void initProfileIfAbsent(Long userId) {
        Long exists = workerProfileMapper.selectCount(
                new QueryWrapper<WorkerProfile>().eq("user_id", userId));
        if (exists != null && exists > 0) {
            return;
        }
        WorkerProfile profile = new WorkerProfile();
        profile.setUserId(userId);
        // 默认技能取词表内已有标签，保证新师傅立即具备被派单的可达性
        profile.setSkillTags(Constants.DEFAULT_WORKER_SKILLS);
        profile.setWorkStatus(Constants.WORK_IDLE);
        profile.setTotalOrders(0);
        profile.setCreateTime(LocalDateTime.now());
        workerProfileMapper.insert(profile);
        log.info("初始化师傅档案 userId={}", userId);
    }

    /**
     * 查询服务技能词表（全部服务项目所需技能的并集）。
     *
     * @return 去重技能标签集合
     */
    @Override
    public Set<String> listSkillVocabulary() {
        // 只读上架+下架全部项目的 skill_required：下架项目的技能仍属词表，
        // 重新上架后须立即有师傅可承接，不能因临时下架而丢失词表项
        List<ServiceItem> items = serviceItemMapper.selectList(
                new QueryWrapper<ServiceItem>().select("skill_required"));
        Set<String> vocab = new LinkedHashSet<>();
        for (ServiceItem item : items) {
            vocab.addAll(splitTags(item.getSkillRequired()));
        }
        return vocab;
    }

    /**
     * 内部工具：统计师傅在手工单数（已派单+服务中）。
     *
     * @param userId 师傅用户ID
     * @return 在手工单数
     */
    private long countActiveOrders(Long userId) {
        Long count = serviceOrderMapper.selectCount(new QueryWrapper<ServiceOrder>()
                .eq("worker_id", userId)
                .in("order_status", Constants.ORDER_DISPATCHED, Constants.ORDER_IN_SERVICE));
        return count == null ? 0L : count;
    }

    /**
     * VO 补全：技能列表 + 状态中文（纯内存派生，零额外 SQL）。
     *
     * @param vo 档案 VO
     */
    private void enrich(WorkerProfileVO vo) {
        vo.setSkillTagsList(splitTags(vo.getSkillTags()));
        Integer ws = vo.getWorkStatus();
        vo.setWorkStatusText(ws != null && ws >= 0 && ws < WORK_STATUS_TEXT.length
                ? WORK_STATUS_TEXT[ws] : "未知");
    }

    /**
     | 分隔技能串拆分为去空白、去空的标签列表（保持提交顺序）。
     *
     * @param raw 原始标签串（可空）
     * @return 标签列表（空串返回空列表）
     */
    private List<String> splitTags(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 关键字规范化：去空白、空串归一为 null。
     *
     * @param kw 原始关键字
     * @return 规范化关键字或 null
     */
    private String normalizeKw(String kw) {
        if (kw == null) {
            return null;
        }
        String trimmed = kw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
