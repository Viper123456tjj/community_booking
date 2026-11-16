package com.community.booking.task;

import com.community.booking.mapper.ServiceOrderMapper;
import com.community.booking.mq.AppointMessageSender;
import com.community.booking.mq.message.OrderNotifyMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 次日上门提醒任务（Batch 4，Spring Task）。
 * <p>每日 18:00 扫描"明天要上门"的已派单订单，逐单投递提醒消息进
 * appoint.notify.queue，由消费者异步发邮件给居民与师傅。选 18:00 的原因：
 * 居民下班到家能及时看到、师傅还来得及调整次日路线。</p>
 * <p>为什么任务只投消息、不直接发邮件：一次扫描可能命中几十单、上百封邮件，
 * 若在调度线程里同步 SMTP 投递，任务时长会被网络 IO 拖着走，且失败的邮件
 * 没有重投机制。投进 MQ 后由消费者逐条 ACK/重试，调度线程始终只做一次
 * 数据库查询 + N 次轻量投递。</p>
 * <p>幂等性：任务本身按"当天"重算——同一天重复触发会给同一批订单重复发提醒，
 * 这在实际运维中是"多发一封"而非"数据错误"，代价可接受；若需要严格去重，
 * 可在 Redis 用 {@code community:remind:{yyyyMMdd}} 做一次性标记，但本项目
 * 不引入该复杂度（运维不会手动重启两次 18:00 的任务）。</p>
 *
 * @author community-booking
 */
@Slf4j
// 定时任务组件
@Component
// 构造器注入订单 Mapper 与消息发送器
@RequiredArgsConstructor
public class AppointRemindTask {

    /** 订单 Mapper：查询次日已派单订单ID */
    private final ServiceOrderMapper orderMapper;
    /** MQ 消息发送器：把提醒投递到 appoint.notify.queue */
    private final AppointMessageSender messageSender;

    /**
     * 每日 18:00 执行：为次日上门订单投递提醒消息。
     */
    @Scheduled(cron = "0 0 18 * * ?")
    public void remindTomorrowAppointments() {
        long start = System.currentTimeMillis();
        try {
            List<Long> orderIds = orderMapper.selectTomorrowDispatchedOrderIds();
            if (orderIds == null || orderIds.isEmpty()) {
                log.info("[定时任务-次日提醒] 明日无已派单的上门工单，跳过");
                return;
            }
            int sent = 0;
            for (Long orderId : orderIds) {
                OrderNotifyMessage message = new OrderNotifyMessage();
                message.setOrderId(orderId);
                message.setType(OrderNotifyMessage.TYPE_REMIND);
                message.setScene("次日上门提醒");
                // 无事务上下文：发送器立即投递（不回滚、不需要 afterCommit）
                messageSender.sendOrderNotify(message);
                sent++;
            }
            log.info("[定时任务-次日提醒] 完成：投递{}条提醒消息，耗时{}ms",
                    sent, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[定时任务-次日提醒] 执行失败：{}，耗时{}ms",
                    e.getMessage(), System.currentTimeMillis() - start, e);
        }
    }
}
