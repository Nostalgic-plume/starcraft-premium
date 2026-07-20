#!/bin/bash
# =========================================================
# Sqoop 导出 — Hive ADS → MySQL shop_ads
# 使用 ads_export_stage.hql 一次性写入所有 HDFS 临时目录
# （9 张表合并为一次 Spark 作业，节省 8 次 SparkSession 启动开销）
#
# 并行导出多张表（MAX_PARALLEL=3），大幅提升导出速度
# =========================================================
source "$(dirname "$0")/common.properties"

set -e

dt="${1:-$(date -d '-1 day' +%F)}"
echo "===== ADS 导出 dt=${dt} ====="

HIVE_EXPORT_PREFIX="/tmp/sqoop/ads_export"
MYSQL_EXTRA="useSSL=false&characterEncoding=utf-8&rewriteBatchedStatements=true"
MYSQL_REALM="-h ${ADS_HOST} -P ${ADS_PORT} -u ${ADS_USER} -p${ADS_PASS}"
MAX_PARALLEL=3
failed=0

# =============================================================
# 统一 Hive 写入：一次 SparkSession 写入所有表的 HDFS 临时目录
# 然后逐表 Sqoop 导出至 MySQL
# =============================================================
echo ""
echo ">>> 统一 Hive 写入（9 张表合并为一次 Spark 作业）..."
hive -f "$(dirname "$0")/ads_export_stage.hql" --hivevar dt="${dt}"
echo "    ✓ HDFS 临时目录全部写入完成"

# =============================================================
# 单表导出函数（在子进程中执行）
# mode: partitioned | non_partitioned
# =============================================================
export_table() {
  local entry="$1"
  local mode="$2"
  IFS=':' read -r table sqoop_columns <<< "$entry"

  echo ">>> [${table}] ${mode} → MySQL"

  if [ "$mode" = "partitioned" ]; then
    # 分区表：按 dt 删除旧数据
    mysql ${MYSQL_REALM} -e "DELETE FROM ${ADS_DB}.${table} WHERE dt='${dt}';"
    local export_dir="${HIVE_EXPORT_PREFIX}/${table}/${dt}"
  else
    # 非分区表：全量清空
    mysql ${MYSQL_REALM} -e "TRUNCATE TABLE ${ADS_DB}.${table};"
    local export_dir="${HIVE_EXPORT_PREFIX}/${table}"
  fi

  # Sqoop 导出
  echo "    Sqoop 导出 → MySQL ..."
  sqoop export \
    --connect "jdbc:mysql://${ADS_HOST}:${ADS_PORT}/${ADS_DB}?${MYSQL_EXTRA}" \
    --username "${ADS_USER}" --password "${ADS_PASS}" \
    --table "${table}" \
    --columns "${sqoop_columns}" \
    --export-dir "${export_dir}" \
    --input-fields-terminated-by '\001' \
    --input-lines-terminated-by '\n' \
    --input-null-string '\\N' \
    --input-null-non-string '\\N' \
    --num-mappers 1 || return 1

  echo "    ✓ ${table} 导出完成"
  return 0
}

# ---------------------------------------------------------
# 并行调度：每 MAX_PARALLEL 个一批
# ---------------------------------------------------------
run_parallel() {
  local -a entries=("${!1}")
  local mode="$2"
  local pids=()
  local names=()

  for entry in "${entries[@]}"; do
    IFS=':' read -r tbl _ <<< "$entry"
    export_table "$entry" "$mode" &
    pids+=($!)
    names+=("$tbl")

    if [ ${#pids[@]} -ge "$MAX_PARALLEL" ]; then
      for i in "${!pids[@]}"; do
        wait "${pids[$i]}" || {
          echo "    ✗ ${names[$i]} 导出失败" >&2
          failed=1
        }
      done
      pids=()
      names=()
    fi
  done

  # 等待剩余任务
  for i in "${!pids[@]}"; do
    wait "${pids[$i]}" || {
      echo "    ✗ ${names[$i]} 导出失败" >&2
      failed=1
    }
  done
}

# =============================================================
# 分区表：并行导出（HDFS → MySQL）
# =============================================================
PARTITIONED_TABLES=(
  "ads_overview:dt,total_users,total_products,total_orders,total_gmv,avg_order_price"
  "ads_gmv_daily:dt,gmv,order_count,paid_order_count,paid_user_count,avg_order_amount"
  "ads_order_stats_daily:dt,total_orders,paid_orders,shipped_orders,completed_orders,cancelled_orders,refund_orders,conversion_rate"
  "ads_user_active:dt,dau,order_user_count"
  "ads_user_new_retain:dt,new_users,day1_retain_users,day1_retain_rate,day7_retain_users,day7_retain_rate,day30_retain_users,day30_retain_rate"
  "ads_shop_daily:dt,shop_id,shop_name,gmv,order_count,product_count"
)

echo ""
echo ">>> 并行导出分区表（MAX_PARALLEL=${MAX_PARALLEL}）..."
run_parallel PARTITIONED_TABLES[@] "partitioned"
echo "    ✓ 所有分区表导出完成"

# =============================================================
# 非分区表：并行全量导出（MySQL 先清空）
# =============================================================
NON_PARTITIONED_TABLES=(
  "ads_category_sales:dt,first_category_id,category_name,sale_count,sale_amount"
  "ads_product_top50:dt,product_id,product_name,category_name,sale_quantity,sale_amount"
  "ads_trend_weekly:week_start,week_end,gmv,order_count,user_count,gmv_mom"
)

echo ""
echo ">>> 并行导出非分区表（MAX_PARALLEL=${MAX_PARALLEL}）..."
run_parallel NON_PARTITIONED_TABLES[@] "non_partitioned"
echo "    ✓ 所有非分区表导出完成"

if [ "$failed" -ne 0 ]; then
  echo "===== ADS 导出完成（部分表失败）=====" >&2
  exit 1
fi

echo ""
echo "===== ADS 导出全部完成 ====="
