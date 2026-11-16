package com.community.booking.service;

import com.community.booking.mq.message.OrderNotifyMessage;

/**
 * 邮件通知业务接口（Batch 4）：订单事件邮件与次日上门提醒。
 * <p>调用方只有两类且都不带登录态：{@code AppointNotifyConsumer}（MQ 消费者）
 * 与 {@code AppointRemindTask}（每日 18:00 定时任务）——因此本接口的实现
 * 不做 IDOR 归属校验（没有"当前用户"概念），收件人一律由订单行派生。</p>
 * <p>环境降级：{@code community.mail.enabled=false}（dev 默认）时不真正投递，
 * 仅以 INFO 日志记录"本应发送给谁"，消费者照常 ACK，避免本机没有 SMTP 时
 * 消息反复重投进死信队列。</p>
 *
 * @author community-booking
 */
public interface MailService {

    /**
     * 按事件类型发送订单通知邮件（派单/完成/取消/次日提醒）。
     * <p>收件人派生规则：派单通知同时发给承接师傅与下单居民；完成通知只发居民
     * （邀请评价）；取消通知发居民，若取消前已派单则同时告知师傅工单已撤销；
     * 次日提醒同时发给居民与师傅，便于双方约定上门时间。</p>
     *
     * @param message 通知消息（订单ID + 事件类型 + 场景描述）
     * @return 实际发送（或日志模拟发送）的邮件封数
     */
    int sendOrderNotifyMail(OrderNotifyMessage message);

    /**
     * 发送次日上门提醒邮件（居民 + 承接师傅各一封）。
     *
     * @param orderId 次日已派单的订单ID
     * @return 实际发送（或日志模拟发送）的邮件封数
     */
    int sendAppointRemindMail(Long orderId);
}
