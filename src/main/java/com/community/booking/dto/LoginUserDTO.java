package com.community.booking.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 当前登录用户上下文 DTO：Service 层 IDOR 越权校验的操作者身份载体。
 * <p>Batch 3 接入 Spring Security 后，Controller 从 SecurityContextHolder
 * 组装本对象传入 Service；本批次先以显式参数传递，保证 Service 层的
 * 越权校验逻辑（水平/垂直越权）已完整落地，Batch 3 仅替换取值来源，
 * 不改任何方法签名。</p>
 * <p>与社团项目的负责人多社团归属不同：本系统的师傅身份以
 * worker_profile.user_id 一一对应 sys_user，接单状态等实时状态一律
 * 由 Service 现查现用，本 DTO 不携带快照，避免过期判定。</p>
 *
 * @author community-booking
 */
@Data
public class LoginUserDTO implements Serializable {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /** 当前登录用户ID（sys_user.id），所有私有数据归属校验的锚点 */
    private Long userId;

    /** 登录用户名 */
    private String username;

    /** 角色：ROLE_RESIDENT / ROLE_WORKER / ROLE_ADMIN */
    private String role;
}
