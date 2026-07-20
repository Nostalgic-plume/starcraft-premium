#!/bin/bash
# =========================================================
# 构建 Azkaban 部署包
# 自动处理 03_dwd_etl 的依赖差异：
#   全量包 → dependencies=01_sqoop_ods_full
#   增量包 → dependencies=02_sqoop_ods_inc
# 使用临时目录打包，不污染工作区
# =========================================================

set -e

mode="${1:-}"
if [ "$mode" != "full" ] && [ "$mode" != "daily" ]; then
  echo "用法: sh build_zip.sh <full|daily>"
  echo "  full  — 全量包（首次部署，含 01 全量导入）"
  echo "  daily — 增量包（每日调度，含 02 增量导入）"
  exit 1
fi

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$BASE_DIR"

# ---------- 文件清单 ----------
COMMON_FILES=(
  "common.properties"
  "sqoop_ods_full.sh"
  "sqoop_ods_inc.sh"
  "dwd_etl.hql"
  "dws_etl.hql"
  "ads_etl.hql"
  "ads_export_stage.hql"
  "ads_export.sh"
)

CORE_CHAIN=(
  "04_dws_etl.job"
  "05_ads_etl.job"
  "06_ads_export.job"
)

if [ "$mode" = "full" ]; then
  ZIP_NAME="shop_warehouse_full.zip"
  DWD_JOB_SRC="03_dwd_etl_full.job"
  EXTRA_JOBS=("01_sqoop_ods_full.job")
  DESC="全量包"
else
  ZIP_NAME="shop_warehouse_daily.zip"
  DWD_JOB_SRC="03_dwd_etl_inc.job"
  EXTRA_JOBS=("02_sqoop_ods_inc.job")
  DESC="增量包"
fi

echo "===== 构建 ${DESC}: ${ZIP_NAME} ====="

# ---------- 准备临时目录 ----------
TMP_DIR="$(mktemp -d)"
trap "rm -rf '${TMP_DIR}'" EXIT

# 1) 复制所有文件
for f in "${COMMON_FILES[@]}" "${CORE_CHAIN[@]}" "${EXTRA_JOBS[@]}"; do
  cp "$f" "${TMP_DIR}/"
done

# 2) 将专用 03 版本复制为 03_dwd_etl.job（Azkaban 作业名 = 文件名去 .job）
cp "${DWD_JOB_SRC}" "${TMP_DIR}/03_dwd_etl.job"
echo "  → 03_dwd_etl.job ← ${DWD_JOB_SRC}"

# 3) 用 PowerShell Compress-Archive 打包（G-Bash 无 zip 命令）
echo "  → 正在打包..."
TMP_WIN="$(cygpath -w "${TMP_DIR}")"
DEST_WIN="$(cygpath -w "${BASE_DIR}/${ZIP_NAME}")"
powershell.exe -Command "
  Compress-Archive -Path '${TMP_WIN}\\*' -DestinationPath '${DEST_WIN}' -Force
"

echo "  → 打包完成: $(ls -lh "${ZIP_NAME}" | awk '{print $5}')"
echo "===== ${ZIP_NAME} 构建完成 ====="
