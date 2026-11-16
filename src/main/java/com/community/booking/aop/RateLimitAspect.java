package com.community.booking.aop;

import com.community.booking.dto.LoginUserDTO;
import com.community.booking.exception.BusinessException;
import com.community.booking.utils.Constants;
import com.community.booking.utils.RateLimit;
import com.community.booking.utils.RedisCacheUtils;
import com.community.booking.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * 接口限流切面（Batch 3）：拦截标注 @RateLimit 的 Controller 方法，
 * 以 Redis 固定窗口计数器实现"居民 60 秒内最多提交 3 次预约"的过载保护。
 * <p>执行时序：@Around 前置计数 -> 超限直接抛 400 业务异常（不进入方法体，
 * 数据库零消耗）-> 未超限放行。计数与窗口 TTL 由 Lua 脚本原子完成
 * （INCR + 首次 EXPIRE），不会出现"计数后进程崩溃导致键永不过期、用户被
 * 永久挡住"的悬空键。</p>
 * <p>计数主体：优先取登录用户ID（同一用户跨设备/跨标签页共享额度）；
 * 极端场景下（如限流注解决定加在匿名接口上）SecurityUtils 抛 401 时回退为
 * 客户端 IP 段，保证限流能力不因取不到身份而失效。</p>
 * <p>降级契约：RedisCacheUtils.incrAndExpire 在 Redis 故障时返回 1（视为
 * 首次计数放行）——限流是护栏而非业务依赖，缓存故障不得把正常用户挡在门外。</p>
 *
 * @author community-booking
 */
@Slf4j
// AOP 切面：由 spring-boot-starter-aop（pom 已引入）自动织入
@Aspect
// Spring 组件，构造注入 RedisCacheUtils
@Component
// 构造器注入限流原语工具
@RequiredArgsConstructor
public class RateLimitAspect {

    /** Redis 计数与原子过期原语（Lua 保证计数+设TTL不可分割） */
    private final RedisCacheUtils redisCacheUtils;

    /** 默认窗口内最大次数（配置 community.ratelimit.max-times，契约值 3） */
    @Value("${community.ratelimit.max-times:3}")
    private int defaultMaxTimes;

    /** 默认计数窗口秒数（配置 community.ratelimit.window-seconds，契约值 60） */
    @Value("${community.ratelimit.window-seconds:60}")
    private int defaultWindowSeconds;

    /**
     * 环绕通知：读注解参数、拼装计数键、累计计数并判定是否超限。
     *
     * @param joinPoint 连接点（被拦截的 Controller 方法）
     * @return 目标方法的原始返回值（未超限时）
     * @throws Throwable 目标方法自身抛出的异常原样上抛
     */
    @Around("@annotation(com.community.booking.utils.RateLimit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        // 1. 取出注解实例（从方法签名取而非 joinPoint.getTarget() 的运行时类，
        //    保证 CGLIB 代理子类上的注解可见性一致）
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        RateLimit rateLimit = signature.getMethod().getAnnotation(RateLimit.class);
        int maxTimes = rateLimit.maxTimes() > 0 ? rateLimit.maxTimes() : defaultMaxTimes;
        int window = rateLimit.windowSeconds() > 0 ? rateLimit.windowSeconds() : defaultWindowSeconds;
        // 2. 拼装计数键：community:ratelimit:{资源标识}:{主体}
        String key = Constants.RATE_LIMIT_PREFIX + rateLimit.value() + ":" + resolveSubject();
        // 3. 原子计数（首次计数同时设置窗口 TTL）
        long count = redisCacheUtils.incrAndExpire(key, window);
        if (count > maxTimes) {
            log.warn("限流拦截: key={} count={}/{} 接口={}", key, count, maxTimes,
                    signature.getMethod().getName());
            // 超限拒绝：400 业务异常，不进入方法体，不消耗数据库资源
            throw new BusinessException(400, rateLimit.message()
                    + "（" + window + " 秒内最多 " + maxTimes + " 次）");
        }
        return joinPoint.proceed();
    }

    /**
     * 解析计数主体：登录用户ID优先，取不到身份时回退客户端 IP。
     *
     * @return 计数键的主体段（如 u12 / ip-192-168-1-5）
     */
    private String resolveSubject() {
        try {
            LoginUserDTO operator = SecurityUtils.currentLoginUser();
            if (operator != null && operator.getUserId() != null) {
                return "u" + operator.getUserId();
            }
        } catch (BusinessException e) {
            // 未认证（理论上已被 URL 规则拦截）：降级用 IP 计数，限流不失效
            log.debug("限流计数降级为 IP 主体：未取到登录态");
        }
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            // 非 Web 线程（如定时任务调用同一方法）：统一主体，仍具备限流意义
            return "system";
        }
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null && !forwarded.isEmpty()
                ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        return "ip-" + ip.replace('.', '-').replace(':', '-');
    }
}
