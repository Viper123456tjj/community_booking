package com.community.booking.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serializable;

/**
 * 服务分类保存入参 DTO（仅 ROLE_ADMIN；id 为空新增，否则修改）。
 * <p>icon 字段限定 Bootstrap Icons class 命名格式（bi- 前缀 + 小写字母
 * 数字连字符），杜绝把任意 HTML 片段写入库内造成模板注入。</p>
 *
 * @author community-booking
 */
@Data
public class CategorySaveDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 分类ID（为空=新增，非空=修改） */
    private Long id;

    /** 分类名称（唯一，1-32字符） */
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 32, message = "分类名称最长32字符")
    private String categoryName;

    /** 图标标识（可空；非空时须为 bi-xxx 形式的 Bootstrap Icons class，
     *  @Pattern 天然放行 null，空串经 ^$ 分支放行） */
    @Pattern(regexp = "^$|^bi-[a-z0-9-]{1,61}$", message = "图标须为 bi- 开头的 Bootstrap Icons class")
    @Size(max = 64, message = "图标标识最长64字符")
    private String icon;

    /** 排序序号（可空默认0，升序展示） */
    private Integer sortOrder;
}
