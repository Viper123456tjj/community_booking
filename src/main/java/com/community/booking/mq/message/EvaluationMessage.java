package com.community.booking.mq.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 评价绩效重算消息体（appoint.eval.queue 的载荷）。
 * <p>居民提交评价后由 {@code ServiceEvaluationServiceImpl} 在事务提交后投递，
 * 消费者调用 {@code WorkerProfileService.recalcRatingScore(workerId)} 以库内
 * 全部评价的 AVG(star) 回写师傅平均分——重算语句本身幂等，消息重复投递
 * 只会得到同一个结果，因此无需在消费者侧再做去重表。</p>
 * <p>消息带 star 与 evaluationId 仅用于日志与审计（可追溯到哪条评价触发了重算），
 * 真正的计算口径不依赖消息内容，避免"消息里的星级"与库内被修改后的数据打架。</p>
 *
 * @author community-booking
 */
@Data
public class EvaluationMessage implements Serializable {

    /** 序列化版本号（JSON 传输，仅作规范声明） */
    private static final long serialVersionUID = 1L;

    /** 评价ID（审计与日志定位） */
    private Long evaluationId;

    /** 被评价订单ID（日志定位） */
    private Long orderId;

    /** 被评价师傅用户ID（重算目标，消费逻辑的唯一必填字段） */
    private Long workerId;

    /** 本次评价星级（仅日志展示，不参与计算） */
    private Integer star;
}
