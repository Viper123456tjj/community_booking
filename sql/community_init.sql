-- =====================================================================
-- 社区便民服务预约系统（community_booking）数据库初始化脚本
-- 适配：MySQL 8.0 / InnoDB / utf8mb4
-- 执行方式：mysql -uroot -p < community_init.sql
-- 约定：所有测试账号密码统一为明文 123456，库内存储 BCryptPasswordEncoder 密文；
--       每张表初始化测试数据不少于 5 条；订单时间使用 NOW() 相对偏移，
--       保证脚本执行后同时存在「待派单/已派单/服务中/已完成/已取消」全部订单状态、
--       AUTO/MANUAL/REASSIGN 三种派单方式、已归档与未归档、已评价与待评价样本，
--       且次日恰有 2 条已派单预约（供每日 18:00 上门提醒邮件定时任务当夜命中）。
--       order_no 日期前缀按脚本基准日期 2026-11-11 静态示意（实际 create_time
--       随 NOW() 相对落库，前缀仅作业务编号示意，唯一性不受影响）。
-- 数据自洽性约定（与 .workbuddy/verify_community_init.py 逐条校验，全部 PASS）：
--   1) 评价只挂在 order_status=3（已完成）且 is_archived=0 的订单上，
--      且评价的 resident_id/worker_id/service_id 与该订单行完全一致；
--   2) 全部订单 price = 对应 service_item.price（下单价格快照一致）；
--   3) worker_profile.total_orders = 该师傅 order_status=3 的订单数；
--   4) worker_profile.rating_score = 该师傅全部评价 star 的平均值（无评价默认 5.00）；
--   5) 持有「已派单/服务中」订单的师傅 work_status=2（忙碌），无进行中工单则为 1 或 0；
--   6) service_item.skill_required 的每个标签均出现在 worker_profile.skill_tags 词表中；
--      AUTO/REASSIGN 订单的承接师傅技能必须覆盖服务项目所需技能；
--   7) service_item.hot_score = 该 service_id 在 service_order 中的下单次数；
--   8) is_archived=1 当且仅当订单已完成且 finish_time 距今超过 7 天。
-- =====================================================================

DROP DATABASE IF EXISTS community_booking;
CREATE DATABASE community_booking DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE community_booking;

-- ---------------------------------------------------------------------
-- 1. sys_user 系统用户表（居民/服务人员/物业管理员三级角色，BCrypt 密码）
-- ---------------------------------------------------------------------
CREATE TABLE sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户主键',
    username    VARCHAR(32)  NOT NULL COMMENT '登录用户名（唯一）',
    password    VARCHAR(64)  NOT NULL COMMENT 'BCryptPasswordEncoder 密文（明文统一 123456）',
    real_name   VARCHAR(32)  NOT NULL COMMENT '真实姓名',
    phone       VARCHAR(20)  NULL COMMENT '手机号',
    email       VARCHAR(64)  NOT NULL COMMENT '邮箱（派单/完成/取消通知与次日提醒邮件收件地址，必填）',
    address     VARCHAR(128) NULL COMMENT '居住地址（居民下单时默认带入服务地址）',
    role        VARCHAR(20)  NOT NULL COMMENT '角色：ROLE_RESIDENT/ROLE_WORKER/ROLE_ADMIN',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1正常/0禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统用户表';

-- ---------------------------------------------------------------------
-- 2. worker_profile 服务人员档案表（1 对 1 关联 ROLE_WORKER 用户；
--    skill_tags 使用 | 分隔技能标签，与 service_item.skill_required 同词表，
--    是智能派单多维权重评分算法（技能匹配+空闲状态+时间冲突+评分/接单量负载均衡）的数据源）
-- ---------------------------------------------------------------------
CREATE TABLE worker_profile (
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '档案主键',
    user_id      BIGINT        NOT NULL COMMENT '关联用户ID（仅 ROLE_WORKER，唯一）',
    skill_tags   VARCHAR(255)  NOT NULL COMMENT '技能标签（|分隔，如 水电维修|管道疏通）',
    work_status  TINYINT       NOT NULL DEFAULT 1 COMMENT '接单状态：1空闲/2忙碌/0休息',
    rating_score DECIMAL(3, 2) NOT NULL DEFAULT 5.00 COMMENT '平均星级评分（1~5，由评价完成经 appoint.eval.queue 异步重算）',
    total_orders INT           NOT NULL DEFAULT 0 COMMENT '累计完成订单数（智能派单负载均衡因子）',
    create_time  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_worker_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '服务人员档案表';

-- ---------------------------------------------------------------------
-- 3. service_category 服务分类表（管理员维护，Redis community:service:categories 缓存源）
-- ---------------------------------------------------------------------
CREATE TABLE service_category (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '分类主键',
    category_name VARCHAR(32) NOT NULL COMMENT '分类名称（家政保洁/管道水电等）',
    icon          VARCHAR(64) NULL COMMENT '分类图标标识（Bootstrap Icons class）',
    sort_order    INT         NOT NULL DEFAULT 0 COMMENT '排序序号（升序展示）',
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_category_name (category_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '服务分类表';

-- ---------------------------------------------------------------------
-- 4. service_item 服务项目表（管理员维护；skill_required 为 | 分隔技能标签，
--    供智能派单算法做技能覆盖匹配；hot_score 由下单行为累计，
--    Redis community:service:hot 热门榜单缓存源）
-- ---------------------------------------------------------------------
CREATE TABLE service_item (
    id             BIGINT         NOT NULL AUTO_INCREMENT COMMENT '项目主键',
    category_id    BIGINT         NOT NULL COMMENT '所属分类ID',
    item_name      VARCHAR(64)    NOT NULL COMMENT '服务项目名称',
    cover_img      VARCHAR(255)   NULL COMMENT '服务封面图片URL',
    price          DECIMAL(10, 2) NOT NULL DEFAULT 0.00 COMMENT '服务价格（元，下单时快照到订单）',
    unit           VARCHAR(16)    NOT NULL DEFAULT '次' COMMENT '计价单位（次/单/小时/个/趟/平方米等）',
    skill_required VARCHAR(128)   NOT NULL COMMENT '所需技能标签（|分隔，须为师傅技能词表子集）',
    description    TEXT           NULL COMMENT '服务详细说明',
    hot_score      INT            NOT NULL DEFAULT 0 COMMENT '热度分（下单+1，供热门服务榜单）',
    status         TINYINT        NOT NULL DEFAULT 1 COMMENT '状态：1上架/0下架',
    create_time    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_item_category (category_id),
    KEY idx_item_name (item_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '服务项目表';

-- ---------------------------------------------------------------------
-- 5. service_order 服务订单表（订单状态机：0待派单/1已派单/2服务中/3已完成/4已取消；
--    dispatch_type：AUTO智能派单/MANUAL手动派单/REASSIGN改派；
--    下单同时投递 appoint.delay.queue 的 30 分钟 TTL 消息，超时未派单由死信消费者
--    自动取消（order_status=4、remark 追记取消原因），状态机推进全部经
--    community:order:lock:{orderNo} 分布式锁保证幂等）
-- ---------------------------------------------------------------------
CREATE TABLE service_order (
    id              BIGINT         NOT NULL AUTO_INCREMENT COMMENT '订单主键',
    order_no        VARCHAR(32)    NOT NULL COMMENT '订单号（唯一，如 WO20261111000015）',
    resident_id     BIGINT         NOT NULL COMMENT '下单居民用户ID',
    service_id      BIGINT         NOT NULL COMMENT '服务项目ID',
    worker_id       BIGINT         NULL COMMENT '承接服务人员用户ID（待派单为 NULL，派单/改派后写入）',
    appoint_time    DATETIME       NOT NULL COMMENT '预约上门时间',
    service_address VARCHAR(128)   NOT NULL COMMENT '服务地址',
    contact_phone   VARCHAR(20)    NOT NULL COMMENT '联系电话',
    remark          VARCHAR(255)   NULL COMMENT '下单备注（取消单在此追记取消原因）',
    price           DECIMAL(10, 2) NOT NULL COMMENT '下单时服务项目价格快照',
    order_status    TINYINT        NOT NULL DEFAULT 0 COMMENT '订单状态：0待派单/1已派单/2服务中/3已完成/4已取消',
    dispatch_type   VARCHAR(16)    NULL COMMENT '派单方式：AUTO智能/MANUAL手动/REASSIGN改派（未派单为 NULL）',
    finish_time     DATETIME       NULL COMMENT '服务完成时间',
    is_archived     TINYINT        NOT NULL DEFAULT 0 COMMENT '归档标记：0正常/1已归档（完成超7天由每日03:00定时任务置1）',
    create_time     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
    update_time     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '状态更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_resident (resident_id),
    KEY idx_order_service (service_id),
    KEY idx_order_worker (worker_id),
    KEY idx_order_appoint (appoint_time),
    KEY idx_order_status (order_status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '服务订单表';

-- ---------------------------------------------------------------------
-- 6. service_evaluation 服务评价表（1 单 1 评：order_id 唯一约束保证评价幂等；
--    评价提交后经 appoint.eval.queue 异步重算师傅 rating_score 与运营看板指标）
-- ---------------------------------------------------------------------
CREATE TABLE service_evaluation (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '评价主键',
    order_id     BIGINT       NOT NULL COMMENT '订单ID（唯一，一单一评）',
    resident_id  BIGINT       NOT NULL COMMENT '评价居民用户ID',
    worker_id    BIGINT       NOT NULL COMMENT '被评价服务人员用户ID',
    service_id   BIGINT       NOT NULL COMMENT '被评价服务项目ID',
    star         TINYINT      NOT NULL COMMENT '星级：1~5星',
    comment      VARCHAR(512) NULL COMMENT '文字评价内容',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_eval_order (order_id),
    KEY idx_eval_worker (worker_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '服务评价表';

-- =====================================================================
-- 初始化测试数据（所有密码密文对应明文 123456，由 BCryptPasswordEncoder 生成并回验）
-- 登录账号速查（密码统一 123456）：
--   管理员   admin
--   师傅端   worker1~worker7（user_id 2~8，其中 user_id 9/worker8 账号禁用）
--   居民端   resident1~resident8（user_id 10~17，其中 user_id 17/resident8 账号禁用）
-- =====================================================================

-- 1. sys_user：20 条（1 名物业管理员 + 11 名便民服务人员 + 8 名社区居民，含 2 个禁用样本）
--    为什么是 11 名师傅：派单演示要求"每个上架服务项目至少有一名【空闲】的合格师傅"。
--    若某个技能只由一位忙碌/休息的师傅覆盖，管理员点一键智能派单必然失败
--    （候选池会排除休息者、算法会排除忙碌者），演示时表现为"系统坏了"。
--    因此 worker9~worker11 专门补齐 日常保洁/家电清洗/管道疏通/搬家搬运/家电维修
--    五个技能的"空闲可派"覆盖，且均为 0 完成单、无评价（rating 取默认 5.00），
--    与 total_orders/rating_score 的种子数据自洽规则不冲突。
-- 登录账号速查（密码统一 123456）：
--   管理员   admin
--   师傅端   worker1~worker10（user_id 2~9、18~20；其中 user_id 9/worker8 账号禁用）
--   居民端   resident1~resident8（user_id 10~17，其中 user_id 17/resident8 账号禁用）
-- =====================================================================

INSERT INTO sys_user (id, username, password, real_name, phone, email, address, role, status, create_time) VALUES
(1,  'admin',     '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '郭建国', '13800000001', 'admin@community.gov.cn',    '物业服务中心',            'ROLE_ADMIN',    1, DATE_SUB(NOW(), INTERVAL 120 DAY)),
(2,  'worker1',   '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '张水管', '13800000002', 'worker1@community.gov.cn',  '康顺社区沿街商铺',        'ROLE_WORKER',   1, DATE_SUB(NOW(), INTERVAL 110 DAY)),
(3,  'worker2',   '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '李电工', '13800000003', 'worker2@community.gov.cn',  '康顺社区2号楼',           'ROLE_WORKER',   1, DATE_SUB(NOW(), INTERVAL 110 DAY)),
(4,  'worker3',   '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '王维修', '13800000004', 'worker3@community.gov.cn',  '康顺社区5号楼',           'ROLE_WORKER',   1, DATE_SUB(NOW(), INTERVAL 108 DAY)),
(5,  'worker4',   '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '刘帮搬', '13800000005', 'worker4@community.gov.cn',  '康顺社区7号楼',           'ROLE_WORKER',   1, DATE_SUB(NOW(), INTERVAL 105 DAY)),
(6,  'worker5',   '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '赵电安', '13800000006', 'worker5@community.gov.cn',  '康顺社区9号楼',           'ROLE_WORKER',   1, DATE_SUB(NOW(), INTERVAL 100 DAY)),
(7,  'worker6',   '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '孙空调', '13800000007', 'worker6@community.gov.cn',  '康顺社区11号楼',          'ROLE_WORKER',   1, DATE_SUB(NOW(), INTERVAL 95 DAY)),
(8,  'worker7',   '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '周家电', '13800000008', 'worker7@community.gov.cn',  '康顺社区12号楼',          'ROLE_WORKER',   1, DATE_SUB(NOW(), INTERVAL 90 DAY)),
(9,  'worker8',   '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '吴锁匠', '13800000009', 'worker8@community.gov.cn',  '康顺社区13号楼',          'ROLE_WORKER',   0, DATE_SUB(NOW(), INTERVAL 85 DAY)),
(10, 'resident1', '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '钱玛丽', '13800000010', 'resident1@community.gov.cn','康顺社区1号楼2单元101',   'ROLE_RESIDENT', 1, DATE_SUB(NOW(), INTERVAL 100 DAY)),
(11, 'resident2', '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '孙阿婆', '13800000011', 'resident2@community.gov.cn','康顺社区2号楼1单元302',   'ROLE_RESIDENT', 1, DATE_SUB(NOW(), INTERVAL 98 DAY)),
(12, 'resident3', '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '李先生', '13800000012', 'resident3@community.gov.cn','康顺社区3号楼1单元601',   'ROLE_RESIDENT', 1, DATE_SUB(NOW(), INTERVAL 96 DAY)),
(13, 'resident4', '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '张女士', '13800000013', 'resident4@community.gov.cn','康顺社区4号楼2单元1802',  'ROLE_RESIDENT', 1, DATE_SUB(NOW(), INTERVAL 94 DAY)),
(14, 'resident5', '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '欧阳修', '13800000014', 'resident5@community.gov.cn','康顺社区5号楼1单元702',   'ROLE_RESIDENT', 1, DATE_SUB(NOW(), INTERVAL 92 DAY)),
(15, 'resident6', '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '黄小厨', '13800000015', 'resident6@community.gov.cn','康顺社区6号楼2单元403',   'ROLE_RESIDENT', 1, DATE_SUB(NOW(), INTERVAL 88 DAY)),
(16, 'resident7', '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '郑老师', '13800000016', 'resident7@community.gov.cn','康顺社区7号楼1单元102',   'ROLE_RESIDENT', 1, DATE_SUB(NOW(), INTERVAL 86 DAY)),
(17, 'resident8', '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '冯冯',   '13800000017', 'resident8@community.gov.cn','康顺社区8号楼2单元1105',  'ROLE_RESIDENT', 0, DATE_SUB(NOW(), INTERVAL 84 DAY)),
(18, 'worker9',   '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '郑保洁', '13800000018', 'worker9@community.gov.cn',  '康顺社区14号楼',          'ROLE_WORKER',   1, DATE_SUB(NOW(), INTERVAL 80 DAY)),
(19, 'worker10',  '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '陈疏通', '13800000019', 'worker10@community.gov.cn', '康顺社区15号楼',          'ROLE_WORKER',   1, DATE_SUB(NOW(), INTERVAL 78 DAY)),
(20, 'worker11',  '$2a$10$0sOoH5VEJ.DTGKzQrwQOAOnd688f1hM12azSy/tc.acMw2HD42f6e', '何家电', '13800000020', 'worker11@community.gov.cn', '康顺社区16号楼',          'ROLE_WORKER',   1, DATE_SUB(NOW(), INTERVAL 76 DAY));

-- 2. worker_profile：11 条（与全部 11 名 ROLE_WORKER 用户 1 对 1；
--    忙碌判定：user_id 2（订单6服务中）、user_id 4（订单12已派单）、user_id 5（订单11已派单）=2；
--    user_id 8（周家电，无进行中工单，休息中）=0；其余空闲=1；
--    user_id 18/19/20 为新增的空闲师傅，均 0 完成单、无评价，rating 取默认 5.00；
--    评分/完成数与 service_evaluation、service_order 两表严格自洽）
INSERT INTO worker_profile (id, user_id, skill_tags, work_status, rating_score, total_orders, create_time) VALUES
(1, 2, '管道疏通|水电维修',                  2, 5.00, 1, DATE_SUB(NOW(), INTERVAL 110 DAY)),
(2, 3, '水电维修|电气故障',                  1, 4.50, 2, DATE_SUB(NOW(), INTERVAL 110 DAY)),
(3, 4, '家电维修|水电维修|家电清洗',         2, 5.00, 1, DATE_SUB(NOW(), INTERVAL 108 DAY)),
(4, 5, '搬家搬运|日常保洁',                  2, 5.00, 1, DATE_SUB(NOW(), INTERVAL 105 DAY)),
(5, 6, '水电维修|锁具服务',                  1, 4.00, 1, DATE_SUB(NOW(), INTERVAL 100 DAY)),
(6, 7, '空调安装|空调移机|电气故障|空调清洗', 1, 3.00, 2, DATE_SUB(NOW(), INTERVAL 95 DAY)),
(7, 8, '家电维修|空调移机',                  0, 5.00, 0, DATE_SUB(NOW(), INTERVAL 90 DAY)),
(8, 9, '锁具服务',                           1, 5.00, 0, DATE_SUB(NOW(), INTERVAL 85 DAY)),
(9, 18, '日常保洁|家电清洗',                 1, 5.00, 0, DATE_SUB(NOW(), INTERVAL 80 DAY)),
(10, 19, '管道疏通|搬家搬运',                1, 5.00, 0, DATE_SUB(NOW(), INTERVAL 78 DAY)),
(11, 20, '家电维修|家电清洗',                1, 5.00, 0, DATE_SUB(NOW(), INTERVAL 76 DAY));

-- 3. service_category：6 条（icon 使用 Bootstrap Icons class 名，供 Thymeleaf 导航渲染）
INSERT INTO service_category (id, category_name, icon, sort_order, create_time) VALUES
(1, '家政保洁', 'bi-person-work', 1, DATE_SUB(NOW(), INTERVAL 120 DAY)),
(2, '管道水电', 'bi-droplet',     2, DATE_SUB(NOW(), INTERVAL 120 DAY)),
(3, '家电维修', 'bi-tools',       3, DATE_SUB(NOW(), INTERVAL 119 DAY)),
(4, '空调服务', 'bi-wind',        4, DATE_SUB(NOW(), INTERVAL 119 DAY)),
(5, '搬运帮助', 'bi-truck',       5, DATE_SUB(NOW(), INTERVAL 118 DAY)),
(6, '锁具开锁', 'bi-key',         6, DATE_SUB(NOW(), INTERVAL 118 DAY));

-- 4. service_item：14 条（每个分类至少 2 条，保证看板分类分布可读；item9/item11 为下架样本；
--    skill_required 全部落在师傅技能词表内；hot_score 与 service_order 下单次数严格一致）
INSERT INTO service_item (id, category_id, item_name, cover_img, price, unit, skill_required, description, hot_score, status, create_time) VALUES
(1,  1, '日常保洁（2小时）',        '/files/item/cleaning-1.jpg',  80.00, '小时', '日常保洁', '两名保洁员上门，覆盖厨房、卫生间与客厅基础清洁，工具与清洁剂自带。',           3, 1, DATE_SUB(NOW(), INTERVAL 115 DAY)),
(2,  2, '室内管道疏通',            '/files/item/drain-2.jpg',     60.00, '单',   '管道疏通', '厨房、卫生间地漏与下水管机械疏通，含存水弯拆洗，不通不收费。',                   2, 1, DATE_SUB(NOW(), INTERVAL 115 DAY)),
(3,  2, '电路检修与开关更换',      '/files/item/electric-3.jpg',  100.00, '单',  '电气故障', '跳闸排查、线路老化检测、开关插座更换，出具书面检修报告。',                       2, 1, DATE_SUB(NOW(), INTERVAL 114 DAY)),
(4,  2, '水龙头/角阀更换',         '/files/item/faucet-4.jpg',    45.00, '个',   '水电维修', '更换冷热水龙头、角阀、软管，配件按实报实销，免人工上门费。',                     1, 1, DATE_SUB(NOW(), INTERVAL 114 DAY)),
(5,  3, '冰箱不制冷检修',          '/files/item/fridge-5.jpg',    120.00, '单',  '家电维修', '冰箱不制冷、冷藏结霜等故障诊断检修，配件另计，提供 90 天返修保障。',             1, 1, DATE_SUB(NOW(), INTERVAL 113 DAY)),
(6,  3, '洗衣机深度清洗',          NULL,                          90.00, '单',   '家电清洗', '波轮/滚筒洗衣机内筒拆机除垢消毒，去除霉味与残留污垢。',                             2, 1, DATE_SUB(NOW(), INTERVAL 113 DAY)),
(7,  3, '微波炉磁控管更换',        NULL,                          75.00, '单',   '家电维修', '微波炉不通电、不加热故障维修，常见磁控管与高压保险更换。',                         0, 1, DATE_SUB(NOW(), INTERVAL 112 DAY)),
(8,  4, '空调外机拆装移机',        '/files/item/hvac-8.jpg',      200.00, '单',  '空调移机', '一拖一挂机整机移机，含拆装机、冷媒回收补充与更换一米铜管。',                       2, 1, DATE_SUB(NOW(), INTERVAL 111 DAY)),
(9,  4, '中央空调风口清洗',        NULL,                          150.00, '单',   '空调清洗', '家庭中央空调出风口滤网拆洗与蒸发器喷雾消毒，改善送风异味。',                       0, 0, DATE_SUB(NOW(), INTERVAL 111 DAY)),
(10, 5, '家庭小型搬家（面包车）',  NULL,                          180.00, '趟',  '搬家搬运', '一车两师傅，承载两室以内家具小件搬运，电梯房与平层同价，超时每小时加收 40 元。', 1, 1, DATE_SUB(NOW(), INTERVAL 110 DAY)),
(11, 5, '适老搬家协助（含陪同收纳）', NULL,                       140.00, '单',   '搬家搬运', '面向独居老人的搬家陪同服务：分类打包、标注收纳、新居归位。',                       0, 0, DATE_SUB(NOW(), INTERVAL 109 DAY)),
(12, 6, '上门开防盗门锁',          '/files/item/lock-12.jpg',     80.00, '次',   '锁具服务', '技术开启各类防盗门锁，不破坏门体；换锁芯费用另计，备案锁具备选用。',               1, 1, DATE_SUB(NOW(), INTERVAL 108 DAY)),
(13, 1, '厨房深度除油（3小时）',    NULL,                          45.00, '小时', '日常保洁', '厨房重油污深度处理：油烟机外壳、灶台、墙面瓷砖与橱柜外部除油，耗材自带。',         0, 1, DATE_SUB(NOW(), INTERVAL 107 DAY)),
(14, 6, '更换C级锁芯',            NULL,                          120.00, '次',   '锁具服务', 'C级叶片锁芯更换，含拆装调试与两把钥匙，出具锁具备案登记单。',                     0, 1, DATE_SUB(NOW(), INTERVAL 107 DAY));

-- 5. service_order：15 条（覆盖全部 5 种订单状态与 3 种派单方式；
--    订单 11/12 预约时间恰落次日，供 18:00 提醒邮件定时任务当夜命中；
--    订单 14/7/8 完成超 7 天已归档且无评价；price 与 service_item.price 一致；
--    hot_score 与各 service_id 下单次数一致；AUTO/REASSIGN 订单师傅技能覆盖服务所需技能）
INSERT INTO service_order (id, order_no, resident_id, service_id, worker_id, appoint_time, service_address, contact_phone, remark, price, order_status, dispatch_type, finish_time, is_archived, create_time, update_time) VALUES
(1,  'WO20261111000014', 10, 1,  NULL, DATE_ADD(NOW(), INTERVAL 2 DAY),                        '康顺社区1号楼2单元101',  '13800000010', '周末上午到家保洁，重点擦玻璃。',                       80.00,  0, NULL,     NULL, 0, DATE_SUB(NOW(), INTERVAL 10 MINUTE),     DATE_SUB(NOW(), INTERVAL 10 MINUTE)),
(2,  'WO20261111000015', 12, 4,  NULL, DATE_ADD(NOW(), INTERVAL 3 DAY),                        '康顺社区3号楼1单元601',  '13800000012', '厨房水槽角阀渗水，请及时处理。',                     45.00,  0, NULL,     NULL, 0, DATE_SUB(NOW(), INTERVAL 5 MINUTE),      DATE_SUB(NOW(), INTERVAL 5 MINUTE)),
(3,  'WO20261104000004', 14, 8,  7,    DATE_SUB(NOW(), INTERVAL 6 DAY),                        '康顺社区5号楼1单元702',  '13800000014', '书房挂机移到客厅，外墙走管。',                       200.00, 3, 'AUTO',   DATE_ADD(DATE_SUB(NOW(), INTERVAL 6 DAY), INTERVAL 2 HOUR),  0, DATE_SUB(NOW(), INTERVAL 7 DAY),       DATE_ADD(DATE_SUB(NOW(), INTERVAL 6 DAY), INTERVAL 2 HOUR)),
(4,  'WO20261107000007', 13, 3,  3,    DATE_SUB(NOW(), INTERVAL 3 DAY),                        '康顺社区4号楼2单元1802', '13800000013', '客厅总闸频繁跳闸，家里有老人。',                     100.00, 3, 'AUTO',   DATE_ADD(DATE_SUB(NOW(), INTERVAL 3 DAY), INTERVAL 1 HOUR),   0, DATE_SUB(NOW(), INTERVAL 4 DAY),       DATE_ADD(DATE_SUB(NOW(), INTERVAL 3 DAY), INTERVAL 1 HOUR)),
(5,  'WO20261109000009', 11, 10, NULL, DATE_SUB(NOW(), INTERVAL 1 DAY),                        '康顺社区2号楼1单元302',  '13800000011', '【居民主动取消】老房子换电梯房计划推迟。',           180.00, 4, NULL,     NULL, 0, DATE_SUB(NOW(), INTERVAL 2 DAY),       DATE_SUB(NOW(), INTERVAL 1 DAY)),
(6,  'WO20261111000013', 11, 2,  2,    DATE_SUB(NOW(), INTERVAL 30 MINUTE),                    '康顺社区2号楼1单元302',  '13800000011', '卫生间地漏返水，尽快上门。',                         60.00,  2, 'MANUAL', NULL, 0, DATE_SUB(NOW(), INTERVAL 5 HOUR),        DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
(7,  'WO20261031000002', 10, 1,  5,    DATE_SUB(NOW(), INTERVAL 10 DAY),                       '康顺社区1号楼2单元101',  '13800000010', '深度保洁，橱柜内部也要擦。',                         80.00,  3, 'MANUAL', DATE_ADD(DATE_SUB(NOW(), INTERVAL 10 DAY), INTERVAL 2 HOUR), 1, DATE_SUB(NOW(), INTERVAL 11 DAY),      DATE_ADD(DATE_SUB(NOW(), INTERVAL 10 DAY), INTERVAL 2 HOUR)),
(8,  'WO20261102000003', 14, 8,  7,    DATE_SUB(NOW(), INTERVAL 8 DAY),                        '康顺社区5号楼1单元702',  '13800000014', '卧室移机改约至周末，改派孙师傅。',                     200.00, 3, 'REASSIGN', DATE_ADD(DATE_SUB(NOW(), INTERVAL 8 DAY), INTERVAL 3 HOUR),  1, DATE_SUB(NOW(), INTERVAL 9 DAY),       DATE_ADD(DATE_SUB(NOW(), INTERVAL 8 DAY), INTERVAL 3 HOUR)),
(9,  'WO20261108000008', 13, 12, 6,    DATE_SUB(NOW(), INTERVAL 2 DAY),                        '康顺社区4号楼2单元1802', '13800000013', '钥匙断在锁芯里了。',                                 80.00,  3, 'AUTO',   DATE_ADD(DATE_SUB(NOW(), INTERVAL 2 DAY), INTERVAL 1 HOUR),   0, DATE_SUB(NOW(), INTERVAL 3 DAY),       DATE_ADD(DATE_SUB(NOW(), INTERVAL 2 DAY), INTERVAL 1 HOUR)),
(10, 'WO20261110000010', 15, 6,  NULL, DATE_ADD(NOW(), INTERVAL 6 HOUR),                   '康顺社区6号楼2单元403',  '13800000015', '【超时未派单自动取消】滚筒洗衣机两年没洗。',         90.00,  4, NULL,     NULL, 0, DATE_SUB(NOW(), INTERVAL 1 DAY),       DATE_ADD(DATE_SUB(NOW(), INTERVAL 1 DAY), INTERVAL 30 MINUTE)),
(11, 'WO20261110000012', 16, 1,  5,    DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 10 HOUR),  '康顺社区7号楼1单元102', '13800000016', '搬家前做一次全屋开荒保洁。',                    80.00,  1, 'AUTO',   NULL, 0, DATE_SUB(NOW(), INTERVAL 20 HOUR),      DATE_SUB(NOW(), INTERVAL 15 MINUTE)),
(12, 'WO20261110000011', 13, 5,  4,    DATE_ADD(DATE_ADD(CURDATE(), INTERVAL 1 DAY), INTERVAL 15 HOUR), '康顺社区4号楼2单元1802', '13800000013', '冰箱冷冻室不结霜，改派王师傅。',                 120.00, 1, 'REASSIGN', NULL, 0, DATE_SUB(NOW(), INTERVAL 22 HOUR),      DATE_SUB(NOW(), INTERVAL 1 HOUR)),
(13, 'WO20261105000005', 15, 3,  3,    DATE_SUB(NOW(), INTERVAL 5 DAY),                        '康顺社区6号楼2单元403',  '13800000015', '入户总线老化想整体更换。',                         100.00, 3, 'AUTO',   DATE_ADD(DATE_SUB(NOW(), INTERVAL 5 DAY), INTERVAL 2 HOUR),   0, DATE_SUB(NOW(), INTERVAL 6 DAY),       DATE_ADD(DATE_SUB(NOW(), INTERVAL 5 DAY), INTERVAL 2 HOUR)),
(14, 'WO20261028000001', 10, 2,  2,    DATE_SUB(NOW(), INTERVAL 13 DAY),                       '康顺社区1号楼2单元101',  '13800000010', '阳台地漏堵了半个月。',                               60.00,  3, 'AUTO',   DATE_ADD(DATE_SUB(NOW(), INTERVAL 13 DAY), INTERVAL 1 HOUR),  1, DATE_SUB(NOW(), INTERVAL 14 DAY),      DATE_ADD(DATE_SUB(NOW(), INTERVAL 13 DAY), INTERVAL 1 HOUR)),
(15, 'WO20261106000006', 12, 6,  4,    DATE_SUB(NOW(), INTERVAL 4 DAY),                        '康顺社区3号楼1单元601',  '13800000012', '滚筒洗衣机拆机清洗，阳台有电源。',                   90.00,  3, 'MANUAL', DATE_ADD(DATE_SUB(NOW(), INTERVAL 4 DAY), INTERVAL 1 HOUR),   0, DATE_SUB(NOW(), INTERVAL 5 DAY),       DATE_ADD(DATE_SUB(NOW(), INTERVAL 4 DAY), INTERVAL 1 HOUR));

-- 6. service_evaluation：5 条（仅针对已完成且未归档订单 3/4/9/13/15；
--    resident_id/worker_id/service_id 与对应订单行完全一致；
--    各师傅评价 star 平均值与 worker_profile.rating_score 严格一致：
--    user2=5.00(无评价默认)、user3=(4+5)/2=4.50、user4=5.00、user5=5.00(仅归档完成单)、
--    user6=4.00、user7=(3+? order8归档无评价 ->仅order3=3.00)、user8/user9=5.00）
INSERT INTO service_evaluation (id, order_id, resident_id, worker_id, service_id, star, comment, create_time) VALUES
(1, 3,  14, 7, 8,  3, '移机后客厅制冷效果一般，师傅说冷媒还要补，态度不错，下次再找。',       DATE_SUB(NOW(), INTERVAL 5 DAY)),
(2, 4,  13, 3, 3,  4, '跳闸原因查得很仔细，换了漏电保护器就好了，就是上门比约定晚了半小时。', DATE_SUB(NOW(), INTERVAL 2 DAY)),
(3, 9,  13, 6, 12, 4, '赵师傅二十分钟技术开启，没破坏门，收费和下单一致，比较放心。',         DATE_SUB(NOW(), INTERVAL 1 DAY)),
(4, 13, 15, 3, 3,  5, '换线规范，用了绝缘检测仪，走线整齐，还贴了回路标签，五星好评！',       DATE_SUB(NOW(), INTERVAL 4 DAY)),
(5, 15, 12, 4, 6,  5, '洗衣机拆开洗干净了，霉味没了，师傅自带鞋套和垫布，很专业。',           DATE_SUB(NOW(), INTERVAL 3 DAY));

-- =====================================================================
-- 自洽性快速复核（脚本执行后可逐条 SELECT，期望每条 count = 0）
-- =====================================================================
-- ① 评价必须挂在已完成且未归档订单上，且三联字段与订单行一致：
SELECT COUNT(*) AS bad_eval FROM service_evaluation e JOIN service_order o ON o.id = e.order_id
 WHERE o.order_status <> 3 OR o.is_archived <> 0
    OR e.resident_id <> o.resident_id OR e.worker_id <> o.worker_id OR e.service_id <> o.service_id;

-- ② 订单价格快照必须与服务项目当前价格一致：
SELECT COUNT(*) AS bad_price FROM service_order o JOIN service_item s ON s.id = o.service_id WHERE o.price <> s.price;

-- ③ hot_score 与下单次数一致：
SELECT COUNT(*) AS bad_hot FROM service_item s LEFT JOIN (SELECT service_id, COUNT(*) c FROM service_order GROUP BY service_id) t ON t.service_id = s.id WHERE IFNULL(t.c, 0) <> s.hot_score;

-- ④ 已派单/服务中订单的师傅必须忙碌(work_status=2)：
SELECT COUNT(*) AS bad_busy FROM service_order o JOIN worker_profile w ON w.user_id = o.worker_id
 WHERE o.order_status IN (1, 2) AND w.work_status <> 2;

-- ⑤ total_orders 与已完成订单数一致、rating_score 与评价均值一致（无评价默认 5.00）：
SELECT COUNT(*) AS bad_worker FROM worker_profile w
 WHERE w.total_orders <> (SELECT COUNT(*) FROM service_order o WHERE o.worker_id = w.user_id AND o.order_status = 3)
    OR w.rating_score <> (SELECT IFNULL(AVG(e.star), 5.00) FROM service_evaluation e WHERE e.worker_id = w.user_id);

-- ⑥ 已派单订单的承接师傅必须是启用状态的 ROLE_WORKER 账号（AUTO/REASSIGN 订单的
--    技能标签覆盖校验涉及 | 分隔串求值，SQL 不便表达，由
--    .workbuddy/verify_community_init.py 脚本在库外完整复验）：
SELECT COUNT(*) AS bad_worker_ref FROM service_order o
 WHERE o.order_status IN (1, 2, 3)
   AND NOT EXISTS (SELECT 1 FROM sys_user u JOIN worker_profile w ON w.user_id = u.id
                   WHERE u.id = o.worker_id AND u.role = 'ROLE_WORKER' AND u.status = 1);
