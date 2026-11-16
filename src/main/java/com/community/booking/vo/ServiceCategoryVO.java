package com.community.booking.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 服务分类出参 VO（居民端服务目录导航与管理员分类管理列表共用）。
 * <p>itemCount 为该分类下上架服务项目数（Mapper COUNT 子查询直出），
 * 居民端据此在导航条上展示"共N项服务"提示，管理员据此判断删除分类是否安全。</p>
 *
 * @author community-booking
 */
@Data
public class ServiceCategoryVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 分类ID */
    private Long id;

    /** 分类名称 */
    private String categoryName;

    /** 图标标识（Bootstrap Icons class，前端渲染 <i class="...">） */
    private String icon;

    /** 排序序号（升序） */
    private Integer sortOrder;

    /** 该分类下上架服务项目数（联表 COUNT 直出） */
    private Integer itemCount;

    /** 创建时间 */
    private LocalDateTime createTime;
}
