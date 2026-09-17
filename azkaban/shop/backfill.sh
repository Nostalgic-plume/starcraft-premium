#!/bin/bash
# =========================================================
# 数仓全量回填脚本（一次性执行）
# 作用：把 MySQL 所有历史数据全量拉入 Hive，
#       按日期自动分片写入 ODS，逐天跑全链路 ETL，
#       最后逐天导出到 MySQL shop_ads
#
# 用法：sh backfill.sh 2026-07-04
# 参数：target_dt = 结束日期（默认昨天）
# =========================================================

set -e
source "$(dirname "$0")/load_common.sh"

target_dt="${1:-$(date -d '-1 day' +%F)}"
# 往前推 7 天作为起始日期
start_dt=$(date -d "${target_dt} -7 days" +%F)
hdfs_prefix="/tmp/sqoop/ods_backfill"

echo "========================================="
echo "  全量回填脚本"
echo "  回填区间: ${start_dt} ~ ${target_dt}"
echo "  共 $((($(date -d "${target_dt}" +%s) - $(date -d "${start_dt}" +%s)) / 86400 + 1)) 天"
echo "========================================="

# ---------------------------------------------------------
# Step 1: Sqoop 全量拉取 5 张增量表（不加 WHERE 过滤）
# ---------------------------------------------------------
echo ""
echo "===== [1/7] Sqoop 全量拉取增量表 ====="

full_import() {
  local table="$1"
  local path="${hdfs_prefix}/${table}"
  echo ">>> 拉取 ${table} → ${path}"
  sqoop import \
    --connect "jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DB}?useSSL=false&characterEncoding=utf-8" \
    --username "${MYSQL_USER}" --password "${MYSQL_PASS}" \
    --table "${table}" \
    --target-dir "${path}" \
    --delete-target-dir \
    --num-mappers 1 \
    --null-string '\\N' --null-non-string '\\N' \
    --fields-terminated-by '\001' --lines-terminated-by '\n'
  echo "    ✓ ${table} 拉取完成"
}

full_import "orders"
full_import "order_item"
full_import "user"
full_import "product"
full_import "sku"

# ---------------------------------------------------------
# Step 2: 将增量数据写入 ODS 分区
#   orders/order_item/user → 按 create_time 动态分片
#   product/sku → 统一放入 target_dt 分区
# ---------------------------------------------------------
echo ""
echo "===== [2/7] ODS 分区写入 ====="

hive_setup="SET hive.execution.engine=spark; SET hive.exec.dynamic.partition.mode=nonstrict;"

# ==================== 2a. orders → ods_orders_inc ====================
echo ">>> orders → ods_orders_inc（按 create_time 分片）"

# 创建临时表（只含 MySQL 原始列，不含 dt 分区列）
hive -e "
  DROP TABLE IF EXISTS ods.ods_orders_backfill;
  CREATE TABLE ods.ods_orders_backfill (
    id                  BIGINT,
    order_no            STRING,
    user_id             BIGINT,
    shop_id             BIGINT,
    total_amount        DECIMAL(10,2),
    actual_amount       DECIMAL(10,2),
    discount_amount     DECIMAL(10,2),
    freight             DECIMAL(10,2),
    status              TINYINT,
    receiver_name       STRING,
    receiver_phone      STRING,
    receiver_address    STRING,
    pay_type            TINYINT,
    pay_time            STRING,
    deliver_time        STRING,
    logistics_no        STRING,
    receive_time        STRING,
    remark              STRING,
    create_time         STRING,
    update_time         STRING,
    deleted             TINYINT
  )
  ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
  LINES TERMINATED BY '\n'
  STORED AS TEXTFILE;
"

# 加载 Sqoop 原始数据
hive -e "LOAD DATA INPATH '${hdfs_prefix}/orders' INTO TABLE ods.ods_orders_backfill;"

# 动态分区写入目标表
hive -e "
  ${hive_setup}
  INSERT OVERWRITE TABLE ods.ods_orders_inc PARTITION (dt)
  SELECT
    id, order_no, user_id, shop_id,
    total_amount, actual_amount, discount_amount, freight,
    status, receiver_name, receiver_phone, receiver_address,
    pay_type, pay_time, deliver_time, logistics_no, receive_time,
    remark, create_time, update_time, deleted,
    DATE_FORMAT(create_time, 'yyyy-MM-dd') AS dt
  FROM ods.ods_orders_backfill;
"

# ==================== 2b. order_item → ods_order_item_inc ====================
echo ">>> order_item → ods_order_item_inc（按 create_time 分片）"

hive -e "
  DROP TABLE IF EXISTS ods.ods_order_item_backfill;
  CREATE TABLE ods.ods_order_item_backfill (
    id                  BIGINT,
    order_id            BIGINT,
    order_no            STRING,
    product_id          BIGINT,
    product_name        STRING,
    sku_id              BIGINT,
    sku_name            STRING,
    product_main_image  STRING,
    price               DECIMAL(10,2),
    quantity            INT,
    subtotal            DECIMAL(10,2),
    create_time         STRING
  )
  ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
  LINES TERMINATED BY '\n'
  STORED AS TEXTFILE;
"

hive -e "LOAD DATA INPATH '${hdfs_prefix}/order_item' INTO TABLE ods.ods_order_item_backfill;"

hive -e "
  ${hive_setup}
  INSERT OVERWRITE TABLE ods.ods_order_item_inc PARTITION (dt)
  SELECT
    id, order_id, order_no,
    product_id, product_name, sku_id, sku_name, product_main_image,
    price, quantity, subtotal, create_time,
    DATE_FORMAT(create_time, 'yyyy-MM-dd') AS dt
  FROM ods.ods_order_item_backfill;
"

# ==================== 2c. user → ods_user_inc ====================
echo ">>> user → ods_user_inc（按 create_time 分片）"

hive -e "
  DROP TABLE IF EXISTS ods.ods_user_backfill;
  CREATE TABLE ods.ods_user_backfill (
    id                  BIGINT,
    username            STRING,
    password            STRING,
    phone               STRING,
    email               STRING,
    nickname            STRING,
    avatar              STRING,
    status              TINYINT,
    admin_level         TINYINT,
    create_time         STRING,
    update_time         STRING,
    deleted             TINYINT
  )
  ROW FORMAT DELIMITED FIELDS TERMINATED BY '\001'
  LINES TERMINATED BY '\n'
  STORED AS TEXTFILE;
"

hive -e "LOAD DATA INPATH '${hdfs_prefix}/user' INTO TABLE ods.ods_user_backfill;"

hive -e "
  ${hive_setup}
  INSERT OVERWRITE TABLE ods.ods_user_inc PARTITION (dt)
  SELECT
    id, username, password, phone, email, nickname, avatar,
    status, admin_level, create_time, update_time, deleted,
    DATE_FORMAT(create_time, 'yyyy-MM-dd') AS dt
  FROM ods.ods_user_backfill;
"

# ==================== 2d. product → ods_product_inc（全量入 target_dt）====================
echo ">>> product → ods_product_inc（全量放入 ${target_dt} 分区）"

hive -e "
  SET hive.execution.engine=mr;
  ALTER TABLE ods.ods_product_inc DROP IF EXISTS PARTITION (dt='${target_dt}');
  LOAD DATA INPATH '${hdfs_prefix}/product'
    INTO TABLE ods.ods_product_inc PARTITION (dt='${target_dt}');
"

# ==================== 2e. sku → ods_sku_inc（全量入 target_dt）====================
echo ">>> sku → ods_sku_inc（全量放入 ${target_dt} 分区）"

hive -e "
  SET hive.execution.engine=mr;
  ALTER TABLE ods.ods_sku_inc DROP IF EXISTS PARTITION (dt='${target_dt}');
  LOAD DATA INPATH '${hdfs_prefix}/sku'
    INTO TABLE ods.ods_sku_inc PARTITION (dt='${target_dt}');
"

# 清理临时表
hive -e "DROP TABLE IF EXISTS ods.ods_orders_backfill;
         DROP TABLE IF EXISTS ods.ods_order_item_backfill;
         DROP TABLE IF EXISTS ods.ods_user_backfill;"

echo "    ✓ ODS 分区写入完成"

# ---------------------------------------------------------
# Step 3: 全量表 Sqoop 导入
# ---------------------------------------------------------
echo ""
echo "===== [3/7] 全量表 Sqoop 导入 ====="
sh "$(dirname "$0")/sqoop_ods_full.sh" "${target_dt}"
echo "    ✓ 全量表导入完成"

# ---------------------------------------------------------
# Step 4: 初始化商品 / SKU 维度表
# （因为所有商品只放在 target_dt 分区，提前初始化让每天的 DWD 引得到）
# ---------------------------------------------------------
echo ""
echo "===== [4/7] 初始化商品 / SKU 维度表 ====="

hive -e "
  ${hive_setup}
  INSERT OVERWRITE TABLE dwd.dwd_dim_product_zip
  SELECT
    t.id AS product_id, t.name AS product_name, t.brand,
    t.category_id, t.first_category_id,
    COALESCE(c.name, c2.name, '未知') AS category_name,
    t.shop_id, t.status,
    '${start_dt}' AS start_date,
    '9999-12-31' AS end_date
  FROM ods.ods_product_inc t
  LEFT JOIN ods.ods_category_full c ON t.category_id = c.id AND c.dt = '${target_dt}'
  LEFT JOIN ods.ods_category_full c2 ON t.first_category_id = c2.id AND c2.dt = '${target_dt}'
  WHERE t.dt = '${target_dt}';
"

hive -e "
  ${hive_setup}
  INSERT OVERWRITE TABLE dwd.dwd_dim_sku_zip
  SELECT
    t.id AS sku_id, t.product_id, t.name AS sku_name, t.specs,
    t.price, t.original_price, t.stock,
    '${start_dt}' AS start_date,
    '9999-12-31' AS end_date
  FROM ods.ods_sku_inc t
  WHERE t.dt = '${target_dt}';
"

echo "    ✓ 商品 / SKU 维度表初始化完成"

# ---------------------------------------------------------
# Step 5: 逐天全链路 ETL（维度表逐步积累 + 分区表每天导出）
#         将三个 HQL 合并为一次调用，减少 Spark 会话开销
# ---------------------------------------------------------
echo ""
echo "===== [5/7] 逐天全链路 ETL（分区表每日刷新） ====="

BASE_DIR="$(dirname "$0")"
current="$start_dt"
day_count=0
while [ "$current" != "$(date -d "${target_dt} +1 day" +%F)" ]; do
  day_count=$((day_count + 1))

  echo ""
  echo "--------- 第 ${day_count} 天: ${current} ---------"

  # 全量表分区复制：category/shop 只导入到了 target_dt 分区
  # DWD ETL 需要用当天分区做 JOIN，提前复制避免 category_name = '未知'
  echo ">>> [${current}] 复制全量表分区（category / shop）"
  hive -e "
    SET hive.execution.engine=mr;
    INSERT OVERWRITE TABLE ods.ods_category_full PARTITION (dt='${current}')
    SELECT * FROM ods.ods_category_full WHERE dt='${target_dt}';
    INSERT OVERWRITE TABLE ods.ods_shop_full PARTITION (dt='${current}')
    SELECT * FROM ods.ods_shop_full WHERE dt='${target_dt}';
  "

  # 合并 dwd + dws + ads 为一次 hive 调用，共用 Spark 会话
  echo ">>> [${current}] dwd + dws + ads_daily ETL（分区表）"
  cat "${BASE_DIR}/dwd_etl.hql" "${BASE_DIR}/dws_etl.hql" "${BASE_DIR}/ads_daily_etl.hql" \
    | hive --hivevar dt="${current}"

  echo ">>> [${current}] ads_export"
  sh "${BASE_DIR}/ads_export.sh" "${current}"

  current=$(date -d "${current} +1 day" +%F)
done

# ---------------------------------------------------------
# Step 6: 最终日非分区表 ETL + 导出
#   Top50 / 分类销售 / 周趋势 只跑最后一天，避免日循环无效覆盖
# ---------------------------------------------------------
echo ""
echo "===== [6/7] 最终日非分区表 ETL + 导出 ====="
echo ">>> 非分区表（Top50 / 分类销售 / 周趋势）仅跑最后一天: ${target_dt}"
hive -f "${BASE_DIR}/ads_final_etl.hql" --hivevar dt="${target_dt}"

echo ">>> 最终导出（分区表 + 非分区表全量导出）"
sh "${BASE_DIR}/ads_export.sh" "${target_dt}"

echo ""
echo "========================================="
echo "  全量回填全部完成！"
echo "  共处理 ${day_count} 天数据"
echo "  MySQL shop_ads 已有 ${start_dt} ~ ${target_dt} 全部数据"
echo "========================================="
