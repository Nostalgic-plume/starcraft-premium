-- =========================================================
-- Shop 离线数仓 - 预聚合表 DDL
-- 注意：此处使用 CREATE TABLE IF NOT EXISTS，幂等执行
-- =========================================================

-- ① 日汇总统计表
-- 维度：日期 | 度量：GMV、订单数、支付数、客单价、新增用户
CREATE TABLE IF NOT EXISTS `dw_daily_stats` (
    `id`               BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    `stat_date`        DATE           NOT NULL COMMENT '统计日期',
    `total_gmv`        DECIMAL(15,2)  NOT NULL DEFAULT 0.00 COMMENT '总 GMV（已完成支付订单）',
    `total_orders`     INT            NOT NULL DEFAULT 0 COMMENT '总订单数',
    `paid_orders`      INT            NOT NULL DEFAULT 0 COMMENT '已支付订单数',
    `shipped_orders`   INT            NOT NULL DEFAULT 0 COMMENT '已发货订单数',
    `completed_orders` INT            NOT NULL DEFAULT 0 COMMENT '已完成订单数',
    `cancelled_orders` INT            NOT NULL DEFAULT 0 COMMENT '已取消订单数',
    `refund_orders`    INT            NOT NULL DEFAULT 0 COMMENT '已退款订单数',
    `new_users`        INT            NOT NULL DEFAULT 0 COMMENT '新增用户数',
    `avg_order_price`  DECIMAL(10,2)  NOT NULL DEFAULT 0.00 COMMENT '平均客单价（已支付订单）',
    `create_time`      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME       NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_stat_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数仓：日汇总统计';


-- ② 分类销售排行表
-- 维度：日期 + 分类 | 度量：销售额、销量
CREATE TABLE IF NOT EXISTS `dw_category_sales` (
    `id`              BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    `stat_date`       DATE           NOT NULL COMMENT '统计日期',
    `category_id`     BIGINT         NOT NULL COMMENT '分类ID',
    `category_name`   VARCHAR(50)    NOT NULL COMMENT '分类名称',
    `sale_amount`     DECIMAL(15,2)  NOT NULL DEFAULT 0.00 COMMENT '销售额',
    `sale_count`      INT            NOT NULL DEFAULT 0 COMMENT '销量（件数）',
    `create_time`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY `uk_date_category` (`stat_date`, `category_id`),
    KEY `idx_date_amount` (`stat_date`, `sale_amount` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数仓：分类销售排行';


-- ③ 商品销售排行表
-- 维度：日期 + 商品 | 度量：销售额、销量
CREATE TABLE IF NOT EXISTS `dw_product_sales` (
    `id`              BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    `stat_date`       DATE           NOT NULL COMMENT '统计日期',
    `product_id`      BIGINT         NOT NULL COMMENT '商品ID',
    `product_name`    VARCHAR(200)   NOT NULL COMMENT '商品名称',
    `sale_amount`     DECIMAL(15,2)  NOT NULL DEFAULT 0.00 COMMENT '销售额',
    `sale_quantity`   INT            NOT NULL DEFAULT 0 COMMENT '销量（件数）',
    `create_time`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY `uk_date_product` (`stat_date`, `product_id`),
    KEY `idx_date_amount` (`stat_date`, `sale_amount` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数仓：商品销售排行';


-- ④ 周趋势表
-- 维度：自然周 | 度量：GMV、订单数
CREATE TABLE IF NOT EXISTS `dw_weekly_trend` (
    `id`              BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    `week_start`      DATE           NOT NULL COMMENT '周开始日期（周一）',
    `week_end`        DATE           NOT NULL COMMENT '周结束日期（周日）',
    `gmv`             DECIMAL(15,2)  NOT NULL DEFAULT 0.00 COMMENT '周 GMV',
    `order_count`     INT            NOT NULL DEFAULT 0 COMMENT '周订单数',
    `create_time`     DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY `uk_week_start` (`week_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数仓：周趋势';


-- ⑤ 用户指标表
-- 维度：日期 | 度量：DAU、下单用户数、新增用户、留存用户
CREATE TABLE IF NOT EXISTS `dw_user_metrics` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
    `stat_date`          DATE         NOT NULL COMMENT '统计日期',
    `dau`                INT          NOT NULL DEFAULT 0 COMMENT '日活跃用户（当日下单用户数）',
    `order_user_count`   INT          NOT NULL DEFAULT 0 COMMENT '下单用户数（当日支付用户）',
    `new_users`          INT          NOT NULL DEFAULT 0 COMMENT '新增用户（注册数）',
    `day1_retain_users`  INT          NOT NULL DEFAULT 0 COMMENT '次日留存用户数',
    `day7_retain_users`  INT          NOT NULL DEFAULT 0 COMMENT '7日留存用户数',
    `day30_retain_users` INT          NOT NULL DEFAULT 0 COMMENT '30日留存用户数',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY `uk_stat_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数仓：用户指标';
