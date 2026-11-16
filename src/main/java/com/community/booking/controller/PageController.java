package com.community.booking.controller;

import com.community.booking.utils.Constants;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面路由控制器（Batch 5）：只做 URL → Thymeleaf 模板的映射。
 * <p>本类刻意保持"零业务"：不注入任何 Service、不向模型塞数据。所有页面数据
 * 一律由前端 jQuery 调 /api/** 获取，原因有两点：</p>
 * <ol>
 *   <li><b>口径唯一</b>：同一个列表接口同时服务三种角色，归属过滤与动作标记
 *       只在 Service 层实现一次，页面渲染不会出现"服务端一套、接口一套"的双口径；</li>
 *   <li><b>职责清晰</b>：模板只负责结构（th:fragment 抽离导航/侧栏/页脚），
 *       数据交互交给 api.js，页面逻辑不与 Java 代码耦合。</li>
 * </ol>
 * <p>角色分流在两道地方发生：SecurityConfig 的 URL 规则（越权用户直接被拒）
 * 与本类的 "/" 入口（已登录用户按角色跳到各自工作台）。</p>
 *
 * @author community-booking
 */
@Controller
public class PageController {

    /**
     * 根路径：未登录跳登录页；已登录按角色分流到各自首页。
     * <p>用 302 而不是直接渲染，是为了让浏览器地址栏显示真实工作台路径，
     * 刷新后不会又回到"分流前的 /"。</p>
     *
     * @return 重定向地址
     */
    @GetMapping("/")
    public String index() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "redirect:/login";
        }
        String role = resolveRole(auth);
        if (Constants.ROLE_ADMIN.equals(role)) {
            return "redirect:/admin/dashboard";
        }
        if (Constants.ROLE_WORKER.equals(role)) {
            return "redirect:/worker/workbench";
        }
        return "redirect:/resident/home";
    }

    /**
     * 登录页（匿名可访问）。
     *
     * @return 登录模板
     */
    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /**
     * 居民服务大厅：分类筛选、服务卡片、热门榜与下单入口。
     *
     * @return 服务大厅模板
     */
    @GetMapping("/resident/home")
    public String residentHome() {
        return "resident/home";
    }

    /**
     * 居民"我的预约"：订单列表、取消与评价入口。
     *
     * @return 我的预约模板
     */
    @GetMapping("/resident/orders")
    public String residentOrders() {
        return "resident/orders";
    }

    /**
     * 师傅工单工作台：已派单/服务中工单与开工、完成操作。
     *
     * @return 工单模板
     */
    @GetMapping("/worker/workbench")
    public String workerWorkbench() {
        return "worker/workbench";
    }

    /**
     * 师傅服务档案：技能标签与接单状态维护。
     *
     * @return 档案模板
     */
    @GetMapping("/worker/profile")
    public String workerProfile() {
        return "worker/profile";
    }

    /**
     * 师傅收到的评价列表。
     *
     * @return 评价模板
     */
    @GetMapping("/worker/evaluations")
    public String workerEvaluations() {
        return "worker/evaluations";
    }

    /**
     * 管理员运营看板：指标卡 + 五张 ECharts 图表。
     *
     * @return 看板模板
     */
    @GetMapping("/admin/dashboard")
    public String adminDashboard() {
        return "admin/dashboard";
    }

    /**
     * 管理员派单中心：待派单列表、智能候选弹窗、改派与运营取消。
     *
     * @return 派单中心模板
     */
    @GetMapping("/admin/dispatch")
    public String adminDispatch() {
        return "admin/dispatch";
    }

    /**
     * 管理员订单管理：全量订单查询与运营取消。
     *
     * @return 订单管理模板
     */
    @GetMapping("/admin/orders")
    public String adminOrders() {
        return "admin/orders";
    }

    /**
     * 管理员服务目录：分类与服务项目的增删改查。
     *
     * @return 服务目录模板
     */
    @GetMapping("/admin/catalog")
    public String adminCatalog() {
        return "admin/catalog";
    }

    /**
     * 管理员师傅管理：师傅档案维护与账号开通。
     *
     * @return 师傅管理模板
     */
    @GetMapping("/admin/workers")
    public String adminWorkers() {
        return "admin/workers";
    }

    /**
     * 从认证主体中解析角色（authorities 里唯一的 ROLE_* 字符串）。
     *
     * @param auth 已认证主体
     * @return 角色常量；解析不到时返回空串（调用方按居民兜底）
     */
    private String resolveRole(Authentication auth) {
        for (GrantedAuthority authority : auth.getAuthorities()) {
            String value = authority.getAuthority();
            if (value != null && value.startsWith("ROLE_")) {
                return value;
            }
        }
        return "";
    }
}
