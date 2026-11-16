package com.community.booking.service;

import com.community.booking.dto.LoginUserDTO;
import com.community.booking.dto.PageQueryDTO;
import com.community.booking.dto.WorkerProfileSaveDTO;
import com.community.booking.entity.WorkerProfile;
import com.community.booking.vo.PageVO;
import com.community.booking.vo.WorkerProfileVO;

/**
 * 服务人员档案业务接口：师傅自助维护 + 管理员师傅管理 + 接单状态联动。
 * <p>技能标签口径：师傅自助维护时每个标签必须落在服务技能词表
 * （全部 service_item.skill_required 标签并集）内——否则服务项目永远无法
 * 被该师傅承接，智能派单技能覆盖匹配必然落空；管理员代管时可自由录入。</p>
 *
 * @author community-booking
 */
public interface WorkerProfileService {

    /**
     * 查询师傅当前档案（含在手工单数与状态中文描述）。
     *
     * @param userId 师傅用户ID（师傅端由 Service 注入为登录用户，杜绝越权查看他人档案）
     * @return 师傅档案 VO
     */
    WorkerProfileVO getMyProfile(Long userId);

    /**
     * 维护师傅档案（技能标签与期望接单状态）。
     * <p>越权防线：操作者必须为档案本人或管理员；师傅本人不得提交忙碌(2)
     * （忙碌由系统在派单/开工时自动置位）；师傅提交的技能标签须通过
     * 服务技能词表校验；休息(0)状态下若仍有在手工单则拒绝切换，
     * 保证"休息中的师傅不接新单"这一派单前提不被绕过。</p>
     *
     * @param dto         档案维护入参
     * @param currentUser 当前登录用户上下文
     */
    void saveProfile(WorkerProfileSaveDTO dto, LoginUserDTO currentUser);

    /**
     * 管理员分页查询师傅列表（可按接单状态筛选，关键字匹配用户名/姓名/技能）。
     *
     * @param query      分页查询入参（keyword 复用，workStatus 由扩展参数传入）
     * @param workStatus 接单状态筛选：1空闲/2忙碌/0休息（可空=不限）
     * @return 师傅档案 VO 分页
     */
    PageVO<WorkerProfileVO> pageWorkers(PageQueryDTO query, Integer workStatus);

    /**
     * 管理员查看单个师傅档案详情。
     *
     * @param userId 师傅用户ID
     * @return 师傅档案 VO
     */
    WorkerProfileVO getWorkerDetail(Long userId);

    /**
     * 按在手工单联动刷新师傅接单状态（派单/开工置忙、完成/取消/改派释放）。
     * <p>规则：该师傅若仍持有已派单(1)或服务中(2)的订单则置 busy(2)；
     * 否则若当前为 busy(2) 则回置 idle(1)——休息(0)是师傅主动意愿，
     * 系统绝不擅自把休息师傅改成空闲。本方法是所有状态迁移后的统一收口，
     * 由 Batch 4 的派单/取消/定时超时路径调用。</p>
     *
     * @param userId 师傅用户ID
     */
    void refreshWorkStatus(Long userId);

    /**
     * 重算师傅平均星级评分（评价提交后经 appoint.eval.queue 异步消费调用）。
     *
     * @param userId 师傅用户ID
     */
    void recalcRatingScore(Long userId);

    /**
     * 累计完成订单数 +1（师傅推进"完成服务"时在事务内调用）。
     *
     * @param userId 师傅用户ID
     */
    void incrementTotalOrders(Long userId);

    /**
     * 为 ROLE_WORKER 用户初始化师傅档案（管理员新增师傅账号时调用；
     * 已存在档案则幂等跳过）。
     *
     * @param userId 师傅用户ID
     */
    void initProfileIfAbsent(Long userId);

    /**
     * 查询全部师傅技能标签并集（服务技能词表，供服务项目保存时校验
     * skill_required 每个标签可达）。
     *
     * @return 去重后的技能标签集合（保持录入顺序无意义，仅供包含判定）
     */
    java.util.Set<String> listSkillVocabulary();
}
