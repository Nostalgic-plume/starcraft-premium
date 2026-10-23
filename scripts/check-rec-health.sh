#!/usr/bin/env bash
#
# 推荐链路健康自检
#
# 回答四个问题，任一不通过即以非 0 退出：
#   1. Flink 实时特征作业是否在运行（不在则特征停止更新，推荐退化为历史/兜底）
#   2. 商品状态缓存是否已建立（为空则所有候选被判 statusUnknown 而过滤，榜单为空）
#   3. 热度榜是否有足够候选（低于热门召回阈值 30 时热门路降级为兜底名单）
#   4. 榜单接口是否返回内容
#
# 用法：
#   bash scripts/check-rec-health.sh
#
# 说明：本脚本只读，不修改任何数据；预热请另行执行 scripts/warmup-rec.py。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  echo "[ERROR] 缺少 $ROOT/.env（需要 REDIS_PASSWORD / REC_INTERNAL_TOKEN）" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1091
source .env
set +a

# Git Bash 会把容器内的绝对路径改写成 Windows 路径
export MSYS_NO_PATHCONV=1

FAIL=0
ok()  { echo "  [OK]   $1"; }
bad() { echo "  [FAIL] $1"; FAIL=1; }

echo "========== 推荐链路健康自检 =========="

# ---- 1. Flink 作业状态 ----
JOBS=$(docker exec shop-flink-jm curl -s http://localhost:8081/jobs/overview 2>/dev/null)
if echo "$JOBS" | grep -q '"name":"rec-feature-job"'; then
  if echo "$JOBS" | grep -q '"state":"RUNNING"'; then
    ok "Flink 作业 rec-feature-job 正在运行"
  else
    bad "Flink 作业存在但未处于 RUNNING（当前状态见 http://127.0.0.1:8081）"
  fi
else
  bad "Flink 作业未提交：实时特征已停止更新，执行 scripts/start-all.sh 或手动 flink run"
fi

# ---- 2. 商品状态缓存 ----
STATE_KEYS=$(docker exec shop-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning \
  --scan --pattern 'rec:item:state:*' 2>/dev/null | wc -l | tr -d ' \r')
if [[ "$STATE_KEYS" =~ ^[0-9]+$ ]] && (( STATE_KEYS > 0 )); then
  ok "商品状态缓存已建立（${STATE_KEYS} 个商品）"
else
  bad "商品状态缓存为空：所有候选会被判 statusUnknown 而过滤，榜单必然为空。检查推荐服务启动日志中的「全量重建」记录"
fi

# ---- 3. 热度榜候选数 ----
HOT=$(docker exec shop-redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning \
  ZCARD rec:hot:item 2>/dev/null | tr -d ' \r')
if [[ "$HOT" =~ ^[0-9]+$ ]] && (( HOT >= 30 )); then
  ok "热度榜候选充足（${HOT} 个，阈值 30）"
else
  bad "热度榜候选不足（${HOT:-0} 个，阈值 30）：热门路会降级为兜底名单，演示前请运行 python scripts/warmup-rec.py 100"
fi

# ---- 4. 榜单接口 ----
RESP=$(curl -s --max-time 10 \
  "http://127.0.0.1:8082/api/rec/home/rank?pageNum=1&pageSize=3" \
  -H "X-Internal-Token: ${REC_INTERNAL_TOKEN:-}" 2>/dev/null)
if echo "$RESP" | grep -q '"itemId"'; then
  ok "榜单接口返回内容"
elif echo "$RESP" | grep -q '1009'; then
  bad "榜单接口鉴权失败：请确认 .env 中的 REC_INTERNAL_TOKEN 与推荐服务一致"
else
  bad "榜单接口无内容或不可达（响应：$(echo "$RESP" | head -c 120)）"
fi

echo "======================================"
if (( FAIL == 0 )); then
  echo "自检通过：推荐链路可用于演示"
  exit 0
else
  echo "自检未通过：按上面的 [FAIL] 项逐条处理" >&2
  exit 1
fi
