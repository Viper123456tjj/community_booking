package com.community.booking.utils;

/**
 * 全局常量集中定义：角色、订单状态机、派单方式、师傅接单状态、
 * 缓存键契约、MQ 拓扑名称、分页边界。
 * <p>集中管理避免魔法字符串散落各层；MQ 名称在 Batch 4 的 config/mq 包中
 * 声明队列交换机时直接复用这里的常量，保证拓扑一致；Redis 键前缀与
 * application.yml 的 community.* 契约配置呼应。</p>
 *
 * @author community-booking
 */
public final class Constants {

    /** 工具类常量集合，禁止实例化 */
    private Constants() {
    }

    // ==================== 角色（与 Spring Security GrantedAuthority 一致） ====================
    /** 社区居民角色：浏览服务、下单预约、取消未派单订单、评价 */
    public static final String ROLE_RESIDENT = "ROLE_RESIDENT";
    /** 便民服务人员角色：维护技能/接单状态、推进工单状态 */
    public static final String ROLE_WORKER = "ROLE_WORKER";
    /** 物业系统管理员角色：分类与项目管理、师傅管理、派单中心、运营看板 */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    // ==================== 账号状态（sys_user.status） ====================
    /** 账号正常（可登录、可被派单） */
    public static final int USER_STATUS_NORMAL = 1;
    /** 账号禁用（登录被拦截，且不得作为派单候选） */
    public static final int USER_STATUS_DISABLED = 0;

    // ==================== 师傅接单状态（worker_profile.work_status） ====================
    /** 休息中：不接受任何新派单 */
    public static final int WORK_REST = 0;
    /** 空闲：可接受智能/手动派单 */
    public static final int WORK_IDLE = 1;
    /** 忙碌：已持有已派单或服务中工单，不再作为派单候选 */
    public static final int WORK_BUSY = 2;

    // ==================== 服务项目状态（service_item.status） ====================
    /** 下架：居民端不可见、不可下单 */
    public static final int ITEM_OFF = 0;
    /** 上架：居民端可见、可下单 */
    public static final int ITEM_ON = 1;

    // ==================== 订单状态机（service_order.order_status） ====================
    /** 0 待派单：下单成功后的初始态，仅此刻可取消 */
    public static final int ORDER_PENDING = 0;
    /** 1 已派单：已指派师傅但尚未开始服务，管理员可改派 */
    public static final int ORDER_DISPATCHED = 1;
    /** 2 服务中：师傅已上门开工，仅师傅本人可推进完成 */
    public static final int ORDER_IN_SERVICE = 2;
    /** 3 已完成：可评价窗口开启（完成且未归档的 7 天内） */
    public static final int ORDER_COMPLETED = 3;
    /** 4 已取消：居民主动取消或 30 分钟超时未派单被死信消费者自动取消 */
    public static final int ORDER_CANCELLED = 4;

    // ==================== 派单方式（service_order.dispatch_type） ====================
    /** 智能派单：多维权重评分算法自动匹配最优师傅 */
    public static final String DISPATCH_AUTO = "AUTO";
    /** 手动派单：管理员在派单中心人工指派 */
    public static final String DISPATCH_MANUAL = "MANUAL";
    /** 改派：已派单未开工的订单更换承接师傅 */
    public static final String DISPATCH_REASSIGN = "REASSIGN";

    // ==================== 订单归档标记（service_order.is_archived） ====================
    /** 正常（未归档）：在列表与运营看板统计范围内 */
    public static final int NOT_ARCHIVED = 0;
    /** 已归档：完成超 7 天由每日 03:00 定时任务置 1，退出常规列表 */
    public static final int ARCHIVED = 1;

    // ==================== 评价星级边界（service_evaluation.star） ====================
    /** 最低 1 星 */
    public static final int MIN_STAR = 1;
    /** 最高 5 星 */
    public static final int MAX_STAR = 5;

    // ==================== 分页边界 ====================
    /** 默认页码（从 1 开始） */
    public static final long DEFAULT_PAGE_NUM = 1L;
    /** 默认每页条数 */
    public static final long DEFAULT_PAGE_SIZE = 10L;
    /** 每页最大条数（防止前端传入超大 pageSize 拖垮数据库） */
    public static final long MAX_PAGE_SIZE = 100L;

    // ==================== Redis 缓存键契约（与计划书一致） ====================
    /** 服务分类列表缓存键：community:service:categories（TTL 见 yml community.cache.category-ttl-minutes） */
    public static final String CACHE_CATEGORY_LIST = "community:service:categories";
    /** 热门服务榜单缓存键：community:service:hot（写路径下单 ZINCRBY + 定时重建兜底） */
    public static final String CACHE_HOT_SERVICES = "community:service:hot";
    /** 订单状态机分布式锁前缀：community:order:lock:{orderNo}（状态机幂等控制） */
    public static final String ORDER_LOCK_PREFIX = "community:order:lock:";
    /** @RateLimit 限流计数键前缀：community:ratelimit:{方法}:{userId} */
    public static final String RATE_LIMIT_PREFIX = "community:ratelimit:";
    /** 缓存空值占位符（防缓存穿透：空结果也短暂缓存该标记） */
    public static final String CACHE_NULL_VALUE = "__NULL__";
    /** 空值占位缓存 TTL：60 秒（防穿透又不长期占用内存） */
    public static final long CACHE_TTL_NULL_SECONDS = 60L;
    /** 分类列表重建互斥键（防缓存击穿：同一时刻只允许一个请求回源写缓存） */
    public static final String CACHE_CATEGORY_MUTEX = "community:service:categories:mutex";
    /** 热门榜单重建互斥键（防缓存击穿：ZSET 缺失时仅持锁者回源重建） */
    public static final String CACHE_HOT_MUTEX = "community:service:hot:mutex";
    /** 互斥锁持有秒数（覆盖一次回源+写缓存耗时，Redis 故障时 TTL 兜底自动释放） */
    public static final long MUTEX_TTL_SECONDS = 10L;
    /** 订单状态机分布式锁持有者标识前缀（配合 UUID 保证 unlock 只释放自己的锁） */
    public static final String ORDER_LOCK_OWNER_PREFIX = "wostate-";

    // ==================== 业务默认值 ====================
    /** 新建师傅档案的默认技能标签（必须是服务技能词表内已有标签，保证数据自洽） */
    public static final String DEFAULT_WORKER_SKILLS = "水电维修|家电维修";
    /** 订单号前缀：WO + yyyyMMdd + 4 位当日流水（如 WO20261111000015） */
    public static final String ORDER_NO_PREFIX = "WO";
    /** 订单号当日流水序列键前缀（Redis INCR 原子自增，避免 COUNT+1 并发撞号） */
    public static final String ORDER_SEQ_KEY_PREFIX = "community:order:seq:";
    /** 订单号流水序列键的 TTL（秒）：2 天足够跨天切换，过期自动回收不占内存 */
    public static final long ORDER_SEQ_TTL_SECONDS = 172800L;
    /** 订单号序列冷启动播种互斥锁前缀（SETNX 保证只有一个线程做 DB 基线校正） */
    public static final String ORDER_SEQ_SEED_LOCK_PREFIX = "community:order:seq:seed:lock:";
    /** 订单号序列就绪标记前缀（存在即代表序列已完成 DB 基线校正，可以发号） */
    public static final String ORDER_SEQ_READY_PREFIX = "community:order:seq:ready:";

    // ==================== RabbitMQ 拓扑（Batch 4 声明时复用） ====================
    /** 业务直连交换机：承载异步通知与绩效重算两类路由 */
    public static final String EXCHANGE_APPOINT = "appoint.direct.exchange";
    /** 异步通知队列：派单成功/服务完成/订单取消邮件与站内提醒 */
    public static final String QUEUE_NOTIFY = "appoint.notify.queue";
    /** 绩效重算队列：评价提交后重算师傅平均星级与运营看板指标 */
    public static final String QUEUE_EVAL = "appoint.eval.queue";
    /** 通知路由键 */
    public static final String ROUTING_NOTIFY = "appoint.notify";
    /** 绩效重算路由键 */
    public static final String ROUTING_EVAL = "appoint.eval";
    /** 业务死信交换机：消费重试耗尽的消息进入 DLX 人工排查 */
    public static final String EXCHANGE_APPOINT_DLX = "appoint.dlx.exchange";
    /** 通知死信队列 */
    public static final String QUEUE_NOTIFY_DLQ = "appoint.notify.dlq";
    /** 绩效重算死信队列 */
    public static final String QUEUE_EVAL_DLQ = "appoint.eval.dlq";
    /** 通知死信路由键 */
    public static final String ROUTING_NOTIFY_DEAD = "appoint.notify.dead";
    /** 绩效重算死信路由键 */
    public static final String ROUTING_EVAL_DEAD = "appoint.eval.dead";
    /** 延迟队列死信交换机：TTL 到期消息唯一出口，指向超时取消队列 */
    public static final String EXCHANGE_DELAY_DLX = "appoint.delay.dlx.exchange";
    /** 预约延迟队列（TTL 30 分钟，无消费者，到期经 DLX 转发） */
    public static final String QUEUE_DELAY = "appoint.delay.queue";
    /** 超时自动取消队列：消费待派单超时订单并幂等置为已取消 */
    public static final String QUEUE_CANCEL = "appoint.cancel.queue";
    /** 超时取消路由键 */
    public static final String ROUTING_CANCEL = "appoint.cancel";
    /** 延迟队列投递路由键：下单后把待派单订单投递到 appoint.delay.queue（TTL 到期经 DLX 进入取消队列） */
    public static final String ROUTING_DELAY = "appoint.delay";
    /** 超时取消死信队列：取消消费者处理异常时的兜底落点，供人工排查后重投 */
    public static final String QUEUE_CANCEL_DLQ = "appoint.cancel.dlq";
    /** 超时取消死信路由键 */
    public static final String ROUTING_CANCEL_DEAD = "appoint.cancel.dead";
}
