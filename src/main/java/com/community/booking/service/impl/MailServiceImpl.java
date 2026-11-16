package com.community.booking.service.impl;

import com.community.booking.entity.ServiceItem;
import com.community.booking.entity.ServiceOrder;
import com.community.booking.entity.SysUser;
import com.community.booking.exception.BusinessException;
import com.community.booking.mapper.ServiceItemMapper;
import com.community.booking.mapper.ServiceOrderMapper;
import com.community.booking.mapper.SysUserMapper;
import com.community.booking.mq.message.OrderNotifyMessage;
import com.community.booking.service.MailService;
import com.community.booking.utils.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 邮件通知业务实现（Batch 4）：订单事件邮件 + 次日上门提醒。
 * <p>三类职责边界清晰：消费者只负责 ACK/NACK 协议适配，本类负责"查库组装正文 +
 * 投递"，JavaMailSender 只负责最后一公里 SMTP。订单/项目/用户数据在发送时
 * 实时查库——邮件正文里的师傅姓名、预约时间永远是最新值，不会因为"消息投递后
 * 又被改派"而写给错的人。</p>
 * <p>投递策略：逐封发送，单封失败仅 WARN 跳过（某位居民邮箱异常不该拖累其他
 * 收件人）；只有"全部失败"才抛 BusinessException，让消费者 nack 进死信队列
 * 人工排查——部分失败若整条消息重投，会造成已成功者重复收信。</p>
 *
 * @author community-booking
 */
@Slf4j
// 邮件业务实现（业务逻辑集中在此类）
@Service
// 构造器注入 Mapper 与邮件发送器
@RequiredArgsConstructor
public class MailServiceImpl implements MailService {

    /** 邮件正文中的时间展示格式（与前端列表口径一致，精确到分钟） */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 订单 Mapper：定位订单与派生收件人、预约时间、服务地址 */
    private final ServiceOrderMapper orderMapper;
    /** 服务项目 Mapper：正文中的项目名称、价格与计价单位 */
    private final ServiceItemMapper itemMapper;
    /** 用户 Mapper：居民/师傅的姓名与邮箱 */
    private final SysUserMapper userMapper;
    /** Spring Boot 自动装配的邮件发送器（spring.mail.* 配置存在时可用） */
    private final JavaMailSender mailSender;

    /** 真实发送开关：false（dev 默认）时仅日志模拟，不投递 */
    @Value("${community.mail.enabled:false}")
    private boolean mailEnabled;

    /** 发件人地址：复用 spring.mail.username（与 SMTP 认证账号一致） */
    @Value("${spring.mail.username:}")
    private String senderAddress;

    /** 邮件正文署名：来自业务配置 community.mail.from-name */
    @Value("${community.mail.from-name:社区便民服务预约系统}")
    private String fromName;

    /**
     * 按事件类型发送订单通知邮件（派单/完成/取消/次日提醒）。
     *
     * @param message 通知消息
     * @return 实际发送（或日志模拟发送）的邮件封数
     */
    @Override
    public int sendOrderNotifyMail(OrderNotifyMessage message) {
        if (message == null || message.getOrderId() == null || message.getType() == null) {
            log.warn("通知消息缺少订单ID或事件类型，跳过发送: {}", message);
            return 0;
        }
        MailContext ctx = loadContext(message.getOrderId());
        if (ctx == null) {
            // 订单已被清理（归档后物理删除等极端场景）：消息无处理价值，返回 0 让消费者 ACK
            log.warn("通知消息关联的订单不存在，跳过发送: orderId={}", message.getOrderId());
            return 0;
        }
        String scene = message.getScene() == null ? "" : message.getScene();
        return switch (message.getType()) {
            case OrderNotifyMessage.TYPE_DISPATCH -> sendDispatch(ctx);
            case OrderNotifyMessage.TYPE_COMPLETE -> sendComplete(ctx);
            case OrderNotifyMessage.TYPE_CANCEL -> sendCancel(ctx, scene);
            case OrderNotifyMessage.TYPE_REMIND -> sendRemind(ctx);
            default -> {
                // 未知类型：返回 0，由消费者按"未知类型"直接死信隔离
                log.warn("未知的通知类型: type={} orderId={}", message.getType(), message.getOrderId());
                yield 0;
            }
        };
    }

    /**
     * 次日上门提醒：居民与师傅各一封，便于双方核对上门时间。
     *
     * @param orderId 次日已派单的订单ID
     * @return 实际发送（或日志模拟发送）的邮件封数
     */
    @Override
    public int sendAppointRemindMail(Long orderId) {
        MailContext ctx = loadContext(orderId);
        if (ctx == null) {
            log.warn("次日提醒关联的订单不存在，跳过发送: orderId={}", orderId);
            return 0;
        }
        return sendRemind(ctx);
    }

    /**
     * 派单通知：一封给承接师傅（含服务地址与居民联系方式），一封给居民。
     *
     * @param ctx 邮件上下文
     * @return 发送封数
     */
    private int sendDispatch(MailContext ctx) {
        ServiceOrder order = ctx.order();
        ServiceItem item = ctx.item();
        int sent = 0;
        // 师傅侧：上门前需要地址与居民电话，正文必须带全
        if (ctx.worker() != null) {
            String workerBody = "您好，" + ctx.worker().getRealName() + "：\n\n"
                    + "系统为您指派了新的服务工单，请按预约时间上门。\n\n"
                    + "工单号：" + order.getOrderNo() + "\n"
                    + "服务项目：" + itemName(item) + "\n"
                    + "预约时间：" + formatTime(order) + "\n"
                    + "服务地址：" + nullSafe(order.getServiceAddress()) + "\n"
                    + "居民联系：" + ctx.resident().getRealName() + " " + nullSafe(order.getContactPhone()) + "\n"
                    + "订单金额：" + priceText(item, order) + "\n\n"
                    + "请登录系统在“我的工单”中点击“开始服务”，服务完成后及时推进工单。\n\n"
                    + fromName;
            sent += dispatch(List.of(ctx.worker()), "【新工单】" + order.getOrderNo() + " " + itemName(item),
                    workerBody, "派单通知-师傅");
        }
        // 居民侧：告知已安排师傅与联系方式，减少催单电话
        String residentBody = "您好，" + ctx.resident().getRealName() + "：\n\n"
                + "您的预约已安排服务人员，请保持电话畅通。\n\n"
                + "工单号：" + order.getOrderNo() + "\n"
                + "服务项目：" + itemName(item) + "\n"
                + "上门师傅：" + (ctx.worker() == null ? "待确认" : ctx.worker().getRealName()
                + " " + nullSafe(ctx.worker().getPhone())) + "\n"
                + "预约时间：" + formatTime(order) + "\n"
                + "服务地址：" + nullSafe(order.getServiceAddress()) + "\n\n"
                + "如需变更时间，请尽快联系物业服务中心协调。\n\n"
                + fromName;
        sent += dispatch(List.of(ctx.resident()), "【预约成功】" + order.getOrderNo() + " 已安排上门师傅",
                residentBody, "派单通知-居民");
        return sent;
    }

    /**
     * 服务完成通知：邀请居民在 7 天窗口内评价。
     *
     * @param ctx 邮件上下文
     * @return 发送封数
     */
    private int sendComplete(MailContext ctx) {
        ServiceOrder order = ctx.order();
        String body = "您好，" + ctx.resident().getRealName() + "：\n\n"
                + "您的服务订单已完成，感谢使用社区便民服务。\n\n"
                + "工单号：" + order.getOrderNo() + "\n"
                + "服务项目：" + itemName(ctx.item()) + "\n"
                + "上门师傅：" + (ctx.worker() == null ? "-" : ctx.worker().getRealName()) + "\n"
                + "完成时间：" + (order.getFinishTime() == null ? "-" : order.getFinishTime().format(TIME_FMT)) + "\n"
                + "订单金额：" + priceText(ctx.item(), order) + "\n\n"
                + "请在 7 天内登录系统对该次服务做出评价（1~5 星），您的评价将用于师傅绩效核算。\n\n"
                + fromName;
        return dispatch(List.of(ctx.resident()), "【服务完成】" + order.getOrderNo() + " 邀请您评价",
                body, "服务完成通知");
    }

    /**
     * 取消通知：居民必收；取消前已派单的，师傅同时收到"工单已撤销"。
     *
     * @param ctx   邮件上下文
     * @param scene 取消场景（居民自助/物业运营/超时未派单）
     * @return 发送封数
     */
    private int sendCancel(MailContext ctx, String scene) {
        ServiceOrder order = ctx.order();
        String sceneText = scene.isEmpty() ? "订单已取消" : scene;
        int sent = 0;
        String residentBody = "您好，" + ctx.resident().getRealName() + "：\n\n"
                + "您的预约订单已取消。\n\n"
                + "工单号：" + order.getOrderNo() + "\n"
                + "服务项目：" + itemName(ctx.item()) + "\n"
                + "取消场景：" + sceneText + "\n"
                + "预约时间：" + formatTime(order) + "\n\n"
                + (order.getRemark() == null ? "" : "备注：" + order.getRemark() + "\n\n")
                + "如仍需服务，欢迎重新提交预约。\n\n"
                + fromName;
        sent += dispatch(List.of(ctx.resident()), "【订单取消】" + order.getOrderNo() + " " + itemName(ctx.item()),
                residentBody, "取消通知-居民");
        if (ctx.worker() != null) {
            String workerBody = "您好，" + ctx.worker().getRealName() + "：\n\n"
                    + "以下工单已被撤销，请勿再上门。\n\n"
                    + "工单号：" + order.getOrderNo() + "\n"
                    + "服务项目：" + itemName(ctx.item()) + "\n"
                    + "预约时间：" + formatTime(order) + "\n"
                    + "撤销场景：" + sceneText + "\n\n"
                    + fromName;
            sent += dispatch(List.of(ctx.worker()), "【工单撤销】" + order.getOrderNo() + " 请勿上门",
                    workerBody, "取消通知-师傅");
        }
        return sent;
    }

    /**
     * 次日上门提醒：居民与师傅各一封。
     *
     * @param ctx 邮件上下文
     * @return 发送封数
     */
    private int sendRemind(MailContext ctx) {
        ServiceOrder order = ctx.order();
        int sent = 0;
        String residentBody = "您好，" + ctx.resident().getRealName() + "：\n\n"
                + "温馨提醒：您预约的服务将于明日上门。\n\n"
                + "工单号：" + order.getOrderNo() + "\n"
                + "服务项目：" + itemName(ctx.item()) + "\n"
                + "预约时间：" + formatTime(order) + "\n"
                + "上门师傅：" + (ctx.worker() == null ? "待确认" : ctx.worker().getRealName()
                + " " + nullSafe(ctx.worker().getPhone())) + "\n"
                + "服务地址：" + nullSafe(order.getServiceAddress()) + "\n\n"
                + "请提前整理服务区域并保持电话畅通。\n\n"
                + fromName;
        sent += dispatch(List.of(ctx.resident()), "【上门提醒】明日 " + formatTime(order) + " " + itemName(ctx.item()),
                residentBody, "次日提醒-居民");
        if (ctx.worker() != null) {
            String workerBody = "您好，" + ctx.worker().getRealName() + "：\n\n"
                    + "温馨提醒：您明日有预约工单，请合理安排路线按时上门。\n\n"
                    + "工单号：" + order.getOrderNo() + "\n"
                    + "服务项目：" + itemName(ctx.item()) + "\n"
                    + "预约时间：" + formatTime(order) + "\n"
                    + "服务地址：" + nullSafe(order.getServiceAddress()) + "\n"
                    + "居民联系：" + ctx.resident().getRealName() + " " + nullSafe(order.getContactPhone()) + "\n\n"
                    + fromName;
            sent += dispatch(List.of(ctx.worker()), "【上门提醒】明日 " + formatTime(order) + " " + itemName(ctx.item()) + " 工单",
                    workerBody, "次日提醒-师傅");
        }
        return sent;
    }

    /**
     * 统一投递：按开关决定真实发送或日志模拟，逐封发送、单封失败跳过、
     * 全部失败抛异常触发死信人工排查。
     *
     * @param recipients 收件人列表
     * @param subject    邮件主题
     * @param body       邮件正文
     * @param scene      场景名（日志区分）
     * @return 实际发送（或日志模拟发送）的封数
     * @throws BusinessException 开启真实发送且全部投递失败
     */
    private int dispatch(List<SysUser> recipients, String subject, String body, String scene) {
        int total = 0;
        int sent = 0;
        int failed = 0;
        for (SysUser user : recipients) {
            if (user == null) {
                continue;
            }
            String email = user.getEmail();
            if (email == null || email.trim().isEmpty()) {
                // 邮箱为空的历史账号直接跳过：没有收件地址不是投递失败
                log.debug("[邮件-{}] 收件人[{}]无邮箱，跳过", scene, user.getUsername());
                continue;
            }
            total++;
            if (!mailEnabled) {
                // dev 降级：不真正投递，仅记录"本应发送"，消费者据此正常 ACK
                log.info("[邮件模拟-{}] 收件人={}({}) 主题={}", scene, user.getRealName(), email, subject);
                sent++;
                continue;
            }
            try {
                SimpleMailMessage mail = new SimpleMailMessage();
                mail.setFrom(senderAddress);
                mail.setTo(email);
                mail.setSubject(subject);
                mail.setText(body);
                mailSender.send(mail);
                sent++;
            } catch (Exception e) {
                // 单封失败仅 WARN 跳过：部分失败不值得整条消息重放
                failed++;
                log.warn("[邮件-{}] 投递失败 收件人={}({}): {}", scene, user.getRealName(), email, e.getMessage());
            }
        }
        if (total == 0) {
            log.info("[邮件-{}] 无有效收件人，跳过发送", scene);
            return 0;
        }
        if (mailEnabled && failed == total) {
            throw new BusinessException(500, "邮件全部投递失败（" + failed + "/" + total + "），转死信人工排查");
        }
        log.info("[邮件-{}] 发送完成：成功 {} 封 / 共 {} 封（失败 {}）", scene, sent, total, failed);
        return sent;
    }

    /**
     * 组装邮件上下文：订单 + 服务项目 + 居民 + 承接师傅（可为空）。
     *
     * @param orderId 订单ID
     * @return 上下文；订单不存在返回 null
     */
    private MailContext loadContext(Long orderId) {
        if (orderId == null) {
            return null;
        }
        ServiceOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            return null;
        }
        ServiceItem item = itemMapper.selectById(order.getServiceId());
        SysUser resident = userMapper.selectById(order.getResidentId());
        if (resident == null) {
            // 居民账号被物理删除：无收件人，整条通知无意义
            return null;
        }
        SysUser worker = order.getWorkerId() == null ? null : userMapper.selectById(order.getWorkerId());
        return new MailContext(order, item, resident, worker);
    }

    /**
     * 服务项目名称（项目被删除时降级为占位文案，避免 NPE）。
     *
     * @param item 服务项目
     * @return 项目名称
     */
    private String itemName(ServiceItem item) {
        return item == null ? "（服务项目已下架）" : item.getItemName();
    }

    /**
     * 预约时间文本（订单时间必填，仍做空值兜底）。
     *
     * @param order 订单
     * @return 格式化时间
     */
    private String formatTime(ServiceOrder order) {
        return order.getAppointTime() == null ? "-" : order.getAppointTime().format(TIME_FMT);
    }

    /**
     * 金额文本：优先用订单价格快照（下单时的成交价），项目缺失时仅显示金额。
     *
     * @param item  服务项目（可空）
     * @param order 订单
     * @return 如 "68.00 元/次"
     */
    private String priceText(ServiceItem item, ServiceOrder order) {
        BigDecimal price = order.getPrice() == null
                ? (item == null ? BigDecimal.ZERO : item.getPrice()) : order.getPrice();
        String unit = item == null ? "" : "/" + item.getUnit();
        return price.stripTrailingZeros().toPlainString() + " 元" + unit;
    }

    /**
     * 空串归一为占位符（邮件正文不出现 null 字样）。
     *
     * @param value 原始值
     * @return 非空文本
     */
    private String nullSafe(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }

    /**
     * 邮件上下文（一次查库得到的四张表快照，供各事件正文复用）。
     *
     * @param order    订单
     * @param item     服务项目（可空）
     * @param resident 居民
     * @param worker   承接师傅（待派单时为 null）
     */
    private record MailContext(ServiceOrder order, ServiceItem item, SysUser resident, SysUser worker) {
    }
}
