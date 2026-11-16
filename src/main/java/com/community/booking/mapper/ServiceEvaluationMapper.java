package com.community.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.community.booking.entity.ServiceEvaluation;
import com.community.booking.vo.EvaluationVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 服务评价 Mapper：继承 BaseMapper 获得单表 CRUD，补充评价联表分页与
 * 单条联表回显两类自定义查询。
 * <p>residentName/workerName/serviceItemName/orderNo 为 VO 联表派生字段，由
 * 本 Mapper JOIN sys_user(居民)/sys_user(师傅)/service_item/service_order
 * 一条 SQL 直出（全部 JOIN 命中主键或唯一索引，不放大行数）。
 * 归属过滤（residentId=本人、workerId=本人、管理员不限）一律由 Service 层
 * 按登录角色注入，严禁前端直传，杜绝越权窥探他人评价。
 * 全部 SQL 100% #{} 预编译，无 ${} 拼接注入风险。</p>
 *
 * @author community-booking
 */
@Mapper
public interface ServiceEvaluationMapper extends BaseMapper<ServiceEvaluation> {

    /**
     * 评价联表分页查询（师傅端"收到的评价"、管理员评价管理、居民"我的评价"三视角共用）。
     * <p>排序按评价时间倒序，最新评价靠前；关键字模糊匹配评价内容与订单号。
     * 星级筛选开放给全部视角（如师傅只想看低分差评）。</p>
     *
     * @param page       分页对象（分页拦截器自动补 COUNT）
     * @param kw         关键字（可空=不筛选，模糊匹配评价内容/订单号）
     * @param star       星级筛选 1~5（可空=不限）
     * @param residentId 归属居民ID（居民"我的评价"视角由 Service 注入；其余 null）
     * @param workerId   归属师傅ID（师傅"收到的评价"视角由 Service 注入；其余 null）
     * @param serviceId  服务项目筛选（可空=不限）
     * @return 评价 VO 分页（联表字段一条 SQL 直出）
     */
    @Select("<script>"
            + "SELECT e.id, e.order_id, o.order_no, e.resident_id, ru.real_name AS resident_name, "
            + "e.worker_id, wu.real_name AS worker_name, e.service_id, si.item_name AS service_item_name, "
            + "e.star, e.comment, e.create_time "
            + "FROM service_evaluation e "
            + "JOIN service_order o ON o.id = e.order_id "
            + "JOIN sys_user ru ON ru.id = e.resident_id "
            + "JOIN sys_user wu ON wu.id = e.worker_id "
            + "JOIN service_item si ON si.id = e.service_id "
            + "<where> "
            + "<if test=\"kw != null and kw != ''\">AND (e.comment LIKE CONCAT('%', #{kw}, '%') "
            + "OR o.order_no LIKE CONCAT('%', #{kw}, '%')) </if> "
            + "<if test=\"star != null\">AND e.star = #{star} </if> "
            + "<if test=\"residentId != null\">AND e.resident_id = #{residentId} </if> "
            + "<if test=\"workerId != null\">AND e.worker_id = #{workerId} </if> "
            + "<if test=\"serviceId != null\">AND e.service_id = #{serviceId} </if> "
            + "</where> "
            + "ORDER BY e.create_time DESC, e.id DESC"
            + "</script>")
    IPage<EvaluationVO> selectEvaluationPage(IPage<EvaluationVO> page, @Param("kw") String kw,
                                             @Param("star") Integer star,
                                             @Param("residentId") Long residentId,
                                             @Param("workerId") Long workerId,
                                             @Param("serviceId") Long serviceId);

    /**
     * 按订单ID联表回显该订单的评价（居民查看"我的评价"详情、管理员追溯单条评价）。
     *
     * @param orderId 订单ID（uk_eval_order 唯一，最多 1 条评价）
     * @return 评价 VO；该订单未评价时返回 null
     */
    @Select("SELECT e.id, e.order_id, o.order_no, e.resident_id, ru.real_name AS resident_name, "
            + "e.worker_id, wu.real_name AS worker_name, e.service_id, si.item_name AS service_item_name, "
            + "e.star, e.comment, e.create_time "
            + "FROM service_evaluation e "
            + "JOIN service_order o ON o.id = e.order_id "
            + "JOIN sys_user ru ON ru.id = e.resident_id "
            + "JOIN sys_user wu ON wu.id = e.worker_id "
            + "JOIN service_item si ON si.id = e.service_id "
            + "WHERE e.order_id = #{orderId} LIMIT 1")
    EvaluationVO selectEvaluationByOrderId(@Param("orderId") Long orderId);
}
