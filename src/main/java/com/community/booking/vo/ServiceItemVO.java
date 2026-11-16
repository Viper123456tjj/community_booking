package com.community.booking.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务项目出参 VO（居民端服务列表/详情、热门榜单与管理员服务管理共用）。
 * <p>categoryName 由 Mapper LEFT JOIN service_category 一条 SQL 直出；
 * skillTagsList 由 | 分隔串拆分而来供前端渲染徽章；coverImg 为相对存储路径，
 * 前端统一拼接 /api/file/ 前缀访问（CommonFileController 校验归属后输出流）。</p>
 *
 * @author community-booking
 */
@Data
public class ServiceItemVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 项目ID */
    private Long id;

    /** 所属分类ID */
    private Long categoryId;

    /** 所属分类名称（联表直出） */
    private String categoryName;

    /** 服务项目名称 */
    private String itemName;

    /** 封面图相对存储路径（可空，前端拼接 /api/file/ 前缀加载） */
    private String coverImg;

    /** 服务价格（元） */
    private BigDecimal price;

    /** 计价单位 */
    private String unit;

    /** 所需技能标签原始串（| 分隔） */
    private String skillRequired;

    /** 技能标签拆分列表（Service 层按 | 拆分，供前端渲染徽章） */
    private List<String> skillTagsList;

    /** 服务详细说明 */
    private String description;

    /** 热度分（= 该服务累计下单次数，下单行为驱动） */
    private Integer hotScore;

    /** 状态：1上架/0下架 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;
}
