package com.community.booking.controller;

import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.OrderPageQueryDTO;
import com.community.booking.dto.PageQueryDTO;
import com.community.booking.service.ServiceCategoryService;
import com.community.booking.service.ServiceEvaluationService;
import com.community.booking.service.ServiceItemService;
import com.community.booking.service.ServiceOrderService;
import com.community.booking.utils.Constants;
import com.community.booking.utils.Result;
import com.community.booking.utils.SecurityUtils;
import com.community.booking.vo.EvaluationVO;
import com.community.booking.vo.HotServiceVO;
import com.community.booking.vo.OrderVO;
import com.community.booking.vo.PageVO;
import com.community.booking.vo.ServiceCategoryVO;
import com.community.booking.vo.ServiceItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.util.List;

/**
 * 通用读接口（/api/common/**，任意已登录角色可访问）。
 * <p>这些读接口对三类角色都有合理用途：居民浏览服务目录与热门榜、
 * 师傅查看可承接的服务与自己的工单、管理员巡检前台视角与派单中心列表，
 * 因此不按角色划分 URL，而是"登录即可 + Service 层按登录角色注入过滤条件"：</p>
 * <ul>
 *   <li>订单分页/详情、评价分页与回显的三视角归属条件一律由 Service 层
 *       按 SecurityUtils 取出的 role + userId 注入（居民只看自己下单的、
 *       师傅只看指派给自己的、管理员全量），前端无从传 userId 越权；</li>
 *   <li>服务项分页/详情对管理员开放上下架全量（status 参数生效），其余角色
 *       由 Controller 按 role 固化为居民视角（仅上架），Service 内部再校验一次。</li>
 * </ul>
 * <p>分页 DTO 以查询串绑定（GET 无请求体），@Validated 触发 JSR-303 的
 * 页码/页容量/状态范围校验，越界回 400 中文提示。</p>
 *
 * @author community-booking
 */
@Slf4j
// REST 接口，统一返回 Result JSON
@RestController
// 通用前缀：SecurityConfig 中归入 anyRequest().authenticated()
@RequestMapping("/api/common")
// 构造器注入所需 Service（仅依赖接口，严禁注入 Mapper）
@RequiredArgsConstructor
public class CommonController {

    /** 服务分类业务（目录导航数据源，读路径带 Redis 缓存） */
    private final ServiceCategoryService categoryService;

    /** 服务项目业务（服务广场、项目详情、热门榜单） */
    private final ServiceItemService itemService;

    /** 订单业务（三视角分页与详情的归属过滤在 Service 层） */
    private final ServiceOrderService orderService;

    /** 评价业务（三视角评价列表与按订单回显） */
    private final ServiceEvaluationService evaluationService;

    /**
     * 服务分类目录（全量，sort_order 升序 + 上架项目数）。
     *
     * @return 分类 VO 全量列表（Redis 命中时零数据库开销）
     */
    @GetMapping("/categories")
    public Result<List<ServiceCategoryVO>> categories() {
        return Result.success(categoryService.listCategories());
    }

    /**
     * 服务项目分页（居民/师傅视角仅上架；管理员可看全量并按状态筛选）。
     *
     * @param query      分页入参（keyword 模糊匹配项目名/说明）
     * @param categoryId 分类筛选（可空=不限）
     * @param status     上下架筛选（仅管理员角色生效，其他角色强制上架）
     * @return 服务项目 VO 分页
     */
    @GetMapping("/items")
    public Result<PageVO<ServiceItemVO>> items(
            @Validated PageQueryDTO query,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer status) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        // 仅管理员角色可享受"看下架"的运营特权；其余角色即使传 status 也忽略，
        // 避免居民/师傅借 status=0 窥探未上架的服务项目
        if (Constants.ROLE_ADMIN.equals(operator.getRole())) {
            return Result.success(itemService.pageItemsForAdmin(query, categoryId, status));
        }
        return Result.success(itemService.pageItemsForResident(query, categoryId));
    }

    /**
     * 服务项目详情（管理员可见下架项目，其余角色下架项目按 404 处理）。
     *
     * @param id 项目ID
     * @return 服务项目 VO（技能标签列表已补全）
     */
    @GetMapping("/items/{id}")
    public Result<ServiceItemVO> itemDetail(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        boolean forResident = !Constants.ROLE_ADMIN.equals(operator.getRole());
        return Result.success(itemService.getItemDetail(id, forResident));
    }

    /**
     * 热门服务 TOP-N（下单行为驱动的热度榜，优先读 Redis ZSET）。
     *
     * @param limit 取前 N 条（1~20，Service 层再次夹取）
     * @return 热门条目列表
     */
    @GetMapping("/items/hot")
    public Result<List<HotServiceVO>> hot(
            @RequestParam(defaultValue = "10")
            @Min(value = 1, message = "热门榜最少取1条")
            @Max(value = 20, message = "热门榜最多取20条") int limit) {
        return Result.success(itemService.listHotServices(limit));
    }

    /**
     * 订单三视角分页：归属条件由 Service 按登录角色注入（居民=自己下单的、
     * 师傅=指派给自己的、管理员=全量），前端无法通过参数越权。
     *
     * @param query 分页入参（订单状态/分类/项目/关键字）
     * @return 订单 VO 分页
     */
    @GetMapping("/orders")
    public Result<PageVO<OrderVO>> orders(@Validated OrderPageQueryDTO query) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(orderService.pageOrders(query, operator));
    }

    /**
     * 订单详情（管理员全量、居民限本人下单、师傅限指派给本人，其余 403）。
     *
     * @param id 订单ID
     * @return 订单 VO（状态与派单方式中文、动作可行性标记已补全）
     */
    @GetMapping("/orders/{id}")
    public Result<OrderVO> orderDetail(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(orderService.getOrderDetail(id, operator));
    }

    /**
     * 按订单回显评价（未评价时 data 为 null；非本人且非管理员 403）。
     *
     * @param id 订单ID
     * @return 评价 VO 或 null
     */
    @GetMapping("/orders/{id}/evaluation")
    public Result<EvaluationVO> evaluationOfOrder(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(evaluationService.getEvaluationByOrderId(id, operator));
    }

    /**
     * 评价三视角分页（居民看自己给出的、师傅看收到的、管理员看全量）。
     *
     * @param query     分页入参（keyword 模糊匹配评价内容）
     * @param star      星级筛选 1~5（可空=不限，Service 层校验范围）
     * @param serviceId 服务项目筛选（可空=不限）
     * @return 评价 VO 分页
     */
    @GetMapping("/evaluations")
    public Result<PageVO<EvaluationVO>> evaluations(
            @Validated PageQueryDTO query,
            @RequestParam(required = false) Integer star,
            @RequestParam(required = false) Long serviceId) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        return Result.success(evaluationService.pageEvaluations(query, star, serviceId, operator));
    }
}
