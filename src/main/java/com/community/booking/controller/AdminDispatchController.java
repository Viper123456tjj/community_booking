package com.community.booking.controller;

import com.community.booking.dto.DispatchDTO;
import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.ReassignDTO;
import com.community.booking.service.DispatchService;
import com.community.booking.service.ServiceOrderService;
import com.community.booking.utils.Result;
import com.community.booking.utils.SecurityUtils;
import com.community.booking.vo.DispatchCandidateVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Size;
import java.util.List;

/**
 * 管理端派单中心接口（/api/admin/dispatch/**，仅 ROLE_ADMIN）。
 * <p>派单是本系统唯一"管理员专属写操作"，单独成类便于与主数据维护
 * 职责分离（前者高频且面向工单流转，后者低频且面向目录配置）。</p>
 * <p>三接口的幂等与并发安全全部在 Service 层收口：状态前置校验给出友好
 * 中文提示，真正的并发判定由"条件 UPDATE（WHERE order_status=期望前态）"
 * 完成，师傅占忙由数据库层原子 UPDATE（WHERE work_status=1）完成；
 * Batch 3 起再叠加 Redis 分布式锁 community:order:lock:{orderNo}
 * 作为快速拒绝的第一道防线。</p>
 * <p>改派接口固定记 dispatch_type=REASSIGN（不接受前端伪造），
 * 与派单接口的 AUTO/MANUAL 形成清晰的审计轨迹三类。</p>
 *
 * @author community-booking
 */
@Slf4j
// REST 接口，统一返回 Result JSON
@RestController
// 管理端前缀：SecurityConfig 已限定仅 ROLE_ADMIN
@RequestMapping("/api/admin/dispatch")
// 类级方法鉴权：与 URL 规则构成纵深防御
@PreAuthorize("hasRole('ADMIN')")
// 构造器注入订单业务
@RequiredArgsConstructor
public class AdminDispatchController {

    /** 订单业务：派单、改派、管理员运营取消 */
    private final ServiceOrderService orderService;

    /** 智能派单业务：候选评分与一键指派 */
    private final DispatchService dispatchService;

    /**
     * 智能派单候选列表（管理端派单弹窗数据源）。
     * <p>返回按综合得分降序的候选人，每行带 matchScore 与推荐理由；
     * 技能未全覆盖的师傅以 skillMatched=false 沉底，前端默认置灰。</p>
     *
     * @param orderId     订单ID（待派单，或已派单用于改派参考）
     * @param windowHours 时间冲突窗口半径（小时，可空=配置默认 2）
     * @return 候选师傅列表
     */
    @GetMapping("/candidates/{orderId}")
    public Result<List<DispatchCandidateVO>> candidates(
            @PathVariable Long orderId,
            @RequestParam(required = false) Integer windowHours) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        List<DispatchCandidateVO> candidates = dispatchService.listCandidates(orderId, windowHours, operator);
        return Result.success(candidates);
    }

    /**
     * 一键智能派单：算法取最高分且技能全覆盖的师傅直接指派。
     * <p>低于阈值时返回 400 并提示人工选择，不在运力紧张时强行派单。</p>
     *
     * @param orderId 待派单订单ID
     * @return 被指派的候选（含得分与理由）
     */
    @PostMapping("/auto/{orderId}")
    public Result<DispatchCandidateVO> autoDispatch(@PathVariable Long orderId) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        DispatchCandidateVO assigned = dispatchService.autoDispatch(orderId, operator);
        log.info("管理员[{}]一键智能派单 orderId={} -> workerId={} score={}",
                operator.getUsername(), orderId, assigned.getUserId(), assigned.getMatchScore());
        return Result.success("已按智能算法指派给 " + assigned.getRealName()
                + "（匹配分 " + assigned.getMatchScore() + "）", assigned);
    }

    /**
     * 派单：把待派单订单指派给指定师傅（智能候选确认传 AUTO，手工指派传 MANUAL）。
     * <p>承接师傅资格（账号启用、师傅角色、非休息、技能覆盖）与订单状态
     * 均由 Service 层现查校验，前端提交的候选结果不构成信任前提。</p>
     *
     * @param dto 派单表单（订单ID + 师傅ID + 派单方式）
     * @return 成功提示
     */
    @PostMapping("/assign")
    public Result<Void> assign(@RequestBody @Validated DispatchDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        orderService.dispatchOrder(dto.getOrderId(), dto.getWorkerId(),
                dto.getDispatchType(), operator);
        log.info("管理员[{}]派单 orderId={} workerId={} type={}",
                operator.getUsername(), dto.getOrderId(), dto.getWorkerId(),
                dto.getDispatchType() == null || dto.getDispatchType().isEmpty()
                        ? "MANUAL" : dto.getDispatchType());
        return Result.success("派单成功，已通知承接师傅", null);
    }

    /**
     * 改派：更换"已派单未开工"订单的承接师傅（旧师傅释放、新师傅占忙）。
     *
     * @param dto 改派表单（订单ID + 新师傅ID）
     * @return 成功提示
     */
    @PostMapping("/reassign")
    public Result<Void> reassign(@RequestBody @Validated ReassignDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        orderService.reassignOrder(dto.getOrderId(), dto.getNewWorkerId(), operator);
        log.info("管理员[{}]改派 orderId={} newWorkerId={}",
                operator.getUsername(), dto.getOrderId(), dto.getNewWorkerId());
        return Result.success("改派成功", null);
    }

    /**
     * 管理员运营取消：待派单与已派单两态可取消（服务中禁止单方面取消）。
     *
     * @param id     订单ID
     * @param reason 取消原因（追记备注，可空，最长200字符）
     * @return 成功提示
     */
    @PutMapping("/order/{id}/cancel")
    public Result<Void> adminCancel(
            @PathVariable Long id,
            @RequestParam(required = false)
            @Size(max = 200, message = "取消原因最长200字符") String reason) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        orderService.adminCancelOrder(id, reason, operator);
        log.info("管理员[{}]运营取消 orderId={}", operator.getUsername(), id);
        return Result.success("订单已取消", null);
    }
}
