package com.community.booking.controller;

import com.community.booking.dto.CategorySaveDTO;
import com.community.booking.dto.ItemSaveDTO;
import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.PageQueryDTO;
import com.community.booking.dto.UserSaveDTO;
import com.community.booking.dto.WorkerProfileSaveDTO;
import com.community.booking.service.ServiceCategoryService;
import com.community.booking.service.ServiceItemService;
import com.community.booking.service.SysUserService;
import com.community.booking.service.WorkerProfileService;
import com.community.booking.utils.Result;
import com.community.booking.utils.SecurityUtils;
import com.community.booking.vo.PageVO;
import com.community.booking.vo.ServiceCategoryVO;
import com.community.booking.vo.ServiceItemVO;
import com.community.booking.vo.UserVO;
import com.community.booking.vo.WorkerProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

/**
 * 管理端目录与账号管理接口（/api/admin/**，仅 ROLE_ADMIN）。
 * <p>承载物业侧的"主数据维护"职责：服务分类 CRUD、服务项目 CRUD 与上下架、
 * 师傅档案与名单、账号管理。垂直越权双防线：URL 前缀（SecurityConfig）
 * + 类级 @PreAuthorize；这些写操作在 Service 层还各自带引用完整性校验
 * （分类下有项目禁止删除、项目被订单引用禁止删除、账号有业务引用禁止删除），
 * 杜绝物理外键缺失导致的悬挂数据。</p>
 * <p>读接口与前台共用一条 SQL：本类的服务项目分页传 status=null 即全量
 * （含下架），与 /api/common/items 的居民端"仅上架"形成对照——差异由
 * Service 层的 status 参数表达，而非两套 SQL。</p>
 *
 * @author community-booking
 */
@Slf4j
// REST 接口，统一返回 Result JSON
@RestController
// 管理端前缀：SecurityConfig 已限定仅 ROLE_ADMIN
@RequestMapping("/api/admin")
// 类级方法鉴权：与 URL 规则构成纵深防御（防止前缀被误配后越权写）
@PreAuthorize("hasRole('ADMIN')")
// 构造器注入四类主数据业务
@RequiredArgsConstructor
public class AdminCatalogController {

    /** 服务分类业务（写路径在事务提交后精确失效列表缓存） */
    private final ServiceCategoryService categoryService;

    /** 服务项目业务（写路径校验技能词表，保证新项目可派单） */
    private final ServiceItemService itemService;

    /** 师傅档案业务（名单查询、档案代管、单个详情） */
    private final WorkerProfileService workerProfileService;

    /** 用户业务（账号增删改查，密码 BCrypt 加密在 Service 层） */
    private final SysUserService sysUserService;

    // ==================== 服务分类管理 ====================

    /**
     * 分类分页列表（含上架项目数，支持名称模糊检索）。
     *
     * @param query 分页入参（keyword 匹配分类名称）
     * @return 分类 VO 分页
     */
    @GetMapping("/category/page")
    public Result<PageVO<ServiceCategoryVO>> categoryPage(@Validated PageQueryDTO query) {
        return Result.success(categoryService.pageCategories(query));
    }

    /**
     * 分类详情（管理员编辑回显）。
     *
     * @param id 分类ID
     * @return 分类 VO
     */
    @GetMapping("/category/{id}")
    public Result<ServiceCategoryVO> categoryDetail(@PathVariable Long id) {
        return Result.success(categoryService.getCategoryDetail(id));
    }

    /**
     * 新增或修改分类（id 为空新增）。
     *
     * @param dto 分类表单（名称、图标、排序）
     * @return 成功提示
     */
    @PostMapping("/category")
    public Result<Void> saveCategory(@RequestBody @Validated CategorySaveDTO dto) {
        categoryService.saveCategory(dto);
        log.info("管理员维护服务分类 name={}", dto.getCategoryName());
        return Result.success("分类已保存", null);
    }

    /**
     * 删除分类（分类下仍有服务项目时被 Service 层拦截）。
     *
     * @param id 分类ID
     * @return 成功提示
     */
    @DeleteMapping("/category/{id}")
    public Result<Void> removeCategory(@PathVariable Long id) {
        categoryService.removeCategory(id);
        log.info("管理员删除分类 id={}", id);
        return Result.success("分类已删除", null);
    }

    // ==================== 服务项目管理 ====================

    /**
     * 服务项目分页（管理员全量视角，可按分类与上下架状态筛选）。
     *
     * @param query      分页入参
     * @param categoryId 分类筛选（可空）
     * @param status     上下架筛选（可空=全量）
     * @return 服务项目 VO 分页
     */
    @GetMapping("/item/page")
    public Result<PageVO<ServiceItemVO>> itemPage(
            @Validated PageQueryDTO query,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer status) {
        return Result.success(itemService.pageItemsForAdmin(query, categoryId, status));
    }

    /**
     * 服务项目详情（管理员可见下架项目）。
     *
     * @param id 项目ID
     * @return 服务项目 VO
     */
    @GetMapping("/item/{id}")
    public Result<ServiceItemVO> itemDetail(@PathVariable Long id) {
        return Result.success(itemService.getItemDetail(id, false));
    }

    /**
     * 新增或修改服务项目（技能标签须全部落在师傅技能词表内，否则拒绝保存）。
     *
     * @param dto 项目表单（分类/名称/价格/单位/技能标签/说明/状态）
     * @return 成功提示
     */
    @PostMapping("/item")
    public Result<Void> saveItem(@RequestBody @Validated ItemSaveDTO dto) {
        itemService.saveItem(dto);
        log.info("管理员维护服务项目 name={}", dto.getItemName());
        return Result.success("服务项目已保存", null);
    }

    /**
     * 删除服务项目（被订单引用时禁止删除，Service 层拦截并建议下架）。
     *
     * @param id 项目ID
     * @return 成功提示
     */
    @DeleteMapping("/item/{id}")
    public Result<Void> removeItem(@PathVariable Long id) {
        itemService.removeItem(id);
        log.info("管理员删除服务项目 id={}", id);
        return Result.success("服务项目已删除", null);
    }

    /**
     * 上下架切换（运营开关，下架后居民端不可见不可下单）。
     *
     * @param id     项目ID
     * @param status 目标状态：1上架/0下架
     * @return 成功提示
     */
    @PutMapping("/item/{id}/status")
    public Result<Void> toggleStatus(
            @PathVariable Long id,
            @RequestParam
            @Min(value = 0, message = "状态最小为0")
            @Max(value = 1, message = "状态最大为1") Integer status) {
        itemService.toggleStatus(id, status);
        log.info("管理员切换服务项目[{}]上下架 status={}", id, status);
        return Result.success(status == 1 ? "已上架" : "已下架", null);
    }

    // ==================== 师傅管理 ====================

    /**
     * 师傅名单分页（可按接单状态筛选，关键字匹配用户名/姓名/技能）。
     *
     * @param query      分页入参
     * @param workStatus 接单状态筛选：0休息/1空闲/2忙碌（可空=不限）
     * @return 师傅档案 VO 分页
     */
    @GetMapping("/worker/page")
    public Result<PageVO<WorkerProfileVO>> workerPage(
            @Validated PageQueryDTO query,
            @RequestParam(required = false) Integer workStatus) {
        return Result.success(workerProfileService.pageWorkers(query, workStatus));
    }

    /**
     * 单个师傅档案详情（派单中心选人时的技能与负载参考）。
     *
     * @param userId 师傅用户ID
     * @return 师傅档案 VO
     */
    @GetMapping("/worker/{userId}")
    public Result<WorkerProfileVO> workerDetail(@PathVariable Long userId) {
        return Result.success(workerProfileService.getWorkerDetail(userId));
    }

    /**
     * 管理员代管师傅档案（指定目标 userId 提交，Service 层校验操作者角色与
     * 档案存在性；管理员可自由录入技能标签，不受词表限制）。
     *
     * @param dto 档案表单（userId + 技能标签 + 目标接单状态）
     * @return 成功提示
     */
    @PutMapping("/worker")
    public Result<Void> saveWorkerProfile(@RequestBody @Validated WorkerProfileSaveDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        workerProfileService.saveProfile(dto, operator);
        log.info("管理员[{}]代管师傅档案 targetUserId={}", operator.getUsername(), dto.getUserId());
        return Result.success("师傅档案已保存", null);
    }

    // ==================== 账号管理 ====================

    /**
     * 账号分页（可按角色与状态筛选，关键字匹配用户名/姓名/手机号）。
     *
     * @param query  分页入参
     * @param role   角色筛选 ROLE_RESIDENT/ROLE_WORKER/ROLE_ADMIN（可空）
     * @param status 账号状态筛选 1正常/0禁用（可空）
     * @return 用户 VO 分页（不含密码密文）
     */
    @GetMapping("/user/page")
    public Result<PageVO<UserVO>> userPage(
            @Validated PageQueryDTO query,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Integer status) {
        return Result.success(sysUserService.pageUsers(query, role, status));
    }

    /**
     * 新增或修改账号（新增密码留空取初始 123456；改角色为师傅时自动建档案）。
     *
     * @param dto 账号表单（用户名/密码/姓名/电话/邮箱/地址/角色/状态）
     * @return 成功提示
     */
    @PostMapping("/user")
    public Result<Void> saveUser(@RequestBody @Validated UserSaveDTO dto) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        sysUserService.saveUser(dto, operator);
        log.info("管理员[{}]维护账号 username={}", operator.getUsername(), dto.getUsername());
        return Result.success("账号已保存", null);
    }

    /**
     * 删除账号（有订单、评价或在途工单引用时禁止删除，Service 层拦截）。
     *
     * @param id 用户ID
     * @return 成功提示
     */
    @DeleteMapping("/user/{id}")
    public Result<Void> removeUser(@PathVariable Long id) {
        LoginUserDTO operator = SecurityUtils.currentLoginUser();
        sysUserService.removeUser(id, operator);
        log.info("管理员[{}]删除账号 userId={}", operator.getUsername(), id);
        return Result.success("账号已删除", null);
    }
}
