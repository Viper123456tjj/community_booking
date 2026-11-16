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
 * 服务项目实体（对应表 service_item），管理员维护的具体可预约服务条目。
 * <p>skill_required 为 | 分隔技能标签，须为师傅技能词表（worker_profile.skill_tags
 * 并集）的子集，供智能派单算法做技能覆盖匹配；price 为下单时快照到订单的
 * 挂牌价；hot_score 由下单行为 +1（经 Redis ZINCRBY 与定时回写落库），
 * 是 Redis 键 community:service:hot 热门榜单的持久化兜底。</p>
 *
 * @author community-booking
 */
@Data
// 表名锁定为 service_item
@TableName("service_item")
public class ServiceItem implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 项目主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属分类ID（FK -> service_category.id，索引 idx_item_category） */
    @TableField("category_id")
    private Long categoryId;

    /** 服务项目名称（索引 idx_item_name，支持关键字检索） */
    @TableField("item_name")
    private String itemName;

    /** 服务封面图相对存储路径（FileUploadUtils.save 的返回值，可空） */
    @TableField("cover_img")
    private String coverImg;

    /** 服务价格（元，下单时快照到 service_order.price） */
    @TableField("price")
    private BigDecimal price;

    /** 计价单位（次/单/小时/个/趟/平方米等，随下单快照进订单备注区展示） */
    @TableField("unit")
    private String unit;

    /** 所需技能标签（| 分隔，须为师傅技能词表子集，派单技能匹配依据） */
    @TableField("skill_required")
    private String skillRequired;

    /** 服务详细说明（居民端详情页正文） */
    @TableField("description")
    private String description;

    /** 热度分（下单 +1，供热门服务榜单 community:service:hot 排序） */
    @TableField("hot_score")
    private Integer hotScore;

    /** 状态：1上架（可见可下单）/0下架（不可见不可下单） */
    @TableField("status")
    private Integer status;

    /** 创建时间，由 Service 层显式赋值 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
