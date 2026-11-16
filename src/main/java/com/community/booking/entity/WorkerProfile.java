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
 * 服务人员档案实体（对应表 worker_profile），1 对 1 关联 ROLE_WORKER 用户。
 * <p>skill_tags 使用 | 分隔技能标签，与 service_item.skill_required 同词表，
 * 是智能派单多维权重评分算法（技能匹配 + 空闲状态 + 时间窗口冲突检测 +
 * 评分与接单量负载均衡）的核心数据源；rating_score 与 total_orders
 * 分别由评价绩效重算（appoint.eval.queue）与完成动作维护，读取即派单因子。</p>
 *
 * @author community-booking
 */
@Data
// 表名锁定为 worker_profile
@TableName("worker_profile")
public class WorkerProfile implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 档案主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联用户ID（仅 ROLE_WORKER，唯一约束 uk_worker_user） */
    @TableField("user_id")
    private Long userId;

    /** 技能标签（| 分隔，如 水电维修|家电维修），派单技能覆盖匹配的基准 */
    @TableField("skill_tags")
    private String skillTags;

    /** 接单状态：1空闲/2忙碌/0休息（忙碌与休息均不作为派单候选） */
    @TableField("work_status")
    private Integer workStatus;

    /** 平均星级评分（1~5，评价完成经 appoint.eval.queue 异步重算，无评价默认 5.00） */
    @TableField("rating_score")
    private BigDecimal ratingScore;

    /** 累计完成订单数（智能派单负载均衡因子，完成动作 +1） */
    @TableField("total_orders")
    private Integer totalOrders;

    /** 创建时间，由 Service 层显式赋值 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
