package com.community.booking.mq.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 订单通知消息体（appoint.notify.queue 的载荷）。
 * <p>设计原则：消息只携带"定位键 + 事件类型"，不携带收件人、项目名称、金额等
 * 业务明细——消费时按 orderId 实时查库组装邮件正文。这样做的三点好处：</p>
 * <ol>
 *   <li>消息体小、序列化稳定，不会因业务字段增减而让在途消息反序列化失败；</li>
 *   <li>消费时刻的订单状态才是准的（例如派单后又被改派，邮件正文取的是最新数据）；</li>
 *   <li>避免"消息里存的手机号/邮箱"与实际账号不一致导致的投递错人。</li>
 * </ol>
 * <p>类型常量与 {@code MailService} 的分发分支一一对应；新增类型时必须同步
 * 在消费者中登记，否则消费者会按未知类型直接死信隔离（毒消息不重投）。</p>
 *
 * @author community-booking
 */
@Data
public class OrderNotifyMessage implements Serializable {

    /** 序列化版本号（JSON 传输，仅作规范声明） */
    private static final long serialVersionUID = 1L;

    /** 派单成功：通知承接师傅"有新工单"，同时通知居民"已安排师傅" */
    public static final String TYPE_DISPATCH = "DISPATCH";

    /** 服务完成：通知居民"服务已完成，请在 7 天内评价" */
    public static final String TYPE_COMPLETE = "COMPLETE";

    /** 订单取消：通知居民取消结果；已派单订单同时通知师傅"工单已撤销" */
    public static final String TYPE_CANCEL = "CANCEL";

    /** 次日上门提醒：由每日 18:00 定时任务扫描次日已派单订单后投递 */
    public static final String TYPE_REMIND = "REMIND";

    /** 订单ID（消费时据此回查订单、居民、师傅、服务项目） */
    private Long orderId;

    /** 事件类型：DISPATCH / COMPLETE / CANCEL / REMIND */
    private String type;

    /** 触发来源描述（如"居民自助取消"/"超时未派单自动取消"），仅用于日志与邮件措辞 */
    private String scene;
}
