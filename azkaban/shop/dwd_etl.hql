-- =========================================================
-- DWD 层 ETL
-- 处理日期: ${hivevar:dt}
-- 维度表: dwd_dim_product_zip / dwd_dim_sku_zip / dwd_dim_user_zip（拉链表）
-- 事实表: dwd_fact_order_detail（分区表）
-- =========================================================
SET hive.execution.engine=spark;
SET hive.exec.dynamic.partition.mode=nonstrict;
SET mapreduce.job.reduces=4;

-- =========================================================
-- 1. 拉链表：dwd_dim_product_zip
-- Step A: 关闭今天变化产品的旧版本
-- Step B: 插入变化/新产品的新版本
-- =========================================================
INSERT OVERWRITE TABLE dwd.dwd_dim_product_zip
SELECT
  d.product_id, d.product_name, d.brand,
  d.category_id, d.first_category_id, d.category_name,
  d.shop_id, d.status,
  d.start_date,
  CASE WHEN d.end_date = '9999-12-31' AND t.id IS NOT NULL
       THEN '${hivevar:dt}' ELSE d.end_date
  END AS end_date
FROM dwd.dwd_dim_product_zip d
LEFT JOIN (SELECT id FROM ods.ods_product_inc WHERE dt = '${hivevar:dt}') t
  ON d.product_id = t.id;

INSERT INTO TABLE dwd.dwd_dim_product_zip
SELECT
  t.id AS product_id,
  t.name AS product_name,
  t.brand,
  t.category_id,
  t.first_category_id,
  COALESCE(c.name, c2.name, '未知') AS category_name,
  t.shop_id,
  t.status,
  '${hivevar:dt}' AS start_date,
  '9999-12-31' AS end_date
FROM ods.ods_product_inc t
LEFT JOIN ods.ods_category_full c
  ON t.category_id = c.id AND c.dt = '${hivevar:dt}'
LEFT JOIN ods.ods_category_full c2
  ON t.first_category_id = c2.id AND c2.dt = '${hivevar:dt}'
WHERE t.dt = '${hivevar:dt}'
  AND NOT EXISTS (
    SELECT 1 FROM dwd.dwd_dim_product_zip d
    WHERE d.product_id = t.id AND d.end_date = '9999-12-31'
  );

-- =========================================================
-- 2. 拉链表：dwd_dim_sku_zip
-- =========================================================
INSERT OVERWRITE TABLE dwd.dwd_dim_sku_zip
SELECT
  d.sku_id, d.product_id, d.sku_name, d.specs,
  d.price, d.original_price, d.stock,
  d.start_date,
  CASE WHEN d.end_date = '9999-12-31' AND t.id IS NOT NULL
       THEN '${hivevar:dt}' ELSE d.end_date
  END AS end_date
FROM dwd.dwd_dim_sku_zip d
LEFT JOIN (SELECT id FROM ods.ods_sku_inc WHERE dt = '${hivevar:dt}') t
  ON d.sku_id = t.id;

INSERT INTO TABLE dwd.dwd_dim_sku_zip
SELECT
  t.id AS sku_id,
  t.product_id,
  t.name AS sku_name,
  t.specs,
  t.price,
  t.original_price,
  t.stock,
  '${hivevar:dt}' AS start_date,
  '9999-12-31' AS end_date
FROM ods.ods_sku_inc t
WHERE t.dt = '${hivevar:dt}'
  AND NOT EXISTS (
    SELECT 1 FROM dwd.dwd_dim_sku_zip d
    WHERE d.sku_id = t.id AND d.end_date = '9999-12-31'
  );

-- =========================================================
-- 3. 拉链表：dwd_dim_user_zip
-- =========================================================
INSERT OVERWRITE TABLE dwd.dwd_dim_user_zip
SELECT
  d.user_id, d.username, d.phone, d.nickname,
  d.status, d.admin_level, d.create_time,
  d.start_date,
  CASE WHEN d.end_date = '9999-12-31' AND t.id IS NOT NULL
       THEN '${hivevar:dt}' ELSE d.end_date
  END AS end_date
FROM dwd.dwd_dim_user_zip d
LEFT JOIN (SELECT id FROM ods.ods_user_inc WHERE dt = '${hivevar:dt}') t
  ON d.user_id = t.id;

INSERT INTO TABLE dwd.dwd_dim_user_zip
SELECT
  t.id AS user_id,
  t.username,
  t.phone,
  t.nickname,
  t.status,
  t.admin_level,
  t.create_time,
  '${hivevar:dt}' AS start_date,
  '9999-12-31' AS end_date
FROM ods.ods_user_inc t
WHERE t.dt = '${hivevar:dt}'
  AND NOT EXISTS (
    SELECT 1 FROM dwd.dwd_dim_user_zip d
    WHERE d.user_id = t.id AND d.end_date = '9999-12-31'
  );

-- =========================================================
-- 4. 事实表：dwd_fact_order_detail（分区表，当天覆盖）
-- 从 ods_orders_inc + ods_order_item_inc 关联
-- 维度退化：用户名、品类信息从维表拉取
-- =========================================================
INSERT OVERWRITE TABLE dwd.dwd_fact_order_detail PARTITION (dt = '${hivevar:dt}')
SELECT
  oi.id                AS order_id,
  o.order_no,
  o.user_id,
  COALESCE(u.username, '未知') AS username,
  o.shop_id,
  oi.product_id,
  oi.product_name,
  oi.sku_id,
  oi.sku_name,
  COALESCE(p.category_id, 0)   AS category_id,
  COALESCE(p.first_category_id, 0) AS first_category_id,
  COALESCE(p.category_name, '未知') AS category_name,
  oi.price,
  oi.quantity,
  oi.subtotal,
  o.total_amount,
  o.actual_amount,
  o.discount_amount,
  o.status             AS order_status,
  o.pay_type,
  o.pay_time,
  o.deliver_time,
  o.receive_time,
  o.create_time
FROM ods.ods_orders_inc o
INNER JOIN ods.ods_order_item_inc oi
  ON o.id = oi.order_id AND oi.dt = '${hivevar:dt}'
LEFT JOIN dwd.dwd_dim_user_zip u
  ON o.user_id = u.user_id AND u.end_date = '9999-12-31'
LEFT JOIN dwd.dwd_dim_product_zip p
  ON oi.product_id = p.product_id AND p.end_date = '9999-12-31'
WHERE o.dt = '${hivevar:dt}';
