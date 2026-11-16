package com.community.booking.service.impl;

import com.community.booking.dto.LoginUserDTO;
import com.community.booking.entity.ServiceItem;
import com.community.booking.entity.ServiceOrder;
import com.community.booking.exception.BusinessException;
import com.community.booking.exception.ForbiddenException;
import com.community.booking.exception.NotFoundException;
import com.community.booking.mapper.ServiceItemMapper;
import com.community.booking.mapper.ServiceOrderMapper;
import com.community.booking.service.DispatchService;
import com.community.booking.service.ServiceOrderService;
import com.community.booking.utils.Constants;
import com.community.booking.vo.DispatchCandidateVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能派单业务实现（Batch 4）：四维权重评分 + 一键指派。
 * <p><b>评分模型（满分 100，扣分项单列）</b></p>
 * <table border="1">
 *   <caption>权重构成</caption>
 *   <tr><th>维度</th><th>权重</th><th>计算口径</th></tr>
 *   <tr><td>技能覆盖</td><td>40</td><td>40 × 命中标签数 / 所需标签数（全覆盖得满分）</td></tr>
 *   <tr><td>接单状态</td><td>20</td><td>空闲 20 / 忙碌 8 / 休息 0（休息师傅本就不进候选池）</td></tr>
 *   <tr><td>服务评分</td><td>20</td><td>rating_score / 5 × 20</td></tr>
 *   <tr><td>当前负载</td><td>15</td><td>在手 0 单 15 / 1 单 8 / 2 单及以上 0</td></tr>
 *   <tr><td>经验均衡</td><td>5</td><td>5 × (1 − min(totalOrders,50)/50)：新师傅略占优，避免老手包场</td></tr>
 *   <tr><td>时段冲突</td><td>−10/单</td><td>预约时间前后 2 小时的窗口内，与其在手工单重叠计数，最多扣 30</td></tr>
 * </table>
 * <p>为什么把技能放在 40 分而不是"一票否决"：技能不完全覆盖的师傅依然可能
 * 是唯一可行解（例如某类工单全城只有两位师傅、其中一位缺一个次要标签），
 * 排序时降权但不直接剔除，把最终判断权交给管理员；而 {@code dispatchOrder}
 * 的资格校验会在真正写库时拒绝缺技能的指派，形成"推荐宽松、执行严格"的分层。</p>
 * <p>为什么经验分是"越少越高"：派单公平性。若按完成量正向加分，头部师傅会
 * 持续吃单、新人永远接不到单，师傅队伍会在两个月内失衡；反向加权能把新单
 * 适度导向低负载师傅，同时它只占 5 分，不足以覆盖技能与评分的差距。</p>
 *
 * @author community-booking
 */
@Slf4j
// 智能派单业务实现
@Service
public class DispatchServiceImpl implements DispatchService {

    /** 技能覆盖权重（最高 40 分，算法的主导因子） */
    private static final double WEIGHT_SKILL = 40.0;
    /** 接单状态权重（空闲 20 / 忙碌 8） */
    private static final double WEIGHT_IDLE = 20.0;
    /** 服务评分权重（rating/5 × 20） */
    private static final double WEIGHT_RATING = 20.0;
    /** 当前负载权重（在手 0 单得满分） */
    private static final double WEIGHT_LOAD = 15.0;
    /** 经验均衡权重（完成量越少得分越高，最多 5 分） */
    private static final double WEIGHT_EXPERIENCE = 5.0;
    /** 经验均衡的饱和完成量（达到 50 单后该项为 0 分） */
    private static final double EXPERIENCE_CAP = 50.0;
    /** 单个时段冲突的扣分 */
    private static final double CONFLICT_PENALTY = 10.0;
    /** 时段冲突扣分上限（避免一次冲突把候选直接打到 0 分） */
    private static final double MAX_CONFLICT_PENALTY = 30.0;
    /** 忙碌状态的基础得分（可被改派但优先级低于空闲师傅） */
    private static final double BUSY_SCORE = 8.0;
    /** 评分满分（5 星制，与 service_evaluation.star 上限一致） */
    private static final double MAX_RATING = 5.0;
    /** 候选列表最多返回条数（防止师傅规模扩大后弹窗过长） */
    private static final int MAX_CANDIDATES = 20;

    /** 订单 Mapper（订单定位 + 候选行查询） */
    private final ServiceOrderMapper orderMapper;
    /** 服务项目 Mapper（取 skill_required 作为技能覆盖的判定基准） */
    private final ServiceItemMapper itemMapper;
    /** 订单业务（真正的派单写操作复用其状态机与分布式锁） */
    private final ServiceOrderService orderService;
    /** 时间冲突判定半径默认值（小时，配置 community.dispatch.conflict-window-hours） */
    private final int defaultWindowHours;
    /** 一键指派的最低综合得分（配置 community.dispatch.auto-threshold） */
    private final double autoThreshold;

    /**
     * 构造注入依赖与派单策略参数。
     *
     * @param orderMapper          订单 Mapper
     * @param itemMapper           项目 Mapper
     * @param orderService         订单业务（派单写入口）
     * @param defaultWindowHours   冲突窗口默认半径（小时）
     * @param autoThreshold        一键指派最低得分
     */
    public DispatchServiceImpl(ServiceOrderMapper orderMapper, ServiceItemMapper itemMapper,
                               ServiceOrderService orderService,
                               @Value("${community.dispatch.conflict-window-hours:2}") int defaultWindowHours,
                               @Value("${community.dispatch.auto-threshold:60}") double autoThreshold) {
        this.orderMapper = orderMapper;
        this.itemMapper = itemMapper;
        this.orderService = orderService;
        this.defaultWindowHours = defaultWindowHours;
        this.autoThreshold = autoThreshold;
    }

    /**
     * 候选师傅评分列表（按综合得分降序）。
     *
     * @param orderId     订单ID
     * @param windowHours 冲突窗口半径（可空=配置默认值）
     * @param currentUser 当前登录管理员
     * @return 候选列表
     */
    @Override
    public List<DispatchCandidateVO> listCandidates(Long orderId, Integer windowHours, LoginUserDTO currentUser) {
        requireAdmin(currentUser);
        ServiceOrder order = requireDispatchableOrder(orderId);
        ServiceItem item = itemMapper.selectById(order.getServiceId());
        if (item == null) {
            throw new NotFoundException("订单关联的服务项目不存在，无法计算派单候选");
        }
        int window = windowHours == null || windowHours <= 0 || windowHours > 24
                ? defaultWindowHours : windowHours;
        // 候选池：账号正常的非休息师傅；改派场景排除本单自身，避免"自己和自己冲突"
        List<DispatchCandidateVO> candidates = orderMapper.selectDispatchCandidates(
                order.getAppointTime(), window, order.getId());
        List<String> required = splitTags(item.getSkillRequired());
        for (DispatchCandidateVO candidate : candidates) {
            score(candidate, required, item.getItemName());
        }
        // 技能未全覆盖的行沉底：同分情况下技能优先，其次按得分降序
        candidates.sort(Comparator
                .comparing((DispatchCandidateVO c) -> Boolean.TRUE.equals(c.getSkillMatched()) ? 0 : 1)
                .thenComparing(DispatchCandidateVO::getMatchScore, Comparator.reverseOrder()));
        if (candidates.size() > MAX_CANDIDATES) {
            return new ArrayList<>(candidates.subList(0, MAX_CANDIDATES));
        }
        return candidates;
    }

    /**
     * 一键智能派单：在"可立即接单"的候选里取最高分者直接指派。
     * <p>筛选口径（三项必须同时满足，缺一不可）：</p>
     * <ol>
     *   <li><b>技能全覆盖</b>：skillMatched=true，否则 dispatchOrder 会因缺技能拒绝；</li>
     *   <li><b>当前空闲</b>：work_status=1。这一条曾漏掉——候选池 SQL 只排除"休息"，
     *       忙碌师傅也在列表里，而忙碌者的得分被折价后仍可能排第一；一旦被选中，
     *       dispatchOrder 里的 trySetBusy（WHERE work_status=1）必然更新 0 行并回滚，
     *       表现为"一键派单永远失败、提示师傅刚被抢占"，其实根本没有并发；
     *   </li>
     *   <li><b>时段无冲突</b>：conflictOrders=0，同一时段已排了工单的师傅不该再被塞单。</li>
     * </ol>
     * <p>注意：候选列表本身仍保留忙碌/冲突的师傅并把得分折价显示，
     * 这样管理员能看懂"为什么系统没推荐他"，也能在紧急情况下手工指派
     * （手工指派是否允许由 dispatchOrder 的资格校验与原子占忙决定）。</p>
     *
     * @param orderId     待派单订单ID
     * @param currentUser 当前登录管理员
     * @return 被指派的候选（含得分与理由）
     */
    @Override
    public DispatchCandidateVO autoDispatch(Long orderId, LoginUserDTO currentUser) {
        List<DispatchCandidateVO> candidates = listCandidates(orderId, null, currentUser);
        // 先按"立刻能接"的硬条件过滤，再取分最高的；顺序不可颠倒，
        // 否则会把忙碌的高分师傅选出来，导致写库阶段必然回滚
        DispatchCandidateVO best = candidates.stream()
                .filter(c -> Boolean.TRUE.equals(c.getSkillMatched()))
                .filter(c -> c.getWorkStatus() != null && c.getWorkStatus() == Constants.WORK_IDLE)
                .filter(c -> c.getConflictOrders() == null || c.getConflictOrders() == 0)
                .findFirst()
                .orElse(null);
        if (best == null) {
            // 区分两种失败原因，给出可执行的中文提示（否则管理员不知道该改什么）
            boolean anySkillMatched = candidates.stream()
                    .anyMatch(c -> Boolean.TRUE.equals(c.getSkillMatched()));
            if (!anySkillMatched) {
                throw new BusinessException(400, "没有技能全覆盖的师傅可承接该服务，请先维护师傅技能或手动指派");
            }
            throw new BusinessException(400, "技能匹配的师傅当前都在忙碌或该时段已有工单，"
                    + "请稍后重试或在候选列表中人工指派");
        }
        if (best.getMatchScore() == null || best.getMatchScore().doubleValue() < autoThreshold) {
            throw new BusinessException(400, "当前最高匹配分仅 " + best.getMatchScore()
                    + "（低于自动派单阈值 " + BigDecimal.valueOf(autoThreshold).stripTrailingZeros().toPlainString()
                    + "），建议在候选列表中人工指派");
        }
        // 写操作复用订单业务的状态机：条件 UPDATE + 分布式锁 + 师傅占忙原子判定
        orderService.dispatchOrder(orderId, best.getUserId(), Constants.DISPATCH_AUTO, currentUser);
        log.info("智能派单完成: orderId={} -> workerId={} score={} reason={}",
                orderId, best.getUserId(), best.getMatchScore(), best.getReason());
        return best;
    }

    /**
     * 计算单个候选的多维得分与推荐理由（就地填充 VO，零额外查询）。
     *
     * @param candidate    候选行（SQL 已带回技能串、状态、评分、在手工单数）
     * @param required     项目所需技能标签
     * @param serviceName  服务名称（仅用于日志可读性）
     */
    private void score(DispatchCandidateVO candidate, List<String> required, String serviceName) {
        List<String> owned = splitTags(candidate.getSkillTags());
        candidate.setSkillTagsList(owned);
        Set<String> ownedSet = new HashSet<>(owned);
        int matched = 0;
        for (String tag : required) {
            if (ownedSet.contains(tag)) {
                matched++;
            }
        }
        candidate.setRequiredSkillCount(required.size());
        candidate.setMatchedSkillCount(matched);
        boolean skillMatched = matched >= required.size();
        candidate.setSkillMatched(skillMatched);

        int active = candidate.getActiveOrders() == null ? 0 : candidate.getActiveOrders();
        int conflict = candidate.getConflictOrders() == null ? 0 : candidate.getConflictOrders();
        candidate.setActiveOrders(active);
        candidate.setConflictOrders(conflict);
        candidate.setTimeConflict(conflict > 0);

        // 1) 技能覆盖：按命中比例给分（全覆盖 = 40）
        double skillPart = required.isEmpty() ? WEIGHT_SKILL
                : WEIGHT_SKILL * matched / required.size();
        // 2) 接单状态：空闲满分，忙碌打折
        boolean idle = candidate.getWorkStatus() != null && candidate.getWorkStatus() == Constants.WORK_IDLE;
        double idlePart = idle ? WEIGHT_IDLE : BUSY_SCORE;
        // 3) 服务评分：rating/5 × 20（null 视为 0，避免 NPE）
        double rating = candidate.getRatingScore() == null ? 0.0 : candidate.getRatingScore().doubleValue();
        double ratingPart = Math.max(0.0, Math.min(rating, MAX_RATING)) / MAX_RATING * WEIGHT_RATING;
        // 4) 当前负载：把手 0 单满分，1 单减半，2 单及以上为 0
        double loadPart = active <= 0 ? WEIGHT_LOAD : (active == 1 ? WEIGHT_LOAD / 2.0 : 0.0);
        // 5) 经验均衡：完成量越少得分越高（最多 5 分）
        int totalOrders = candidate.getTotalOrders() == null ? 0 : candidate.getTotalOrders();
        double expPart = WEIGHT_EXPERIENCE * (1.0 - Math.min(totalOrders, EXPERIENCE_CAP) / EXPERIENCE_CAP);
        // 6) 时段冲突扣分：每单 −10，最多扣 30
        double penalty = Math.min(conflict * CONFLICT_PENALTY, MAX_CONFLICT_PENALTY);
        double total = Math.max(0.0, skillPart + idlePart + ratingPart + loadPart + expPart - penalty);
        candidate.setMatchScore(BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP));

        // 推荐理由：按"管理员最关心的顺序"拼接，一眼看出推荐/不推荐的原因
        StringBuilder reason = new StringBuilder();
        reason.append(skillMatched ? "技能全覆盖(" + matched + "/" + required.size() + ")"
                : "技能缺 " + (required.size() - matched) + " 项(" + matched + "/" + required.size() + ")");
        reason.append("、").append(idle ? "当前空闲" : "手中 " + active + " 单");
        reason.append("、").append(conflict == 0 ? "时段无冲突" : "时段冲突 " + conflict + " 单（−"
                + BigDecimal.valueOf(penalty).stripTrailingZeros().toPlainString() + "）");
        reason.append("、评分 ").append(BigDecimal.valueOf(rating).setScale(2, RoundingMode.HALF_UP));
        reason.append("、累计完成 ").append(totalOrders).append(" 单");
        candidate.setReason(reason.toString());
        log.debug("候选评分[{}] {} 服务[{}] => {}", candidate.getRealName(), candidate.getMatchScore(),
                serviceName, candidate.getReason());
    }

    /**
     * 订单定位与可派单态校验（待派单可首次派单，已派单未开工可看改派候选）。
     *
     * @param orderId 订单ID
     * @return 订单实体
     */
    private ServiceOrder requireDispatchableOrder(Long orderId) {
        if (orderId == null) {
            throw new BusinessException(400, "订单ID不能为空");
        }
        ServiceOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new NotFoundException("服务订单不存在或已被清理");
        }
        if (Constants.ORDER_PENDING != order.getOrderStatus()
                && Constants.ORDER_DISPATCHED != order.getOrderStatus()) {
            throw new BusinessException(400, "只有待派单或已派单未开工的订单可以查看派单候选");
        }
        return order;
    }

    /**
     * 管理员角色校验（垂直越权防线，与 Controller 的 @PreAuthorize 双重保障）。
     *
     * @param currentUser 当前登录用户
     */
    private void requireAdmin(LoginUserDTO currentUser) {
        if (currentUser == null || !Constants.ROLE_ADMIN.equals(currentUser.getRole())) {
            throw new ForbiddenException("只有物业管理员可以执行派单操作");
        }
    }

    /**
     * | 分隔标签串拆分为去空白去重列表（与订单模块同一口径）。
     *
     * @param raw 原始串
     * @return 标签列表
     */
    private List<String> splitTags(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }
}
