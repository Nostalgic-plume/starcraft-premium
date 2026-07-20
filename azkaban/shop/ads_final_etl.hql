-- =========================================================
-- ADS 层 ETL — 非分区表（最终日一次性刷新）
-- 分区表请使用 ads_daily_etl.hql
-- 处理日期: ${hivevar:dt}
-- =========================================================
SET hive.execution.engine=spark;
SET hive.exec.dynamic.partition.mode=nonstrict;
SET mapreduce.job.reduces=4;

-- =========================================================
-- 4. ads_category_sales：分类销售排行（全表覆盖）
-- 注：回填时仅最终日跑一次，日常 Azkaban 流每日跑
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
-- 5. ads_product_top50：商品销量 TOP 50（全表覆盖）
-- 注：回填时仅最终日跑一次，日常 Azkaban 流每日跑
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
-- 注：回填时读取 dws_order_daily 已有历史数据，展示完整趋势
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
