package com.community.booking.controller;

import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.WorkerProfileSaveDTO;
import com.community.booking.service.ServiceOrderService;
import com.community.booking.service.WorkerProfileService;
import com.community.booking.utils.Result;
import com.community.booking.utils.SecurityUtils;
import com.community.booking.vo.WorkerProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 师傅端接口（/api/worker/**，仅 ROLE_WORKER）。
 * <p>垂直越权双防线：URL 前缀规则（SecurityConfig）+ 类级 @PreAuthorize。
 * 本类有一个特殊越权收口：维护档案的 DTO 携带 userId 字段（管理员代管场景
 * 需要指定目标师傅），师傅端入口一律由本 Controller 把 userId 覆写为登录
 * 用户ID，杜绝师傅借同一 DTO 提交他人档案；管理员的代管入口在
 * AdminController，走同一个 Service 方法（Service 层对两种入口都做了归属
 * 校验，双保险）。</p>
 * <p>工单列表与详情在 /api/common/orders 下按登录角色自动过滤，本类只承载
 * "只有承接师傅本人能做"的两步状态推进（开始服务、完成服务），
 * 归属与状态前置条件由 Service 层校验 + 条件 UPDATE 保证。</p>
 *
 * @author community-booking
 */
@Slf4j
// REST 接口，统一返回 Result JSON
@RestController
// 师傅端前缀：SecurityConfig 已限定仅 ROLE_WORKER
@RequestMapping("/api/worker")
// 类级方法鉴权：与 URL 规则构成纵深防御
@PreAuthorize("hasRole('WORKER')")
// 构造器注入订单与师傅档案业务
@RequiredArgsConstructor
public class WorkerController {

    /** 订单业务：开始服务、完成服务（仅承接本人的工单） */
    private final ServiceOrderService orderService;

    /** 师傅档案业务：自助维护技能标签与接单状态 */
    private final WorkerProfileService workerProfileService;

    /**
     * 查询本人师傅档案（技能标签、接单状态、平均星级、累计完成数、在手工单数）。
     *
     * @return 本人档案 VO
     */
    @GetMapping("/profile")
    public Result<WorkerProfileVO> myProfile() {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(workerProfileService.getMyProfile(operator.getUserId()));
    }

    /**
     * 维护本人档案（技能标签 + 期望接单状态）。
     * <p>安全收口：忽略前端提交的 userId，强制置为登录用户ID——师傅端
     * 永远只能改自己的档案；技能标签须通过服务技能词表校验、休息状态下
     * 仍有在手工单时禁止切换，这些规则全部在 Service 层实现。</p>
     *
     * @param dto 档案表单（技能标签串 + 目标状态 0休息/1空闲）
     * @return 成功提示
     */
    @PutMapping("/profile")
    public Result<Void> saveProfile(@RequestBody @Validated WorkerProfileSaveDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        // 覆写目标用户为登录本人：杜绝借同一 DTO 越权编辑他人档案
        dto.setUserId(operator.getUserId());
        workerProfileService.saveProfile(dto, operator);
        log.info("师傅[{}]维护个人档案", operator.getUsername());
        return Result.success("档案已保存", null);
    }

    /**
     * 开始服务（已派单 -> 服务中，仅承接本人）。
     *
     * @param id 订单ID
     * @return 成功提示
     */
    @PutMapping("/order/{id}/start")
    public Result<Void> startService(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        orderService.startService(id, operator);
        log.info("师傅[{}]开始服务 orderId={}", operator.getUsername(), id);
        return Result.success("已标记为开始服务", null);
    }

    /**
     * 完成服务（服务中 -> 已完成，仅承接本人；同事务累计完成数并回置空闲）。
     *
     * @param id 订单ID
     * @return 成功提示
     */
    @PutMapping("/order/{id}/complete")
    public Result<Void> completeService(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        orderService.completeService(id, operator);
        log.info("师傅[{}]完成服务 orderId={}", operator.getUsername(), id);
        return Result.success("服务已完成，等待居民评价", null);
    }
}
