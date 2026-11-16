package com.community.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.community.booking.entity.ServiceItem;
import com.community.booking.vo.ServiceItemVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 服务项目 Mapper：继承 BaseMapper 获得单表 CRUD，补充联表分页、详情联表
 * 单查、热门 TOP-N 与热度分原子自增四类自定义方法。
 * <p>categoryName 为 VO 联表派生字段，由本 Mapper JOIN service_category
 * 一条 SQL 直出（JOIN 命中主键不放大行数，分页 COUNT 口径准确）。
 * hot_score 是热门榜单（Redis ZSET community:service:hot）的持久化兜底：
 * 下单事务内原子 +1，缓存不可用时由 selectHotTop 直接回退数据库口径。
 * 全部 SQL 100% #{} 预编译。</p>
 *
 * @author community-booking
 */
@Mapper
public interface ServiceItemMapper extends BaseMapper<ServiceItem> {

    /**
     * 服务项目联表分页查询（居民端服务广场与管理员服务管理共用一条 SQL）。
     * <p>三视角差异仅由可空参数表达：居民端传 status=1（只看上架）；
     * 管理员传 status=null 全量或按筛选。排序口径统一为"热度降序 + id
     * 兜底"：居民端热门靠前符合导购直觉，管理员端同序便于与前台对照。</p>
     *
     * @param page       分页对象（分页拦截器自动补 COUNT）
     * @param kw         关键字（可空=不筛选，模糊匹配项目名称/说明正文）
     * @param categoryId 分类筛选（可空=不限）
     * @param status     上下架筛选（可空=不限；居民端由 Service 固定传 1）
     * @return 服务项目 VO 分页（categoryName 一条 SQL 直出）
     */
    @Select("<script>"
            + "SELECT s.id, s.category_id, c.category_name, s.item_name, s.cover_img, "
            + "s.price, s.unit, s.skill_required, s.description, s.hot_score, s.status, s.create_time "
            + "FROM service_item s LEFT JOIN service_category c ON c.id = s.category_id "
            + "<where> "
            + "<if test=\"kw != null and kw != ''\">AND (s.item_name LIKE CONCAT('%', #{kw}, '%') "
            + "OR s.description LIKE CONCAT('%', #{kw}, '%')) </if> "
            + "<if test=\"categoryId != null\">AND s.category_id = #{categoryId} </if> "
            + "<if test=\"status != null\">AND s.status = #{status} </if> "
            + "</where> "
            + "ORDER BY s.hot_score DESC, s.id DESC"
            + "</script>")
    IPage<ServiceItemVO> selectItemPage(IPage<ServiceItemVO> page, @Param("kw") String kw,
                                        @Param("categoryId") Long categoryId, @Param("status") Integer status);

    /**
     * 服务项目详情联表单查（categoryName 与列表口径一致，一条 SQL 直出）。
     *
     * @param id 项目ID
     * @return 服务项目 VO；不存在时返回 null
     */
    @Select("SELECT s.id, s.category_id, c.category_name, s.item_name, s.cover_img, "
            + "s.price, s.unit, s.skill_required, s.description, s.hot_score, s.status, s.create_time "
            + "FROM service_item s LEFT JOIN service_category c ON c.id = s.category_id "
            + "WHERE s.id = #{id} LIMIT 1")
    ServiceItemVO selectItemDetailById(@Param("id") Long id);

    /**
     * 热门服务 TOP-N（仅上架项目，热度降序）：
     * Redis 榜单不可用时的数据库回退口径，同时是 ZSET 冷启动重建的数据源。
     *
     * @param limit 取前 N 条（上限由 Service 层控制，默认 10）
     * @return 热门服务项目 VO 列表（categoryName 直出）
     */
    @Select("SELECT s.id, s.category_id, c.category_name, s.item_name, s.cover_img, "
            + "s.price, s.unit, s.skill_required, s.description, s.hot_score, s.status, s.create_time "
            + "FROM service_item s LEFT JOIN service_category c ON c.id = s.category_id "
            + "WHERE s.status = 1 ORDER BY s.hot_score DESC, s.id DESC LIMIT #{limit}")
    List<ServiceItemVO> selectHotTop(@Param("limit") int limit);

    /**
     * 热度分原子 +1（居民下单成功事务内调用，与订单 INSERT 同事务）。
     * <p>原子自增避免"读-改-写"并发丢失；Batch 3 起与 Redis ZINCRBY 双轨
     * （Redis 失败仅告警不回滚，DB 列始终是重建兜底）。</p>
     *
     * @param id 项目ID
     * @return 受影响行数
     */
    @Update("UPDATE service_item SET hot_score = hot_score + 1 WHERE id = #{id}")
    int addHotScore(@Param("id") Long id);

    /**
     * 按ID集合回查服务项目（热门榜单以 Redis ZSET 提供"ID + 热度分"的排序
     * 骨架，展示字段现查数据库，保证名称/价格/分类改动能即时反映在榜单上）。
     * <p>只回查上架项目：ZSET 中可能残留已下架项目的ID，本查询直接过滤，
     * 下架项目自然退出榜单而无需任何缓存联动删除（读路径自愈）。</p>
     *
     * @param ids 项目ID集合（非空，由 Service 层保证）
     * @return 上架项目 VO 列表（categoryName 直出，顺序由数据库返回序决定，
     *          Service 层按 ZSET 排名在内存中重排）
     */
    @Select("<script>"
            + "SELECT s.id, s.category_id, c.category_name, s.item_name, s.cover_img, "
            + "s.price, s.unit, s.skill_required, s.description, s.hot_score, s.status, s.create_time "
            + "FROM service_item s LEFT JOIN service_category c ON c.id = s.category_id "
            + "WHERE s.status = 1 AND s.id IN "
            + "<foreach collection=\"ids\" item=\"id\" open=\"(\" separator=\",\" close=\")\">#{id}</foreach>"
            + "</script>")
    List<ServiceItemVO> selectItemsByIds(@Param("ids") java.util.Collection<Long> ids);
}
