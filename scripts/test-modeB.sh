#!/usr/bin/env bash
#
# Mode B 联调自动化测试脚本
#
# 作用：一条命令完成端到端联调——
#   起 3 个 mock DSP(8081/8082/8083) + SSP(8080, http模式)
#   → 等全部就绪 → 发 N 个竞价请求 → 等异步 bid_log 落库
#   → 汇总统计 + 导出 bid_log → 自动停掉所有服务。
#
# 用法：./scripts/test-modeB.sh [请求次数=20] [输出根目录=test-results] [广告位ID=slot-test-001] [拍卖方式=first]
#   例：./scripts/test-modeB.sh            # 默认发 20 个，一价，存到 test-results/
#       ./scripts/test-modeB.sh 5          # 只发 5 个
#       ./scripts/test-modeB.sh 20 test-results slot-test-001 second   # 跑二价拍卖
#
# 产物(归档到 test-results/modeB-<时间戳>/)：
#   summary.txt     汇总(结果视角：成功/no fill 数、各 DSP 中标分布)
#   bid_log.tsv     本批 bid_log(过程视角：每个 DSP 的出价/状态/耗时)
#   requests.jsonl  每条请求+响应(一行一个 JSON 对象)
#   ssp.log / dsp-{a,b,c}.log  各服务运行日志
#
# 前置：MySQL(mini_ssp) + Redis 已启动；dsp_config.bid_url 指向 8081-8083。
# 说明：占用 8080-8083 的残留进程会被自动清理；脚本退出(含报错)时 trap 兜底停服务。

# set 的三个开关：让脚本“出错就停”，避免某步悄悄失败还往下跑
#   -e            命令返回非 0 立刻退出
#   -u            用到未定义变量就报错
#   -o pipefail   管道里任意一段失败，整条管道算失败
set -euo pipefail

# ---------- 1. 参数与变量 ----------

# $1 = 请求次数，没传默认 20；$2 = 输出根目录，没传默认 test-results
REQUEST_COUNT="${1:-20}"
OUTPUT_ROOT="${2:-test-results}"
# 竞价用的广告位 ID，默认 slot-test-001(已关联 dsp-001/002/003)
SLOT_ID="${3:-slot-test-001}"
# 拍卖方式：first=一价 | second=二价，默认 first
AUCTION_TYPE="${4:-first}"

# 带时间戳的本次结果目录，例如 test-results/modeB-20260614-101500
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RESULT_DIR="${OUTPUT_ROOT}/modeB-${TIMESTAMP}"

# 端口常量
SSP_PORT=8080
DSP_A_PORT=8081
DSP_B_PORT=8082
DSP_C_PORT=8083

# 本测试用到的全部端口，free_ports / cleanup 都按它清理
ALL_PORTS=("${SSP_PORT}" "${DSP_A_PORT}" "${DSP_B_PORT}" "${DSP_C_PORT}")

# MySQL 连接参数(用于导出本批 bid_log)。
# 若同目录有 .env(项目根)，先加载它；密码等敏感信息从环境变量读，不写死在脚本里。
ENV_FILE="$(dirname "$0")/../.env"
if [ -f "${ENV_FILE}" ]; then set -a; . "${ENV_FILE}"; set +a; fi
DB_USER="${DB_USERNAME:-root}"
DB_PASS="${DB_PASSWORD:?请在 .env 设置 DB_PASSWORD 或导出环境变量}"
DB_NAME="mini_ssp"

# 之后每启动一个服务，就把它的进程号 append 进这个数组，cleanup 时统一 kill
SERVICE_PIDS=()

# ---------- 2. 清理机制(先架好骨架) ----------

# trap cleanup EXIT：无论脚本正常结束、报错退出、还是被 Ctrl+C，都会执行 cleanup。
# 现在 SERVICE_PIDS 还是空的，cleanup 什么都不杀；等后面启动服务后它才有活干。
cleanup() {
  echo "[cleanup] 收尾中..."
  # 先停我们记录的启动进程(maven / spring-boot:run 父进程)
  for pid in "${SERVICE_PIDS[@]:-}"; do
    if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
      echo "[cleanup] 已停止进程 ${pid}"
    fi
  done
  # 再按端口兜底清理：spring-boot:run 默认 fork 出独立子 JVM，
  # 上面只杀了父进程，真正占端口的子 JVM 要靠按端口清才干净。
  for port in "${ALL_PORTS[@]}"; do
    kill_port "${port}"
  done
}
trap cleanup EXIT

# ---------- 3. 端口清理 ----------

# 停掉占用某个端口的进程：先温和 kill(SIGTERM，让 Spring 优雅关闭)，
# 仍在则 kill -9 强杀兜底。返回前端口应已释放。
# 按端口清是“最稳”的方式——不管 spring-boot:run 有没有 fork 出子 JVM，
# 真正监听端口的那个进程都会被 lsof 找到并杀掉。
kill_port() {
  local port="$1" pids
  pids="$(lsof -ti:"${port}" 2>/dev/null || true)"
  [ -z "${pids}" ] && return 0

  echo "[端口] ${port} 被占用(pid: ${pids})，停止..."
  echo "${pids}" | xargs kill 2>/dev/null || true
  sleep 1

  pids="$(lsof -ti:"${port}" 2>/dev/null || true)"
  if [ -n "${pids}" ]; then
    echo "[端口] ${port} 仍在，强制停止(kill -9)..."
    echo "${pids}" | xargs kill -9 2>/dev/null || true
  fi
}

# 开跑前：把这几个端口上的残留服务清掉(上次没停干净的)。
free_ports() {
  for port in "${ALL_PORTS[@]}"; do
    kill_port "${port}"
  done
  echo "[预检] 端口 ${SSP_PORT}/${DSP_A_PORT}/${DSP_B_PORT}/${DSP_C_PORT} 已就绪 ✓"
}

# ---------- 4. 启动服务 / 就绪等待 ----------

# 后台启动一个服务，日志写进结果目录，进程号记进 SERVICE_PIDS 供 cleanup 用。
# 参数：$1=显示名/日志名(如 dsp-a)  $2=工作目录(如 mock-dsp)  $3..=要执行的命令
# 注意：( cd ... && exec "$@" ) 的重定向由外层 shell 在 cd 之前就打开了，
#       所以 ${RESULT_DIR}(绝对路径) 不受子 shell 里 cd 影响。
start_service() {
  local name="$1" workdir="$2"; shift 2
  ( cd "${workdir}" && exec "$@" ) > "${RESULT_DIR}/${name}.log" 2>&1 &
  local pid=$!
  SERVICE_PIDS+=("${pid}")
  echo "[启动] ${name} (pid ${pid}) → ${RESULT_DIR}/${name}.log"
}

# 轮询探活直到服务就绪(HTTP 返回 200 或 500 都算“起来了”)，超时则报错退出。
# 参数：$1=显示名  $2=最大等待秒数  $3..=一条完整的 curl 探活请求参数
wait_until_ready() {
  local name="$1" max="$2"; shift 2
  local i code
  for ((i = 1; i <= max; i++)); do
    code="$(curl -s -o /dev/null -w '%{http_code}' "$@" 2>/dev/null || true)"
    if [[ "${code}" == "200" || "${code}" == "500" ]]; then
      echo "[就绪] ${name} 已就绪 (${i}s)"
      return 0
    fi
    sleep 1
  done
  echo "[就绪] ${name} 启动超时(${max}s)，见日志 ${RESULT_DIR}/" >&2
  exit 1
}

# 发 REQUEST_COUNT 个竞价请求，每条“请求+响应”写一行(JSONL)进 requests.jsonl。
send_bids() {
  local jsonl="${RESULT_DIR}/requests.jsonl"
  local i rid req resp
  echo "--- 发送 ${REQUEST_COUNT} 个竞价请求 → slot=${SLOT_ID} ---"
  for ((i = 1; i <= REQUEST_COUNT; i++)); do
    rid="req-${TIMESTAMP}-${i}"
    req="{\"id\":\"${rid}\",\"tagid\":\"${SLOT_ID}\",\"device\":{\"os\":\"iOS\"},\"user\":{\"id\":\"u1\"}}"
    resp="$(curl -s -X POST -H "Content-Type: application/json" -d "${req}" \
      "http://localhost:${SSP_PORT}/api/v1/bid" 2>/dev/null || true)"
    # 响应为空(网络错等)时填 null，保证写出的仍是合法 JSON 行
    [ -z "${resp}" ] && resp="null"
    printf '{"n":%d,"requestId":"%s","request":%s,"response":%s}\n' \
      "${i}" "${rid}" "${req}" "${resp}" >> "${jsonl}"
  done
  echo "[请求] 完成，明细写入 ${jsonl}"
}

# 从 requests.jsonl 汇总统计，同时打印到屏幕并写入 summary.txt。
# 纯用 grep/sort/uniq 做文本统计，不依赖 jq/python。
summarize() {
  local jsonl="${RESULT_DIR}/requests.jsonl"
  local summary="${RESULT_DIR}/summary.txt"
  local total success nofill
  total="$(wc -l < "${jsonl}" | tr -d ' ')"
  success="$(grep -c '"code":0' "${jsonl}" || true)"   # code 0 = 成功中标
  nofill="$(grep -c '"code":1' "${jsonl}" || true)"    # code 1 = no fill

  {
    echo "===== Mode B 联调测试汇总 ====="
    echo "时间        : ${TIMESTAMP}"
    echo "广告位      : ${SLOT_ID}"
    echo "拍卖方式    : ${AUCTION_TYPE}"
    echo "总请求      : ${total}"
    echo "成功(code 0): ${success}"
    echo "no fill(1)  : ${nofill}"
    echo
    echo "各 DSP 中标次数:"
    # 抓出所有 winDsp，去掉前后缀只留 dsp-xxx，排序后计数
    grep -o '"winDsp":"[^"]*"' "${jsonl}" | sed 's/.*:"//;s/"//' | sort | uniq -c
  } | tee "${summary}"

  echo "[汇总] 写入 ${summary}"
}

# 导出本批请求(按时间戳前缀匹配)的 bid_log 到 bid_log.tsv。
export_bid_log() {
  local tsv="${RESULT_DIR}/bid_log.tsv"
  echo "--- 导出 bid_log ---"
  if mysql -u"${DB_USER}" -p"${DB_PASS}" -D "${DB_NAME}" -e "
      SELECT request_id, dsp_id, bid_price, status, response_time_ms, win
      FROM bid_log WHERE request_id LIKE 'req-${TIMESTAMP}-%'
      ORDER BY request_id, dsp_id;" > "${tsv}" 2>/dev/null; then
    echo "[bid_log] 写入 ${tsv} ($(($(wc -l < "${tsv}") - 1)) 条)"
  else
    echo "[bid_log] 导出失败(检查 MySQL 连接/账号)，跳过" >&2
  fi
}

# ---------- 主流程 ----------

echo "=== Mode B 联调测试 ==="
echo "请求次数 : ${REQUEST_COUNT}"
echo "拍卖方式 : ${AUCTION_TYPE}"
echo "结果目录 : ${RESULT_DIR}"

mkdir -p "${RESULT_DIR}"
# 转成绝对路径：后面 start_service 会 cd 进子目录，相对路径会写错位置
RESULT_DIR="$(cd "${RESULT_DIR}" && pwd)"
echo "[准备] 结果目录已创建 ✓ (${RESULT_DIR})"

free_ports

# ---------- 第1步：启动 3 个 mock DSP 并等待就绪 ----------

echo "--- 启动 mock DSP ---"
start_service "dsp-a" "mock-dsp" ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=dsp-a
start_service "dsp-b" "mock-dsp" ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=dsp-b
start_service "dsp-c" "mock-dsp" ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=dsp-c

wait_until_ready "DSP-A(${DSP_A_PORT})" 90 -X POST -H "Content-Type: application/json" -d '{}' "http://localhost:${DSP_A_PORT}/bid"
wait_until_ready "DSP-B(${DSP_B_PORT})" 90 -X POST -H "Content-Type: application/json" -d '{}' "http://localhost:${DSP_B_PORT}/bid"
wait_until_ready "DSP-C(${DSP_C_PORT})" 90 -X POST -H "Content-Type: application/json" -d '{}' "http://localhost:${DSP_C_PORT}/bid"

# ---------- 第2步：启动 SSP(http 模式)并等待就绪 ----------

echo "--- 启动 SSP(http 模式) ---"
# 工作目录是项目根(.)；--ssp.dsp.mode=http 让 BidService 走 WebClient 真实调用
# 多个启动参数：用 jvmArguments 以 -D 系统属性传(空格分隔)，Spring 同样能读到。
# 不用 run.arguments 的逗号分隔——实测逗号未被拆分，会把整串当成一个属性值。
start_service "ssp" "." ./mvnw -q spring-boot:run \
  "-Dspring-boot.run.jvmArguments=-Dssp.dsp.mode=http -Dssp.bid.auction-type=${AUCTION_TYPE}"

# SSP 要连 MySQL/Redis，启动较慢，超时给到 120s；探活用 GET(默认方法)
wait_until_ready "SSP(${SSP_PORT})" 120 "http://localhost:${SSP_PORT}/api/v1/admin/dsps"

echo "[准备] SSP 已就绪(http 模式)。"

# ---------- 第3步：发送竞价请求并记录 ----------

send_bids

# ---------- 第4步：汇总统计 + 导出 bid_log ----------

# bid_log 是异步写的，等一下让它落库，再查(且必须在 cleanup 杀 SSP 之前)
echo "[等待] 等 3s 让异步 bid_log 落库..."
sleep 3

summarize
export_bid_log

echo ""
echo "✅ 测试完成，全部结果在: ${RESULT_DIR}"
