#!/bin/bash
# 载入数仓 ETL 配置。
#
# 取值优先级：已 export 的环境变量 > 同目录 common.properties > 报错退出。
# 真实配置文件含明文数据库密码，不入库（见 .gitignore），模板见 common.properties.example。
# 部署到 Azkaban 时可用项目 properties 或环境变量注入，无需把密码写进文件。
#
# 用法：在各执行脚本中 `source "$(dirname "$0")/load_common.sh"` 取代原先直接 source
# common.properties，即可获得「环境变量优先」的取值顺序。

_CONF="$(dirname "${BASH_SOURCE[0]}")/common.properties"

if [ -f "$_CONF" ]; then
  # 只填补「尚未设置」的变量：已 export 的环境变量优先，不被文件覆盖
  while IFS='=' read -r _k _v; do
    case "$_k" in '' | '#'*) continue ;; esac
    _k="$(printf '%s' "$_k" | tr -d '[:space:]')"
    [ -n "${!_k:-}" ] || export "$_k=$_v"
  done < "$_CONF"
fi

if [ -z "${MYSQL_PASS:-}" ]; then
  {
    echo "配置缺失：未找到 $_CONF，且环境变量 MYSQL_PASS 未注入。"
    echo "请复制 common.properties.example 为 common.properties 并填写真实值。"
  } >&2
  exit 1
fi

unset _CONF _k _v
