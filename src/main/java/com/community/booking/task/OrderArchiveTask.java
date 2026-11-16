package com.community.booking.task;

import com.community.booking.mapper.ServiceOrderMapper;
import com.community.booking.service.ServiceItemService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每日凌晨归档与榜单校准任务（Batch 4，Spring Task）。
 * <p>执行时间 03:00：选在社区服务的绝对低谷（居民休息、师傅无工单、运维窗口），
 * 归档的批量 UPDATE 与榜单重建都不会与白天的派单/下单争抢数据库与 Redis。</p>
 * <p>两件事放在同一个任务里，是因为它们共享同一执行时机与同一失败语义——
 * 都是"可重入的批量维护"：归档条件含 is_archived=0（重复执行不会重复计数），
 * 榜单重建是全量覆盖（重复执行结果一致）。若拆成两个 @Scheduled，运维要
 * 分别排查两次失败，收益不足以抵消复杂度。</p>
 * <p>异常处理：任一环节抛异常都只记录 ERROR，不让调度线程终止——Spring 的
 * 定时任务若抛出未捕获异常，后续调度仍会继续，但日志栈会埋没真正原因，
 * 因此这里显式 catch 并把"归档数/重建数/耗时"打成一行可检索的摘要。</p>
 *
 * @author community-booking
 */
@Slf4j
// 定时任务组件（位于计划书规定的 task 包）
@Component
// 构造器注入订单 Mapper 与项目业务
@RequiredArgsConstructor
public class OrderArchiveTask {

    /** 订单 Mapper：批量归档完成超期订单 */
    private final ServiceOrderMapper orderMapper;
    /** 服务项目业务：重建热门榜单（校准 ZINCRBY 漂移） */
    private final ServiceItemService serviceItemService;

    /** 完成后保留天数（配置 community.order.archive-days，契约 7 天） */
    @Value("${community.order.archive-days:7}")
    private int archiveDays;

    /**
     * 每日 03:00 执行：归档完成超 7 天的订单 + 全量重建热门榜单。
     * <p>cron 六位含义：秒 分 时 日 月 周；{@code 0 0 3 * * ?} 即每天 03:00:00。
     * 用 {@code ?} 而非 {@code *} 占位"周"，是 Quartz 风格 cron 的规范写法
     * （日与周互斥，必须有一个为 ?）。</p>
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void archiveAndCalibrate() {
        long start = System.currentTimeMillis();
        try {
            int archived = orderMapper.archiveCompletedOrders(archiveDays);
            int hotSize = serviceItemService.refreshHotRanking();
            log.info("[定时任务-凌晨归档] 完成：归档{}单（完成超{}天）、热门榜重建{}条、耗时{}ms",
                    archived, archiveDays, hotSize, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("[定时任务-凌晨归档] 执行失败：{}，耗时{}ms",
                    e.getMessage(), System.currentTimeMillis() - start, e);
        }
    }
}
