package com.community.booking.utils;

import com.community.booking.config.RedisConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Redis 缓存与分布式原语工具（Batch 3 统一入口，配合 Constants 中的契约键使用）。
 * <p>四个关键防护设计：</p>
 * <ol>
 *   <li><b>缓存穿透防护</b>：空结果（null/空列表）也短暂缓存 __NULL__ 占位符
 *       （TTL 60 秒），同一无效查询在窗口内不再反复打数据库；</li>
 *   <li><b>缓存击穿防护</b>：热键失效重建提供 SETNX 互斥锁（tryMutex），
 *       同一时刻仅持锁者回源重建，其余请求直接回源读取不加写放大；</li>
 *   <li><b>事务感知失效</b>：写路径的失效一律注册到事务 afterCommit 回调——
 *       避免"先删缓存、事务随后回滚"导致旧数据回填；无事务上下文时立即执行；</li>
 *   <li><b>故障降级</b>：全部 Redis 读写 try-catch，连接异常仅 WARN 并回源
 *       数据库——缓存是加速器不是依赖项，Redis 不可用绝不能阻断业务主流程。</li>
 * </ol>
 * <p>value 为手动 JSON 字符串（StringRedisTemplate + 缓存专用 ObjectMapper），
 * 空值占位符保持为可直读的字面量 __NULL__；类型信息随 JSON 属性写入，
 * 读回还原为具体 VO 类型（见 RedisConfig）。同时封装 ZSET 热度榜、
 * 分布式锁（community:order:lock:{orderNo}）与限流计数（@RateLimit 切面）
 * 三类原语，Batch 4 的状态机幂等直接复用本类的 tryLock/unlock。</p>
 *
 * @author community-booking
 */
@Slf4j
// Spring 组件，供 Service 层与切面构造器注入
@Component
public class RedisCacheUtils {

    /** 解锁脚本（GET 比对持有者后 DEL）：静态常量避免每次调用重复构造与 SHA 重算 */
    private static final org.springframework.data.redis.core.script.RedisScript<Long> UNLOCK_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) "
                            + "else return 0 end",
                    Long.class);

    /** 限流计数脚本（INCR + 首次设 TTL 原子完成）：避免计数后崩溃导致键永不过期 */
    private static final org.springframework.data.redis.core.script.RedisScript<Long> RATELIMIT_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local c = redis.call('INCR', KEYS[1]) "
                            + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end return c",
                    Long.class);

    /**
     * 序列自增脚本（"就绪才发号"）：KEYS[1]=序列键，KEYS[2]=就绪标记键，ARGV[1]=TTL。
     * <p>就绪标记不存在时直接返回 -1，表示序列尚未完成播种校正，调用方必须先去
     * 播种、不得发号。这样把"基线校正"与"发号"严格串行化：任何线程都不可能
     * 在基线抬升之前拿到 1、2、3 这类小流水（否则会与库里已有订单号正面相撞）。</p>
     */
    private static final org.springframework.data.redis.core.script.RedisScript<Long> SEQ_INCR_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('EXISTS', KEYS[2]) == 0 then return -1 end "
                            + "local v = redis.call('INCR', KEYS[1]) "
                            + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                            + "redis.call('EXPIRE', KEYS[2], ARGV[1]) "
                            + "return v",
                    Long.class);

    /**
     * 序列播种脚本：KEYS[1]=序列键，KEYS[2]=就绪标记键，ARGV[1]=基线值，ARGV[2]=TTL。
     * <p>两步写入必须原子，否则可能出现"标记已就绪但序列还是旧值"的中间态，
     * 让并发线程读到未校正的序列。</p>
     */
    private static final org.springframework.data.redis.core.script.RedisScript<Long> SEQ_SEED_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) "
                            + "redis.call('SET', KEYS[2], '1', 'EX', ARGV[2]) "
                            + "return 1",
                    Long.class);

    /** String 序列化模板（Boot 自动装配，key/value 均为 String） */
    private final StringRedisTemplate redisTemplate;

    /** 缓存专用序列化器（开启类型标记，与 Web 层 ObjectMapper 隔离） */
    private final ObjectMapper cacheObjectMapper;

    /**
     * 构造注入：显式 @Qualifier 锁定缓存专用 Mapper（RedisConfig 定义），
     * 避免与 Web 层 @Primary Mapper 的注入歧义。
     *
     * @param redisTemplate     String 序列化模板
     * @param cacheObjectMapper 缓存专用 ObjectMapper（Bean 名 cacheObjectMapper）
     */
    public RedisCacheUtils(StringRedisTemplate redisTemplate,
                           @Qualifier(RedisConfig.BEAN_CACHE_OBJECT_MAPPER) ObjectMapper cacheObjectMapper) {
        this.redisTemplate = redisTemplate;
        this.cacheObjectMapper = cacheObjectMapper;
    }

    /**
     * 读缓存列表：命中返回；未命中回源 loader；空结果写 __NULL__ 防穿透。
     * <p>调用方契约：loader 返回 null 或空列表时本方法返回空列表（而非 null），
     * 保证"空列表是合法业务结果"的语义不变。</p>
     *
     * @param key     完整缓存键（由调用方按 Constants 契约前缀拼装）
     * @param ttl     正常结果过期秒数
     * @param typeRef 列表元素类型引用
     * @param loader  未命中时的回源加载器
     * @param <T>     列表元素类型
     * @return 缓存命中或回源得到的列表；回源为空时为空列表
     */
    public <T> List<T> listOrLoad(String key, long ttl, TypeReference<List<T>> typeRef, Supplier<List<T>> loader) {
        // 1. 尝试读缓存（读失败降级回源，不阻断业务）
        String raw = safeGet(key);
        if (raw != null) {
            if (Constants.CACHE_NULL_VALUE.equals(raw)) {
                // 空值占位命中：直接返回空列表（穿透防护生效，不打库）
                return new ArrayList<>();
            }
            try {
                List<T> cached = cacheObjectMapper.readValue(raw, typeRef);
                log.debug("缓存命中: key={}", key);
                return cached;
            } catch (Exception e) {
                // 脏数据/历史格式：删除坏键后回源重建
                log.warn("缓存反序列化失败，删除坏键回源: key={} - {}", key, e.getMessage());
                safeDelete(key);
            }
        }
        // 2. 回源加载
        List<T> loaded = loader.get();
        // 3. 写缓存：空结果写 __NULL__（短 TTL），非空写 JSON（正常 TTL）
        if (loaded == null || loaded.isEmpty()) {
            safeSet(key, Constants.CACHE_NULL_VALUE, Constants.CACHE_TTL_NULL_SECONDS);
            return loaded == null ? new ArrayList<>() : loaded;
        }
        try {
            safeSet(key, cacheObjectMapper.writeValueAsString(loaded), ttl);
        } catch (Exception e) {
            log.warn("缓存序列化失败（本次不缓存）: key={} - {}", key, e.getMessage());
        }
        return loaded;
    }

    /**
     * 读缓存对象：命中返回；未命中回源 loader；null 结果可写 __NULL__ 防穿透。
     *
     * @param key       完整缓存键
     * @param ttl       正常结果过期秒数
     * @param typeRef   目标类型引用
     * @param loader    未命中时的回源加载器
     * @param cacheNull 是否缓存 null 结果（业务上"不存在"需防穿透时传 true）
     * @param <T>       目标类型
     * @return 缓存命中或回源结果；回源为 null 时返回 null
     */
    public <T> T objectOrLoad(String key, long ttl, TypeReference<T> typeRef, Supplier<T> loader, boolean cacheNull) {
        String raw = safeGet(key);
        if (raw != null) {
            if (Constants.CACHE_NULL_VALUE.equals(raw)) {
                return null;
            }
            try {
                T cached = cacheObjectMapper.readValue(raw, typeRef);
                log.debug("缓存命中: key={}", key);
                return cached;
            } catch (Exception e) {
                log.warn("缓存反序列化失败，删除坏键回源: key={} - {}", key, e.getMessage());
                safeDelete(key);
            }
        }
        T loaded = loader.get();
        if (loaded == null) {
            if (cacheNull) {
                safeSet(key, Constants.CACHE_NULL_VALUE, Constants.CACHE_TTL_NULL_SECONDS);
            }
            return null;
        }
        try {
            safeSet(key, cacheObjectMapper.writeValueAsString(loaded), ttl);
        } catch (Exception e) {
            log.warn("缓存序列化失败（本次不缓存）: key={} - {}", key, e.getMessage());
        }
        return loaded;
    }

    /**
     * 失效单个缓存键（事务感知：注册到 afterCommit，提交后才真正删除）。
     *
     * @param key 完整缓存键
     */
    public void evict(String key) {
        afterCommitOrNow(() -> safeDelete(key));
    }

    /**
     * 读缓存列表（带热键重建互斥，防缓存击穿）：命中返回；未命中时——
     * 抢到重建锁的请求回源并写缓存，未抢到的请求仅回源读库、不写缓存。
     * <p>与 listOrLoad 的差异在于"惊群收敛"：缓存键失效的瞬间，同千万并发
     * 只有一条 SQL 回源，其余请求走各自的回源读（无写放大），
     * 读多写少的目录类接口因此不会在 TTL 到期时打穿数据库。</p>
     *
     * @param key       完整缓存键
     * @param ttl       正常结果过期秒数（由调用方按 community.cache.* 配置换算）
     * @param mutexKey  重建互斥键（Constants.CACHE_*_MUTEX）
     * @param mutexTtl  互斥锁持有秒数（Constants.MUTEX_TTL_SECONDS）
     * @param typeRef   列表元素类型引用
     * @param loader    回源加载器
     * @param <T>       列表元素类型
     * @return 缓存命中或回源得到的列表；回源为空时为空列表
     */
    public <T> List<T> listOrLoadWithMutex(String key, long ttl, String mutexKey, long mutexTtl,
                                           TypeReference<List<T>> typeRef, Supplier<List<T>> loader) {
        String raw = safeGet(key);
        if (raw != null) {
            if (Constants.CACHE_NULL_VALUE.equals(raw)) {
                // 空值占位命中（防穿透）：直接返回空列表，不打库
                return new ArrayList<>();
            }
            try {
                List<T> cached = cacheObjectMapper.readValue(raw, typeRef);
                log.debug("缓存命中: key={}", key);
                return cached;
            } catch (Exception e) {
                log.warn("缓存反序列化失败，删除坏键回源: key={} - {}", key, e.getMessage());
                safeDelete(key);
            }
        }
        // 2. 未命中：抢到重建锁则回源+写缓存，否则只回源读（不写，避免并发写放大）
        boolean holder = tryMutex(mutexKey, mutexTtl);
        List<T> loaded = loader.get();
        if (!holder) {
            // 已有他人在重建本键：本次直接返回回源结果，不写缓存（由持锁者写入）
            log.debug("缓存重建互斥未抢到，本次仅回源读: key={}", key);
            return loaded == null ? new ArrayList<>() : loaded;
        }
        try {
            if (loaded == null || loaded.isEmpty()) {
                safeSet(key, Constants.CACHE_NULL_VALUE, Constants.CACHE_TTL_NULL_SECONDS);
                return loaded == null ? new ArrayList<>() : loaded;
            }
            safeSet(key, cacheObjectMapper.writeValueAsString(loaded), ttl);
        } catch (Exception e) {
            log.warn("缓存序列化失败（本次不缓存）: key={} - {}", key, e.getMessage());
        } finally {
            // 主动释放互斥锁：让下一次失效可立即重建（失败时 TTL 兜底）
            releaseMutex(mutexKey);
        }
        return loaded;
    }

    /**
     * 按键模式批量失效（SCAN 游标遍历，避免 KEYS 阻塞 Redis；事务感知）。
     *
     * @param pattern Redis 键模式（如 community:ratelimit:order:* ）
     */
    public void evictByPattern(String pattern) {
        afterCommitOrNow(() -> safeScanDelete(pattern));
    }

    /**
     * 热键重建互斥锁（防缓存击穿）：SETNX + TTL，抢到返回 true。
     * <p>未抢到锁的调用方应直接回源读库（不写缓存），避免并发下同一热键
     * 被多路请求同时重建造成写放大与惊群。</p>
     *
     * @param key      互斥键（Constants.CACHE_*_MUTEX）
     * @param ttl      锁持有秒数（建议 5~10 秒，覆盖一次回源耗时）
     * @return true=抢到重建权；false=已有他人在重建
     */
    public boolean tryMutex(String key, long ttl) {
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(ttl));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            // Redis 故障时"视为抢到锁"：让调用方照常回源，保证降级可用
            log.warn("Redis 互斥锁读取失败（视为抢到）: key={} - {}", key, e.getMessage());
            return true;
        }
    }

    /**
     * 释放互斥锁（仅删自己抢到的键，失败仅 WARN，TTL 兜底自动过期）。
     *
     * @param key 互斥键
     */
    public void releaseMutex(String key) {
        safeDelete(key);
    }

    /**
     * 有序集合按分值降序取前 N 条成员（热门榜单 ZREVRANGE）。
     *
     * @param key   ZSET 键
     * @param limit 前 N 条
     * @return 成员集合（降序）；Redis 不可用时返回 null 表示"需回退数据库口径"
     */
    public Set<String> zrevrange(String key, int limit) {
        try {
            Set<String> members = redisTemplate.opsForZSet()
                    .reverseRange(key, 0, Math.max(0, limit - 1));
            // 键存在但为空集合返回空 Set（与"Redis 不可用"的 null 区分开）
            return members == null ? new java.util.LinkedHashSet<>() : members;
        } catch (Exception e) {
            log.warn("Redis ZSET 读失败（回退数据库口径）: key={} - {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 有序集合成员 +score（ZREVRANGE WITHSCORES，一次取回分值，供热门榜直接展示）。
     *
     * @param key   ZSET 键
     * @param limit 前 N 条
     * @return 带分值的元组列表（降序）；Redis 不可用时返回 null
     */
    public List<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> zrevrangeWithScores(
            String key, int limit) {
        try {
            Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> tuples =
                    redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, Math.max(0, limit - 1));
            if (tuples == null) {
                return new ArrayList<>();
            }
            // reverseRangeWithScores 返回的 LinkedHashSet 已按 score 降序，保持原序
            return new ArrayList<>(tuples);
        } catch (Exception e) {
            log.warn("Redis ZSET 读失败（回退数据库口径）: key={} - {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 热度分原子 +1（ZINCRBY）：下单成功后调用，失败仅 WARN（DB hot_score 列
     * 仍是权威口径，榜单由互斥重建任务/下次失效回源自然校准）。
     *
     * @param key    ZSET 键
     * @param member 成员（项目ID字符串）
     * @param delta  增量
     */
    public void zincrby(String key, String member, double delta) {
        try {
            redisTemplate.opsForZSet().incrementScore(key, member, delta);
        } catch (Exception e) {
            log.warn("Redis ZINCRBY 失败（热度以库内列为准）: key={} member={} - {}",
                    key, member, e.getMessage());
        }
    }

    /**
     * 清空重建 ZSET（管道单次操作：删旧键 + 批量 ZADD + 设 TTL），事务感知 afterCommit。
     * <p>memberScoreProvider 返回"成员 -> 分值"的映射，顺序无关（ZSET 自排序）；
     * 空映射时退化为删除键（下一次读会触发重建，语义与"无数据"一致）。</p>
     *
     * @param key                  ZSET 键
     * @param ttl                  整体过期秒数（下次重建前有效）
     * @param memberScoreProvider  成员与分值来源
     */
    public void rebuildZset(String key, long ttl, Supplier<java.util.Map<String, Double>> memberScoreProvider) {
        afterCommitOrNow(() -> {
            try {
                java.util.Map<String, Double> members = memberScoreProvider.get();
                if (members == null || members.isEmpty()) {
                    safeDelete(key);
                    return;
                }
                redisTemplate.executePipelined((RedisCallback<Void>) connection -> {
                    connection.del(key.getBytes());
                    for (var entry : members.entrySet()) {
                        connection.zAdd(key.getBytes(), entry.getValue(), entry.getKey().getBytes());
                    }
                    connection.expire(key.getBytes(), ttl);
                    return null;
                });
                log.info("热门榜单 ZSET 重建完成: key={} size={}", key, members.size());
            } catch (Exception e) {
                log.warn("Redis ZSET 重建失败（榜单回退数据库口径）: key={} - {}", key, e.getMessage());
            }
        });
    }

    /**
     * 尝试获取分布式锁（SET key value NX EX）：订单状态机幂等第一道防线。
     *
     * @param key        锁键（Constants.ORDER_LOCK_PREFIX + orderNo）
     * @param value      锁值（持有者标识，解锁时比对，防误删他人锁）
     * @param ttlSeconds 持有超时秒数（配置 community.order.lock-seconds）
     * @return true=加锁成功；false=锁被占用（调用方应放弃本次状态推进）；
     *         Redis 不可用时返回 true（降级放行，由条件 UPDATE 兜底幂等）
     */
    public boolean tryLock(String key, String value, long ttlSeconds) {
        try {
            Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, value, Duration.ofSeconds(ttlSeconds));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            // 降级：Redis 故障时放行，状态机幂等由 Service 的条件 UPDATE 兜底
            log.warn("Redis 加锁失败（降级放行，依赖条件 UPDATE 保证幂等）: key={} - {}", key, e.getMessage());
            return true;
        }
    }

    /**
     * 释放分布式锁（Lua 脚本 GET 比对后 DEL，保证只释放自己持有的锁）。
     *
     * @param key   锁键
     * @param value 加锁时的持有者标识
     */
    public void unlock(String key, String value) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), value);
        } catch (Exception e) {
            log.warn("Redis 解锁失败（锁将随 TTL 自动过期）: key={} - {}", key, e.getMessage());
        }
    }

    /**
     * 固定窗口计数（@RateLimit 切面使用）：INCR 后仅在首次计数时设置窗口 TTL。
     * <p>实现细节：用 Lua 保证"计数 + 设 TTL"原子性，避免 INCR 后进程崩溃
     * 导致键永不过期、用户被永久挡住。</p>
     *
     * @param key    计数键（Constants.RATE_LIMIT_PREFIX + 方法 + userId）
     * @param window 窗口秒数
     * @return 当前窗口内的累计次数；Redis 不可用时返回 1（降级放行）
     */
    public long incrAndExpire(String key, long window) {
        try {
            Long count = redisTemplate.execute(RATELIMIT_SCRIPT, List.of(key), String.valueOf(window));
            return count == null ? 1L : count;
        } catch (Exception e) {
            log.warn("Redis 限流计数失败（降级放行）: key={} - {}", key, e.getMessage());
            return 1L;
        }
    }

    /**
     * 序列自增（"就绪才发号"契约）：就绪标记不存在时返回 -1，调用方须先播种。
     * <p>Redis 不可用时降级返回 -1（同样视为未就绪），迫使调用方走播种分支；
     * 播种失败则整体报系统繁忙，绝不退化成"凭小流水发号"。</p>
     *
     * @param seqKey   序列键
     * @param readyKey 就绪标记键
     * @param ttl      键过期秒数
     * @return 自增后的序列值；未就绪返回 -1
     */
    public long incrSeqIfReady(String seqKey, String readyKey, long ttl) {
        try {
            Long v = redisTemplate.execute(SEQ_INCR_SCRIPT, List.of(seqKey, readyKey), String.valueOf(ttl));
            return v == null ? -1L : v;
        } catch (Exception e) {
            log.warn("Redis 序列自增失败（视为未就绪）: key={} - {}", seqKey, e.getMessage());
            return -1L;
        }
    }

    /**
     * 序列播种：把序列直接置为基线值并打上就绪标记，两步原子写入。
     *
     * @param seqKey   序列键
     * @param readyKey 就绪标记键
     * @param baseline 基线值（DB 当日已有订单数，保证后续流水严格大于它）
     * @param ttl      键过期秒数
     * @return true=播种成功；false=Redis 故障
     */
    public boolean seedSeq(String seqKey, String readyKey, long baseline, long ttl) {
        try {
            redisTemplate.execute(SEQ_SEED_SCRIPT, List.of(seqKey, readyKey),
                    String.valueOf(baseline), String.valueOf(ttl));
            return true;
        } catch (Exception e) {
            log.warn("Redis 序列播种失败: key={} - {}", seqKey, e.getMessage());
            return false;
        }
    }

    /**
     * 在事务提交后执行副作用（公开入口：供 Service 层把 ZINCRBY 等"只增计数"
     * 一类的缓存副作用挂到 afterCommit，避免事务回滚后缓存凭空偏差）。
     * <p>当前无事务同步上下文时立即执行；动作内部应自行做异常防护。</p>
     *
     * @param action 提交后执行的动作
     */
    public void afterCommit(Runnable action) {
        afterCommitOrNow(action);
    }

    /**
     * 在事务结束后释放资源（提交与回滚都执行，afterCompletion 语义）：
     * 专用于分布式锁的解锁时机——锁必须活到事务结束（提交或回滚）那一刻，
     * 否则锁会在事务提交前释放，让并发请求读到未提交数据后误判状态。
     * <p>无事务上下文时立即执行；进程崩溃来不及执行时由锁的 TTL 兜底过期。</p>
     *
     * @param action 事务结束后执行的动作（如 unlock）
     */
    public void afterRelease(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    // 提交(STATUS_COMMITTED)与回滚都释放，避免回滚把锁滞留到 TTL 到期
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * 在事务提交后执行失效；当前无事务同步时立即执行（读路径场景）。
     *
     * @param action 失效/重建动作（内部已做异常防护）
     */
    private void afterCommitOrNow(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 写路径在 @Transactional 内调用：等数据真正落库后才操作缓存，
            // 回滚则不操作（旧缓存仍然有效，天然正确）
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * 安全读：Redis 异常时返回 null（视为未命中，降级回源）。
     *
     * @param key 缓存键
     * @return 缓存原始字符串；未命中或读失败返回 null
     */
    private String safeGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis 读失败（降级回源）: key={} - {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * 安全写：失败仅 WARN，不阻断业务。
     *
     * @param key   缓存键
     * @param value 缓存值（JSON 或 __NULL__）
     * @param ttl   过期秒数
     */
    private void safeSet(String key, String value, long ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttl));
        } catch (Exception e) {
            log.warn("Redis 写失败（本次不缓存）: key={} - {}", key, e.getMessage());
        }
    }

    /**
     * 安全删除：失败仅 WARN（TTL 到期后键自然过期，影响有限）。
     *
     * @param key 缓存键
     */
    private void safeDelete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis 删除失败（等待 TTL 自然过期）: key={} - {}", key, e.getMessage());
        }
    }

    /**
     * SCAN 游标批量删除：count 200 分批逐条删除，游标自动归还连接。
     *
     * @param pattern 键模式
     */
    private void safeScanDelete(String pattern) {
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
                // Cursor 实现 Closeable，try-with-resources 保证游标归还
                try (var cursor = connection.scan(ScanOptions.scanOptions().match(pattern).count(200).build())) {
                    while (cursor.hasNext()) {
                        connection.del(cursor.next());
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("Redis 批量删除失败（等待 TTL 自然过期）: pattern={} - {}", pattern, e.getMessage());
        }
    }
}
