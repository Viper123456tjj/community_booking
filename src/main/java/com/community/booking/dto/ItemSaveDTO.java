package com.community.booking.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 服务项目保存入参 DTO（仅 ROLE_ADMIN；id 为空新增，否则修改）。
 * <p>hot_score 与封面图不接受前端直传：热度由下单行为驱动、封面经
 * 独立上传接口落盘后回写，本 DTO 只承载管理员手工维护字段。
 * skill_required 的每个标签必须落在师傅技能词表内（Service 层校验），
 * 保证智能派单算法的技能覆盖匹配永不为空集。</p>
 *
 * @author community-booking
 */
@Data
public class ItemSaveDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 项目ID（为空=新增，非空=修改） */
    private Long id;

    /** 所属分类ID（须真实存在，Service 层校验防悬挂引用） */
    @NotNull(message = "所属分类不能为空")
    private Long categoryId;

    /** 服务项目名称（唯一由 Service 层查重，1-64字符） */
    @NotBlank(message = "服务项目名称不能为空")
    @Size(max = 64, message = "项目名称最长64字符")
    private String itemName;

    /** 服务价格（元，大于0） */
    @NotNull(message = "服务价格不能为空")
    @DecimalMin(value = "0.01", message = "服务价格必须大于0")
    private BigDecimal price;

    /** 计价单位（次/单/小时/个/趟/平方米，白名单由 Service 层校验） */
    @NotBlank(message = "计价单位不能为空")
    @Size(max = 16, message = "计价单位最长16字符")
    private String unit;

    /** 所需技能标签（| 分隔，每标签须存在于师傅技能词表，Service 层校验） */
    @NotBlank(message = "所需技能标签不能为空")
    @Pattern(regexp = "[\\u4e00-\\u9fa5A-Za-z0-9]+(\\|[\\u4e00-\\u9fa5A-Za-z0-9]+)*",
            message = "技能标签须为竖线分隔的标签串，标签内不允许空白与特殊字符")
    @Size(max = 128, message = "所需技能标签最长128字符")
    private String skillRequired;

    /** 服务详细说明（可空，最长2000字符） */
    @Size(max = 2000, message = "服务说明最长2000字符")
    private String description;

    /** 状态：1上架/0下架（可空默认1） */
    @Min(value = 0, message = "状态最小为0")
    @Max(value = 1, message = "状态最大为1")
    private Integer status;
}
