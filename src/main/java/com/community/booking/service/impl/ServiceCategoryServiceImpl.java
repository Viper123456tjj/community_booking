package com.community.booking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.booking.dto.CategorySaveDTO;
import com.community.booking.dto.PageQueryDTO;
import com.community.booking.entity.ServiceCategory;
import com.community.booking.entity.ServiceItem;
import com.community.booking.exception.BusinessException;
import com.community.booking.exception.NotFoundException;
import com.community.booking.mapper.ServiceCategoryMapper;
import com.community.booking.mapper.ServiceItemMapper;
import com.community.booking.service.ServiceCategoryService;
import com.community.booking.utils.Constants;
import com.community.booking.utils.RedisCacheUtils;
import com.community.booking.vo.PageVO;
import com.community.booking.vo.ServiceCategoryVO;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务分类业务实现：居民端目录读 + 管理员分类管理写。
 * <p>Batch 3 已接入 Redis 三重防护（方法签名不变，Controller 无感）：</p>
 * <ul>
 *   <li><b>读路径（缓存穿透 + 击穿）</b>：listCategories 经
 *       {@code listOrLoadWithMutex} 读 community:service:categories——
 *       命中零 SQL；未命中时仅抢到重建互斥键的请求回源并写缓存，其余请求
 *       直接回源读不写缓存（防惊群写放大）；回源为空写 __NULL__ 占位 60 秒，
 *       同一无效状态在窗口内不再反复打库；</li>
 *   <li><b>写路径（精确失效）</b>：saveCategory / removeCategory 在事务内注册
 *       afterCommit 失效回调，数据真正落库后才删除缓存键——回滚则不删，
 *       旧缓存仍然正确，不会出现"先删缓存、事务回滚导致旧数据回填"；</li>
 *   <li><b>故障降级</b>：RedisCacheUtils 内部全量 try-catch，Redis 不可用时
 *       自动直查数据库，缓存是加速器而非依赖项。</li>
 * </ul>
 * <p>TTL 取配置 community.cache.category-ttl-minutes（契约 30 分钟）：
 * 写路径已精确失效，TTL 只用于兜底"外部直接改库"等非常规变更。</p>
 *
 * @author community-booking
 */
@Slf4j
@Service
public class ServiceCategoryServiceImpl implements ServiceCategoryService {

    /** 分类 Mapper（主表与联表列表数据源） */
    private final ServiceCategoryMapper categoryMapper;
    /** 服务项目 Mapper（删除分类前的引用校验，防悬挂外键） */
    private final ServiceItemMapper itemMapper;
    /** Redis 缓存原语（读路径缓存包裹 + 写路径事务后精确失效） */
    private final RedisCacheUtils redisCacheUtils;
    /** 分类列表缓存 TTL（分钟，配置 community.cache.category-ttl-minutes） */
    private final long categoryTtlMinutes;

    /**
     * 构造注入 Mapper、缓存工具与 TTL 配置。
     *
     * @param categoryMapper      分类 Mapper
     * @param itemMapper          服务项目 Mapper
     * @param redisCacheUtils     Redis 缓存原语
     * @param categoryTtlMinutes  分类列表缓存 TTL（配置注入，契约 30 分钟）
     */
    public ServiceCategoryServiceImpl(ServiceCategoryMapper categoryMapper, ServiceItemMapper itemMapper,
                                      RedisCacheUtils redisCacheUtils,
                                      @Value("${community.cache.category-ttl-minutes:30}") long categoryTtlMinutes) {
        this.categoryMapper = categoryMapper;
        this.itemMapper = itemMapper;
        this.redisCacheUtils = redisCacheUtils;
        this.categoryTtlMinutes = categoryTtlMinutes;
    }

    /**
     * 全量分类列表（居民端目录导航与缓存回源数据源）。
     * <p>读路径已接缓存：命中直接返回；未命中走互斥重建，同一瞬间的并发
     * 请求只有一条 SQL 回源；空目录写 __NULL__ 短缓存防穿透。</p>
     *
     * @return 分类 VO 全量列表
     */
    @Override
    public List<ServiceCategoryVO> listCategories() {
        // 一条 SQL 直出 itemCount（分类数量有限，全量取而非分页流，语义为目录）
        return redisCacheUtils.listOrLoadWithMutex(
                Constants.CACHE_CATEGORY_LIST,
                categoryTtlMinutes * 60L,
                Constants.CACHE_CATEGORY_MUTEX,
                Constants.MUTEX_TTL_SECONDS,
                new TypeReference<List<ServiceCategoryVO>>() { },
                () -> categoryMapper.listCategoriesWithItemCount());
    }

    /**
     * 管理员分页查询分类。
     *
     * @param query 分页入参
     * @return 分类 VO 分页
     */
    @Override
    public PageVO<ServiceCategoryVO> pageCategories(PageQueryDTO query) {
        long pageNum = query == null || query.getPageNum() == null
                ? Constants.DEFAULT_PAGE_NUM : query.getPageNum();
        long pageSize = query == null || query.getPageSize() == null
                ? Constants.DEFAULT_PAGE_SIZE : Math.min(query.getPageSize(), Constants.MAX_PAGE_SIZE);
        Page<ServiceCategoryVO> page = new Page<>(pageNum, pageSize);
        IPage<ServiceCategoryVO> result = categoryMapper.selectCategoryPage(page, normalizeKw(
                query == null ? null : query.getKeyword()));
        return PageVO.of(result);
    }

    /**
     * 分类详情（不存在即 404）。
     *
     * @param id 分类ID
     * @return 分类 VO
     */
    @Override
    public ServiceCategoryVO getCategoryDetail(Long id) {
        ServiceCategoryVO vo = categoryMapper.selectCategoryVOById(id);
        if (vo == null) {
            throw new NotFoundException("服务分类不存在");
        }
        return vo;
    }

    /**
     * 新增/修改分类：名称排除自身查重，排序缺省 0。
     *
     * @param dto 保存入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveCategory(CategorySaveDTO dto) {
        boolean isCreate = dto.getId() == null;
        // 排除自身查重（修改时名称不变放行），与唯一索引 uk_category_name 双保险
        QueryWrapper<ServiceCategory> wrapper = new QueryWrapper<ServiceCategory>()
                .eq("category_name", dto.getCategoryName());
        if (!isCreate) {
            wrapper.ne("id", dto.getId());
        }
        if (categoryMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(400, "分类名称已存在: " + dto.getCategoryName());
        }
        ServiceCategory entity;
        if (isCreate) {
            entity = new ServiceCategory();
            entity.setCategoryName(dto.getCategoryName());
            entity.setIcon(normalizeIcon(dto.getIcon()));
            entity.setSortOrder(dto.getSortOrder() == null ? 0 : dto.getSortOrder());
            entity.setCreateTime(LocalDateTime.now());
            categoryMapper.insert(entity);
            log.info("新增服务分类[{}]", entity.getCategoryName());
        } else {
            ServiceCategory exist = categoryMapper.selectById(dto.getId());
            if (exist == null) {
                throw new NotFoundException("服务分类不存在");
            }
            exist.setCategoryName(dto.getCategoryName());
            // 空串归一为 null：允许管理员清空图标（前端回退默认图标渲染）
            exist.setIcon(normalizeIcon(dto.getIcon()));
            exist.setSortOrder(dto.getSortOrder() == null ? exist.getSortOrder() : dto.getSortOrder());
            categoryMapper.updateById(exist);
            log.info("修改服务分类[{}]", exist.getCategoryName());
        }
        // 写路径精确失效：事务提交后删除目录缓存键（itemCount 随项目变动、
        // 分类增删改名都会使旧目录失效），Redis 故障时仅 WARN，TTL 到期自愈
        redisCacheUtils.evict(Constants.CACHE_CATEGORY_LIST);
    }

    /**
     * 删除分类：下有服务项目（含下架）即禁止，防止 category_id 悬挂。
     *
     * @param id 分类ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeCategory(Long id) {
        ServiceCategory exist = categoryMapper.selectById(id);
        if (exist == null) {
            throw new NotFoundException("服务分类不存在");
        }
        // COUNT 含上架与下架项目：下架项目重新上架仍需分类归属，故一并拦截
        Long refCount = itemMapper.selectCount(new QueryWrapper<ServiceItem>().eq("category_id", id));
        if (refCount != null && refCount > 0) {
            throw new BusinessException(400, "该分类下仍有 " + refCount + " 个服务项目，请先处理项目后再删除分类");
        }
        categoryMapper.deleteById(id);
        log.info("删除服务分类[{}]", exist.getCategoryName());
        // 目录缓存失效（afterCommit）：删除后目录少一行，必须让下一次读回源
        redisCacheUtils.evict(Constants.CACHE_CATEGORY_LIST);
    }

    /**
     * 图标归一：空串转 null，非空保持原值（格式合法性由 DTO 的 @Pattern 保证）。
     *
     * @param icon 原始图标串
     * @return null 或去空白后的图标串
     */
    private String normalizeIcon(String icon) {
        if (icon == null) {
            return null;
        }
        String trimmed = icon.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 关键字规范化：去空白，空串归一为 null（不参与筛选）。
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
