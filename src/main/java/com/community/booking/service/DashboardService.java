package com.community.booking.service;

import com.community.booking.dto.LoginUserDTO;
import com.community.booking.vo.DashboardVO;

/**
 * 运营看板业务接口（Batch 4）：为管理员提供"一屏六图"的聚合数据。
 * <p>指标口径与 SQL 的对应关系见 {@code DashboardMapper}；本层只做三件事：
 * 权限校验（仅管理员）、稀疏日期补齐、状态码到中文的映射——不在 SQL 里
 * 做展示层转换，保证聚合查询只关心"数与量"，措辞与格式留在应用层。</p>
 *
 * @author community-booking
 */
public interface DashboardService {

    /**
     * 运营看板总览：指标卡 + 近 7 日趋势 + 状态分布 + 分类占比 + 师傅排行 + 热门服务。
     *
     * @param currentUser 当前登录用户（须为 ROLE_ADMIN，服务层二次校验）
     * @return 看板聚合数据
     */
    DashboardVO getOverview(LoginUserDTO currentUser);
}
