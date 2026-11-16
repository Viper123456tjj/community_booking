package com.community.booking.mq;

import com.community.booking.mq.message.OrderTimeoutMessage;
import com.community.booking.service.ServiceOrderService;
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
 * 预约超时自动取消消费者（Batch 4，绑定 appoint.cancel.queue）。
 * <p>消息来源不是生产者直投，而是 appoint.delay.queue 的 30 分钟 TTL 到期后
 * 由 Broker 经延迟 DLX 转发过来——这是"延迟任务"的关键一环，消费者本身感知
 * 不到延迟，只负责处理"该取消的订单"。</p>
 * <p>四类结果都算正常消费（均 ACK，不重投）：</p>
 * <ul>
 *   <li>订单不存在：已被清理，无处理价值；</li>
 *   <li>订单已派单/已完成/已取消：说明 30 分钟内管理员已派单或居民已取消，
 *       条件 UPDATE 影响 0 行，属于<b>设计内的幂等命中</b>，不能当成错误重投；</li>
 *   <li>取消成功：条件 UPDATE 命中 1 行，订单置为已取消并追记备注；</li>
 *   <li>数据库异常：nack 进 appoint.cancel.dlq 人工排查（重投大概率同样失败）。</li>
 * </ul>
 * <p>与居民自助取消共用 Service 层的同一原子口径（WHERE order_status=0），
 * 因此"已派单不得被取消"这一不变量在任何路径下都不会被绕过。</p>
 *
 * @author community-booking
 */
@Slf4j
// MQ 消费者组件
@Component
// 构造器注入订单业务
@RequiredArgsConstructor
public class AppointTimeoutCancelConsumer {

    /** 订单业务：超时取消的状态机入口（条件 UPDATE + 分布式锁） */
    private final ServiceOrderService orderService;

    /**
     * 消费一条超时消息并按状态机幂等取消。
     *
     * @param msg         超时消息（订单ID + 订单号 + 下单时间文本）
     * @param message     原始消息（异常日志用）
     * @param channel     RabbitMQ 通道
     * @param deliveryTag 投递标签
     * @throws IOException 通道网络异常
     */
    @RabbitListener(queues = Constants.QUEUE_CANCEL)
    public void onTimeoutMessage(OrderTimeoutMessage msg, Message message, Channel channel,
                                 @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            if (msg == null || msg.getOrderId() == null) {
                log.warn("超时消息缺少订单ID，直接死信隔离: {}", bodyOf(message));
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            boolean cancelled = orderService.cancelTimeoutOrder(msg.getOrderId());
            if (cancelled) {
                log.info("超时未派单自动取消成功: orderId={} orderNo={} createTime={}",
                        msg.getOrderId(), msg.getOrderNo(), msg.getCreateTime());
            } else {
                // 已派单/已取消/订单不存在：幂等命中，记录后正常确认
                log.info("超时取消消息幂等跳过（订单已流转或不存在）: orderId={} orderNo={}",
                        msg.getOrderId(), msg.getOrderNo());
            }
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("超时取消消费失败（进入死信队列人工排查）: {} | body={}",
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
