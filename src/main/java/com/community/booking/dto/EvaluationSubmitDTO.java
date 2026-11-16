package com.community.booking.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 评价提交入参 DTO（POST /api/evaluation 请求体）。
 * <p>一单一评：被评价订单ID由前端回传，但归属（须为订单下单居民本人、
 * 订单已完成且在 7 天可评价窗口内）由 Service 层校验；订单号不信任前端，
 * 一律以库内订单行派生 resident_id/worker_id/service_id。</p>
 *
 * @author community-booking
 */
@Data
public class EvaluationSubmitDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 被评价订单ID（须为本人已完成且未归档、完成未超7天的订单） */
    @NotNull(message = "被评价订单不能为空")
    private Long orderId;

    /** 星级：1~5星 */
    @NotNull(message = "星级不能为空")
    @Min(value = 1, message = "最低1星")
    @Max(value = 5, message = "最高5星")
    private Integer star;

    /** 文字评价内容（可空，最长512字符） */
    @Size(max = 512, message = "评价内容最长512字符")
    private String comment;
}
