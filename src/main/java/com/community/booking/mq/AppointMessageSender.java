package com.community.booking.mq;

import com.community.booking.mq.message.EvaluationMessage;
import com.community.booking.mq.message.OrderNotifyMessage;
import com.community.booking.mq.message.OrderTimeoutMessage;
import com.community.booking.utils.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 预约业务消息发送器（Batch 4）：把"发什么、发到哪"封装成三个语义化方法，
 * 业务决策（何时发、发给谁）仍留在各 ServiceImpl 中——严格遵循
 * "MQ 发送在 ServiceImpl 完成、消费者只做协议适配"的分层约定。
 * <p>三条可靠性设计：</p>
 * <ol>
 *   <li><b>事务提交后投递</b>：所有发送都注册到 {@code afterCommit}。若在事务内
 *       直接投递，一旦事务回滚，消费者会处理一条"库内并不存在"的订单消息，
 *       造成邮件误发与日志噪音；无事务上下文时立即投递（定时任务场景）。</li>
 *   <li><b>投递失败不阻断业务</b>：MQ 不可用时仅 WARN 并记下场景与路由键，
 *       下单/派单/评价等主流程照常成功——异步通知是旁路增强，不是业务前置条件。</li>
 *   <li><b>消息体只带定位键</b>：见各 Message 类的注释，消费时实时查库组装内容。</li>
 * </ol>
 *
 * @author community-booking
 */
@Slf4j
// MQ 发送组件（位于计划书规定的 mq 包，仅被 ServiceImpl 调用）
@Component
// 构造器注入 RabbitTemplate（已在 RabbitMQConfig 中挂载 JSON 转换器）
@RequiredArgsConstructor
public class AppointMessageSender {

    /** Spring AMQP 发送模板（JSON 序列化，队列持久化由拓扑声明保证） */
    private final RabbitTemplate rabbitTemplate;

    /** 投递总开关：dev 未部署 RabbitMQ 时置 false，避免每次写操作等连接超时 */
    @Value("${community.mq.publish-enabled:true}")
    private boolean publishEnabled;

    /**
     * 投递预约超时延迟消息：进入 appoint.delay.queue 后 30 分钟 TTL 到期，
     * 由 Broker 经延迟 DLX 转发到 appoint.cancel.queue 触发超时取消。
     *
     * @param message 延迟消息（订单ID + 订单号）
     */
    public void sendTimeoutDelay(OrderTimeoutMessage message) {
        publishAfterCommit(Constants.EXCHANGE_APPOINT, Constants.ROUTING_DELAY, message, "预约延迟");
    }

    /**
     * 投递订单通知消息：派单/完成/取消/次日提醒四类邮件通知统一入口。
     *
     * @param message 通知消息（订单ID + 事件类型）
     */
    public void sendOrderNotify(OrderNotifyMessage message) {
        publishAfterCommit(Constants.EXCHANGE_APPOINT, Constants.ROUTING_NOTIFY, message, "订单通知");
    }

    /**
     * 投递评价绩效重算消息：消费者据此重算师傅平均星级。
     *
     * @param message 评价消息（评价ID + 师傅ID）
     */
    public void sendEvaluation(EvaluationMessage message) {
        publishAfterCommit(Constants.EXCHANGE_APPOINT, Constants.ROUTING_EVAL, message, "绩效重算");
    }

    /**
     * 事务感知投递：有事务则挂到 afterCommit，无事务则立即执行。
     * <p>投递异常在此处吞掉并 WARN：MQ 故障不能把已经成功的下单/派单事务带崩。</p>
     *
     * @param exchange   目标交换机
     * @param routingKey 路由键
     * @param payload    消息体（JSON 序列化）
     * @param scene      场景名（日志区分，如"订单通知"）
     */
    private void publishAfterCommit(String exchange, String routingKey, Object payload, String scene) {
        if (!publishEnabled) {
            // 开发环境未部署 RabbitMQ：只记 DEBUG，避免每次下单/派单都等一次连接超时
            log.debug("[MQ-{}] 投递开关关闭（community.mq.publish-enabled=false），本次不投递", scene);
            return;
        }
        // 投递动作本身：异常仅告警，不上抛
        Runnable task = () -> {
            try {
                rabbitTemplate.convertAndSend(exchange, routingKey, payload);
                log.info("[MQ-{}] 投递成功 routingKey={} payload={}", scene, routingKey, payload);
            } catch (Exception e) {
                log.warn("[MQ-{}] 投递失败（业务主流程不受影响）: routingKey={} cause={}",
                        scene, routingKey, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务内：等数据真正落库后再投递，回滚则消息不会发出
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            // 无事务上下文（如定时任务直接调用）：立即投递
            task.run();
        }
    }
}
