package com.community.booking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.booking.dto.EvaluationSubmitDTO;
import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.PageQueryDTO;
import com.community.booking.entity.ServiceEvaluation;
import com.community.booking.entity.ServiceOrder;
import com.community.booking.exception.BusinessException;
import com.community.booking.exception.ForbiddenException;
import com.community.booking.exception.NotFoundException;
import com.community.booking.mapper.ServiceEvaluationMapper;
import com.community.booking.mapper.ServiceOrderMapper;
import com.community.booking.mq.AppointMessageSender;
import com.community.booking.mq.message.EvaluationMessage;
import com.community.booking.service.ServiceEvaluationService;
import com.community.booking.service.WorkerProfileService;
import com.community.booking.utils.Constants;
import com.community.booking.vo.EvaluationVO;
import com.community.booking.vo.PageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 服务评价业务实现：一单一评写入 + 三视角评价查询 + 单条回显。
 * <p>可评价窗口的三重前置：订单已完成(3) + 未归档(is_archived=0) + 完成时间
 * 距今不超过 7 天——与 OrderVO.canEvaluate 的 SQL CASE WHEN 口径逐字对齐，
 * 前端按钮显示与实际可提交条件永远一致，不会出现"按钮在但提交被拒"。
 * 评价写入后同事务同步重算师傅评分（Batch 4 起改为经 appoint.eval.queue
 * 异步重算，本方法签名不变，届时仅需把同步调用挪到消费者）。</p>
 *
 * @author community-booking
 */
@Slf4j
@Service
public class ServiceEvaluationServiceImpl implements ServiceEvaluationService {

    /** 可评价窗口天数（完成超 7 天关闭评价入口，与归档任务口径一致） */
    private static final int EVALUATE_WINDOW_DAYS = 7;

    /** 评价 Mapper（本领域主表与联表数据源） */
    private final ServiceEvaluationMapper evaluationMapper;
    /** 订单 Mapper（评价前置校验与派生字段来源） */
    private final ServiceOrderMapper orderMapper;
    /** 师傅档案业务（绩效重算统一收口，不越层直改他领域主表） */
    private final WorkerProfileService workerProfileService;
    /** MQ 消息发送器（评价绩效重算消息） */
    private final AppointMessageSender messageSender;
    /** 同步重算兜底开关：dev（消费者未启动）为 true，prod 走纯异步为 false */
    private final boolean evalSyncFallback;

    /**
     * 构造注入依赖。
     *
     * @param evaluationMapper       评价 Mapper
     * @param orderMapper            订单 Mapper
     * @param workerProfileService   师傅档案业务（评分重算）
     * @param messageSender          MQ 消息发送器
     * @param evalSyncFallback       是否在事务内同步重算兜底（dev=true / prod=false）
     */
    public ServiceEvaluationServiceImpl(ServiceEvaluationMapper evaluationMapper,
                                        ServiceOrderMapper orderMapper,
                                        WorkerProfileService workerProfileService,
                                        AppointMessageSender messageSender,
                                        @Value("${community.mq.eval-sync-fallback:true}") boolean evalSyncFallback) {
        this.evaluationMapper = evaluationMapper;
        this.orderMapper = orderMapper;
        this.workerProfileService = workerProfileService;
        this.messageSender = messageSender;
        this.evalSyncFallback = evalSyncFallback;
    }

    /**
     * 提交评价：本人 + 已完成未归档 + 7 天窗口 + 一单一评四重校验，
     * 写入后同步重算师傅评分，单事务。
     *
     * @param dto         评价入参
     * @param currentUser 当前登录用户
     * @return 新评价ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitEvaluation(EvaluationSubmitDTO dto, LoginUserDTO currentUser) {
        if (currentUser == null || !Constants.ROLE_RESIDENT.equals(currentUser.getRole())) {
            throw new ForbiddenException("只有下单居民可以提交服务评价");
        }
        ServiceOrder order = orderMapper.selectById(dto.getOrderId());
        if (order == null) {
            throw new NotFoundException("被评价的订单不存在");
        }
        // 水平越权防线：只能评价自己下的单
        if (!order.getResidentId().equals(currentUser.getUserId())) {
            throw new ForbiddenException("只能评价自己提交的预约订单");
        }
        // 状态机前置：服务未完成不可评价
        if (Constants.ORDER_COMPLETED != order.getOrderStatus()) {
            throw new BusinessException(400, "只有已完成的订单可以评价（当前状态: "
                    + order.getOrderStatus() + "）");
        }
        // 归档前置：完成超 7 天由每日 03:00 定时任务归档，归档单退出可评价窗口
        if (Constants.ARCHIVED == order.getIsArchived()) {
            throw new BusinessException(400, "该订单已归档，评价入口已关闭");
        }
        // 窗口前置：完成时间在 7 天内才允许评价
        if (order.getFinishTime() == null
                || order.getFinishTime().isBefore(LocalDateTime.now().minusDays(EVALUATE_WINDOW_DAYS))) {
            throw new BusinessException(400, "服务完成已超过 " + EVALUATE_WINDOW_DAYS
                    + " 天，评价入口已关闭");
        }
        // 一单一评先行校验（uk_eval_order 唯一索引仍是最终兜底，此处给出更友好提示）
        Long existed = evaluationMapper.selectCount(
                new QueryWrapper<ServiceEvaluation>().eq("order_id", order.getId()));
        if (existed != null && existed > 0) {
            throw new BusinessException(400, "该订单已评价，不能重复提交");
        }
        // 防御待派单/超时取消样本：已完成但未承接师傅（理论上不存在，兜底拒绝）
        if (order.getWorkerId() == null) {
            throw new BusinessException(400, "该订单没有承接师傅，无法评价");
        }
        ServiceEvaluation evaluation = new ServiceEvaluation();
        evaluation.setOrderId(order.getId());
        // 三联派生字段一律取订单行现值，严禁信任前端，保证与订单数据自洽
        evaluation.setResidentId(order.getResidentId());
        evaluation.setWorkerId(order.getWorkerId());
        evaluation.setServiceId(order.getServiceId());
        evaluation.setStar(dto.getStar());
        evaluation.setComment(trimOrNull(dto.getComment()));
        evaluation.setCreateTime(LocalDateTime.now());
        evaluationMapper.insert(evaluation);
        // 主路径：事务提交后投递 appoint.eval.queue，由消费者异步重算师傅平均星级
        EvaluationMessage evalMessage = new EvaluationMessage();
        evalMessage.setEvaluationId(evaluation.getId());
        evalMessage.setOrderId(order.getId());
        evalMessage.setWorkerId(order.getWorkerId());
        evalMessage.setStar(evaluation.getStar());
        messageSender.sendEvaluation(evalMessage);
        // 降级兜底：dev 环境消费者未启动（application-dev.yml auto-startup=false）时，
        // 消息会滞留在队列里，若不同步重算会导致师傅评分长期不更新；prod 置 false 后
        // 评价接口只做一次 INSERT + 一次投递，响应时间与评价表的写入成本解耦
        if (evalSyncFallback) {
            workerProfileService.recalcRatingScore(order.getWorkerId());
        }
        log.info("居民[{}]评价订单[{}] {}星", currentUser.getUsername(), order.getOrderNo(), dto.getStar());
        return evaluation.getId();
    }

    /**
     * 三视角评价分页（归属条件由登录角色注入，严禁前端直传）。
     *
     * @param query       分页入参
     * @param star        星级筛选（可空）
     * @param serviceId   服务项目筛选（可空）
     * @param currentUser 当前登录用户
     * @return 评价 VO 分页
     */
    @Override
    public PageVO<EvaluationVO> pageEvaluations(PageQueryDTO query, Integer star, Long serviceId,
                                                 LoginUserDTO currentUser) {
        if (currentUser == null || currentUser.getUserId() == null) {
            throw new ForbiddenException("请先登录后再查看评价");
        }
        if (star != null && (star < Constants.MIN_STAR || star > Constants.MAX_STAR)) {
            throw new BusinessException(400, "星级筛选只能为 1~5");
        }
        long pageNum = query == null || query.getPageNum() == null
                ? Constants.DEFAULT_PAGE_NUM : query.getPageNum();
        long pageSize = query == null || query.getPageSize() == null
                ? Constants.DEFAULT_PAGE_SIZE : Math.min(query.getPageSize(), Constants.MAX_PAGE_SIZE);
        Page<EvaluationVO> page = new Page<>(pageNum, pageSize);
        String kw = normalizeKw(query == null ? null : query.getKeyword());
        Long residentId = null;
        Long workerId = null;
        // 居民看自己给出的评价，师傅看收到的评价，管理员全量
        if (Constants.ROLE_RESIDENT.equals(currentUser.getRole())) {
            residentId = currentUser.getUserId();
        } else if (Constants.ROLE_WORKER.equals(currentUser.getRole())) {
            workerId = currentUser.getUserId();
        } else if (!Constants.ROLE_ADMIN.equals(currentUser.getRole())) {
            throw new ForbiddenException("当前角色无权查看评价");
        }
        IPage<EvaluationVO> result = evaluationMapper.selectEvaluationPage(page, kw, star,
                residentId, workerId, serviceId);
        return PageVO.of(result);
    }

    /**
     * 按订单回显评价（未评价返回 null；非归属非管理员 403）。
     *
     * @param orderId     订单ID
     * @param currentUser 当前登录用户
     * @return 评价 VO 或 null
     */
    @Override
    public EvaluationVO getEvaluationByOrderId(Long orderId, LoginUserDTO currentUser) {
        if (orderId == null) {
            throw new BusinessException(400, "订单ID不能为空");
        }
        if (currentUser == null || currentUser.getUserId() == null) {
            throw new ForbiddenException("请先登录后再查看评价");
        }
        // 先取订单确认存在，再据归属判定，避免"订单不存在"与"无权查看"信息泄露差异过大
        ServiceOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new NotFoundException("服务订单不存在");
        }
        String role = currentUser.getRole();
        EvaluationVO vo = evaluationMapper.selectEvaluationByOrderId(orderId);
        if (vo == null) {
            // 未评价时仍须做归属校验：防止越权探测"该订单是否已评价"之外的路径枚举
            boolean allowed = Constants.ROLE_ADMIN.equals(role)
                    || (Constants.ROLE_RESIDENT.equals(role) && order.getResidentId().equals(currentUser.getUserId()))
                    || (Constants.ROLE_WORKER.equals(role) && currentUser.getUserId().equals(order.getWorkerId()));
            if (!allowed) {
                throw new ForbiddenException("无权查看该订单的评价");
            }
            return null;
        }
        // 已评价时以评价行的归属为准（评价与订单一致，任一口径等价）
        boolean allowed = Constants.ROLE_ADMIN.equals(role)
                || vo.getResidentId().equals(currentUser.getUserId())
                || vo.getWorkerId().equals(currentUser.getUserId());
        if (!allowed) {
            throw new ForbiddenException("无权查看该订单的评价");
        }
        return vo;
    }

    /**
     * 空串归一 null（评价内容允许仅打星不留言）。
     *
     * @param value 原始内容
     * @return null 或去空白内容
     */
    private String trimOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 关键字规范化（空串归一 null）。
     *
     * @param kw 原始关键字
     * @return 规范化关键字
     */
    private String normalizeKw(String kw) {
        if (kw == null) {
            return null;
        }
        String trimmed = kw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
