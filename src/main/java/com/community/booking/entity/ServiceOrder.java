package com.community.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 服务订单实体（对应表 service_order），系统主业务单据。
 * <p>订单状态机（order_status）：0待派单 -> 1已派单 -> 2服务中 -> 3已完成，
 * 0/1 态可取消 -> 4已取消（居民主动或 30 分钟超时死信自动取消）；全部状态
 * 迁移经 Redis 分布式锁 community:order:lock:{orderNo} 保证幂等。
 * dispatch_type 记录派单方式（AUTO智能/MANUAL手动/REASSIGN改派，未派单为 null）；
 * 下单时服务项目价格快照到 price，后续管理员改价不影响历史订单；
 * is_archived 由每日 03:00 定时任务对完成超 7 天订单置 1。</p>
 *
 * @author community-booking
 */
@Data
// 表名锁定为 service_order
@TableName("service_order")
public class ServiceOrder implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 订单主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 订单号（唯一约束 uk_order_no，格式 WO+yyyyMMdd+4位流水，分布式锁键成分） */
    @TableField("order_no")
    private String orderNo;

    /** 下单居民用户ID（FK -> sys_user.id，IDOR 归属校验锚点） */
    @TableField("resident_id")
    private Long residentId;

    /** 服务项目ID（FK -> service_item.id） */
    @TableField("service_id")
    private Long serviceId;

    /** 承接服务人员用户ID（FK -> sys_user.id；待派单为 NULL，派单/改派后写入） */
    @TableField("worker_id")
    private Long workerId;

    /** 预约上门时间（索引 idx_order_appoint，18:00 次日提醒邮件按此筛选） */
    @TableField("appoint_time")
    private LocalDateTime appointTime;

    /** 服务地址（下单时表单填写，默认带入居民档案地址） */
    @TableField("service_address")
    private String serviceAddress;

    /** 联系电话（下单时表单填写，默认带入居民手机号） */
    @TableField("contact_phone")
    private String contactPhone;

    /** 下单备注（取消单在此追记取消原因，如【超时未派单自动取消】） */
    @TableField("remark")
    private String remark;

    /** 下单时服务项目价格快照（元） */
    @TableField("price")
    private BigDecimal price;

    /** 订单状态：0待派单/1已派单/2服务中/3已完成/4已取消 */
    @TableField("order_status")
    private Integer orderStatus;

    /** 派单方式：AUTO智能/MANUAL手动/REASSIGN改派（未派单为 NULL） */
    @TableField("dispatch_type")
    private String dispatchType;

    /** 服务完成时间（师傅推进完成时写入，归档与 7 天可评价窗口按此计算） */
    @TableField("finish_time")
    private LocalDateTime finishTime;

    /** 归档标记：0正常/1已归档（完成超7天由每日03:00定时任务置1） */
    @TableField("is_archived")
    private Integer isArchived;

    /** 下单时间 */
    @TableField("create_time")
    private LocalDateTime createTime;

    /** 状态更新时间（ON UPDATE CURRENT_TIMESTAMP 由数据库自动维护） */
    @TableField("update_time")
    private LocalDateTime updateTime;
}
