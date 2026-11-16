package com.community.booking.service.impl;

import com.community.booking.dto.LoginUserDTO;
import com.community.booking.exception.ForbiddenException;
import com.community.booking.mapper.DashboardMapper;
import com.community.booking.service.DashboardService;
import com.community.booking.service.ServiceItemService;
import com.community.booking.utils.Constants;
import com.community.booking.vo.DashboardCountRowVO;
import com.community.booking.vo.DashboardKpiVO;
import com.community.booking.vo.DashboardTrendRowVO;
import com.community.booking.vo.DashboardVO;
import com.community.booking.vo.HotServiceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营看板业务实现（Batch 4）：六组聚合数据的组装与展示层转换。
 * <p>三个实现要点：</p>
 * <ol>
 *   <li><b>趋势补零</b>：SQL 只返回有单的日期，本层从"今天往前 6 天"循环对齐，
 *       缺失日期填 0——否则 ECharts 会把 7 天的数据挤成 3 个点，看板失真；</li>
 *   <li><b>状态码转中文</b>：SQL 返回状态码，本层按下标映射"待派单/已派单/
 *       服务中/已完成/已取消"，与订单列表的中文口径完全一致；</li>
 *   <li><b>热门榜复用缓存</b>：热门前 5 直接调用 ServiceItemService.listHotServices，
 *       命中 Redis ZSET；Redis 不可用时该方法内部回退库内 hot_score，
 *       看板因此不会因为缓存故障而空白。</li>
 * </ol>
 *
 * @author community-booking
 */
@Slf4j
// 看板业务实现
@Service
// 构造器注入聚合 Mapper 与项目业务（热门榜）
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    /** 订单状态中文映射（下标即状态值 0~4，与订单模块口径一致） */
    private static final String[] STATUS_TEXT = {
            "待派单", "已派单", "服务中", "已完成", "已取消"
    };

    /** 趋势图 X 轴日期格式（MM-dd，7 个点足够，无需年份） */
    private static final DateTimeFormatter TREND_FMT = DateTimeFormatter.ofPattern("MM-dd");

    /** 趋势统计天数（含今天） */
    private static final int TREND_DAYS = 7;

    /** 热门榜展示条数 */
    private static final int HOT_LIMIT = 5;

    /** 看板聚合 Mapper */
    private final DashboardMapper dashboardMapper;
    /** 服务项目业务（热门榜数据源，Redis ZSET + 库内兜底） */
    private final ServiceItemService serviceItemService;

    /**
     * 运营看板总览。
     *
     * @param currentUser 当前登录用户（须为管理员）
     * @return 看板聚合数据
     */
    @Override
    public DashboardVO getOverview(LoginUserDTO currentUser) {
        // 垂直越权防线：URL 规则与 @PreAuthorize 之外的第三道校验，防止未来被
        // 误挂到其它路由时泄露全站经营数据（看板含金额与人员绩效）
        if (currentUser == null || !Constants.ROLE_ADMIN.equals(currentUser.getRole())) {
            throw new ForbiddenException("只有物业管理员可以查看运营看板");
        }
        DashboardVO vo = new DashboardVO();
        vo.setKpi(buildKpi(dashboardMapper.selectKpi()));
        vo.setTrend(buildTrend(dashboardMapper.selectTrend7Days()));
        vo.setStatusDistribution(convertStatus(dashboardMapper.selectStatusDistribution()));
        vo.setCategoryDistribution(convert(dashboardMapper.selectCategoryDistribution()));
        vo.setWorkerRank(convert(dashboardMapper.selectWorkerRankTop5()));
        vo.setHotItems(buildHotItems());
        log.debug("运营看板数据组装完成: 待派单={} 在途={}",
                vo.getKpi().getPendingOrders(), vo.getKpi().getActiveOrders());
        return vo;
    }

    /**
     * 指标卡转换：Mapper 行 → 前端契约对象，并对 null 做 0 兜底
     * （空库时 COUNT 返回 0，但 AVG 在无评价时返回 null）。
     *
     * @param row Mapper 聚合行
     * @return 指标卡
     */
    private DashboardVO.Kpi buildKpi(DashboardKpiVO row) {
        DashboardVO.Kpi kpi = new DashboardVO.Kpi();
        if (row == null) {
            // 极端情况（表被清空）：返回全 0 指标卡而不是 null，前端无需判空
            kpi.setTodayOrders(0L);
            kpi.setPendingOrders(0L);
            kpi.setActiveOrders(0L);
            kpi.setTodayCompleted(0L);
            kpi.setAvgStar30d(java.math.BigDecimal.ZERO);
            kpi.setIdleWorkers(0L);
            kpi.setTotalWorkers(0L);
            kpi.setWeekCancelled(0L);
            return kpi;
        }
        kpi.setTodayOrders(orZero(row.getTodayOrders()));
        kpi.setPendingOrders(orZero(row.getPendingOrders()));
        kpi.setActiveOrders(orZero(row.getActiveOrders()));
        kpi.setTodayCompleted(orZero(row.getTodayCompleted()));
        kpi.setAvgStar30d(row.getAvgStar30d() == null ? java.math.BigDecimal.ZERO : row.getAvgStar30d());
        kpi.setIdleWorkers(orZero(row.getIdleWorkers()));
        kpi.setTotalWorkers(orZero(row.getTotalWorkers()));
        kpi.setWeekCancelled(orZero(row.getWeekCancelled()));
        return kpi;
    }

    /**
     * 趋势补齐：以"今天往前 6 天"为骨架，把稀疏聚合行挂到对应日期上。
     *
     * @param rows 聚合行（可能缺少某些日期）
     * @return 连续 7 个趋势点
     */
    private List<DashboardVO.TrendPoint> buildTrend(List<DashboardTrendRowVO> rows) {
        Map<LocalDate, DashboardTrendRowVO> indexed = new HashMap<>();
        if (rows != null) {
            for (DashboardTrendRowVO row : rows) {
                if (row != null && row.getStatDate() != null) {
                    indexed.put(row.getStatDate(), row);
                }
            }
        }
        List<DashboardVO.TrendPoint> points = new ArrayList<>(TREND_DAYS);
        LocalDate today = LocalDate.now();
        for (int offset = TREND_DAYS - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            DashboardTrendRowVO row = indexed.get(date);
            points.add(new DashboardVO.TrendPoint(
                    date.format(TREND_FMT),
                    row == null ? 0L : orZero(row.getTotal()),
                    row == null ? 0L : orZero(row.getCompleted())));
        }
        return points;
    }

    /**
     * 状态分布转换：状态码 → 中文名，非法码保留原值（不丢数据）。
     *
     * @param rows 状态聚合行
     * @return 环形图数据
     */
    private List<DashboardVO.NameValue> convertStatus(List<DashboardCountRowVO> rows) {
        List<DashboardVO.NameValue> list = new ArrayList<>();
        if (rows == null) {
            return list;
        }
        for (DashboardCountRowVO row : rows) {
            String code = row.getStatName();
            String name;
            try {
                int index = Integer.parseInt(code);
                name = index >= 0 && index < STATUS_TEXT.length ? STATUS_TEXT[index] : "未知(" + code + ")";
            } catch (NumberFormatException e) {
                name = "未知(" + code + ")";
            }
            list.add(new DashboardVO.NameValue(name, orZero(row.getStatValue())));
        }
        return list;
    }

    /**
     * 通用名称-数值转换（分类占比 / 师傅排行共用）。
     *
     * @param rows 聚合行
     * @return 图表数据
     */
    private List<DashboardVO.NameValue> convert(List<DashboardCountRowVO> rows) {
        List<DashboardVO.NameValue> list = new ArrayList<>();
        if (rows == null) {
            return list;
        }
        for (DashboardCountRowVO row : rows) {
            list.add(new DashboardVO.NameValue(
                    row.getStatName() == null ? "未命名" : row.getStatName(),
                    orZero(row.getStatValue())));
        }
        return list;
    }

    /**
     * 热门服务：复用 ServiceItemService 的 Redis 热度榜（含 Redis 故障回退）。
     *
     * @return 热门榜数据
     */
    private List<DashboardVO.NameValue> buildHotItems() {
        List<DashboardVO.NameValue> list = new ArrayList<>();
        List<HotServiceVO> hot = serviceItemService.listHotServices(HOT_LIMIT);
        for (HotServiceVO item : hot) {
            list.add(new DashboardVO.NameValue(item.getItemName(),
                    item.getHotScore() == null ? 0L : item.getHotScore()));
        }
        return list;
    }

    /**
     * null 归一为 0（COUNT 理论不为 null，防御性处理）。
     *
     * @param value 原始值
     * @return 非空数值
     */
    private long orZero(Long value) {
        return value == null ? 0L : value;
    }
}
