-- 个性化实时推荐子系统 - 推荐库建表脚本
-- 执行方式：mysql -u root -p < rec-schema.sql
-- 对应文档：《详细设计说明书》4.1 MySQL 数据表详细设计（7 张表）
--
-- 库划分说明：按概要设计 5.2，系统不新建独立数据库实例，推荐库为独立 database（shop_rec），
-- 与商城业务库（shop）分离；推荐服务通过独立数据源读写本库，对商城业务库只读。

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS shop_rec
    DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;

USE shop_rec;

-- ========== 1. 行为明细表 ==========
-- 用途：唯一记录用户行为事件，用于画像生成、特征重建与离线统计，以事件标识唯一约束实现上报去重
CREATE TABLE IF NOT EXISTS t_user_behavior_log (
    id            bigint       NOT NULL                        COMMENT '主键标识',
    event_id      varchar(64)  NOT NULL                        COMMENT '上报事件的幂等标识，唯一约束用于去重',
    user_id       bigint       NOT NULL                        COMMENT '用户标识',
    item_id       bigint       NOT NULL                        COMMENT '商品标识',
    event_type    varchar(16)  NOT NULL                        COMMENT '行为类型：view/add_cart/favorite/purchase',
    event_time    datetime(3)  NOT NULL                        COMMENT '行为发生时间（事件时间），窗口归属以此为准',
    duration      int                   DEFAULT 0              COMMENT '商品页停留时长（毫秒），空值表示未采集；仅用于无效行为识别，禁止参与加权与特征计算',
    received_time datetime(3)  NOT NULL                        COMMENT '服务端接收时间，用于计算链路时延',
    source        tinyint      NOT NULL DEFAULT 0              COMMENT '数据来源：0 真实上报，1 离线灌数',
    create_time   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_user_time (user_id, event_time),
    KEY idx_item_time (item_id, event_time),
    KEY idx_received (received_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户行为明细';

-- ========== 2. 用户画像表 ==========
-- 用途：保存用户长期偏好及其历史版本，支持版本回滚与有效性切换
CREATE TABLE IF NOT EXISTS t_user_profile (
    id             bigint      NOT NULL                        COMMENT '主键标识',
    user_id        bigint      NOT NULL                        COMMENT '用户标识',
    version        int         NOT NULL                        COMMENT '版本号，取日期 yyyyMMdd',
    category_pref  json                 DEFAULT NULL           COMMENT '类目偏好列表，元素含类目标识与偏好分，默认取前 5 名',
    brand_pref     json                 DEFAULT NULL           COMMENT '品牌偏好列表，元素含品牌标识与偏好分，默认取前 5 名',
    behavior_count int         NOT NULL DEFAULT 0              COMMENT '参与归纳的行为条数',
    status         tinyint     NOT NULL DEFAULT 0              COMMENT '状态：0 有效，1 历史版本',
    create_time    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_version (user_id, version),
    KEY idx_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户画像（带版本）';

-- ========== 3. 商品热度表 ==========
-- 用途：保存各统计窗口下的商品热度快照，用于离线核对与索引重建
CREATE TABLE IF NOT EXISTS t_item_hot_score (
    id          bigint        NOT NULL                        COMMENT '主键标识',
    item_id     bigint        NOT NULL                        COMMENT '商品标识',
    window_type varchar(8)    NOT NULL                        COMMENT '统计窗口：1h/24h/7d',
    score       decimal(12,4) NOT NULL DEFAULT 0              COMMENT '热度分，行为加权累加值',
    stat_time   datetime      NOT NULL                        COMMENT '统计时间点',
    update_time datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_item_window (item_id, window_type, stat_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品热度快照';

-- ========== 4. 策略配置表 ==========
-- 用途：保存推荐策略配置项，是配置的权威来源（内存库副本用于热更新）
CREATE TABLE IF NOT EXISTS t_rec_config (
    id           bigint       NOT NULL                        COMMENT '主键标识',
    config_key   varchar(64)  NOT NULL                        COMMENT '配置键',
    config_value varchar(255) NOT NULL                        COMMENT '配置值',
    value_type   varchar(16)  NOT NULL DEFAULT 'string'       COMMENT '值类型：int/decimal/string/json',
    group_name   varchar(32)  NOT NULL                        COMMENT '配置分组：行为权值、窗口权值、召回、打分、缓存、开关',
    remark       varchar(255)          DEFAULT NULL           COMMENT '配置说明',
    operator     varchar(32)           DEFAULT NULL           COMMENT '最后修改人',
    update_time  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后修改时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key),
    KEY idx_group (group_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐策略配置';

-- ========== 5. 固定坑位表 ==========
-- 用途：保存运营配置的固定坑位，输出阶段按位置注入榜单（不参与个性化打分）
CREATE TABLE IF NOT EXISTS t_rec_fixed_slot (
    id            bigint   NOT NULL                        COMMENT '主键标识',
    slot_position int      NOT NULL                        COMMENT '坑位位置，从 1 开始',
    item_id       bigint   NOT NULL                        COMMENT '坑位商品标识',
    start_time    datetime NOT NULL                        COMMENT '生效开始时间',
    end_time      datetime NOT NULL                        COMMENT '生效结束时间',
    status        tinyint  NOT NULL DEFAULT 1              COMMENT '状态：0 停用，1 启用',
    operator      varchar(32)       DEFAULT NULL           COMMENT '配置人',
    create_time   datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_position_time (slot_position, start_time),
    KEY idx_item (item_id),
    KEY idx_status_time (status, start_time, end_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='固定坑位配置';

-- ========== 6. 过滤规则表 ==========
-- 用途：保存黑名单、会员限定、地域限制与低质商品规则，供融合过滤环节读取
CREATE TABLE IF NOT EXISTS t_rec_filter_rule (
    id           bigint       NOT NULL                        COMMENT '主键标识',
    rule_type    varchar(16)  NOT NULL                        COMMENT '规则类型：blacklist/member_only/region_limit/low_quality',
    target_type  varchar(16)  NOT NULL                        COMMENT '作用对象：item/category',
    target_value varchar(64)  NOT NULL                        COMMENT '对象取值：商品标识、类目标识或地区编码',
    status       tinyint      NOT NULL DEFAULT 1              COMMENT '状态：0 停用，1 启用',
    remark       varchar(255)          DEFAULT NULL           COMMENT '规则说明',
    operator     varchar(32)           DEFAULT NULL           COMMENT '维护人',
    update_time  datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rule_target (rule_type, target_type, target_value),
    KEY idx_type_status (rule_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐过滤规则';

-- ========== 7. 链路指标表 ==========
-- 用途：保存特征覆盖率、召回命中率、降级次数与各环节耗时等观测数据
CREATE TABLE IF NOT EXISTS t_pipeline_metric (
    id           bigint        NOT NULL                        COMMENT '主键标识',
    metric_name  varchar(64)   NOT NULL                        COMMENT '指标名称',
    metric_dim   varchar(64)            DEFAULT NULL           COMMENT '维度取值，如降级状态、召回路由、丢弃原因',
    metric_value decimal(14,4) NOT NULL DEFAULT 0              COMMENT '指标值；计数类存次数，比率类存百分比',
    stat_time    datetime      NOT NULL                        COMMENT '统计时间点，汇总指标取整点',
    create_time  datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
    PRIMARY KEY (id),
    KEY idx_metric_time (metric_name, stat_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='推荐链路指标';

-- ========== 8. 建表校验（7 张表应全部存在）==========
SELECT TABLE_NAME AS 表名, TABLE_COMMENT AS 说明
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'shop_rec'
ORDER BY TABLE_NAME;
