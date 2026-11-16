package com.community.booking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.community.booking.dto.ItemSaveDTO;
import com.community.booking.dto.PageQueryDTO;
import com.community.booking.entity.ServiceCategory;
import com.community.booking.entity.ServiceItem;
import com.community.booking.entity.ServiceOrder;
import com.community.booking.exception.BusinessException;
import com.community.booking.exception.NotFoundException;
import com.community.booking.mapper.ServiceCategoryMapper;
import com.community.booking.mapper.ServiceItemMapper;
import com.community.booking.mapper.ServiceOrderMapper;
import com.community.booking.service.ServiceItemService;
import com.community.booking.service.WorkerProfileService;
import com.community.booking.utils.FileUploadUtils;
import com.community.booking.utils.Constants;
import com.community.booking.utils.RedisCacheUtils;
import com.community.booking.vo.HotServiceVO;
import com.community.booking.vo.PageVO;
import com.community.booking.vo.ServiceItemVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 服务项目业务实现：居民端服务广场（仅上架）+ 管理员服务管理 + 热门榜单。
 * <p>核心业务护栏：skill_required 的每个标签必须存在于师傅技能词表——否则
 * 该服务永远无师傅可承接，智能派单必然返回空候选，居民下单后只能超时自动取消；
 * 删除项目时若已被订单引用则禁止物理删除，保证订单 service_id 与价格快照可追溯。</p>
 * <p>热门榜（community:service:hot，Redis ZSET）Batch 3 接入后的读路径设计：
 * ZSET 只提供"项目ID + 热度分"的排序骨架，展示字段（名称/价格/分类）每次现查
 * 数据库并过滤 status=1——这样下架项目自然退出榜单、名称与价格改动能即时反映，
 * 上下架切换与项目改名都<b>无需任何缓存联动失效</b>（读路径自愈），Redis 不可用
 * 时直接回退库内 hot_score 列口径。写路径仅在下单成功后 ZINCRBY +1（挂
 * afterCommit，事务回滚不会虚增热度），冷启动/键过期时由抢到互斥键的请求
 * 用库内 TOP-N 重建。</p>
 *
 * @author community-booking
 */
@Slf4j
@Service
public class ServiceItemServiceImpl implements ServiceItemService {

    /** 合法计价单位白名单（与建表注释口径一致） */
    private static final Set<String> VALID_UNITS = Set.of("次", "单", "小时", "个", "趟", "平方米");

    /** 热门榜单默认与上限条数（防止前端传 limit=9999 拖垮缓存回查） */
    private static final int HOT_DEFAULT_LIMIT = 10;
    private static final int HOT_MAX_LIMIT = 20;

    /** 服务项目 Mapper（本领域主表与联表数据源） */
    private final ServiceItemMapper itemMapper;
    /** 分类 Mapper（categoryId 存在性校验，防悬挂引用） */
    private final ServiceCategoryMapper categoryMapper;
    /** 订单 Mapper（删除项目前订单引用校验） */
    private final ServiceOrderMapper orderMapper;
    /** 师傅档案业务（技能词表来源，校验 skill_required 每个标签可承接） */
    private final WorkerProfileService workerProfileService;
    /** 文件上传工具（删除未被引用项目时清理封面附件） */
    private final FileUploadUtils fileUploadUtils;
    /** Redis 缓存原语（热门榜 ZSET 读写与互斥重建） */
    private final RedisCacheUtils redisCacheUtils;
    /** 热门榜 ZSET TTL（分钟，配置 community.cache.hot-ttl-minutes，契约 10 分钟） */
    private final long hotTtlMinutes;

    /**
     * 构造注入依赖。
     *
     * @param itemMapper             项目 Mapper
     * @param categoryMapper         分类 Mapper
     * @param orderMapper            订单 Mapper
     * @param workerProfileService   师傅档案业务（技能词表）
     * @param fileUploadUtils        附件工具
     * @param redisCacheUtils        Redis 缓存原语（热门榜 ZSET）
     * @param hotTtlMinutes          热门榜 ZSET TTL（配置注入，契约 10 分钟）
     */
    public ServiceItemServiceImpl(ServiceItemMapper itemMapper, ServiceCategoryMapper categoryMapper,
                                  ServiceOrderMapper orderMapper, WorkerProfileService workerProfileService,
                                  FileUploadUtils fileUploadUtils, RedisCacheUtils redisCacheUtils,
                                  @Value("${community.cache.hot-ttl-minutes:10}") long hotTtlMinutes) {
        this.itemMapper = itemMapper;
        this.categoryMapper = categoryMapper;
        this.orderMapper = orderMapper;
        this.workerProfileService = workerProfileService;
        this.fileUploadUtils = fileUploadUtils;
        this.redisCacheUtils = redisCacheUtils;
        this.hotTtlMinutes = hotTtlMinutes;
    }

    /**
     * 居民端服务分页：Service 层强制固化"只看上架"，前端无法越权窥探下架项目。
     *
     * @param query      分页入参
     * @param categoryId 分类筛选（可空）
     * @return 项目 VO 分页
     */
    @Override
    public PageVO<ServiceItemVO> pageItemsForResident(PageQueryDTO query, Long categoryId) {
        // 居民视角状态条件由 Service 写死为上架(1)，不接受前端传入 status
        return doPage(query, categoryId, Constants.ITEM_ON);
    }

    /**
     * 管理员端服务分页（可按上下架筛选，null=全量）。
     *
     * @param query      分页入参
     * @param categoryId 分类筛选（可空）
     * @param status     上下架筛选（可空）
     * @return 项目 VO 分页
     */
    @Override
    public PageVO<ServiceItemVO> pageItemsForAdmin(PageQueryDTO query, Long categoryId, Integer status) {
        if (status != null && status != Constants.ITEM_ON && status != Constants.ITEM_OFF) {
            throw new BusinessException(400, "非法的上下架筛选值: " + status);
        }
        return doPage(query, categoryId, status);
    }

    /**
     * 项目详情：居民端访问下架项目按 404（与列表口径一致，下架即"不存在"，
     * 不泄露"该项目存在但已下架"的信息）。
     *
     * @param id          项目ID
     * @param forResident 是否居民端视角
     * @return 项目 VO
     */
    @Override
    public ServiceItemVO getItemDetail(Long id, boolean forResident) {
        ServiceItemVO vo = itemMapper.selectItemDetailById(id);
        if (vo == null || (forResident && Constants.ITEM_OFF == vo.getStatus())) {
            throw new NotFoundException("服务项目不存在或已下架");
        }
        vo.setSkillTagsList(splitTags(vo.getSkillRequired()));
        return vo;
    }

    /**
     * 热门服务 TOP-N（Batch 3 起优先读 Redis ZSET 的排名骨架，展示字段现查库
     * 补全并过滤下架项目；ZSET 缺失时由抢到重建互斥键的请求用库内 TOP-N 重建，
     * Redis 完全不可用时回退 selectHotTop 的库内 hot_score 口径）。
     *
     * @param limit 前 N 条（1~20，越界夹取）
     * @return 热门条目列表
     */
    @Override
    public List<HotServiceVO> listHotServices(int limit) {
        int n = limit <= 0 ? HOT_DEFAULT_LIMIT : Math.min(limit, HOT_MAX_LIMIT);
        // 1. 读 ZSET 排名骨架（多取 5 条冗余：榜单里可能残留已下架项目ID，
        //    回查过滤后不足 n 条时按实际可得返回，语义为"榜单快照"）
        List<TypedTuple<String>> ranked = redisCacheUtils.zrevrangeWithScores(
                Constants.CACHE_HOT_SERVICES, Math.min(n + 5, HOT_MAX_LIMIT + 5));
        if (ranked == null) {
            // Redis 不可用：回退库内 hot_score 权威口径（降级，不阻断前台）
            return hotFromDb(n);
        }
        if (ranked.isEmpty()) {
            // 键缺失/空榜单：抢互斥键者回源重建 ZSET，未抢到的本次直接走库内口径
            if (redisCacheUtils.tryMutex(Constants.CACHE_HOT_MUTEX, Constants.MUTEX_TTL_SECONDS)) {
                redisCacheUtils.rebuildZset(Constants.CACHE_HOT_SERVICES, hotTtlMinutes * 60L,
                        this::buildHotMemberScores);
            }
            return hotFromDb(n);
        }
        // 2. 用 ZSET 给出的ID集合回查数据库补全展示字段（只回查上架项目，
        //    下架项目自然退出榜单——上下架无需任何缓存联动失效）
        List<Long> ids = new ArrayList<>();
        for (TypedTuple<String> tuple : ranked) {
            if (tuple.getValue() != null) {
                ids.add(Long.parseLong(tuple.getValue()));
            }
        }
        if (ids.isEmpty()) {
            return hotFromDb(n);
        }
        Map<Long, ServiceItemVO> dict = itemMapper.selectItemsByIds(ids).stream()
                .collect(Collectors.toMap(ServiceItemVO::getId, row -> row, (a, b) -> a));
        // 3. 按 ZSET 排名在内存中重排（数据库 IN 查询返回序不保证），
        //    热度分直接采用 ZSET 分值（与库内列同源，ZINCRBY 已双轨维护）
        List<HotServiceVO> result = new ArrayList<>();
        for (TypedTuple<String> tuple : ranked) {
            if (result.size() >= n) {
                break;
            }
            if (tuple.getValue() == null) {
                continue;
            }
            ServiceItemVO row = dict.get(Long.parseLong(tuple.getValue()));
            if (row == null) {
                // 下架或已删除的项目：自然跳过（读路径自愈，无需清理缓存）
                continue;
            }
            result.add(toHot(row, tuple.getScore() == null ? 0L : tuple.getScore().longValue()));
        }
        // 榜单因下架被过滤到空时回退库内口径，保证前台不会出现空卡片
        return result.isEmpty() ? hotFromDb(n) : result;
    }

    /**
     * 重建热门榜单：以库内 hot_score 为准全量重刷 ZSET。
     * <p>由每日 03:00 的 {@code OrderArchiveTask} 调用，用于校准 ZINCRBY 的长期漂移。
     * 无事务上下文时 rebuildZset 立即执行；即使 Redis 不可用也只 WARN，
     * 下一次读榜单会按"键缺失→互斥重建"的既有路径自愈。</p>
     *
     * @return 写入的榜单成员数
     */
    @Override
    public int refreshHotRanking() {
        Map<String, Double> members = buildHotMemberScores();
        redisCacheUtils.rebuildZset(Constants.CACHE_HOT_SERVICES, hotTtlMinutes * 60L, () -> members);
        log.info("热门榜单全量重建完成: {} 条成员", members.size());
        return members.size();
    }

    /**
     * 新增/修改服务项目：项目名查重、分类存在性、单位白名单、技能词表校验，
     * 全部在事务内完成，保证新项目上架即"可下单且可派单"。
     *
     * @param dto 保存入参
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveItem(ItemSaveDTO dto) {
        boolean isCreate = dto.getId() == null;
        // 项目名排除自身查重（列表页同名会让居民无法区分服务）
        QueryWrapper<ServiceItem> nameWrapper = new QueryWrapper<ServiceItem>()
                .eq("item_name", dto.getItemName());
        if (!isCreate) {
            nameWrapper.ne("id", dto.getId());
        }
        if (itemMapper.selectCount(nameWrapper) > 0) {
            throw new BusinessException(400, "服务项目名称已存在: " + dto.getItemName());
        }
        // 分类存在性校验（库内无物理外键，引用完整性由 Service 层把关防悬挂）
        ServiceCategory category = categoryMapper.selectById(dto.getCategoryId());
        if (category == null) {
            throw new BusinessException(400, "所选服务分类不存在，请重新选择");
        }
        // 计价单位白名单（防止自由文本导致前端"元/次"展示口径混乱）
        String unit = dto.getUnit().trim();
        if (!VALID_UNITS.contains(unit)) {
            throw new BusinessException(400, "计价单位只能是 " + String.join("/", VALID_UNITS)
                    + "，当前提交: " + unit);
        }
        // 技能词表校验：每个所需标签都要有师傅具备，否则该项目永远派不出单
        List<String> required = splitTags(dto.getSkillRequired());
        if (required.isEmpty()) {
            throw new BusinessException(400, "所需技能标签不能为空");
        }
        Set<String> vocab = workerProfileService.listSkillVocabulary();
        List<String> unknown = required.stream().filter(t -> !vocab.contains(t)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException(400, "以下所需技能暂无师傅具备，无法派单，请先补充师傅技能后再保存: "
                    + String.join("、", unknown));
        }
        ServiceItem entity;
        if (isCreate) {
            entity = new ServiceItem();
            entity.setCategoryId(dto.getCategoryId());
            entity.setItemName(dto.getItemName());
            entity.setPrice(dto.getPrice());
            entity.setUnit(unit);
            entity.setSkillRequired(String.join("|", required));
            entity.setDescription(dto.getDescription());
            // 封面由独立上传接口落盘后回写，本表单不接受前端直传路径
            entity.setCoverImg(null);
            // 新项目热度从 0 起算，后续由下单行为驱动
            entity.setHotScore(0);
            entity.setStatus(dto.getStatus() == null ? Constants.ITEM_ON : dto.getStatus());
            entity.setCreateTime(LocalDateTime.now());
            itemMapper.insert(entity);
            log.info("新增服务项目[{}] categoryId={} price={}", entity.getItemName(),
                    entity.getCategoryId(), entity.getPrice());
        } else {
            ServiceItem exist = itemMapper.selectById(dto.getId());
            if (exist == null) {
                throw new NotFoundException("服务项目不存在");
            }
            exist.setCategoryId(dto.getCategoryId());
            exist.setItemName(dto.getItemName());
            exist.setPrice(dto.getPrice());
            exist.setUnit(unit);
            exist.setSkillRequired(String.join("|", required));
            exist.setDescription(dto.getDescription());
            if (dto.getStatus() != null) {
                exist.setStatus(dto.getStatus());
            }
            // hot_score/coverImg 不接受表单覆盖，保持库内现值
            itemMapper.updateById(exist);
            log.info("修改服务项目[{}]", exist.getItemName());
        }
        // 热门榜联动：evict 走事务感知（afterCommit 才删，回滚则不删）。
        // 榜单读路径本可"现查库自愈"，这里主动失效是为了让新建项目在下一次读
        // 时由互斥重建立即进入 ZSET 骨架（否则新项目要等旧 ZSET 自然过期才上榜）
        redisCacheUtils.evict(Constants.CACHE_HOT_SERVICES);
    }

    /**
     * 删除服务项目：被订单引用即禁止物理删除；未被引用时顺带清理封面附件。
     *
     * @param id 项目ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeItem(Long id) {
        ServiceItem exist = itemMapper.selectById(id);
        if (exist == null) {
            throw new NotFoundException("服务项目不存在");
        }
        // 任意状态的订单引用都禁止删除：待派单/在途订单删除项目后将无法派单与追溯价格
        Long orderRefs = orderMapper.selectCount(new QueryWrapper<ServiceOrder>().eq("service_id", id));
        if (orderRefs != null && orderRefs > 0) {
            throw new BusinessException(400, "该项目已被 " + orderRefs + " 条订单引用，不能删除，建议下架以停止接单");
        }
        // 附件清理放在删除成功后：删除失败时项目记录仍在，封面可继续访问
        itemMapper.deleteById(id);
        fileUploadUtils.delete(exist.getCoverImg());
        log.info("删除服务项目[{}]", exist.getItemName());
        // 热门榜联动失效：删除后 ZSET 残留的ID会在读路径被过滤跳过（自愈），
        // 主动失效让榜单少一个空位、其余项目名次立即上移
        redisCacheUtils.evict(Constants.CACHE_HOT_SERVICES);
    }

    /**
     * 上下架切换（运营开关）。
     *
     * @param id     项目ID
     * @param status 目标状态 1/0
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(Long id, Integer status) {
        if (status == null || (status != Constants.ITEM_ON && status != Constants.ITEM_OFF)) {
            throw new BusinessException(400, "上下架状态只能为 1(上架) 或 0(下架)");
        }
        ServiceItem exist = itemMapper.selectById(id);
        if (exist == null) {
            throw new NotFoundException("服务项目不存在");
        }
        ServiceItem update = new ServiceItem();
        update.setId(id);
        update.setStatus(status);
        itemMapper.updateById(update);
        log.info("服务项目[{}]切换为{}", exist.getItemName(), status == Constants.ITEM_ON ? "上架" : "下架");
        // 热门榜联动失效（事务感知）：重新上架的项目可能已不在 ZSET 骨架内，
        // 主动失效让下一次读触发互斥重建，名次与可见性立即一致
        redisCacheUtils.evict(Constants.CACHE_HOT_SERVICES);
    }

    /**
     * 绑定服务项目封面图（独立上传接口落盘后回写，Batch 2 的 ItemSaveDTO
     * 刻意不含 coverImg 字段，封面不随表单单次往返覆盖）。
     * <p>业务规则：项目须真实存在；新路径与旧路径不同时先更新记录再删除旧附件
     * （顺序保证"更新失败则旧附件仍在"）；同一路径重复提交幂等返回。</p>
     *
     * @param itemId     项目ID
     * @param coverPath  附件相对存储路径（FileUploadUtils.save 的返回值）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveCoverImage(Long itemId, String coverPath) {
        ServiceItem exist = itemMapper.selectById(itemId);
        if (exist == null) {
            throw new NotFoundException("服务项目不存在");
        }
        String oldPath = exist.getCoverImg();
        // 同路径重复回写（如管理员重新选择同一文件）：幂等，不做任何写
        if (coverPath != null && coverPath.equals(oldPath)) {
            return;
        }
        ServiceItem update = new ServiceItem();
        update.setId(itemId);
        update.setCoverImg(coverPath);
        itemMapper.updateById(update);
        // 先落库再清旧附件：更新失败时旧封面仍可用，避免"记录已指向不存在文件"
        fileUploadUtils.delete(oldPath);
        log.info("服务项目[{}]封面图已更新为 {}", exist.getItemName(), coverPath);
    }

    /**
     * 取服务项目封面的相对存储路径（图片直读接口按ID定位附件用，404 判定收口在
     * Service 层，Controller 因此无需注入 Mapper）。
     *
     * @param itemId 项目ID
     * @return 封面相对路径
     */
    @Override
    public String getCoverPath(Long itemId) {
        ServiceItem exist = itemMapper.selectById(itemId);
        if (exist == null) {
            throw new NotFoundException("服务项目不存在");
        }
        if (exist.getCoverImg() == null || exist.getCoverImg().isEmpty()) {
            throw new NotFoundException("该服务项目未设置封面图");
        }
        return exist.getCoverImg();
    }

    /**
     * 库内热门口径（Redis 不可用 / 榜单被下架过滤到空时的回退路径）：
     * 直接取上架项目的 hot_score 降序 TOP-N，一条 SQL 直出 categoryName。
     *
     * @param n 前 N 条
     * @return 热门条目列表
     */
    private List<HotServiceVO> hotFromDb(int n) {
        List<ServiceItemVO> rows = itemMapper.selectHotTop(n);
        return rows.stream().map(row -> toHot(row, row.getHotScore() == null ? 0L : row.getHotScore().longValue()))
                .collect(Collectors.toList());
    }

    /**
     * 项目 VO -> 热门榜单条目：只保留榜单展示所需的 6 个字段（前端卡片轻量契约）。
     *
     * @param row   项目 VO（categoryName 已由联表 SQL 直出）
     * @param score 热度分（来自 ZSET 分值或库内列值，两者双轨同源）
     * @return 热门条目
     */
    private HotServiceVO toHot(ServiceItemVO row, long score) {
        HotServiceVO hot = new HotServiceVO();
        hot.setId(row.getId());
        hot.setItemName(row.getItemName());
        hot.setCategoryName(row.getCategoryName());
        hot.setHotScore(score);
        hot.setPrice(row.getPrice());
        hot.setUnit(row.getUnit());
        return hot;
    }

    /**
     * 热门榜 ZSET 重建数据源：库内上架项目 hot_score 降序 TOP-N 转"ID -> 分值"映射。
     * <p>只取上架项目、分值 0 的项目也入榜（新上架项目热度从 0 起算，
     * 入榜后下单即 +1 爬升）；映射容量受 HOT_MAX_LIMIT 约束，避免 ZSET 无界增长。</p>
     *
     * @return 成员（项目ID字符串）与分值映射
     */
    private Map<String, Double> buildHotMemberScores() {
        Map<String, Double> members = new LinkedHashMap<>();
        for (ServiceItemVO row : itemMapper.selectHotTop(HOT_MAX_LIMIT)) {
            members.put(String.valueOf(row.getId()),
                    row.getHotScore() == null ? 0.0 : row.getHotScore().doubleValue());
        }
        return members;
    }

    /**
     * 共用分页逻辑：三视角差异仅由 status 参数表达，技能列表内存补全。
     *
     * @param query      分页入参
     * @param categoryId 分类筛选
     * @param status     状态筛选（null=不限）
     * @return 项目 VO 分页
     */
    private PageVO<ServiceItemVO> doPage(PageQueryDTO query, Long categoryId, Integer status) {
        long pageNum = query == null || query.getPageNum() == null
                ? Constants.DEFAULT_PAGE_NUM : query.getPageNum();
        long pageSize = query == null || query.getPageSize() == null
                ? Constants.DEFAULT_PAGE_SIZE : Math.min(query.getPageSize(), Constants.MAX_PAGE_SIZE);
        Page<ServiceItemVO> page = new Page<>(pageNum, pageSize);
        String kw = normalizeKw(query == null ? null : query.getKeyword());
        IPage<ServiceItemVO> result = itemMapper.selectItemPage(page, kw, categoryId, status);
        result.getRecords().forEach(vo -> vo.setSkillTagsList(splitTags(vo.getSkillRequired())));
        return PageVO.of(result);
    }

    /**
     | 分隔标签串拆分为去空白去重的标签列表。
     *
     * @param raw 原始标签串（可空）
     * @return 标签列表
     */
    private List<String> splitTags(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return List.of();
        }
        return Arrays.stream(raw.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 关键字规范化：去空白，空串归一为 null。
     *
     * @param kw 原始关键字
     * @return 规范化关键字或 null
     */
    private String normalizeKw(String kw) {
        if (kw == null) {
            return null;
        }
        String trimmed = kw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
