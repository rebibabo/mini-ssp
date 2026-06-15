# Mini-SSP

简化版 **SSP(Supply-Side Platform,供给方平台)** —— 程序化广告里代表媒体方的平台。
它接收媒体(App/网站)的广告请求,**并发**向多个 DSP(需求方平台)竞价,选出最高出价,返回中标广告。

> 个人学习项目,用来吃透 SSP 竞价链路 + Spring 生态(并发、缓存、限流、WebClient、消息队列等)。

## 技术栈

- **Spring Boot 3.4.5 / Java 17**
- **MySQL 8** + MyBatis-Plus
- **Redis** —— 配置缓存、QPS 限流计数、竞价结果缓存
- **Kafka**(KRaft 单节点)—— bid_log 异步写入解耦
- **WebClient**(WebFlux)—— 异步调用 DSP
- **springdoc-openapi** —— Swagger 接口文档
- Lombok、CompletableFuture 并发竞价

## 核心竞价流程

```
媒体/App ──BidRequest──▶ SSP
                          │ 1. 查广告位(Redis 缓存→DB)
                          │ 2. 查关联 DSP 列表
                          │ 3. CompletableFuture 并发向各 DSP 竞价(每 DSP 独立超时 + QPS 限流)
                          │ 4. 全局超时(200ms)收集已返回的出价
                          │ 5. 过滤(>0 且 ≥底价)→ 选最高 → 计价策略算成交价
                          │ 6. bid_log 发往 Kafka(消费者批量入库)
                          │ 7. 中标结果存 Redis(供埋点回调)
媒体/App  ◀─BidResponse────┘
```

竞价的"截止时间"机制:并发问所有 DSP,**到 deadline 就用已返回的出价竞价,迟到的当弃权丢弃**——不会被慢 DSP 拖死。

## 模块

| 目录 | 说明 |
|------|------|
| `src/` | Mini-SSP 主服务(端口 8080) |
| `mock-dsp/` | 独立的 Mock DSP 服务,一份代码用 profile `dsp-a/b/c` 起 3 实例(8081/8082/8083),模拟出价/延迟/超时异常 |
| `docker/` | Kafka + Prometheus + Grafana 的 docker-compose(`docker/kafka/`) |
| `scripts/` | `test-modeB.sh` 端到端联调自动化脚本 |

## 快速开始

### 1. 前置依赖

- MySQL(库 `mini_ssp`,表见下文)、Redis 已启动
- 启动 Kafka:

```bash
cd docker && docker compose up -d
```

### 2. 配置敏感信息(.env)

敏感配置(DB 密码)走环境变量,不入代码库。复制模板并填入你的值:

```bash
cp .env.example .env      # 然后编辑 .env 填 DB_PASSWORD
```

`spring-dotenv` 会在启动时自动加载根目录 `.env`。

### 3. 启动

```bash
./mvnw spring-boot:run               # 默认 Mode A(进程内模拟 DSP)+ 一价拍卖
```

- Swagger 文档:http://localhost:8080/swagger-ui.html

## 关键配置开关

| 配置 | 取值 | 作用 |
|------|------|------|
| `ssp.dsp.mode` | `mock`(默认) / `http` | 进程内模拟 DSP / WebClient 真实 HTTP 调用 |
| `ssp.bid.auction-type` | `first`(默认) / `second` | 一价拍卖 / 二价拍卖(付第二高价 + 增量) |
| `ssp.bid.global-timeout-ms` | 默认 200 | 整体竞价超时 |
| `ssp.cache.enabled` | true/false | 配置缓存开关 |

启动时覆盖(多个参数用 jvmArguments):

```bash
./mvnw spring-boot:run "-Dspring-boot.run.jvmArguments=-Dssp.dsp.mode=http -Dssp.bid.auction-type=second"
```

## Mode B 联调(真实 HTTP 调用独立 Mock DSP)

```bash
# 1. 三个终端各起一个 mock DSP
cd mock-dsp
./mvnw spring-boot:run -Dspring-boot.run.profiles=dsp-a   # 8081
./mvnw spring-boot:run -Dspring-boot.run.profiles=dsp-b   # 8082
./mvnw spring-boot:run -Dspring-boot.run.profiles=dsp-c   # 8083(偶发超时/异常,练容错)

# 2. SSP 切 http 模式
./mvnw spring-boot:run "-Dspring-boot.run.jvmArguments=-Dssp.dsp.mode=http"
```

或一键端到端(自动起停所有服务、发请求、汇总、导出 bid_log):

```bash
./scripts/test-modeB.sh 20 test-results slot-test-001 second   # 20 个请求,二价
```

结果按时间戳归档到 `test-results/modeB-<ts>/`(summary / requests / bid_log / 各服务日志)。

## 主要 API

统一响应:`{ "code": 0, "message": "success", "data": {} }`(`code=1` 表示 no fill)。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/bid` | 核心竞价 |
| GET | `/api/v1/track/impression?rid=` | 曝光追踪(204) |
| GET | `/api/v1/track/click?rid=` | 点击追踪(302 跳转) |
| GET/POST/PUT/DELETE | `/api/v1/admin/slots` | 广告位 CRUD |
| GET/POST/PUT/DELETE | `/api/v1/admin/dsps` | DSP CRUD |
| GET | `/api/v1/admin/logs` | 竞价日志查询 |

## 数据库

库 `mini_ssp`,表:`ad_slot`、`dsp_config`、`slot_dsp_rel`、`bid_log`、`event_log`。

## 测试

```bash
./mvnw test                          # 全部
./mvnw test -Dtest=BidServiceTest    # 单个类(纯单元,无需 DB/Redis)
```

- 单元测试:`BidServiceTest`(竞价决策)、`PricingStrategyTest`(一价/二价)、`RateLimiterTest`(限流)
- 集成测试:`*ControllerTest`(需 MySQL + Redis)

## 可观测性:Metrics(Prometheus + Grafana)

通过 Micrometer 暴露 `/actuator/prometheus`,埋点覆盖竞价 QPS、各 DSP 出价结果(win/lose/no_bid/error/rate_limited/timeout)、DSP 调用耗时、整体竞价耗时。

```bash
cd docker/kafka && docker compose up -d prometheus grafana
```

- Prometheus UI: http://localhost:9090 (Targets 页确认 `mini-ssp` job 为 `UP`,前提 SSP 在宿主机 8080 跑着)
- Grafana: http://localhost:3000 (admin/admin),数据源 Prometheus 填 `http://prometheus:9090`

常用 PromQL:

```promql
# fill QPS
sum(rate(ssp_bid_requests_total{result="fill"}[1m])) by (result)

# No Fill 率(0~1,面板 Unit 设为 Percent 0.0-1.0)
sum(rate(ssp_bid_requests_total{result="no_fill"}[1m])) / sum(rate(ssp_bid_requests_total[1m]))
```

## 设计要点

- **策略模式**:DSP 调用(`DspCaller`)、计价(`PricingStrategy`)各两实现,`@ConditionalOnProperty` 按配置切换
- **并发竞价**:`CompletableFuture.supplyAsync` + 线程池,总耗时 ≈ 最慢 DSP 而非求和;双层超时(单 DSP + 全局)
- **缓存**:Cache-Aside(广告位/DSP 配置),Redis INCR 固定窗口限流
- **Kafka 解耦**:竞价线程只 `send`(非阻塞),消费者 `@KafkaListener` 批量 `insertBatch` 入库,不占竞价线程
- **二价拍卖**:中标者付第二高价 + 增量,成交价持久化到 `bid_log.win_price`
