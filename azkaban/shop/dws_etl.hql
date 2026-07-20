-- =========================================================
-- DWS 层 ETL — 轻度聚合
-- 处理日期: ${hivevar:dt}
-- =========================================================
SET hive.execution.engine=spark;
SET hive.exec.dynamic.partition.mode=nonstrict;
SET mapreduce.job.reduces=4;

-- =========================================================
-- 1. dws_order_daily：订单日汇总（订单级去重避免金额重复累加）
--    原 CTE order_level 改为内联子查询，兼容 Hive 3.x SQL 解析
-- =========================================================
INSERT OVERWRITE TABLE dws.dws_order_daily PARTITION (dt = '${hivevar:dt}')
SELECT
  COUNT(1)                                       AS order_count,
  COUNT(CASE WHEN order_status IN (1,2,3,4)
             THEN 1 END)                         AS paid_order_count,
  COUNT(CASE WHEN order_status = 4 THEN 1 END)   AS completed_count,
  COUNT(CASE WHEN order_status = 5 THEN 1 END)   AS cancelled_count,
  COALESCE(SUM(total_amount), 0)                 AS total_amount,
  COALESCE(SUM(actual_amount), 0)                AS actual_amount,
  COALESCE(SUM(discount_amount), 0)              AS discount_amount,
  COUNT(DISTINCT user_id)                        AS user_count,
  COALESCE(ROUND(SUM(actual_amount) / NULLIF(COUNT(1), 0), 2), 0) AS avg_order_amount,
  (SELECT COALESCE(SUM(quantity), 0)
   FROM dwd.dwd_fact_order_detail
   WHERE dt = '${hivevar:dt}')                   AS product_sold
FROM (
  SELECT DISTINCT order_id, user_id, shop_id,
         total_amount, actual_amount, discount_amount, order_status
  FROM dwd.dwd_fact_order_detail
  WHERE dt = '${hivevar:dt}'
    AND order_status <> 5
) order_level;

-- =========================================================
-- 2. dws_product_sale_daily：商品销售日汇总
-- =========================================================
INSERT OVERWRITE TABLE dws.dws_product_sale_daily PARTITION (dt = '${hivevar:dt}')
SELECT
  product_id,
  product_name,
  category_name,
  COALESCE(SUM(quantity), 0)   AS sale_quantity,
  COALESCE(SUM(subtotal), 0)   AS sale_amount,
  COUNT(DISTINCT order_id)     AS order_count
FROM dwd.dwd_fact_order_detail
WHERE dt = '${hivevar:dt}'
GROUP BY product_id, product_name, category_name;

-- =========================================================
-- 3. dws_shop_daily：店铺日汇总
-- =========================================================
INSERT OVERWRITE TABLE dws.dws_shop_daily PARTITION (dt = '${hivevar:dt}')
SELECT
  shop_id,
  COALESCE(SUM(CASE WHEN order_status NOT IN (5,6)
               THEN actual_amount END), 0)       AS gmv,
  COUNT(DISTINCT order_id)                       AS order_count,
  (SELECT COUNT(DISTINCT product_id)
   FROM dwd.dwd_fact_order_detail
   WHERE dt = '${hivevar:dt}' AND shop_id = so.shop_id) AS product_count,
  COUNT(DISTINCT user_id)                        AS user_count
FROM (
  SELECT DISTINCT shop_id, order_id, user_id, actual_amount, order_status
  FROM dwd.dwd_fact_order_detail
  WHERE dt = '${hivevar:dt}'
) so
GROUP BY shop_id;

-- =========================================================
-- 4. dws_user_active_daily：用户活跃日汇总
-- =========================================================
INSERT OVERWRITE TABLE dws.dws_user_active_daily PARTITION (dt = '${hivevar:dt}')
SELECT
  COUNT(DISTINCT user_id)                        AS dau,
  (SELECT COALESCE(COUNT(1), 0)
   FROM dwd.dwd_dim_user_zip
   WHERE start_date = '${hivevar:dt}')           AS new_user_count,
  COUNT(DISTINCT user_id)                        AS order_user_count,
  COUNT(DISTINCT CASE WHEN order_status IN (1,2,3,4)
                 THEN user_id END)               AS paid_user_count,
  ROUND(COUNT(1) / NULLIF(COUNT(DISTINCT user_id), 0), 2) AS avg_user_order
FROM (
  SELECT DISTINCT user_id, order_id, order_status
  FROM dwd.dwd_fact_order_detail
  WHERE dt = '${hivevar:dt}'
) order_user;
