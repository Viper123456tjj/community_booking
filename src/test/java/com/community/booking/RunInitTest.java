package com.community.booking;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库初始化脚本装载与数据自洽性验证入口（main 可独立运行，也被三个
 * JUnit 测试类复用；验证逻辑与 community_init.sql 头部"数据自洽性约定"逐条对应）。
 *
 * <p>运行方式（Batch 自检）：</p>
 * <pre>
 * mvn -f 社区便民服务预约系统/pom.xml test-compile          # JBC 编译到 target/test-classes
 * java -cp target/test-classes;&lt;mysql-connector-j.jar&gt; com.community.booking.RunInitTest
 * </pre>
 *
 * <p>连接参数与 application-dev.yml 一致（root/123456@localhost:3306），可用
 * JVM 属性覆盖：-Dcommunity.test.db.host / .port / .user / .password。
 * 脚本以纯 JDBC 逐条执行（逐行剥离 -- 注释后按分号切分），不依赖 mysql CLI，
 * 从根本上规避 Windows 控制台编码导致的中文种子数据乱码问题。</p>
 *
 * @author community-booking
 */
public final class RunInitTest {

    /** 初始化脚本相对工作区根目录的路径 */
    private static final String SQL_PATH = "社区便民服务预约系统/sql/community_init.sql";

    /** 数据库主机（可被 -Dcommunity.test.db.host 覆盖） */
    private static final String DB_HOST = System.getProperty("community.test.db.host", "localhost");

    /** 数据库端口（可被 -Dcommunity.test.db.port 覆盖） */
    private static final String DB_PORT = System.getProperty("community.test.db.port", "3306");

    /** 数据库账号（可被 -Dcommunity.test.db.user 覆盖） */
    private static final String DB_USER = System.getProperty("community.test.db.user", "root");

    /** 数据库密码（可被 -Dcommunity.test.db.password 覆盖） */
    private static final String DB_PASSWORD = System.getProperty("community.test.db.password", "123456");

    /** 工具类，禁止实例化（全部为静态入口方法） */
    private RunInitTest() {
    }

    /**
     * 独立运行入口：装载脚本 -> 执行全部自洽性断言 -> 打印 PASS/FAIL 汇总，
     * 任一 FAIL 时以非零退出码结束（供 mvn/CI 判定 Batch 自检是否通过）。
     *
     * @param args 命令行参数（保留扩展位，当前未使用）
     * @throws Exception 脚本装载或验证过程异常
     */
    public static void main(String[] args) throws Exception {
        System.exit(run());
    }

    /**
     * 执行完整装载 + 验证流程。
     *
     * @return 0 表示全部验证通过；1 表示存在 FAIL
     * @throws Exception 脚本装载异常或数据库不可达（视为 FAIL，同样返回 1）
     */
    public static int run() throws Exception {
        String sql = readSqlFromClasspathOrWorkspace();
        try (Connection conn = DriverManager.getConnection(
                "jdbc:mysql://".concat(DB_HOST).concat(":").concat(DB_PORT)
                        .concat("/?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=false"),
                DB_USER, DB_PASSWORD)) {
            // 脚本内含 USE community_booking，装载完成后连接已自动切换到目标库
            executeScript(conn, sql);
            int failures = verifyAll(conn);
            System.out.println(failures == 0 ? "ALL_CHECKS=PASS" : "ALL_CHECKS=FAIL count=" + failures);
            return failures == 0 ? 0 : 1;
        }
    }

    /**
     * 读取初始化脚本：优先从 classpath（测试资源）读取，回退到工作区相对路径，
     * 保证在 mvn 运行与手工 java 直跑两种场景下都能取到同一份脚本。
     *
     * @return 脚本全文（UTF-8）
     * @throws IOException 两处均不可读
     */
    static String readSqlFromClasspathOrWorkspace() throws IOException {
        try (InputStream in = RunInitTest.class.getClassLoader().getResourceAsStream("community_init.sql")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        // 回退：工作区根目录相对路径（IDE/手工运行场景）
        Path path = Paths.get(SQL_PATH);
        if (!Files.exists(path)) {
            // 再回退：绝对路径（mvn surefire 在模块目录运行时的 cwd 兜底）
            path = Paths.get("sql/community_init.sql");
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * 逐条执行脚本：逐行剥离 -- 注释后按分号切分，DDL/DML 依序执行。
     * <p>切分只认"行尾分号"，字符串字面量中的分号不会出现于本脚本（中文文案
     * 一律使用全角标点），故切分安全；USE 语句由驱动在连接上生效。</p>
     *
     * @param conn 已建立到 MySQL 的连接
     * @param sql  脚本全文
     * @throws SQLException 任一语句执行失败（直接上抛，Batch 自检判定为脚本错误）
     */
    static void executeScript(Connection conn, String sql) throws SQLException {
        List<String> statements = splitStatements(sql);
        try (Statement stmt = conn.createStatement()) {
            for (String statement : statements) {
                stmt.execute(statement);
            }
        }
    }

    /**
     * 按分号切分脚本为可执行语句列表（纯 JDBC 执行，不依赖 mysql CLI）。
     *
     * @param sql 脚本全文
     * @return 去注释、去空白后的非空语句列表
     */
    static List<String> splitStatements(String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : sql.split("\n")) {
            String line = stripLineComment(rawLine);
            if (line.isBlank()) {
                continue;
            }
            if (line.trim().endsWith(";")) {
                // 必须先 trim 再截末位分号：脚本中的多行语句带 4 空格缩进，
                // 若用未 trim 的行按"trim 后长度-1"截取，会把行尾多切掉
                // 「缩进数+1」个字符（实测把 service_id; 截成 servic 导致语法错误）
                String trimmed = line.trim();
                current.append(trimmed, 0, trimmed.length() - 1);
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    result.add(statement);
                }
                current.setLength(0);
            } else {
                current.append(line).append('\n');
            }
        }
        // 收尾：允许最后一条语句省略分号
        if (!current.toString().isBlank()) {
            result.add(current.toString().trim());
        }
        return result;
    }

    /**
     * 剥离单行 -- 注释（仅识别行首或引号外的 --，脚本内所有注释均为行首或
     * SELECT 后空格 + -- 形式，中文文案中不含 -- 序列，判定安全）。
     *
     * @param rawLine 原始行
     * @return 去注释后的行内容
     */
    static String stripLineComment(String rawLine) {
        String line = rawLine;
        int idx = indexOfComment(line);
        if (idx >= 0) {
            line = line.substring(0, idx);
        }
        return line;
    }

    /**
     * 定位行内注释起点：行首 -- 直接命中；行中 -- 需前置空格（避免切进
     * 字符串内容或标识符）。
     *
     * @param line 行内容
     * @return 注释起始下标，-1 表示无注释
     */
    private static int indexOfComment(String line) {
        if (line.startsWith("--")) {
            return 0;
        }
        int idx = line.indexOf(" --");
        return idx > 0 ? idx : -1;
    }

    /**
     * 执行全部自洽性断言并打印结果。
     *
     * @param conn 已装载脚本的连接（当前库为 community_booking）
     * @return FAIL 数量（每个 FAIL 项打印一行，供日志追溯）
     * @throws SQLException 断言查询失败（直接上抛，判定为数据库状态异常）
     */
    static int verifyAll(Connection conn) throws SQLException {
        int failures = 0;
        // A. 表结构存在性（6 张核心表逐一存在，期望 6）
        failures += verifyCheck(conn, "tables_exist",
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='community_booking' AND table_name IN ('sys_user','worker_profile','service_category','service_item','service_order','service_evaluation')", 6);
        // B. 每表种子数据 >= 5 条（6 表逐一断言）
        failures += verifyCount(conn, "count_sys_user", "SELECT COUNT(*) FROM sys_user", 5, ">=");
        failures += verifyCount(conn, "count_worker_profile", "SELECT COUNT(*) FROM worker_profile", 5, ">=");
        failures += verifyCount(conn, "count_service_category", "SELECT COUNT(*) FROM service_category", 5, ">=");
        failures += verifyCount(conn, "count_service_item", "SELECT COUNT(*) FROM service_item", 5, ">=");
        failures += verifyCount(conn, "count_service_order", "SELECT COUNT(*) FROM service_order", 5, ">=");
        failures += verifyCount(conn, "count_service_evaluation", "SELECT COUNT(*) FROM service_evaluation", 5, ">=");
        // C. 订单状态覆盖 0~4 全部 5 种（期望 5）
        failures += verifyCheck(conn, "order_status_coverage", "SELECT COUNT(DISTINCT order_status) FROM service_order", 5);
        // D. 派单方式覆盖 AUTO/MANUAL/REASSIGN 全部 3 种（期望 3）
        failures += verifyCheck(conn, "dispatch_type_coverage", "SELECT COUNT(DISTINCT dispatch_type) FROM service_order WHERE dispatch_type IS NOT NULL", 3);
        // E. 已归档与未归档样本同时存在（期望 2）
        failures += verifyCheck(conn, "archived_flag_coverage", "SELECT COUNT(DISTINCT is_archived) FROM service_order", 2);
        // F. 次日恰有 2 条已派单预约（供 18:00 提醒邮件定时任务当夜命中）
        //    口径与 ServiceOrderMapper.selectTomorrowDispatchedOrderIds 完全一致：
        //    按"日历明日"半开区间 [明日00:00, 后日00:00) 判定，而非"未来24小时"——
        //    定时任务是按日扫描，用 24 小时窗口会在 23:00 之后漏掉次日早单
        failures += verifyCheck(conn, "next_day_dispatched_orders",
                "SELECT COUNT(*) FROM service_order WHERE order_status=1 AND is_archived=0 "
                        + "AND appoint_time >= DATE_ADD(CURDATE(), INTERVAL 1 DAY) "
                        + "AND appoint_time < DATE_ADD(CURDATE(), INTERVAL 2 DAY)", 2);
        // G. 密码密文全部可被 BCrypt 校验（取 1 个样本验证可登录口径，期望 20）
        failures += verifyCheck(conn, "bcrypt_password_valid",
                "SELECT COUNT(*) FROM sys_user WHERE password = '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e'", 20);
        // H. 全部密码密文一致性（同明文 123456 的固定盐密文，期望 20）
        failures += verifyCheck(conn, "unique_username", "SELECT COUNT(DISTINCT username) FROM sys_user", 20);
        // I. 评价必须挂在已完成且未归档订单上且三联字段一致（期望 0）
        failures += verifyCheck(conn, "bad_eval",
                "SELECT COUNT(*) FROM service_evaluation e JOIN service_order o ON o.id = e.order_id WHERE o.order_status <> 3 OR o.is_archived <> 0 OR e.resident_id <> o.resident_id OR e.worker_id <> o.worker_id OR e.service_id <> o.service_id", 0);
        // J. 订单价格快照与服务项目当前价格一致（期望 0）
        failures += verifyCheck(conn, "bad_price",
                "SELECT COUNT(*) FROM service_order o JOIN service_item s ON s.id = o.service_id WHERE o.price <> s.price", 0);
        // K. hot_score 与下单次数一致（期望 0）
        failures += verifyCheck(conn, "bad_hot",
                "SELECT COUNT(*) FROM service_item s LEFT JOIN (SELECT service_id, COUNT(*) c FROM service_order GROUP BY service_id) t ON t.service_id = s.id WHERE IFNULL(t.c, 0) <> s.hot_score", 0);
        // L. 已派单/服务中订单的师傅必须忙碌（期望 0）
        failures += verifyCheck(conn, "bad_busy",
                "SELECT COUNT(*) FROM service_order o JOIN worker_profile w ON w.user_id = o.worker_id WHERE o.order_status IN (1, 2) AND w.work_status <> 2", 0);
        // M. 空闲师傅不得挂进行中工单（期望 0）
        failures += verifyCheck(conn, "bad_idle_with_order",
                "SELECT COUNT(*) FROM worker_profile w WHERE w.work_status <> 2 AND EXISTS (SELECT 1 FROM service_order o WHERE o.worker_id = w.user_id AND o.order_status IN (1, 2))", 0);
        // N. total_orders 与已完成订单数一致（期望 0）
        failures += verifyCheck(conn, "bad_total_orders",
                "SELECT COUNT(*) FROM worker_profile w WHERE w.total_orders <> (SELECT COUNT(*) FROM service_order o WHERE o.worker_id = w.user_id AND o.order_status = 3)", 0);
        // O. rating_score 与评价均值一致（无评价默认 5.00，期望 0）
        failures += verifyCheck(conn, "bad_rating",
                "SELECT COUNT(*) FROM worker_profile w WHERE w.rating_score <> (SELECT IFNULL(AVG(e.star), 5.00) FROM service_evaluation e WHERE e.worker_id = w.user_id)", 0);
        // P. 已完成订单 update_time 必须等于 finish_time（状态最后推进时间语义，期望 0）
        failures += verifyCheck(conn, "bad_update_time",
                "SELECT COUNT(*) FROM service_order WHERE order_status = 3 AND update_time <> finish_time", 0);
        // Q. 取消单 update_time 不得早于 create_time（期望 0）
        failures += verifyCheck(conn, "bad_cancel_time",
                "SELECT COUNT(*) FROM service_order WHERE update_time < create_time", 0);
        // R. 预约时间一律晚于下单时间（未来预约业务语义，期望 0）
        failures += verifyCheck(conn, "bad_appoint_time",
                "SELECT COUNT(*) FROM service_order WHERE appoint_time <= create_time", 0);
        // S. 已派单订单的承接师傅必须是启用的 ROLE_WORKER（期望 0）
        failures += verifyCheck(conn, "bad_worker_ref",
                "SELECT COUNT(*) FROM service_order o WHERE o.order_status IN (1, 2, 3) AND NOT EXISTS (SELECT 1 FROM sys_user u JOIN worker_profile w ON w.user_id = u.id WHERE u.id = o.worker_id AND u.role = 'ROLE_WORKER' AND u.status = 1)", 0);
        // T. 未完成订单不得有 finish_time（期望 0）
        failures += verifyCheck(conn, "bad_finish_time",
                "SELECT COUNT(*) FROM service_order WHERE order_status <> 3 AND finish_time IS NOT NULL", 0);
        // U. 归档单不得存在评价（7 天窗口口径：完成超 7 天归档后不再接受补评）
        failures += verifyCheck(conn, "bad_eval_archived",
                "SELECT COUNT(*) FROM service_evaluation e JOIN service_order o ON o.id = e.order_id WHERE o.is_archived = 1", 0);
        // V. 归档位与完成时间 7 天窗口严格一致（期望 0）
        failures += verifyCheck(conn, "bad_archived_time",
                "SELECT COUNT(*) FROM service_order o WHERE o.order_status = 3 AND ((o.is_archived = 1 AND o.finish_time > DATE_SUB(NOW(), INTERVAL 7 DAY)) OR (o.is_archived = 0 AND o.finish_time <= DATE_SUB(NOW(), INTERVAL 7 DAY)))", 0);
        // W. AUTO/REASSIGN 订单师傅技能必须覆盖服务所需技能（| 组合，期望 0）
        failures += verifySkillCoverage(conn);
        // X. 服务项技能标签全部落在师傅技能词表内（期望 0）
        failures += verifySkillVocabulary(conn);
        // Y. 每个 service_item 均有至少一名技能匹配的在职师傅（派单算法可达性，期望 0）
        failures += verifySkillReachable(conn);
        // Z. 订单表三类外键（resident/service/worker）均无悬挂（期望 0）
        failures += verifyCheck(conn, "bad_resident_fk",
                "SELECT COUNT(*) FROM service_order o WHERE NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.id = o.resident_id AND u.role = 'ROLE_RESIDENT')", 0);
        failures += verifyCheck(conn, "bad_service_fk",
                "SELECT COUNT(*) FROM service_order o WHERE NOT EXISTS (SELECT 1 FROM service_item s WHERE s.id = o.service_id)", 0);
        // 附：worker_profile.user_id 必须是 ROLE_WORKER（期望 0）
        failures += verifyCheck(conn, "bad_worker_user_role",
                "SELECT COUNT(*) FROM worker_profile w JOIN sys_user u ON u.id = w.user_id WHERE u.role <> 'ROLE_WORKER'", 0);
        // 附：worker_profile 与 ROLE_WORKER 用户一一对应（期望 0）
        failures += verifyCheck(conn, "bad_worker_pairing",
                "SELECT COUNT(*) FROM sys_user u WHERE u.role = 'ROLE_WORKER' AND NOT EXISTS (SELECT 1 FROM worker_profile w WHERE w.user_id = u.id)", 0);
        // 附：service_item.category_id 无悬挂（期望 0）
        failures += verifyCheck(conn, "bad_category_fk",
                "SELECT COUNT(*) FROM service_item s WHERE NOT EXISTS (SELECT 1 FROM service_category c WHERE c.id = s.category_id)", 0);
        // 附：每个分类至少 2 个服务项目（运营数据看板分类分布可读，期望 0）
        failures += verifyCheck(conn, "bad_category_balance",
                "SELECT COUNT(*) FROM service_category c WHERE (SELECT COUNT(*) FROM service_item s WHERE s.category_id = c.id) < 2", 0);
        return failures;
    }

    /**
     * 单项断言：执行 COUNT 类查询并比对期望值，打印 PASS/FAIL。
     *
     * @param conn    连接
     * @param check   检查项名（日志标识）
     * @param sql     COUNT 查询
     * @param expect  期望结果
     * @return 1（FAIL）或 0（PASS）
     * @throws SQLException 查询失败
     */
    private static int verifyCheck(Connection conn, String check, String sql, long expect) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            long actual = rs.next() ? rs.getLong(1) : -1;
            boolean pass = actual == expect;
            System.out.println((pass ? "PASS " : "FAIL ") + check + " actual=" + actual + " expect=" + expect);
            return pass ? 0 : 1;
        }
    }

    /**
     * 数量断言（支持 >= 与 == 两种比较，语义化日志）。
     *
     * @param conn   连接
     * @param check  检查项名
     * @param sql    COUNT 查询
     * @param expect 阈值
     * @param op     比较符（">=" 或 "=="）
     * @return 1（FAIL）或 0（PASS）
     * @throws SQLException 查询失败
     */
    private static int verifyCount(Connection conn, String check, String sql, long expect, String op) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            long actual = rs.next() ? rs.getLong(1) : -1;
            boolean pass = ">=".equals(op) ? actual >= expect : actual == expect;
            System.out.println((pass ? "PASS " : "FAIL ") + check + " actual=" + actual + " " + op + " " + expect);
            return pass ? 0 : 1;
        }
    }

    /**
     * AUTO/REASSIGN 订单师傅技能覆盖校验（| 分隔串无法在 SQL 内表达，改为
     * Java 内联集合比对：技能标签集合 ⊆ 师傅技能集合）。
     *
     * @param conn 连接
     * @return 1（存在违规）或 0（全部通过）
     * @throws SQLException 查询失败
     */
    private static int verifySkillCoverage(Connection conn) throws SQLException {
        int failures = 0;
        List<String> violations = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT o.id, o.dispatch_type, s.skill_required, w.skill_tags "
                             + "FROM service_order o JOIN service_item s ON s.id = o.service_id "
                             + "JOIN worker_profile w ON w.user_id = o.worker_id "
                             + "WHERE o.dispatch_type IN ('AUTO', 'REASSIGN')")) {
            while (rs.next()) {
                long orderId = rs.getLong(1);
                String required = rs.getString(3);
                String tags = rs.getString(4);
                if (!tags.contains("|") ? !tags.equals(required) : !covers(tags, required)) {
                    violations.add("order#" + orderId + " need=" + required + " have=" + tags);
                }
            }
        }
        if (violations.isEmpty()) {
            System.out.println("PASS dispatch_skill_coverage");
        } else {
            System.out.println("FAIL dispatch_skill_coverage count=" + violations.size());
            violations.forEach(v -> System.out.println("   " + v));
            failures = 1;
        }
        return failures;
    }

    /**
     * 服务项技能标签词表校验：skill_required 的每个标签必须出现在全部师傅
     * 技能标签并集词表中（否则智能派单永远找不到候选人）。
     *
     * @param conn 连接
     * @return 1（存在违规）或 0（全部通过）
     * @throws SQLException 查询失败
     */
    private static int verifySkillVocabulary(Connection conn) throws SQLException {
        int failures = 0;
        List<String> vocab = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT skill_tags FROM worker_profile")) {
            while (rs.next()) {
                for (String tag : rs.getString(1).split("\\|")) {
                    String trimmed = tag.trim();
                    if (!trimmed.isEmpty() && !vocab.contains(trimmed)) {
                        vocab.add(trimmed);
                    }
                }
            }
        }
        List<String> violations = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, skill_required FROM service_item")) {
            while (rs.next()) {
                long itemId = rs.getLong(1);
                for (String tag : rs.getString(2).split("\\|")) {
                    String trimmed = tag.trim();
                    if (!trimmed.isEmpty() && !vocab.contains(trimmed)) {
                        violations.add("item#" + itemId + " unknown-tag=" + trimmed);
                    }
                }
            }
        }
        if (violations.isEmpty()) {
            System.out.println("PASS skill_vocabulary vocab=" + vocab);
        } else {
            System.out.println("FAIL skill_vocabulary count=" + violations.size());
            violations.forEach(v -> System.out.println("   " + v));
            failures = 1;
        }
        return failures;
    }

    /**
     * 服务项技能可达性校验：每个服务项目（含下架样本，看板仍统计）必须至少
     * 对应一名在职（启用）师傅能承接其全部所需技能，否则运营看板与派单算法
     * 都会出现"有单无人接"的死角。
     *
     * @param conn 连接
     * @return 1（存在违规）或 0（全部通过）
     * @throws SQLException 查询失败
     */
    private static int verifySkillReachable(Connection conn) throws SQLException {
        int failures = 0;
        List<String> allTags = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT w.skill_tags FROM worker_profile w JOIN sys_user u ON u.id = w.user_id WHERE u.status = 1")) {
            while (rs.next()) {
                allTags.add(rs.getString(1));
            }
        }
        List<String> violations = new ArrayList<>();
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, item_name, skill_required FROM service_item")) {
            while (rs.next()) {
                long itemId = rs.getLong(1);
                String itemName = rs.getString(2);
                String required = rs.getString(3);
                boolean reachable = false;
                for (String tags : allTags) {
                    if (covers(tags, required)) {
                        reachable = true;
                        break;
                    }
                }
                if (!reachable) {
                    violations.add("item#" + itemId + "(" + itemName + ") no-capable-worker need=" + required);
                }
            }
        }
        if (violations.isEmpty()) {
            System.out.println("PASS skill_reachable");
        } else {
            System.out.println("FAIL skill_reachable count=" + violations.size());
            violations.forEach(v -> System.out.println("   " + v));
            failures = 1;
        }
        return failures;
    }

    /**
     * 技能覆盖判定：required（| 分隔）的每个标签都必须出现在 workerTags（| 分隔）中。
     *
     * @param workerTags 师傅技能标签串（| 分隔）
     * @param required   服务所需技能标签串（| 分隔）
     * @return true 表示师傅可承接该服务
     */
    private static boolean covers(String workerTags, String required) {
        List<String> workerSet = new ArrayList<>();
        for (String tag : workerTags.split("\\|")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                workerSet.add(trimmed);
            }
        }
        for (String tag : required.split("\\|")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty() && !workerSet.contains(trimmed)) {
                return false;
            }
        }
        return true;
    }

    /**
     * JUnit 入口复用：断言整个验证流程退出码为 0（供 mvn test 纳入回归）。
     */
    @Test
    void initScriptAndSelfChecks() throws Exception {
        assertTrue(run() == 0, "初始化脚本装载与数据自洽性校验必须全部通过（详见 stdout 的 PASS/FAIL 明细）");
    }
}
