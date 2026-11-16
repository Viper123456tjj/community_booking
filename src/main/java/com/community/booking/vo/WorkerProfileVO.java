package com.community.booking.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务人员档案出参 VO（管理员"服务人员管理"列表与师傅端"我的档案"共用）。
 * <p>username/realName/phone/email/status 由 Mapper JOIN sys_user 一条 SQL 直出
 * （避免 N+1）；activeOrders 为该师傅"已派单+服务中"在手工单数，
 * 管理员据此判断能否强制休息或删除；skillTagsList 由 | 分隔串拆分而来，
 * 前端渲染为标签徽章。</p>
 *
 * @author community-booking
 */
@Data
public class WorkerProfileVO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 档案ID */
    private Long id;

    /** 师傅用户ID（sys_user.id，所有操作的锚点） */
    private Long userId;

    /** 登录用户名（联表直出） */
    private String username;

    /** 真实姓名（联表直出） */
    private String realName;

    /** 手机号（联表直出） */
    private String phone;

    /** 邮箱（联表直出，派单通知收件地址） */
    private String email;

    /** 技能标签原始串（| 分隔，联表直出） */
    private String skillTags;

    /** 技能标签拆分列表（Service 层按 | 拆分，供前端渲染徽章） */
    private List<String> skillTagsList;

    /** 接单状态：1空闲/2忙碌/0休息 */
    private Integer workStatus;

    /** 接单状态中文描述（Service 层按状态映射，供表格直接展示） */
    private String workStatusText;

    /** 平均星级评分（1~5） */
    private BigDecimal ratingScore;

    /** 累计完成订单数 */
    private Integer totalOrders;

    /** 在手工单数（已派单+服务中，联表子查询直出） */
    private Integer activeOrders;

    /** 账号状态：1正常/0禁用（联表直出，禁用不可派单） */
    private Integer status;

    /** 档案创建时间 */
    private LocalDateTime createTime;
}
