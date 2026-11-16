package com.community.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.community.booking.entity.ServiceCategory;
import com.community.booking.vo.ServiceCategoryVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 服务分类 Mapper：继承 BaseMapper 获得单表 CRUD，补充带"上架项目数"的
 * 联表分页与全量列表两类自定义查询。
 * <p>itemCount 为"该分类下上架(1)服务项目数"的 COUNT 子查询直出，居民端
 * 据此显示"共N项服务"、管理员据此判断删除分类是否安全。全部 SQL 100%
 * #{} 预编译，keyword 经 CONCAT('%', #{kw}, '%') 传参，无 ${} 注入风险。</p>
 *
 * @author community-booking
 */
@Mapper
public interface ServiceCategoryMapper extends BaseMapper<ServiceCategory> {

    /**
     * 分类全量列表（sort_order 升序 + itemCount 子查询直出）。
     * <p>居民端服务目录导航一次性取全量（分类数量有限，语义为目录而非
     * 分页流），本方法是 Redis 键 community:service:categories 缓存的
     * 回源数据源（Batch 3 接入缓存后复用本查询，签名不变）。</p>
     *
     * @return 分类 VO 全量列表（含上架项目数）
     */
    @Select("SELECT c.id, c.category_name, c.icon, c.sort_order, c.create_time, "
            + "(SELECT COUNT(*) FROM service_item s WHERE s.category_id = c.id AND s.status = 1) AS item_count "
            + "FROM service_category c ORDER BY c.sort_order ASC, c.id ASC")
    java.util.List<ServiceCategoryVO> listCategoriesWithItemCount();

    /**
     * 分类分页联表查询（管理员"服务分类管理"列表）。
     *
     * @param page 分页对象（分页拦截器自动补 COUNT）
     * @param kw   关键字（可空=不筛选，模糊匹配分类名称）
     * @return 分类 VO 分页（itemCount 直出）
     */
    @Select("<script>"
            + "SELECT c.id, c.category_name, c.icon, c.sort_order, c.create_time, "
            + "(SELECT COUNT(*) FROM service_item s WHERE s.category_id = c.id AND s.status = 1) AS item_count "
            + "FROM service_category c "
            + "<where> "
            + "<if test=\"kw != null and kw != ''\">AND c.category_name LIKE CONCAT('%', #{kw}, '%') </if> "
            + "</where> "
            + "ORDER BY c.sort_order ASC, c.id ASC"
            + "</script>")
    IPage<ServiceCategoryVO> selectCategoryPage(IPage<ServiceCategoryVO> page, @Param("kw") String kw);

    /**
     * 分类联表单查（含 itemCount，管理员编辑回显与详情共用）。
     *
     * @param id 分类ID
     * @return 分类 VO；不存在时返回 null
     */
    @Select("SELECT c.id, c.category_name, c.icon, c.sort_order, c.create_time, "
            + "(SELECT COUNT(*) FROM service_item s WHERE s.category_id = c.id AND s.status = 1) AS item_count "
            + "FROM service_category c WHERE c.id = #{id} LIMIT 1")
    ServiceCategoryVO selectCategoryVOById(@Param("id") Long id);
}
