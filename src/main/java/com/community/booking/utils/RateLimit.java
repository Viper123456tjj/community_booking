package com.community.booking.utils;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解（@RateLimit）：标注在 Controller 方法上，由 RateLimitAspect
 * 以 Redis 计数器实现固定窗口限流。
 * <p>本系统的契约场景：限制居民 1 分钟内最多提交 3 次预约（community.ratelimit.*
 * 配置项提供默认阈值）。计数键为 community:ratelimit:{方法标识}:{userId}，
 * 首次计数时同时设置窗口 TTL，窗口内累加超过阈值即抛 400 业务异常。</p>
 * <p>降级契约：Redis 不可用时切面放行请求并 WARN——限流是过载保护而非业务依赖，
 * 缓存故障不得把正常用户挡在门外（数据库仍有下单幂等与状态机护栏）。</p>
 *
 * @author community-booking
 */
// 仅可用于方法
@Target(ElementType.METHOD)
// 运行期可见（切面依赖反射读取注解参数）
@Retention(RetentionPolicy.RUNTIME)
// 参与文档生成
@Documented
public @interface RateLimit {

    /**
     * 限流键的方法段（如 "order:create"），用于区分同一用户的不同接口计数。
     *
     * @return 限流资源标识
     */
    String value();

    /**
     * 窗口内最大允许次数（<=0 时取配置 community.ratelimit.max-times 的默认值）。
     *
     * @return 最大次数
     */
    int maxTimes() default -1;

    /**
     * 计数窗口秒数（<=0 时取配置 community.ratelimit.window-seconds 的默认值）。
     *
     * @return 窗口秒数
     */
    int windowSeconds() default -1;

    /**
     * 超限时的中文提示文案。
     *
     * @return 提示文案
     */
    String message() default "操作过于频繁，请稍后再试";
}
