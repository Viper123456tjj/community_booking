package com.community.booking.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.io.Serializable;

/**
 * 通用分页查询入参 DTO（服务项列表、订单列表、师傅列表、评价列表、账号列表共用）。
 * <p>页码从 1 开始；pageSize 上限 100 与 MybatisPlusConfig 的 maxLimit 一致，
 * 双保险防止前端传入超大页容量拖垮数据库。keyword 由 Service 层拼装 like，
 * 全部走 #{} 传参，无 ${} 注入风险。</p>
 *
 * @author community-booking
 */
@Data
public class PageQueryDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 页码（从1开始，缺省1） */
    @Min(value = 1, message = "页码最小为1")
    private Long pageNum = 1L;

    /** 每页条数（1-100，缺省10） */
    @Min(value = 1, message = "每页至少1条")
    @Max(value = 100, message = "每页最多100条")
    private Long pageSize = 10L;

    /** 关键字（服务名称/订单号/姓名/用户名等模糊检索，可空） */
    private String keyword;
}
