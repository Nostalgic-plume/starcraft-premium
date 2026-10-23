#!/usr/bin/env bash
#
# 一键启动脚本：中间件 → 依赖构建 → Flink 作业 → 双进程 → Nginx 重载
#
# 用法：
#   bash scripts/start-all.sh              # 完整启动
#   bash scripts/start-all.sh --no-docker  # 中间件已在运行，跳过 docker compose
#
# 设计目标（对应 docs 中记录的三类反复踩坑）：
#   1. 启动前强制 clean 构建，避免 IDE 增量编译把坏 class 写进 target/；
#   2. 统一加载仓库根 .env，两个 Spring Boot 服务不再依赖手工 source；
#   3. Flink 作业未运行时自动打包并提交，避免「推荐一直是兜底」。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ---- 环境（按需修改；也可在外部 export 覆盖） ----
export JAVA_HOME="${JAVA_HOME:-C:/Users/26373/.jdks/openjdk-22.0.1}"
export PATH="$JAVA_HOME/bin:/d/software/maven/apache-maven-3.9.9/bin:$PATH"

if [[ ! -f .env ]]; then
  echo "[ERROR] 缺少 $ROOT/.env（DB_PASSWORD / REC_INTERNAL_TOKEN / REDIS_PASSWORD 等）" >&2
  exit 1
fi

# ---- 1. 中间件 ----
if [[ "${1:-}" != "--no-docker" ]]; then
  echo "[1/6] 启动中间件（默认 + rec profile）..."
  docker compose up -d
  docker compose --profile rec up -d
  # RAG 检索需要 Qdrant + TEI，按需启用：
  # docker compose --profile rag --profile gpu up -d
else
  echo "[1/6] 跳过 docker compose"
fi

# ---- 2. 加载 .env（Flink 提交参数与本地进程共用同一份） ----
set -a
# shellcheck disable=SC1091
source .env
set +a

# ---- 3. 构建（clean 消灭 IDE 污染） ----
echo "[2/6] clean 构建（跳过测试）..."
mvn -q clean install -DskipTests

# ---- 4. Flink 实时特征作业 ----
echo "[3/6] 检查 Flink 作业..."
if MSYS_NO_PATHCONV=1 docker exec shop-flink-jm flink list 2>/dev/null | grep -q "rec-feature-job (RUNNING)"; then
  echo "      作业已在运行，跳过提交"
else
  echo "      未检测到运行中的作业，打包并提交..."
  (cd shop-rec-flink && mvn -q package -DskipTests)
  MSYS_NO_PATHCONV=1 docker cp shop-rec-flink/target/shop-rec-flink-1.0.0.jar \
    shop-flink-jm:/tmp/shop-rec-flink.jar
  MSYS_NO_PATHCONV=1 docker exec shop-flink-jm flink run -d \
    -c com.shop.rec.flink.FeatureJob /tmp/shop-rec-flink.jar \
    --kafka.bootstrap kafka:9093 \
    --redis.host redis --redis.port 6379 --redis.password "$REDIS_PASSWORD"
fi

# ---- 5. 启动两个 Spring Boot 进程 ----
mkdir -p logs
echo "[4/6] 启动商城后端（:8080）..."
nohup mvn -pl shop-server spring-boot:run > logs/shop-server.log 2>&1 &
echo "[5/6] 启动推荐服务（:8082）..."
nohup mvn -pl shop-rec spring-boot:run > logs/shop-rec.log 2>&1 &

wait_port() {
  local port="$1" name="$2" retries="${3:-60}"
  for ((i = 0; i < retries; i++)); do
    if netstat -ano 2>/dev/null | grep -q "LISTENING" && \
       netstat -ano 2>/dev/null | grep -E "[:.]${port}[[:space:]]" | grep -q "LISTENING"; then
      echo "      ${name} 已就绪（:${port}）"
      return 0
    fi
    sleep 2
  done
  echo "[ERROR] ${name} 在 ${retries} 次检查内未就绪，查看 logs/ 下日志" >&2
  return 1
}

wait_port 8080 "商城后端"
wait_port 8082 "推荐服务"

# ---- 6. Nginx 重载（配置/前端产物更新后立即生效） ----
echo "[6/6] 重载 Nginx..."
docker compose exec -T nginx nginx -s reload || true

echo
echo "启动完成："
echo "  用户端    http://localhost"
echo "  管理后台  http://localhost/admin/"
echo "  商家中心  http://localhost/merchant/"
echo "  日志      logs/shop-server.log / logs/shop-rec.log"

# ---- 7. 推荐链路自检（只读，不阻断启动） ----
# 重点拦截两类「服务起来了但推荐不可用」的静默故障：
# Flink 作业未提交（特征停止更新）、启动重建失败（商品状态缓存为空）。
echo
bash "$ROOT/scripts/check-rec-health.sh" || echo "[WARN] 推荐链路自检未通过，见上方明细" >&2
