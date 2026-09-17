#!/bin/bash
# =========================================================
# Sqoop 全量导入 — MySQL shop → Hive ODS
# 处理 10 张全量表，并行执行（MAX_PARALLEL=3）
# =========================================================
source "$(dirname "$0")/load_common.sh"

dt="${1:-$(date -d '-1 day' +%F)}"
echo "===== 全量导入 dt=${dt} ====="

MAX_PARALLEL=3
HDFS_TMP_PREFIX="/tmp/sqoop/ods_full"
failed=0

# 10 张全量表
FULL_TABLES=(
  "address"
  "admin"
  "cart"
  "category"
  "permission"
  "role"
  "role_permission"
  "shop"
  "user_role"
  "user_status_log"
)

# ---------------------------------------------------------
# 单表导入函数（在子进程中执行）
# ---------------------------------------------------------
import_table() {
  local table="$1"
  local hive_table="ods_${table}_full"
  echo ">>> [${table}] → ${HIVE_ODS}.${hive_table} (dt=${dt})"

  # Step 1: Sqoop 导入到 HDFS 临时目录
  sqoop import \
    --connect "jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DB}?useSSL=false&characterEncoding=utf-8" \
    --username "${MYSQL_USER}" --password "${MYSQL_PASS}" \
    --table "${table}" \
    --target-dir "${HDFS_TMP_PREFIX}/${table}/${dt}" \
    --delete-target-dir \
    --num-mappers 1 \
    --null-string '\\N' --null-non-string '\\N' \
    --fields-terminated-by '\001' --lines-terminated-by '\n' || return 1

  # Step 2: 覆盖加载到 Hive 分区
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
# 并行调度：每 MAX_PARALLEL 个一批
# ---------------------------------------------------------
pids=()
tables_in_batch=()

for table in "${FULL_TABLES[@]}"; do
  import_table "$table" &
  pids+=($!)
  tables_in_batch+=("$table")

  if [ ${#pids[@]} -ge "$MAX_PARALLEL" ]; then
    for i in "${!pids[@]}"; do
      wait "${pids[$i]}" || {
        echo "    ✗ ${tables_in_batch[$i]} 导入失败" >&2
        failed=1
      }
    done
    pids=()
    tables_in_batch=()
  fi
done

# 等待剩余任务
for i in "${!pids[@]}"; do
  wait "${pids[$i]}" || {
    echo "    ✗ ${tables_in_batch[$i]} 导入失败" >&2
    failed=1
  }
done

if [ "$failed" -ne 0 ]; then
  echo "===== 全量导入完成（部分表失败）=====" >&2
  exit 1
fi

echo "===== 全量导入完成 ====="
