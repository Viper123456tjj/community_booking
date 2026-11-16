package com.community.booking.controller;

import com.community.booking.dto.EvaluationSubmitDTO;
import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.OrderCreateDTO;
import com.community.booking.service.ServiceEvaluationService;
import com.community.booking.service.ServiceOrderService;
import com.community.booking.utils.RateLimit;
import com.community.booking.utils.Result;
import com.community.booking.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Size;

/**
 * 居民端写接口（/api/resident/**，仅 ROLE_RESIDENT）。
 * <p>垂直越权双防线：URL 前缀规则（SecurityConfig）+ 类级 @PreAuthorize；
 * 水平越权（取消/评价他人的订单）由 Service 层按登录 userId 与订单归属判定，
 * 本 Controller 一律不接受 userId 参数。</p>
 * <p>下单接口叠加 @RateLimit：契约口径"居民 60 秒内最多提交 3 次预约"，
 * 由 RateLimitAspect 以 Redis Lua 原子计数实现——拦截发生在进入方法体之前，
 * 恶意连点不会消耗数据库资源。</p>
 * <p>读接口（订单列表/详情、评价回显）位于 /api/common/**，三视角共用一条
 * SQL 与一套归属注入逻辑，本类只承载"只有居民能做"的写动作。</p>
 *
 * @author community-booking
 */
@Slf4j
// REST 接口，统一返回 Result JSON
@RestController
// 居民端前缀：SecurityConfig 已限定仅 ROLE_RESIDENT
@RequestMapping("/api/resident")
// 类级方法鉴权：与 URL 规则构成纵深防御（角色判定取认证主体，不可伪造）
@PreAuthorize("hasRole('RESIDENT')")
// 构造器注入订单与评价业务
@RequiredArgsConstructor
public class ResidentController {

    /** 订单业务：下单、取消（状态机与价格快照在 Service 层） */
    private final ServiceOrderService orderService;

    /** 评价业务：一单一评的四重前置校验在 Service 层 */
    private final ServiceEvaluationService evaluationService;

    /**
     * 提交预约订单（价格服务端快照、订单号服务端生成、初始态待派单）。
     * <p>@RateLimit 使用配置默认阈值（60 秒 3 次）：契约场景为"限制居民
     * 1 分钟内最多提交 3 次预约"，超限回 400 中文提示，不消耗数据库。</p>
     *
     * @param dto 下单表单（项目ID/预约时间/地址/电话/备注，JSR-303 校验）
     * @return 成功时 data 为新订单ID
     */
    @PostMapping("/order")
    @RateLimit(value = "order:create", message = "预约提交过于频繁，请稍后再试")
    public Result<Long> createOrder(@RequestBody @Validated OrderCreateDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        Long orderId = orderService.createOrder(dto, operator);
        return Result.success("预约提交成功，等待物业派单", orderId);
    }

    /**
     * 取消预约（仅本人、仅待派单态可取消，Service 层条件 UPDATE 原子推进）。
     *
     * @param id     订单ID
     * @param reason 取消原因（追记到备注，可空，最长200字符）
     * @return 成功提示
     */
    @PutMapping("/order/{id}/cancel")
    public Result<Void> cancelOrder(
            @PathVariable Long id,
            @RequestParam(required = false)
            @Size(max = 200, message = "取消原因最长200字符") String reason) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        orderService.cancelOrder(id, reason, operator);
        return Result.success("预约已取消", null);
    }

    /**
     * 提交服务评价（仅本人已完成且未归档、完成未超 7 天的订单，一单一评）。
     *
     * @param dto 评价表单（订单ID/星级 1~5/文字内容，JSR-303 校验）
     * @return 成功时 data 为新评价ID
     */
    @PostMapping("/evaluation")
    public Result<Long> submitEvaluation(@RequestBody @Validated EvaluationSubmitDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        Long evaluationId = evaluationService.submitEvaluation(dto, operator);
        return Result.success("感谢评价，已反馈至物业与师傅", evaluationId);
    }
}
