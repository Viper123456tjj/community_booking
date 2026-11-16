package com.community.booking.mq.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 预约超时延迟消息体（appoint.delay.queue 的载荷）。
 * <p>下单成功后投递，队列本身<b>不声明消费者</b>，仅靠 {@code x-message-ttl}
 * 到期后由 Broker 经死信交换机转发到 appoint.cancel.queue——这是 RabbitMQ
 * 实现"延迟任务"的标准做法：不引入延迟插件，TTL + DLX 即可精确到毫秒级。</p>
 * <p>消息只带定位键与下单时间：消费者重新查库判定当前状态，绝不依据消息里的
 * 时间做"是否超时"的最终裁决（消息可能因重投而晚到，库内 create_time 才是权威口径）。</p>
 *
 * @author community-booking
 */
@Data
public class OrderTimeoutMessage implements Serializable {

    /** 序列化版本号（JSON 传输，仅作规范声明） */
    private static final long serialVersionUID = 1L;

    /** 订单ID（消费时回查状态） */
    private Long orderId;

    /** 订单号（写日志与取消备注用，避免消费者再查一次索引） */
    private String orderNo;

    /** 下单时间文本（yyyy-MM-dd HH:mm:ss，仅用于日志追踪，不参与超时裁决） */
    private String createTime;
}
