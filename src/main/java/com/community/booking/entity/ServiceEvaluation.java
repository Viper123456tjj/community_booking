package com.community.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 服务评价实体（对应表 service_evaluation），一单一评。
 * <p>order_id 唯一约束（uk_eval_order）保证同一订单只能评价一次，评价提交
 * 天然幂等；resident_id/worker_id/service_id 由下单派生写入并与订单行保持一致
 * （数据自洽性约定）。评价提交后经 appoint.eval.queue 异步重算师傅
 * rating_score（平均星级）与运营看板满意度指标。</p>
 *
 * @author community-booking
 */
@Data
// 表名锁定为 service_evaluation
@TableName("service_evaluation")
public class ServiceEvaluation implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 评价主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 订单ID（唯一约束 uk_eval_order，一单一评的幂等保证） */
    @TableField("order_id")
    private Long orderId;

    /** 评价居民用户ID（与订单 resident_id 一致） */
    @TableField("resident_id")
    private Long residentId;

    /** 被评价服务人员用户ID（与订单 worker_id 一致，索引 idx_eval_worker） */
    @TableField("worker_id")
    private Long workerId;

    /** 被评价服务项目ID（与订单 service_id 一致） */
    @TableField("service_id")
    private Long serviceId;

    /** 星级：1~5星 */
    @TableField("star")
    private Integer star;

    /** 文字评价内容（可空，最长512字符） */
    @TableField("comment")
    private String comment;

    /** 评价时间 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
