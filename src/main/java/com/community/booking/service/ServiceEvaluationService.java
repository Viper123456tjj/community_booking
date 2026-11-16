package com.community.booking.service;

import com.community.booking.dto.EvaluationSubmitDTO;
import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.PageQueryDTO;
import com.community.booking.vo.EvaluationVO;
import com.community.booking.vo.PageVO;

/**
 * 服务评价业务接口：居民提交评价 + 三视角评价列表查询 + 单条回显。
 * <p>一单一评由 uk_eval_order 唯一约束兜底，Service 层先行校验给出比
 * "唯一键冲突"更友好的中文提示；评价的 resident_id/worker_id/service_id
 * 一律由订单行派生写入，严禁信任前端传值，保证与订单数据自洽。</p>
 *
 * @author community-booking
 */
public interface ServiceEvaluationService {

    /**
     * 提交评价（仅下单居民本人、仅已完成且未归档且完成未超 7 天的订单）。
     * <p>写入后（Batch 4 起）投递 appoint.eval.queue 异步重算师傅平均星级与
     * 运营看板满意度指标；本批次先同步重算，保证评分即时正确。</p>
     *
     * @param dto         评价入参（订单ID/星级/文字内容）
     * @param currentUser 当前登录居民
     * @return 新评价ID
     */
    Long submitEvaluation(EvaluationSubmitDTO dto, LoginUserDTO currentUser);

    /**
     * 三视角评价分页：按当前登录角色自动注入归属条件（居民看自己给出的、
     * 师傅看收到的、管理员看全量）。
     *
     * @param query       分页查询入参（keyword/star 由子类参数或扩展字段传入）
     * @param star        星级筛选 1~5（可空=不限）
     * @param serviceId   服务项目筛选（可空=不限）
     * @param currentUser 当前登录用户
     * @return 评价 VO 分页
     */
    PageVO<EvaluationVO> pageEvaluations(PageQueryDTO query, Integer star, Long serviceId,
                                          LoginUserDTO currentUser);

    /**
     * 按订单ID回显评价（该订单未评价时返回 null）。
     * <p>越权校验：管理员可看全部；居民仅可看自己评价过的订单；师傅仅可看
     * 自己收到的评价；其余按 403 拦截。</p>
     *
     * @param orderId     订单ID
     * @param currentUser 当前登录用户
     * @return 评价 VO；未评价时返回 null
     */
    EvaluationVO getEvaluationByOrderId(Long orderId, LoginUserDTO currentUser);
}
