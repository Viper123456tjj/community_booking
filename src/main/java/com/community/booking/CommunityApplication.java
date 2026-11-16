package com.community.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 社区便民服务预约系统启动类。
 *
 * <p>系统面向社区居民、便民服务人员与物业管理员三类角色，提供服务分类/项目浏览、
 * 在线预约下单、智能派单、服务履约与评价的全链路闭环。</p>
 *
 * <p>批量执行的五个 Batch 将逐步补齐：config（Security/Redis/MQ/MyBatis）、
 * controller、service.impl、mapper、entity、dto、vo、exception、utils、task、mq。
 * 本类作为 Spring Boot 应用唯一入口，开启声明式事务与定时任务：
 * 定时任务承载每日 03:00 归档清理与每日 18:00 次日上门提醒邮件，
 * 声明式事务保证下单、派单、评价等写操作与缓存/MQ 副作用的边界一致。</p>
 *
 * <p>技术栈锁定：Spring Boot 2.7.18 + JDK 17 + MySQL 8.0 + MyBatis-Plus 3.5.3.1，
 * 视图层为 Thymeleaf + Bootstrap 5 + jQuery + ECharts 5（严禁 Vue/React）。</p>
 */
@SpringBootApplication
@EnableTransactionManagement
@EnableScheduling
public class CommunityApplication {

    /**
     * 应用主入口：以非 Web 容器无关的标准方式启动 Spring 应用上下文。
     *
     * @param args 命令行参数（生产环境可传 --spring.profiles.active=prod 切换环境）
     */
    public static void main(String[] args) {
        SpringApplication.run(CommunityApplication.class, args);
    }
}
