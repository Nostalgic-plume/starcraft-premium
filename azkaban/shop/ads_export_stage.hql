-- =========================================================
-- ADS 导出临时文件生成：Hive ADS → HDFS 文本文件（\001 分隔）
-- 合并所有 9 张表的 INSERT OVERWRITE DIRECTORY 为一次 Spark 作业
-- 调用方式: hive -f ads_export_stage.hql --hivevar dt=YYYY-MM-DD
-- =========================================================
SET hive.execution.engine=spark;

-- =========================================================
-- 分区表（按 dt 过滤）
-- =========================================================

-- 1. ads_overview：全局概览
INSERT OVERWRITE DIRECTORY '/tmp/sqoop/ads_export/ads_overview/${hivevar:dt}'
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
SELECT '${hivevar:dt}' AS dt,
       total_users, total_products, total_orders, total_gmv, avg_order_price
FROM ads.ads_overview
WHERE dt = '${hivevar:dt}';

-- 2. ads_gmv_daily：当日 GMV 日报
INSERT OVERWRITE DIRECTORY '/tmp/sqoop/ads_export/ads_gmv_daily/${hivevar:dt}'
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
SELECT '${hivevar:dt}' AS dt,
       gmv, order_count, paid_order_count, paid_user_count, avg_order_amount
FROM ads.ads_gmv_daily
WHERE dt = '${hivevar:dt}';

-- 3. ads_order_stats_daily：当日订单状态分布
INSERT OVERWRITE DIRECTORY '/tmp/sqoop/ads_export/ads_order_stats_daily/${hivevar:dt}'
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
SELECT '${hivevar:dt}' AS dt,
       total_orders, paid_orders, shipped_orders, completed_orders,
       cancelled_orders, refund_orders,
       NVL(conversion_rate, 0.00) AS conversion_rate
FROM ads.ads_order_stats_daily
WHERE dt = '${hivevar:dt}';

-- 4. ads_user_active：当日用户活跃
INSERT OVERWRITE DIRECTORY '/tmp/sqoop/ads_export/ads_user_active/${hivevar:dt}'
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
SELECT '${hivevar:dt}' AS dt,
       dau, order_user_count
FROM ads.ads_user_active
WHERE dt = '${hivevar:dt}';

-- 5. ads_user_new_retain：用户新增与留存
INSERT OVERWRITE DIRECTORY '/tmp/sqoop/ads_export/ads_user_new_retain/${hivevar:dt}'
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
SELECT '${hivevar:dt}' AS dt,
       new_users,
       day1_retain_users, NVL(day1_retain_rate, 0.00) AS day1_retain_rate,
       day7_retain_users, NVL(day7_retain_rate, 0.00) AS day7_retain_rate,
       day30_retain_users, NVL(day30_retain_rate, 0.00) AS day30_retain_rate
FROM ads.ads_user_new_retain
WHERE dt = '${hivevar:dt}';

-- 6. ads_shop_daily：店铺日报
INSERT OVERWRITE DIRECTORY '/tmp/sqoop/ads_export/ads_shop_daily/${hivevar:dt}'
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
SELECT '${hivevar:dt}' AS dt,
       NVL(shop_id, 0) AS shop_id,
       shop_name, gmv, order_count, product_count
FROM ads.ads_shop_daily
WHERE dt = '${hivevar:dt}';

-- =========================================================
-- 非分区表（全量写入）
-- =========================================================

-- 7. ads_category_sales：分类销售排行
INSERT OVERWRITE DIRECTORY '/tmp/sqoop/ads_export/ads_category_sales'
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
SELECT dt, first_category_id, category_name, sale_count, sale_amount
FROM ads.ads_category_sales;

-- 8. ads_product_top50：商品销量 TOP 50
INSERT OVERWRITE DIRECTORY '/tmp/sqoop/ads_export/ads_product_top50'
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
SELECT dt, product_id, product_name, category_name, sale_quantity, sale_amount
FROM ads.ads_product_top50;

-- 9. ads_trend_weekly：近 8 周趋势
INSERT OVERWRITE DIRECTORY '/tmp/sqoop/ads_export/ads_trend_weekly'
ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
SELECT week_start, week_end, gmv, order_count, user_count, gmv_mom
FROM ads.ads_trend_weekly;
