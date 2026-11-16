package com.community.booking.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.community.booking.entity.WorkerProfile;
import com.community.booking.vo.WorkerProfileVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 服务人员档案 Mapper：继承 BaseMapper 获得单表 CRUD，补充联表分页列表、
 * 联表单查与完成数原子自增三类自定义方法。
 * <p>username/realName/phone/email/status 为 VO 联表派生字段、activeOrders
 * 为"已派单+服务中"在手工单数，均由本 Mapper 的 IPage 联表分页方法一条
 * SQL 直出（分页拦截器自动补写 COUNT；JOIN 均命中主键不放大行数），
 * 避免 Service 逐条查询造成 N+1。全部 SQL 100% #{} 预编译。</p>
 *
 * @author community-booking
 */
@Mapper
public interface WorkerProfileMapper extends BaseMapper<WorkerProfile> {

    /**
     * 师傅档案联表分页查询（管理员"服务人员管理"列表）。
     * <p>排序口径：按平均评分降序、同分按完成数降序、再按 id 兜底，
     * 让优质师傅天然靠前，与派单候选排序口径一致。keyword 模糊匹配
     * 用户名/姓名/技能标签（like 经 #{} 传参无注入风险）。</p>
     *
     * @param page      分页对象（分页拦截器自动补 COUNT）
     * @param kw        关键字（可空=不筛选）
     * @param workStatus 接单状态筛选：1空闲/2忙碌/0休息（可空=不限）
     * @return 师傅档案 VO 分页（联表字段一条 SQL 直出）
     */
    @Select("<script>"
            + "SELECT wp.id, wp.user_id, u.username, u.real_name, u.phone, u.email, "
            + "wp.skill_tags, wp.work_status, wp.rating_score, wp.total_orders, "
            + "u.status, wp.create_time, "
            + "(SELECT COUNT(*) FROM service_order o WHERE o.worker_id = wp.user_id "
            + "AND o.order_status IN (1, 2)) AS active_orders "
            + "FROM worker_profile wp JOIN sys_user u ON u.id = wp.user_id "
            + "<where> "
            + "<if test=\"kw != null and kw != ''\">AND (u.username LIKE CONCAT('%', #{kw}, '%') "
            + "OR u.real_name LIKE CONCAT('%', #{kw}, '%') OR wp.skill_tags LIKE CONCAT('%', #{kw}, '%')) </if> "
            + "<if test=\"workStatus != null\">AND wp.work_status = #{workStatus} </if> "
            + "</where> "
            + "ORDER BY wp.rating_score DESC, wp.total_orders DESC, wp.id DESC"
            + "</script>")
    IPage<WorkerProfileVO> selectWorkerPage(IPage<WorkerProfileVO> page, @Param("kw") String kw,
                                            @Param("workStatus") Integer workStatus);

    /**
     * 师傅档案联表单查（师傅端"我的档案"与管理员查看单个师傅详情共用）。
     *
     * @param userId 师傅用户ID
     * @return 师傅档案 VO；不存在时返回 null
     */
    @Select("SELECT wp.id, wp.user_id, u.username, u.real_name, u.phone, u.email, "
            + "wp.skill_tags, wp.work_status, wp.rating_score, wp.total_orders, "
            + "u.status, wp.create_time, "
            + "(SELECT COUNT(*) FROM service_order o WHERE o.worker_id = wp.user_id "
            + "AND o.order_status IN (1, 2)) AS active_orders "
            + "FROM worker_profile wp JOIN sys_user u ON u.id = wp.user_id "
            + "WHERE wp.user_id = #{userId} LIMIT 1")
    WorkerProfileVO selectWorkerDetailByUserId(@Param("userId") Long userId);

    /**
     * 累计完成订单数原子 +1（师傅推进"完成服务"时在事务内调用）。
     * <p>用 UPDATE ... total_orders = total_orders + 1 原子自增，避免
     * "读-改-写"并发丢失；与智能派单负载均衡因子的实时性要求一致。</p>
     *
     * @param userId 师傅用户ID
     * @return 受影响行数
     */
    @Update("UPDATE worker_profile SET total_orders = total_orders + 1 WHERE user_id = #{userId}")
    int addTotalOrders(@Param("userId") Long userId);

    /**
     * 重算平均星级评分（评价提交经 appoint.eval.queue 异步消费后调用）。
     * <p>直接以库内该师傅全部评价的 AVG(star) 回写，无评价时回写默认 5.00；
     * 一条 SQL 完成读算写，天然幂等——重复消费同一评价消息结果不变。</p>
     *
     * @param userId 师傅用户ID
     * @return 受影响行数
     */
    @Update("UPDATE worker_profile SET rating_score = "
            + "IFNULL((SELECT ROUND(AVG(e.star), 2) FROM service_evaluation e WHERE e.worker_id = #{userId}), 5.00) "
            + "WHERE user_id = #{userId}")
    int recalcRatingScore(@Param("userId") Long userId);

    /**
     * 空闲状态下的原子置忙（智能/手动派单占用师傅的并发防线）。
     * <p>UPDATE 的 WHERE 与 SET 在同一语句内原子求值：仅当当前为空闲(1)时
     * 才会置为忙碌(2)，返回 0 表示师傅已被并发派单抢走或处于休息，
     * 调用方据此回滚或重选候选人——这是数据库层的最终防抢单兜底。</p>
     *
     * @param userId 师傅用户ID
     * @return 受影响行数（1=占用成功；0=非空闲已被抢占）
     */
    @Update("UPDATE worker_profile SET work_status = 2 WHERE user_id = #{userId} AND work_status = 1")
    int trySetBusy(@Param("userId") Long userId);
}
