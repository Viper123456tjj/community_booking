package com.community.booking.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 智能派单候选人评分出参 VO（Batch 4 智能派单弹窗数据源）。
 * <p>候选行由候选 SQL 直出（启用师傅 + 档案 + 在手工单数），多维权重评分
 * （技能覆盖 + 空闲状态 + 时间窗口冲突 + 评分与接单量负载均衡）在内存中
 * 计算后按综合得分降序返回；技能标签覆盖与时间冲突判定结果随行返回，
 * 前端弹窗据此渲染"推荐理由"列，管理员对照后确认指派或人工改选。</p>
 *
 * @author community-booking
 */
@Data
public class DispatchCandidateVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 师傅用户ID（sys_user.id，确认派单时回传） */
    private Long userId;

    /** 登录用户名 */
    private String username;

    /** 真实姓名 */
    private String realName;

    /** 手机号（管理员弹窗展示，便于电话协调） */
    private String phone;

    /** 技能标签原始串（| 分隔） */
    private String skillTags;

    /** 技能标签拆分列表（供前端渲染徽章） */
    private List<String> skillTagsList;

    /** 命中的所需技能数（与项目 skill_required 的交集大小） */
    private Integer matchedSkillCount;

    /** 所需技能总数（项目 skill_required 标签数，覆盖判定分母） */
    private Integer requiredSkillCount;

    /** 技能是否全覆盖（false 的行默认被算法过滤，仅作参考行返回） */
    private Boolean skillMatched;

    /** 接单状态：1空闲/2忙碌/0休息 */
    private Integer workStatus;

    /** 时间窗口冲突：预约时段±2小时是否与在手工单重叠 */
    private Boolean timeConflict;

    /** 冲突的在手工单数（0 表示无冲突） */
    private Integer conflictOrders;

    /** 在手工单数（已派单+服务中） */
    private Integer activeOrders;

    /** 平均星级评分 */
    private BigDecimal ratingScore;

    /** 累计完成订单数（负载均衡因子） */
    private Integer totalOrders;

    /** 综合评分（多维权重加权总分，降序排序依据） */
    private BigDecimal matchScore;

    /** 推荐理由（如"技能全覆盖、当前空闲、时间无冲突、近7日仅1单"） */
    private String reason;
}
