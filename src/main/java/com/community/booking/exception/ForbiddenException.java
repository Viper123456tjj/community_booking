package com.community.booking.exception;

/**
 * 越权/权限不足异常（403）：IDOR 校验失败时由 Service 层抛出。
 * <p>典型场景：居民查看他人订单、师傅推进非指派工单、非管理员维护
 * 服务分类与项目或执行派单。水平越权与垂直越权统一用本异常表达。</p>
 *
 * @author community-booking
 */
public class ForbiddenException extends BusinessException {

    /** 序列化版本号 */
    private static final long serialVersionUID = 1L;

    /**
     * 构造 403 异常。
     *
     * @param msg 具体中文提示（如"只能查看自己提交的预约订单"）
     */
    public ForbiddenException(String msg) {
        super(403, msg);
    }
}
