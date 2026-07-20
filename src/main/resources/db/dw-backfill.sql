-- =========================================================
-- Shop 离线数仓 - 历史数据回填 + 日常 ETL
-- 幂等执行：使用 INSERT ... ON DUPLICATE KEY UPDATE
-- =========================================================

-- ========== 1. 日汇总统计 ==========
INSERT INTO `dw_daily_stats` (
    `stat_date`, `total_gmv`, `total_orders`,
    `paid_orders`, `shipped_orders`, `completed_orders`,
    `cancelled_orders`, `refund_orders`, `new_users`, `avg_order_price`
)
SELECT
    DATE(o.create_time)                                                  AS `stat_date`,
    COALESCE(SUM(CASE WHEN o.status IN (1,2,3,4) THEN o.actual_amount ELSE 0 END), 0) AS `total_gmv`,
    COUNT(*)                                                             AS `total_orders`,
    SUM(CASE WHEN o.status = 1 THEN 1 ELSE 0 END)                       AS `paid_orders`,
    SUM(CASE WHEN o.status = 2 THEN 1 ELSE 0 END)                       AS `shipped_orders`,
    SUM(CASE WHEN o.status IN (3,4) THEN 1 ELSE 0 END)                  AS `completed_orders`,
    SUM(CASE WHEN o.status = 5 THEN 1 ELSE 0 END)                       AS `cancelled_orders`,
    SUM(CASE WHEN o.status = 6 THEN 1 ELSE 0 END)                       AS `refund_orders`,
    (SELECT COUNT(*) FROM `user` u
     WHERE u.deleted = 0 AND DATE(u.create_time) = DATE(o.create_time))  AS `new_users`,
    COALESCE(AVG(CASE WHEN o.status IN (1,2,3,4) THEN o.actual_amount ELSE NULL END), 0) AS `avg_order_price`
FROM `orders` o
WHERE o.deleted = 0
GROUP BY DATE(o.create_time)
ON DUPLICATE KEY UPDATE
    `total_gmv`        = VALUES(`total_gmv`),
    `total_orders`     = VALUES(`total_orders`),
    `paid_orders`      = VALUES(`paid_orders`),
    `shipped_orders`   = VALUES(`shipped_orders`),
    `completed_orders` = VALUES(`completed_orders`),
    `cancelled_orders` = VALUES(`cancelled_orders`),
    `refund_orders`    = VALUES(`refund_orders`),
    `new_users`        = VALUES(`new_users`),
    `avg_order_price`  = VALUES(`avg_order_price`);


-- ========== 2. 分类销售排行 ==========
INSERT INTO `dw_category_sales` (
    `stat_date`, `category_id`, `category_name`, `sale_amount`, `sale_count`
)
SELECT
    DATE(o.create_time)                      AS `stat_date`,
    c.id                                     AS `category_id`,
    c.name                                   AS `category_name`,
    COALESCE(SUM(oi.subtotal), 0)            AS `sale_amount`,
    SUM(oi.quantity)                         AS `sale_count`
FROM `order_item` oi
JOIN `orders` o ON o.id = oi.order_id
JOIN `product` p ON p.id = oi.product_id
JOIN `category` c ON c.id = p.category_id
WHERE o.deleted = 0 AND o.status IN (1,2,3,4)
GROUP BY DATE(o.create_time), c.id, c.name
ON DUPLICATE KEY UPDATE
    `sale_amount` = VALUES(`sale_amount`),
    `sale_count`  = VALUES(`sale_count`);


-- ========== 3. 商品销售排行 ==========
INSERT INTO `dw_product_sales` (
    `stat_date`, `product_id`, `product_name`, `sale_amount`, `sale_quantity`
)
SELECT
    DATE(o.create_time)                       AS `stat_date`,
    oi.product_id                             AS `product_id`,
    oi.product_name                           AS `product_name`,
    COALESCE(SUM(oi.subtotal), 0)             AS `sale_amount`,
    SUM(oi.quantity)                          AS `sale_quantity`
FROM `order_item` oi
JOIN `orders` o ON o.id = oi.order_id
WHERE o.deleted = 0 AND o.status IN (1,2,3,4)
GROUP BY DATE(o.create_time), oi.product_id, oi.product_name
ON DUPLICATE KEY UPDATE
    `sale_amount`   = VALUES(`sale_amount`),
    `sale_quantity` = VALUES(`sale_quantity`);


-- ========== 4. 周趋势 ==========
INSERT INTO `dw_weekly_trend` (
    `week_start`, `week_end`, `gmv`, `order_count`
)
SELECT
    DATE_SUB(DATE(o.create_time), INTERVAL WEEKDAY(o.create_time) DAY)   AS `week_start`,
    DATE_ADD(
        DATE_SUB(DATE(o.create_time), INTERVAL WEEKDAY(o.create_time) DAY),
        INTERVAL 6 DAY
    )                                                                     AS `week_end`,
    COALESCE(SUM(CASE WHEN o.status IN (1,2,3,4) THEN o.actual_amount ELSE 0 END), 0) AS `gmv`,
    COUNT(*)                                                              AS `order_count`
FROM `orders` o
WHERE o.deleted = 0
GROUP BY `week_start`
ON DUPLICATE KEY UPDATE
    `week_end`     = VALUES(`week_end`),
    `gmv`          = VALUES(`gmv`),
    `order_count`  = VALUES(`order_count`);


-- ========== 5. 用户指标 ==========
INSERT INTO `dw_user_metrics` (
    `stat_date`, `dau`, `order_user_count`, `new_users`,
    `day1_retain_users`, `day7_retain_users`, `day30_retain_users`
)
WITH
-- 所有有数据的日期
dates AS (
    SELECT DISTINCT DATE(create_time) AS stat_date FROM `orders` WHERE deleted = 0
    UNION
    SELECT DISTINCT DATE(create_time) FROM `user` WHERE deleted = 0
),
-- 日活跃用户（当日下单用户）
dau AS (
    SELECT DATE(create_time) AS dt, COUNT(DISTINCT user_id) AS cnt
    FROM `orders` WHERE deleted = 0 GROUP BY DATE(create_time)
),
-- 日下单支付用户
order_users AS (
    SELECT DATE(create_time) AS dt, COUNT(DISTINCT user_id) AS cnt
    FROM `orders` WHERE deleted = 0 AND status IN (1,2,3,4) GROUP BY DATE(create_time)
),
-- 新增用户
new_users AS (
    SELECT DATE(create_time) AS dt, COUNT(*) AS cnt
    FROM `user` WHERE deleted = 0 GROUP BY DATE(create_time)
),
-- 用户首次下单日期
first_orders AS (
    SELECT user_id, MIN(DATE(create_time)) AS first_order_date
    FROM `orders` WHERE deleted = 0 GROUP BY user_id
),
-- 次日留存：首单后第 1 天有单
retain_day1 AS (
    SELECT fo.first_order_date AS dt, COUNT(*) AS cnt
    FROM first_orders fo
    WHERE EXISTS (
        SELECT 1 FROM `orders` o2
        WHERE o2.user_id = fo.user_id AND o2.deleted = 0
          AND DATE(o2.create_time) = DATE_ADD(fo.first_order_date, INTERVAL 1 DAY)
    )
    GROUP BY fo.first_order_date
),
-- 7 日留存：首单后第 2~7 天有单
retain_day7 AS (
    SELECT fo.first_order_date AS dt, COUNT(*) AS cnt
    FROM first_orders fo
    WHERE EXISTS (
        SELECT 1 FROM `orders` o2
        WHERE o2.user_id = fo.user_id AND o2.deleted = 0
          AND DATE(o2.create_time) > fo.first_order_date
          AND DATE(o2.create_time) <= DATE_ADD(fo.first_order_date, INTERVAL 7 DAY)
    )
    GROUP BY fo.first_order_date
),
-- 30 日留存：首单后第 2~30 天有单
retain_day30 AS (
    SELECT fo.first_order_date AS dt, COUNT(*) AS cnt
    FROM first_orders fo
    WHERE EXISTS (
        SELECT 1 FROM `orders` o2
        WHERE o2.user_id = fo.user_id AND o2.deleted = 0
          AND DATE(o2.create_time) > fo.first_order_date
          AND DATE(o2.create_time) <= DATE_ADD(fo.first_order_date, INTERVAL 30 DAY)
    )
    GROUP BY fo.first_order_date
)
SELECT
    d.stat_date,
    COALESCE(dau.cnt, 0)          AS `dau`,
    COALESCE(ou.cnt, 0)           AS `order_user_count`,
    COALESCE(nu.cnt, 0)           AS `new_users`,
    COALESCE(r1.cnt, 0)           AS `day1_retain_users`,
    COALESCE(r7.cnt, 0)           AS `day7_retain_users`,
    COALESCE(r30.cnt, 0)          AS `day30_retain_users`
FROM dates d
LEFT JOIN dau   ON d.stat_date = dau.dt
LEFT JOIN order_users ou ON d.stat_date = ou.dt
LEFT JOIN new_users nu ON d.stat_date = nu.dt
LEFT JOIN retain_day1 r1  ON d.stat_date = r1.dt
LEFT JOIN retain_day7 r7  ON d.stat_date = r7.dt
LEFT JOIN retain_day30 r30 ON d.stat_date = r30.dt
ON DUPLICATE KEY UPDATE
    `dau`                = VALUES(`dau`),
    `order_user_count`   = VALUES(`order_user_count`),
    `new_users`          = VALUES(`new_users`),
    `day1_retain_users`  = VALUES(`day1_retain_users`),
    `day7_retain_users`  = VALUES(`day7_retain_users`),
    `day30_retain_users` = VALUES(`day30_retain_users`);
