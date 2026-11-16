package com.community.booking.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 热门服务条目 VO（Redis ZSET community:service:hot 的榜单行）。
 * <p>由 Redis 有序集合按 score 逆序截取，再回查 service_item 补全展示字段；
 * hotScore 为 ZSET 实时分值（下单 +1），与库内 hot_score 列的定时回写值
 * 存在分钟级差异，属可接受的最终一致。</p>
 *
 * @author community-booking
 */
@Data
public class HotServiceVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 服务项目ID */
    private Long id;

    /** 服务项目名称 */
    private String itemName;

    /** 所属分类名称 */
    private String categoryName;

    /** 热度分（Redis ZSET 实时分值 = 下单累计次数） */
    private Long hotScore;

    /** 服务价格（元） */
    private BigDecimal price;

    /** 计价单位 */
    private String unit;
}
