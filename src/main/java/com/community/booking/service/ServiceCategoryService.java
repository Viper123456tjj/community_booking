package com.community.booking.service;

import com.community.booking.dto.CategorySaveDTO;
import com.community.booking.dto.PageQueryDTO;
import com.community.booking.vo.PageVO;
import com.community.booking.vo.ServiceCategoryVO;

import java.util.List;

/**
 * 服务分类业务接口：居民端目录导航（读）+ 管理员分类管理（写）。
 * <p>读路径全量列表是 Redis 键 community:service:categories 的缓存回源数据源
 * （Batch 3 接入缓存后方法签名不变，仅实现内部加缓存）；写路径仅管理员可达，
 * 变更后在事务提交后精确失效缓存。</p>
 *
 * @author community-booking
 */
public interface ServiceCategoryService {

    /**
     * 全量分类列表（sort_order 升序，含上架项目数），居民端目录导航与缓存源。
     *
     * @return 分类 VO 全量列表
     */
    List<ServiceCategoryVO> listCategories();

    /**
     * 管理员分页查询分类列表。
     *
     * @param query 分页查询入参（keyword 模糊匹配分类名称）
     * @return 分类 VO 分页
     */
    PageVO<ServiceCategoryVO> pageCategories(PageQueryDTO query);

    /**
     * 分类详情（含上架项目数，管理员编辑回显用）。
     *
     * @param id 分类ID
     * @return 分类 VO；不存在时抛出 NotFoundException
     */
    ServiceCategoryVO getCategoryDetail(Long id);

    /**
     * 新增或修改分类（id 为空新增，否则修改）。
     * <p>业务规则：分类名称排除自身查重；写操作后失效分类列表缓存。</p>
     *
     * @param dto 分类保存入参
     */
    void saveCategory(CategorySaveDTO dto);

    /**
     * 删除分类：该分类下仍有服务项目（无论上架下架）时禁止删除，
     * 防止 service_item.category_id 悬挂引用。
     *
     * @param id 分类ID
     */
    void removeCategory(Long id);
}
