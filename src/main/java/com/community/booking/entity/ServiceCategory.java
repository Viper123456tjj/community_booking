package com.community.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 服务分类实体（对应表 service_category），管理员维护的一级服务品类
 * （家政保洁/管道水电/家电维修等），是居民端服务目录的第一层导航。
 * <p>本表数据是 Redis 缓存键 community:service:categories 的缓存源：
 * 读路径列表整体缓存，管理员增删改后经事务提交精确失效重建（Batch 3 落地）。</p>
 *
 * @author community-booking
 */
@Data
// 表名锁定为 service_category
@TableName("service_category")
public class ServiceCategory implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 分类主键，数据库自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 分类名称，唯一约束 uk_category_name（如 家政保洁/管道水电） */
    @TableField("category_name")
    private String categoryName;

    /** 分类图标标识（Bootstrap Icons class 名，如 bi-droplet，供 Thymeleaf 导航渲染） */
    @TableField("icon")
    private String icon;

    /** 排序序号（升序展示），居民端目录与管理员列表同序 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 创建时间，由 Service 层显式赋值 */
    @TableField("create_time")
    private LocalDateTime createTime;
}
