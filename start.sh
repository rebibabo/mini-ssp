#!/bin/bash
# Mini-SSP 一键启动
#
# 用法：
#   ./start.sh                   # 基础版（brew MySQL/Redis + Java app）
#   ./start.sh --kafka           # 加 Kafka（bid-log 异步写入）
#   ./start.sh --metrics         # 加监控（Prometheus + Grafana）
#   ./start.sh --kafka --metrics # 全量
#   ./start.sh --perf            # 本机压测模式（跳过 bid_log 写入 + perf profile）
#   ./start.sh --down            # 停止 Docker 服务（Java app 请手动 Ctrl+C）

set -e

KAFKA=false
METRICS=false
PERF=false
DOWN=false

for arg in "$@"; do
  case $arg in
    --kafka)   KAFKA=true ;;
    --metrics) METRICS=true ;;
    --perf)    PERF=true ;;
    --down)    DOWN=true ;;
    --help|-h)
      sed -n '2,9p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
  esac
done

# ---------- 停止 ----------
if $DOWN; then
  echo "Stopping Docker services..."
  PROFILES=""
  $KAFKA   && PROFILES="$PROFILES --profile kafka"
  $METRICS && PROFILES="$PROFILES --profile metrics"
  if $KAFKA || $METRICS; then
    docker compose -f docker/docker-compose.yml $PROFILES down
  fi
  echo "Done. (Java app: Ctrl+C in its terminal)"
  exit 0
fi

# ---------- 启动 ----------

# 1. brew：MySQL + Redis
echo "[1/3] Starting MySQL and Redis (brew)..."
brew services start mysql 2>/dev/null || true
brew services start redis 2>/dev/null || true

# 2. Docker：Kafka / Prometheus+Grafana（按需）
if $KAFKA || $METRICS; then
  echo "[2/3] Starting Docker services..."
  PROFILES=""
  $KAFKA   && PROFILES="$PROFILES --profile kafka"
  $METRICS && PROFILES="$PROFILES --profile metrics"
  docker compose -f docker/docker-compose.yml $PROFILES up -d

  # 等 Kafka 就绪（否则 app 启动时连不上）
  if $KAFKA; then
    echo "      Waiting for Kafka..."
    for i in $(seq 1 20); do
      nc -z localhost 9092 2>/dev/null && break
      sleep 2
    done
  fi
else
  echo "[2/3] Skipping Docker services (no --kafka / --metrics)"
fi

# 3. Java app（前台运行，Ctrl+C 退出）
echo "[3/3] Starting SSP app (Ctrl+C to stop)..."

# 先清掉 8080 的残留进程
PIDS="$(lsof -ti:8080 2>/dev/null || true)"
if [ -n "$PIDS" ]; then
  echo "      Port 8080 in use, killing..."
  echo "$PIDS" | xargs kill 2>/dev/null || true
  sleep 1
  PIDS="$(lsof -ti:8080 2>/dev/null || true)"
  [ -n "$PIDS" ] && echo "$PIDS" | xargs kill -9 2>/dev/null || true
fi
echo ""

APP_ARGS=""
$KAFKA && APP_ARGS="$APP_ARGS --ssp.bid-log.mode=kafka"
$PERF && APP_ARGS="$APP_ARGS --spring.profiles.active=perf"

if [ -n "$APP_ARGS" ]; then
  ./mvnw spring-boot:run -Dspring-boot.run.arguments="$APP_ARGS"
else
  ./mvnw spring-boot:run
fi
