package com.community.booking.mq;

import com.community.booking.mq.message.EvaluationMessage;
import com.community.booking.service.WorkerProfileService;
import com.community.booking.utils.Constants;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 评价绩效重算消费者（Batch 4，绑定 appoint.eval.queue）。
 * <p>为什么"重算平均星级"要异步：一次评价提交涉及写评价表 + 聚合 AVG(star) +
 * 回写师傅档案三张表，若同步执行会把居民的评价请求拖到几百毫秒；而评分只影响
 * 派单权重与展示，短暂延迟完全可接受。异步化后评价接口只做一次 INSERT + 一条
 * 消息投递，响应时间稳定在个位数毫秒。</p>
 * <p>幂等性：重算语句是 {@code UPDATE ... SET rating_score = (SELECT AVG(...))}，
 * 天然幂等，消息重复投递只会算出同一个结果，无需去重表。消费失败 nack 进
 * appoint.eval.dlq 人工排查（例如师傅档案被误删导致回写 0 行）。</p>
 *
 * @author community-booking
 */
@Slf4j
// MQ 消费者组件
@Component
// 构造器注入师傅档案业务
@RequiredArgsConstructor
public class AppointEvalConsumer {

    /** 师傅档案业务：平均星级重算入口（内部一条 SQL 完成读算写） */
    private final WorkerProfileService workerProfileService;

    /**
     * 消费一条评价消息并触发绩效重算。
     *
     * @param msg         评价消息（评价ID + 师傅ID）
     * @param message     原始消息（异常日志用）
     * @param channel     RabbitMQ 通道
     * @param deliveryTag 投递标签
     * @throws IOException 通道网络异常
     */
    @RabbitListener(queues = Constants.QUEUE_EVAL)
    public void onEvaluationMessage(EvaluationMessage msg, Message message, Channel channel,
                                    @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            if (msg == null || msg.getWorkerId() == null) {
                // 缺重算目标：无法处理，直接死信隔离
                log.warn("评价消息缺少师傅ID，直接死信隔离: {}", bodyOf(message));
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            workerProfileService.recalcRatingScore(msg.getWorkerId());
            log.info("绩效重算完成: evaluationId={} orderId={} workerId={} star={}",
                    msg.getEvaluationId(), msg.getOrderId(), msg.getWorkerId(), msg.getStar());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("绩效重算消费失败（进入死信队列人工排查）: {} | body={}",
                    e.getMessage(), bodyOf(message), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 提取原始消息体文本（仅用于异常日志）。
     *
     * @param message 原始消息
     * @return UTF-8 解码文本
     */
    private String bodyOf(Message message) {
        if (message == null || message.getBody() == null) {
            return "<empty>";
        }
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }
}
