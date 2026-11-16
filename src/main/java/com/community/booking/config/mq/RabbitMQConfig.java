package com.community.booking.config.mq;

import com.community.booking.utils.Constants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑声明（Batch 4，全部名称取自 Constants 的 MQ 契约常量）。
 * <p>拓扑结构（三交换机 + 六队列 + 七绑定）：</p>
 * <pre>
 * appoint.direct.exchange ─┬─ appoint.notify.queue   ──(消费失败)──> appoint.dlx.exchange ─> appoint.notify.dlq
 *                          ├─ appoint.eval.queue     ──(消费失败)──> appoint.dlx.exchange ─> appoint.eval.dlq
 *                          └─ appoint.delay.queue    [无消费者，TTL 30 分钟]
 *                                     │ TTL 到期经 x-dead-letter-exchange 转发
 *                                     ▼
 *                          appoint.delay.dlx.exchange ──> appoint.cancel.queue ──(消费失败)──> appoint.cancel.dlq
 * </pre>
 * <p>三个业务队列与取消队列都挂 x-dead-letter-exchange：消费端重试耗尽后消息
 * 进入死信队列而不是被丢弃，保证超时取消、通知、绩效三类异步任务可追溯、可人工重投。</p>
 * <p>延迟队列是"TTL + DLX"实现延迟任务的经典组合：生产者只往 appoint.delay.queue
 * 投递，队列不给任何消费者，消息到期后由 Broker 自动死信到取消队列，从而在
 * 不安装延迟插件的前提下实现"下单 30 分钟未派单自动取消"。</p>
 * <p>声明出来的 Queue/Exchange/Binding 由 Boot 的 RabbitAdmin 在连接建立后幂等
 * 声明（已存在则校验参数一致），重复启动不会报错；参数不一致时会抛出
 * PRECONDITION_FAILED——改了 TTL 之类的参数需要先删除旧队列，部署文档中已注明。</p>
 *
 * @author community-booking
 */
@Configuration
public class RabbitMQConfig {

    /**
     * 业务直连交换机：承载通知、绩效重算、延迟投递三类路由键。
     *
     * @return 持久化直连交换机
     */
    @Bean
    public DirectExchange appointExchange() {
        // durable=true：Broker 重启后交换机仍在，避免消息路由失败
        return new DirectExchange(Constants.EXCHANGE_APPOINT, true, false);
    }

    /**
     * 业务死信交换机：业务队列消费重试耗尽的消息经此进入对应 DLQ。
     *
     * @return 持久化死信交换机
     */
    @Bean
    public DirectExchange appointDlxExchange() {
        return new DirectExchange(Constants.EXCHANGE_APPOINT_DLX, true, false);
    }

    /**
     * 延迟死信交换机：延迟队列 TTL 到期的唯一出口，只绑定超时取消队列。
     * <p>与业务 DLX 分开声明的原因：延迟队列的"死信"是<b>正常业务流转</b>
     * （时间到了该取消），而业务 DLX 的"死信"是<b>异常兜底</b>（消费失败）。
     * 两者语义混在同一个交换机上会让运维分不清"消息是超时了还是出错了"。</p>
     *
     * @return 持久化延迟死信交换机
     */
    @Bean
    public DirectExchange delayDlxExchange() {
        return new DirectExchange(Constants.EXCHANGE_DELAY_DLX, true, false);
    }

    /**
     * 异步通知队列：派单成功、服务完成、订单取消、次日提醒四类邮件通知。
     *
     * @return 队列声明（死信指向业务 DLX 的通知死信路由键）
     */
    @Bean
    public Queue notifyQueue() {
        return QueueBuilder.durable(Constants.QUEUE_NOTIFY)
                .deadLetterExchange(Constants.EXCHANGE_APPOINT_DLX)
                .deadLetterRoutingKey(Constants.ROUTING_NOTIFY_DEAD)
                .build();
    }

    /**
     * 绩效重算队列：评价提交后异步重算师傅平均星级。
     *
     * @return 队列声明（死信指向业务 DLX 的绩效死信路由键）
     */
    @Bean
    public Queue evalQueue() {
        return QueueBuilder.durable(Constants.QUEUE_EVAL)
                .deadLetterExchange(Constants.EXCHANGE_APPOINT_DLX)
                .deadLetterRoutingKey(Constants.ROUTING_EVAL_DEAD)
                .build();
    }

    /**
     * 预约延迟队列：无消费者，仅承载 TTL。
     * <p>TTL 取 {@code community.mq.appoint-delay-ttl-ms}（默认 1800000 = 30 分钟）。
     * 注意 RabbitMQ 的队列级 TTL 只在"消息到达队首"时判定，因此本队列必须
     * 保持单一用途（只放同一种延迟时长的消息），否则短 TTL 消息会被长 TTL
     * 消息堵住——本系统只有"待派单超时"一种延迟场景，天然满足该前提。</p>
     *
     * @param delayTtlMs 延迟时长（毫秒），由配置注入
     * @return 延迟队列声明（死信指向延迟 DLX 的超时取消路由键）
     */
    @Bean
    public Queue delayQueue(@Value("${community.mq.appoint-delay-ttl-ms:1800000}") long delayTtlMs) {
        return QueueBuilder.durable(Constants.QUEUE_DELAY)
                // 队列级 TTL：所有进入本队列的消息在 30 分钟后过期
                .ttl((int) Math.min(delayTtlMs, Integer.MAX_VALUE))
                .deadLetterExchange(Constants.EXCHANGE_DELAY_DLX)
                .deadLetterRoutingKey(Constants.ROUTING_CANCEL)
                .build();
    }

    /**
     * 超时自动取消队列：消费延迟到期的订单并幂等置为已取消。
     *
     * @return 队列声明（死信指向业务 DLX 的取消死信路由键）
     */
    @Bean
    public Queue cancelQueue() {
        return QueueBuilder.durable(Constants.QUEUE_CANCEL)
                .deadLetterExchange(Constants.EXCHANGE_APPOINT_DLX)
                .deadLetterRoutingKey(Constants.ROUTING_CANCEL_DEAD)
                .build();
    }

    /**
     * 通知死信队列：消费失败的通知消息落此，供人工排查后重投。
     *
     * @return 纯死信队列（不再挂死信参数，避免死信循环）
     */
    @Bean
    public Queue notifyDlq() {
        return QueueBuilder.durable(Constants.QUEUE_NOTIFY_DLQ).build();
    }

    /**
     * 绩效重算死信队列。
     *
     * @return 纯死信队列
     */
    @Bean
    public Queue evalDlq() {
        return QueueBuilder.durable(Constants.QUEUE_EVAL_DLQ).build();
    }

    /**
     * 超时取消死信队列。
     *
     * @return 纯死信队列
     */
    @Bean
    public Queue cancelDlq() {
        return QueueBuilder.durable(Constants.QUEUE_CANCEL_DLQ).build();
    }

    /**
     * 绑定通知路由键到通知队列。
     *
     * @return 绑定声明
     */
    @Bean
    public Binding notifyBinding() {
        return BindingBuilder.bind(notifyQueue()).to(appointExchange()).with(Constants.ROUTING_NOTIFY);
    }

    /**
     * 绑定绩效重算路由键到绩效队列。
     *
     * @return 绑定声明
     */
    @Bean
    public Binding evalBinding() {
        return BindingBuilder.bind(evalQueue()).to(appointExchange()).with(Constants.ROUTING_EVAL);
    }

    /**
     * 绑定延迟路由键到延迟队列（生产者投递入口）。
     * <p>延迟队列的 TTL 由配置驱动，其 Bean 方法带参数，故这里用
     * {@code @Qualifier("delayQueue")} 显式注入已声明的队列实例，
     * 避免容器中存在多个 Queue Bean 时按类型注入产生歧义。</p>
     *
     * @param delayQueue 延迟队列实例（Spring 已按配置 TTL 声明）
     * @return 绑定声明
     */
    @Bean
    public Binding delayBinding(@Qualifier("delayQueue") Queue delayQueue) {
        return BindingBuilder.bind(delayQueue).to(appointExchange()).with(Constants.ROUTING_DELAY);
    }

    /**
     * 绑定超时取消路由键到取消队列（延迟 DLX 的唯一出口）。
     *
     * @return 绑定声明
     */
    @Bean
    public Binding cancelBinding() {
        return BindingBuilder.bind(cancelQueue()).to(delayDlxExchange()).with(Constants.ROUTING_CANCEL);
    }

    /**
     * 绑定通知死信路由键到通知 DLQ。
     *
     * @return 绑定声明
     */
    @Bean
    public Binding notifyDlqBinding() {
        return BindingBuilder.bind(notifyDlq()).to(appointDlxExchange()).with(Constants.ROUTING_NOTIFY_DEAD);
    }

    /**
     * 绑定绩效死信路由键到绩效 DLQ。
     *
     * @return 绑定声明
     */
    @Bean
    public Binding evalDlqBinding() {
        return BindingBuilder.bind(evalDlq()).to(appointDlxExchange()).with(Constants.ROUTING_EVAL_DEAD);
    }

    /**
     * 绑定取消死信路由键到取消 DLQ。
     *
     * @return 绑定声明
     */
    @Bean
    public Binding cancelDlqBinding() {
        return BindingBuilder.bind(cancelDlq()).to(appointDlxExchange()).with(Constants.ROUTING_CANCEL_DEAD);
    }

    /**
     * JSON 消息转换器：消息体以 Jackson JSON 收发，类型信息随 __TypeId__ 头传递，
     * 消费端方法签名直接声明强类型消息体即可自动还原。
     *
     * @return JSON 消息转换器
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 定制 RabbitTemplate：挂载 JSON 转换器，生产端统一以 JSON 投递。
     * <p>手动 ACK 模式下由消费端确认，生产端不开启 confirm/returns 回调
     * （本系统的可靠性由"队列持久化 + 事务提交后投递 + 死信兜底"三层保证）。</p>
     *
     * @param connectionFactory Boot 自动装配的连接工厂
     * @return 完成转换器装配的 RabbitTemplate
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
