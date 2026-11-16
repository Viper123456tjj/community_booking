package com.community.booking.service;

import com.community.booking.dto.LoginUserDTO;
import com.community.booking.vo.DispatchCandidateVO;

import java.util.List;

/**
 * 智能派单业务接口（Batch 4）：候选师傅多维权重评分 + 一键自动指派。
 * <p>算法的输入是"订单的预约时间 + 服务项目所需的技能标签"，输出是
 * 按综合得分降序排列的候选师傅列表；评分完全在应用层内存中完成，
 * 数据库只负责返回候选原始行（技能串、接单状态、评分、在手工单数），
 * 因此调整权重不需要改 SQL，也不会给数据库增加聚合负担。</p>
 * <p>与 {@code ServiceOrderService.dispatchOrder} 的分工：本接口只负责
 * "推荐谁"（读路径），真正的写操作仍走 dispatchOrder——状态机幂等、
 * 分布式锁、师傅占忙的原子 UPDATE 全部复用既有实现，不重复造轮子。</p>
 *
 * @author community-booking
 */
public interface DispatchService {

    /**
     * 候选师傅评分列表（管理端智能派单弹窗数据源）。
     * <p>返回行按综合得分降序；技能未全覆盖的师傅排在末尾并标记
     * {@code skillMatched=false}，供管理员识别"为什么他不推荐"，但前端默认
     * 不把它作为可一键指派的候选（强行指派会被 dispatchOrder 的资格校验拒绝）。</p>
     *
     * @param orderId     订单ID（待派单或已派单未开工）
     * @param windowHours 时间冲突判定半径（小时，可空=用配置默认 2 小时）
     * @param currentUser 当前登录管理员
     * @return 候选列表（含 matchScore 与推荐理由）
     */
    List<DispatchCandidateVO> listCandidates(Long orderId, Integer windowHours, LoginUserDTO currentUser);

    /**
     * 一键智能派单：取综合得分最高且技能全覆盖的师傅直接指派。
     * <p>安全阈值：最高分低于 {@code community.dispatch.auto-threshold}
     * （默认 60）时拒绝自动指派并提示管理员人工选择——低分通常意味着
     * 时段冲突或运力紧张，算法不该替管理员做这种决定。</p>
     *
     * @param orderId     待派单订单ID
     * @param currentUser 当前登录管理员
     * @return 被指派的候选师傅（含得分与推荐理由，便于前端提示"已派给谁、为什么"）
     */
    DispatchCandidateVO autoDispatch(Long orderId, LoginUserDTO currentUser);
}
