package com.community.booking.controller;

import com.community.booking.dto.LoginUserDTO;
import com.community.booking.service.DashboardService;
import com.community.booking.utils.Result;
import com.community.booking.utils.SecurityUtils;
import com.community.booking.vo.DashboardVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营看板接口（/api/admin/dashboard/**，仅 ROLE_ADMIN）。
 * <p>单接口返回六组数据：前端 ECharts 只发一次请求即可同时渲染指标卡、
 * 折线、饼图、环形、横向柱与热门榜，避免"一屏六图六请求"的加载抖动。</p>
 * <p>Controller 保持零业务：不做聚合、不做补零、不做状态转中文，
 * 全部交给 Service——这样看板口径调整时只改一处，前端契约不变。</p>
 *
 * @author community-booking
 */
@Slf4j
// REST 接口，统一返回 Result JSON
@RestController
// 管理端前缀：SecurityConfig 已限定仅 ROLE_ADMIN
@RequestMapping("/api/admin/dashboard")
// 类级方法鉴权：与 URL 规则构成纵深防御
@PreAuthorize("hasRole('ADMIN')")
// 构造器注入看板业务
@RequiredArgsConstructor
public class DashboardController {

    /** 看板业务：六组聚合数据的一次性组装 */
    private final DashboardService dashboardService;

    /**
     * 运营看板总览（指标卡 + 五张图）。
     *
     * @return 看板聚合数据
     */
    @GetMapping("/overview")
    public Result<DashboardVO> overview() {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        DashboardVO data = dashboardService.getOverview(operator);
        log.debug("管理员[{}]查看运营看板", operator.getUsername());
        return Result.success(data);
    }
}
