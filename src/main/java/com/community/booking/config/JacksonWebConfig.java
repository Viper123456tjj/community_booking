package com.community.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Web 层 ObjectMapper 显式装配（与缓存 Mapper 分离，避免污染 HTTP 契约）。
 * <p>背景：RedisConfig 中的缓存专用 ObjectMapper（开启 DefaultTyping 类型标记）
 * 会使 Boot 的 JacksonAutoConfiguration 因 ConditionalOnMissingBean(ObjectMapper)
 * 整体退出，MVC 的 MappingJackson2HttpMessageConverter 会被迫注入该缓存 Mapper——
 * 它要求输入 JSON 携带 "@class" 类型标记，浏览器提交的普通 JSON（如登录表单、
 * 下单表单）因此解析失败。</p>
 * <p>本类显式提供 @Primary 的 Web Mapper：由 Boot 自动装配的
 * Jackson2ObjectMapperBuilder 构建（spring.jackson.date-format/time-zone 等
 * 配置照常生效），不开启类型标记。未加 @Qualifier 的注入点一律解析到本 Bean；
 * RedisCacheUtils 仍按 @Qualifier(cacheObjectMapper) 使用缓存 Mapper，
 * 两个序列化器各司其职、互不污染。</p>
 *
 * @author community-booking
 */
// 声明为配置类，补充 Web 层序列化器 Bean
@Configuration
public class JacksonWebConfig {

    /**
     * Web 层专用 ObjectMapper（HTTP 请求/响应 JSON，无类型标记）。
     *
     * @param builder Boot 自动装配的构建器（已应用 spring.jackson.* 全局配置）
     * @return 与 Boot 自动配置行为一致的 Web Mapper
     */
    @Bean
    // 主 Bean：MVC 消息转换器与 SecurityConfig 的未限定注入指向本 Mapper
    @Primary
    public ObjectMapper webObjectMapper(Jackson2ObjectMapperBuilder builder) {
        // createXmlMapper(false)：仅 JSON；builder 已按 application.yml 的
        // jackson 日期格式与时区完成定制，这里直接构建成品
        return builder.createXmlMapper(false).build();
    }
}
