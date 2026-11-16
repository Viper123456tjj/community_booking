package com.community.booking.mq;

import com.community.booking.mq.message.OrderNotifyMessage;
import com.community.booking.service.MailService;
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
 * 订单通知消费者（Batch 4，绑定 appoint.notify.queue）。
 * <p>职责边界：只做协议适配（解析消息 → 调用 {@link MailService} → ACK/NACK），
 * 不在此处写任何业务判断。邮件内容、收件人、降级逻辑全部在 MailServiceImpl 内。</p>
 * <p>ACK 策略：处理成功 basicAck；消息体缺定位键或事件类型未知时 basicNack
 * (requeue=false) 直接进 appoint.notify.dlq——重投也不会成功的"毒消息"必须
 * 快速隔离，不能堵住队列；业务异常（如全部投递失败）同样 nack 进 DLQ 人工排查。</p>
 * <p>dev 环境 {@code community.mail.enabled=false} 时 MailServiceImpl 仅日志模拟、
 * 正常返回，消费照常 ACK——本机没有 SMTP 不会让消息在死信队列里堆积。</p>
 *
 * @author community-booking
 */
@Slf4j
// MQ 消费者组件（位于计划书规定的 mq 包）
@Component
// 构造器注入邮件业务
@RequiredArgsConstructor
public class AppointNotifyConsumer {

    /** 邮件业务：按事件类型组装并投递通知邮件 */
    private final MailService mailService;

    /**
     * 消费一条订单通知消息。
     *
     * @param msg         通知消息体（订单ID + 事件类型）
     * @param message     原始消息（异常日志用）
     * @param channel     RabbitMQ 通道（手动 ACK/NACK）
     * @param deliveryTag 投递标签
     * @throws IOException 通道网络异常（由容器兜底重连）
     */
    @RabbitListener(queues = Constants.QUEUE_NOTIFY)
    public void onNotifyMessage(OrderNotifyMessage msg, Message message, Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            if (msg == null || msg.getOrderId() == null || msg.getType() == null) {
                log.warn("通知消息缺少订单ID或类型，直接死信隔离: {}", bodyOf(message));
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            int sent = mailService.sendOrderNotifyMail(msg);
            log.info("通知消息处理完成: orderId={} type={} 发送{}封", msg.getOrderId(), msg.getType(), sent);
            // 单条确认（prefetch=1，逐条 ACK 公平分发）
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("通知消息消费失败（进入死信队列人工排查）: {} | body={}",
                    e.getMessage(), bodyOf(message), e);
            // requeue=false：配合队列 DLX 参数进入 appoint.notify.dlq
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 提取原始消息体文本（仅用于异常日志，解码失败返回占位串）。
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
