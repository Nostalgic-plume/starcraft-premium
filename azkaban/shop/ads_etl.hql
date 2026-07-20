-- =========================================================
-- ADS 层 ETL — 指标汇总，供 Sqoop 导出至 MySQL shop_ads
-- 处理日期: ${hivevar:dt}
-- =========================================================
SET hive.execution.engine=spark;
SET hive.exec.dynamic.partition.mode=nonstrict;
SET mapreduce.job.reduces=4;

-- =========================================================
-- 1. ads_overview：全局概览（累计用户/商品 + 当日订单/GMV/均价）
-- =========================================================
INSERT OVERWRITE TABLE ads.ads_overview PARTITION (dt = '${hivevar:dt}')
SELECT
  (SELECT COUNT(1) FROM dwd.dwd_dim_user_zip WHERE end_date = '9999-12-31')     AS total_users,
  (SELECT COUNT(1) FROM dwd.dwd_dim_product_zip WHERE end_date = '9999-12-31')  AS total_products,
  NVL((SELECT order_count FROM dws.dws_order_daily WHERE dt = '${hivevar:dt}'), 0)       AS total_orders,
  NVL((SELECT actual_amount FROM dws.dws_order_daily WHERE dt = '${hivevar:dt}'), 0.0)   AS total_gmv,
  NVL((SELECT avg_order_amount FROM dws.dws_order_daily WHERE dt = '${hivevar:dt}'), 0.0) AS avg_order_price
FROM (SELECT 1) t;

-- =========================================================
-- 2. ads_gmv_daily：当日 GMV 日报
-- =========================================================
INSERT OVERWRITE TABLE ads.ads_gmv_daily PARTITION (dt = '${hivevar:dt}')
SELECT
  od.actual_amount       AS gmv,
  od.order_count,
  od.paid_order_count,
  NVL(pay.paid_users, 0) AS paid_user_count,
  od.avg_order_amount
FROM dws.dws_order_daily od
LEFT JOIN (
  SELECT COUNT(DISTINCT user_id) AS paid_users
  FROM dwd.dwd_fact_order_detail
  WHERE dt = '${hivevar:dt}' AND order_status IN (1,2,3,4)
) pay ON 1=1
WHERE od.dt = '${hivevar:dt}';

-- =========================================================
-- 3. ads_order_stats_daily：当日订单状态分布
-- =========================================================
INSERT OVERWRITE TABLE ads.ads_order_stats_daily PARTITION (dt = '${hivevar:dt}')
SELECT
  CAST(COUNT(*) AS INT)                                                     AS total_orders,
  CAST(SUM(IF(order_status = 1, 1, 0)) AS INT)                             AS paid_orders,
  CAST(SUM(IF(order_status = 2, 1, 0)) AS INT)                             AS shipped_orders,
  CAST(SUM(IF(order_status = 4, 1, 0)) AS INT)                             AS completed_orders,
  CAST(SUM(IF(order_status = 5, 1, 0)) AS INT)                             AS cancelled_orders,
  CAST(SUM(IF(order_status = 6, 1, 0)) AS INT)                             AS refund_orders,
  NVL(CAST(ROUND(
    CAST(SUM(IF(order_status = 1, 1, 0)) AS DECIMAL(10,2))
    / NULLIF(CAST(COUNT(*) AS DECIMAL(10,2)), 0) * 100,
  2) AS DECIMAL(5,2)), 0.00)                                              AS conversion_rate
FROM (
  SELECT DISTINCT order_id, order_status
  FROM dwd.dwd_fact_order_detail
  WHERE dt = '${hivevar:dt}'
) t;

-- =========================================================
-- 4. ads_category_sales：分类销售排行
-- 注：全表覆盖，保留所有品类
-- =========================================================
INSERT OVERWRITE TABLE ads.ads_category_sales
SELECT
  '${hivevar:dt}'                                             AS dt,
  first_category_id,
  COALESCE(category_name, '未知')                              AS category_name,
  CAST(SUM(quantity) AS BIGINT)                               AS sale_count,
  CAST(SUM(actual_amount) AS DECIMAL(16,2))                   AS sale_amount
FROM dwd.dwd_fact_order_detail
WHERE dt = '${hivevar:dt}'
GROUP BY first_category_id, category_name
ORDER BY sale_amount DESC;

-- =========================================================
-- 5. ads_product_top50：商品销量 TOP 50
-- 注：全表覆盖
-- =========================================================
INSERT OVERWRITE TABLE ads.ads_product_top50
SELECT
  '${hivevar:dt}'                                             AS dt,
  product_id,
  product_name,
  category_name,
  CAST(SUM(quantity) AS BIGINT)                               AS sale_quantity,
  CAST(SUM(actual_amount) AS DECIMAL(16,2))                   AS sale_amount
FROM dwd.dwd_fact_order_detail
WHERE dt = '${hivevar:dt}'
GROUP BY product_id, product_name, category_name
ORDER BY sale_amount DESC
LIMIT 50;

-- =========================================================
-- 6. ads_trend_weekly：近 8 周趋势（全表覆盖）
-- dayofweek: 1=周日,2=周一...7=周六
-- week_start = 当周周一, week_end = 当周周日
-- 注意：Hive 中 WITH 子句必须在 INSERT 之前
-- =========================================================
WITH weekly_agg AS (
  SELECT
    date_sub(dt, CAST((dayofweek(dt) + 5) % 7 AS INT))       AS week_start,
    date_add(date_sub(dt, CAST((dayofweek(dt) + 5) % 7 AS INT)), 6) AS week_end,
    CAST(SUM(actual_amount) AS DECIMAL(16,2))                 AS gmv,
    CAST(SUM(order_count) AS BIGINT)                          AS order_count,
    CAST(SUM(user_count) AS BIGINT)                           AS user_count
  FROM dws.dws_order_daily
  WHERE dt >= date_add('${hivevar:dt}', -55)
  GROUP BY date_sub(dt, CAST((dayofweek(dt) + 5) % 7 AS INT)),
           date_add(date_sub(dt, CAST((dayofweek(dt) + 5) % 7 AS INT)), 6)
)
INSERT OVERWRITE TABLE ads.ads_trend_weekly
SELECT
  week_start,
  week_end,
  gmv,
  order_count,
  user_count,
  CAST(COALESCE(
    ROUND((gmv - LAG(gmv) OVER (ORDER BY week_start)) / NULLIF(LAG(gmv) OVER (ORDER BY week_start), 0) * 100, 2),
    0.00
  ) AS DECIMAL(5,2))                                          AS gmv_mom
FROM weekly_agg
ORDER BY week_start ASC;

-- =========================================================
-- 7. ads_user_active：当日用户活跃
-- =========================================================
INSERT OVERWRITE TABLE ads.ads_user_active PARTITION (dt = '${hivevar:dt}')
SELECT
  ua.dau,
  ua.order_user_count
FROM dws.dws_user_active_daily ua
WHERE ua.dt = '${hivevar:dt}';

-- =========================================================
-- 8. ads_user_new_retain：用户新增与留存
-- dayX_retain_users = 在 dt day 活跃的用户中，属于 X 天前注册的 cohort
-- =========================================================
INSERT OVERWRITE TABLE ads.ads_user_new_retain PARTITION (dt = '${hivevar:dt}')
SELECT
  CAST((SELECT COUNT(1) FROM dwd.dwd_dim_user_zip
         WHERE start_date = '${hivevar:dt}' AND end_date = '9999-12-31') AS INT) AS new_users,

  CAST((SELECT COUNT(1) FROM dwd.dwd_dim_user_zip c
         WHERE c.start_date = date_add('${hivevar:dt}', -1) AND c.end_date = '9999-12-31'
           AND EXISTS (SELECT 1 FROM dwd.dwd_fact_order_detail a
                        WHERE a.dt = '${hivevar:dt}' AND a.user_id = c.user_id)) AS INT) AS day1_retain_users,

  NVL(CAST(ROUND(
    (SELECT COUNT(1) FROM dwd.dwd_dim_user_zip c
      WHERE c.start_date = date_add('${hivevar:dt}', -1) AND c.end_date = '9999-12-31'
        AND EXISTS (SELECT 1 FROM dwd.dwd_fact_order_detail a
                     WHERE a.dt = '${hivevar:dt}' AND a.user_id = c.user_id)) * 100.0 /
    NULLIF((SELECT COUNT(1) FROM dwd.dwd_dim_user_zip
             WHERE start_date = date_add('${hivevar:dt}', -1) AND end_date = '9999-12-31'), 0)
  , 2) AS DECIMAL(5,2)), 0.00) AS day1_retain_rate,

  CAST((SELECT COUNT(1) FROM dwd.dwd_dim_user_zip c
         WHERE c.start_date = date_add('${hivevar:dt}', -7) AND c.end_date = '9999-12-31'
           AND EXISTS (SELECT 1 FROM dwd.dwd_fact_order_detail a
                        WHERE a.dt = '${hivevar:dt}' AND a.user_id = c.user_id)) AS INT) AS day7_retain_users,

  NVL(CAST(ROUND(
    (SELECT COUNT(1) FROM dwd.dwd_dim_user_zip c
      WHERE c.start_date = date_add('${hivevar:dt}', -7) AND c.end_date = '9999-12-31'
        AND EXISTS (SELECT 1 FROM dwd.dwd_fact_order_detail a
                     WHERE a.dt = '${hivevar:dt}' AND a.user_id = c.user_id)) * 100.0 /
    NULLIF((SELECT COUNT(1) FROM dwd.dwd_dim_user_zip
             WHERE start_date = date_add('${hivevar:dt}', -7) AND end_date = '9999-12-31'), 0)
  , 2) AS DECIMAL(5,2)), 0.00) AS day7_retain_rate,

  CAST((SELECT COUNT(1) FROM dwd.dwd_dim_user_zip c
         WHERE c.start_date = date_add('${hivevar:dt}', -30) AND c.end_date = '9999-12-31'
           AND EXISTS (SELECT 1 FROM dwd.dwd_fact_order_detail a
                        WHERE a.dt = '${hivevar:dt}' AND a.user_id = c.user_id)) AS INT) AS day30_retain_users,

  NVL(CAST(ROUND(
    (SELECT COUNT(1) FROM dwd.dwd_dim_user_zip c
      WHERE c.start_date = date_add('${hivevar:dt}', -30) AND c.end_date = '9999-12-31'
        AND EXISTS (SELECT 1 FROM dwd.dwd_fact_order_detail a
                     WHERE a.dt = '${hivevar:dt}' AND a.user_id = c.user_id)) * 100.0 /
    NULLIF((SELECT COUNT(1) FROM dwd.dwd_dim_user_zip
             WHERE start_date = date_add('${hivevar:dt}', -30) AND end_date = '9999-12-31'), 0)
  , 2) AS DECIMAL(5,2)), 0.00) AS day30_retain_rate
FROM (SELECT 1) t;

-- =========================================================
-- 9. ads_shop_daily：店铺日报
-- 需关联 shop 表获取 shop_name
-- =========================================================
INSERT OVERWRITE TABLE ads.ads_shop_daily PARTITION (dt = '${hivevar:dt}')
SELECT
  COALESCE(s.shop_name, CONCAT('店铺_', sd.shop_id)) AS shop_name,
  sd.gmv,
  sd.order_count,
  sd.product_count,
  NVL(sd.shop_id, 0) AS shop_id
FROM dws.dws_shop_daily sd
LEFT JOIN ods.ods_shop_full s ON sd.shop_id = s.id AND s.dt = '${hivevar:dt}'
WHERE sd.dt = '${hivevar:dt}';
