#!/bin/bash
# =========================================================
# Sqoop 增量导入 — MySQL shop → Hive ODS
# 处理 5 张增量表，并行执行（MAX_PARALLEL=3）
# =========================================================
source "$(dirname "$0")/load_common.sh"

dt="${1:-$(date -d '-1 day' +%F)}"
today=$(date -d "${dt} +1 day" +%F)
echo "===== 增量导入 dt=${dt}, today=${today} ====="

MAX_PARALLEL=3
HDFS_TMP_PREFIX="/tmp/sqoop/ods_inc"
failed=0

# 每张表的过滤字段（mysql_table:hive_suffix:date_field）
INC_TABLES=(
  "orders:orders:create_time"
  "order_item:order_item:create_time"
  "product:product:update_time"
  "sku:sku:update_time"
  "user:user:create_time"
)

# ---------------------------------------------------------
# 单表导入函数
# ---------------------------------------------------------
import_table() {
  local entry="$1"
  IFS=':' read -r table hive_table_suffix date_field <<< "$entry"
  local hive_table="ods_${hive_table_suffix}_inc"
  local where_sql="${date_field} >= '${dt} 00:00:00' AND ${date_field} < '${today} 00:00:00'"

  echo ">>> [${table}] → ${HIVE_ODS}.${hive_table} (dt=${dt}, ${date_field}>=${dt})"

  sqoop import \
    --connect "jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DB}?useSSL=false&characterEncoding=utf-8" \
    --username "${MYSQL_USER}" --password "${MYSQL_PASS}" \
    --table "${table}" \
    --where "${where_sql}" \
    --target-dir "${HDFS_TMP_PREFIX}/${table}/${dt}" \
    --delete-target-dir \
    --num-mappers 1 \
    --null-string '\\N' --null-non-string '\\N' \
    --fields-terminated-by '\001' --lines-terminated-by '\n' || return 1

  hive -e "
    SET hive.execution.engine=mr;
    ALTER TABLE ${HIVE_ODS}.${hive_table} DROP IF EXISTS PARTITION (dt='${dt}');
    LOAD DATA INPATH '${HDFS_TMP_PREFIX}/${table}/${dt}'
      INTO TABLE ${HIVE_ODS}.${hive_table} PARTITION (dt='${dt}');
  " || return 1

  echo "    ✓ ${hive_table} dt=${dt} 导入完成"
  return 0
}

# ---------------------------------------------------------
# 并行调度
# ---------------------------------------------------------
pids=()
entries_in_batch=()

for entry in "${INC_TABLES[@]}"; do
  import_table "$entry" &
  pids+=($!)
  entries_in_batch+=("$entry")

  if [ ${#pids[@]} -ge "$MAX_PARALLEL" ]; then
    for i in "${!pids[@]}"; do
      wait "${pids[$i]}" || {
        IFS=':' read -r tbl _ _ <<< "${entries_in_batch[$i]}"
        echo "    ✗ ${tbl} 导入失败" >&2
        failed=1
      }
    done
    pids=()
    entries_in_batch=()
  fi
done

# 等待剩余任务
for i in "${!pids[@]}"; do
  wait "${pids[$i]}" || {
    IFS=':' read -r tbl _ _ <<< "${entries_in_batch[$i]}"
    echo "    ✗ ${tbl} 导入失败" >&2
    failed=1
  }
done

if [ "$failed" -ne 0 ]; then
  echo "===== 增量导入完成（部分表失败）=====" >&2
  exit 1
fi

echo "===== 增量导入完成 ====="
