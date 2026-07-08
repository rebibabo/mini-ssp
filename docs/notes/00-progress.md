# 进度记录与阶段总结

> 本文件为分类整理后的主题笔记。

## 1. 进度记录

| 状态 | 阶段 | 内容 |
|------|------|------|
| ✅ | Phase 1-5 | 核心骨架 / 竞价链路 / 缓存限流 / 埋点 + AOP 等(已完成) |
| ✅ | Phase 6 | 真实 Mock DSP 服务 + WebClient(本次完成,见下方 2026-06-14) |
| 🔶 | Phase 7 | 单元测试(部分完成) |
| ✅ | Phase 10 | Metrics：埋点 + Prometheus/Grafana + 竞价大盘(4面板)全部完成(见第 35 节) |
| ✅ | Phase 11 | TraceId：MDC + Filter 日志追踪(见第 36 节) |
| ✅ | 排障 | 并发200只有100+QPS：线程池 max-size 扫描实验(见第 40 节) |

### 2026-06-14 — Phase 6：真实 Mock DSP + WebClient（Mode B）

**做了什么**
- 策略接口 `DspCaller`，两个实现按 `ssp.dsp.mode` 配置切换(详见第 27 节)：
  - `MockDspCaller`(Mode A，默认，进程内 `MockDspHandler`)
  - `DspBidClient`(Mode B，WebClient 真实 HTTP)
- 新建独立项目 `mock-dsp/`(独立 pom + 启动类，非子模块)，一份代码用 profile `dsp-a/dsp-b/dsp-c` 起 3 实例(8081/8082/8083)，行为(延迟/出价/异常率)由 `@ConfigurationProperties` 配置驱动。
- `BidService` 依赖从 `MockDspHandler` 改为 `DspCaller` 接口；`BidServiceTest` 同步改用 mock `DspCaller`(`argThat` 按 dspId 匹配，加 null 守卫)，4 用例全过。
- DB：三个 DSP 的 `bid_url` 改指向 `http://localhost:808x/bid`。

**联调验证(Mode B 真实 HTTP 跑通)**
- mock DSP 日志确认三服务都收到了 `req-modeB-*`，竞价决策正确。
- bid_log 暴露"按截止时间收集"机制：
  - 冷启动首请求 WebClient 建连慢 → 三家全超 150ms → no fill。
  - 某次 DSP-B 出 7.83 但 152ms 超时被丢 → 1.40 的 C 反而中标(迟到当弃权)。
  - DSP-B 延迟配 50~200ms 而超时 150ms，约 1/4 概率踩线超时。
- **细节**：单 DSP 超时记 `status=3`(异常，`.timeout()` 抛 `TimeoutException`)，**不是** `status=0`；`status=0` 只在全局 `allOf` 超时时出现。

**怎么跑**：3 个终端起 `mock-dsp`(`-Dspring-boot.run.profiles=dsp-a/b/c`)→ SSP `--ssp.dsp.mode=http` 启动 → POST `/api/v1/bid`。

### 2026-06-14 — 新增 Mode B 联调自动化脚本

- 新增 `scripts/test-modeB.sh`：一条命令完成「起 4 个服务 → 等就绪 → 发 N 个竞价请求 → 等异步落库 → 汇总+导出 bid_log → 自动停服务」，结果按时间戳归档到 `test-results/modeB-<ts>/`(已加进 `.gitignore`)。详见第 28 节。
- 用法：`./scripts/test-modeB.sh [请求次数=20] [输出目录=test-results] [广告位=slot-test-001]`
- 产物：`summary.txt`(结果视角)/`bid_log.tsv`(过程视角)/`requests.jsonl`/各服务 `.log`。
- 踩坑：`spring-boot:run` 默认 fork 子 JVM，杀 maven 父进程清不掉端口 → cleanup 改为「杀记录 pid + 按端口兜底清」。

### 2026-06-14 — Swagger/OpenAPI 接口文档（Phase 4 收尾项）

- 接入 springdoc-openapi 2.8.6，`/swagger-ui.html` 可视化 + 在线调试，14 个接口全收录。详见第 29 节。
- 关键兼容点：Spring Boot 3.x **必须用 springdoc 2.x**(springfox 已死、不兼容)。
- 改动：`pom.xml` 加依赖；`config/OpenApiConfig.java` 全局标题/版本；5 个 Controller 加 `@Tag`+14 个方法 `@Operation`；核心 DTO 字段加 `@Schema`(描述+example)。
- 至此 Phase 4 的 Swagger 项完成；dev.md 计划内功能基本收齐。

### 2026-06-14 — 二价拍卖（策略模式）

- 新增计价策略 `PricingStrategy` + `FirstPricePricing`(一价，默认)/`SecondPricePricing`(二价)，按 `ssp.bid.auction-type` 切换。详见第 30 节。
- `BidService` 选出赢家后，把有效出价(降序)+底价交给策略算 `winPrice`，不再写死 `winner.bidPrice`。
- 端到端验证：出价 6.32(中标)/3.32/0.72 → winPrice=3.33(第二高+0.01)，赢家付第二高价。
- 新增 `PricingStrategyTest`(4 用例)覆盖：一价、二价、单一出价付底价、并列最高封顶。

### 2026-06-14 — bid_log 增加成交价 win_price

- 起因：`bid_log` 只有各 DSP 的 `bid_price`，没有成交价；二价下 winPrice ≠ 中标者 bidPrice，光查 DB 看不到实付价。
- 改动三处：① 数据库 `ALTER TABLE bid_log ADD COLUMN win_price DECIMAL(10,4) NULL`；② `BidLog` entity 加 `winPrice` 字段；③ `BidService` 把 winPrice 提前到写日志前算好，`asyncSaveBidLogs` 只写到 `win==1` 那条记录（非中标记 NULL）。
- 验证：二价下 bid_log 中标行 `win_price=1.05`（= 第二高 1.04 + 0.01），与响应 winPrice 一致。

### 2026-06-14 — bid_log 写入改用 Kafka（生产者+消费者解耦）

- 动机：原来 `asyncSaveBidLogs` 用 `bidExecutor`(竞价线程池)逐条 insert，和竞价抢线程、CallerRunsPolicy 还可能阻塞响应、N 条 insert 往返多。详见第 32 节。
- 改造：`BidService` 不再写库，改 `kafkaTemplate.send("bid-log", requestId, bidLog)`(非阻塞，不占竞价线程)；新增 `BidLogConsumer` 用 `@KafkaListener` 批量收 `List<BidLog>` → `BidLogMapper.insertBatch` 一条 SQL 多行入库。
- 环境：`docker/docker-compose.yml` 单节点 Kafka(KRaft，无 Zookeeper)，内存 ~316MiB；坑：advertised.listeners 不能用 0.0.0.0。
- 验证：发竞价 → 1s 内 3 条落库；消费者日志「批量入库 3 条」；消费者组 LAG=0。
- 单测：`BidServiceTest` mock 从 `BidLogMapper` 换成 `KafkaTemplate`，8 用例全过。

### 2026-06-14 — 用户频次控制（Frequency Cap）

- 区分：**频次控制 ≠ QPS 限流**——前者限「每用户对每 DSP 每天展示几次」(保护体验)，后者限「每 DSP 每秒接几个请求」(保护 DSP)。详见第 33 节。
- 实现：新增 `FrequencyCapService`(Redis 按天固定窗口，用 `StringRedisTemplate` 做计数器)；**检查**在竞价时剔除看够的 DSP，**计数**在曝光埋点时 +1。
- 配套：`BidResponse` 加 `userId`(随结果缓存进 Redis，曝光回调才知道是谁)；配置 `ssp.freq.daily-cap=3`；匿名用户跳过。
- 验证：曝光后 `ssp:freq:{user}:{dsp}:{日期}` 计数 +1；把用户对三个 DSP 都设到上限 → 竞价返回 no fill。
- 单测：`FrequencyCapServiceTest` 6 用例(达上限拦截/首次设 TTL 等)，全部 20 个单测通过。

### 2026-06-14 — bid_log 写入加策略开关（direct/kafka）+ 写入压测

- 用策略模式给 bid_log 落库加开关 `ssp.bid-log.mode`：`direct`(默认,同步单条直写,无需 Docker) | `kafka`(发 Kafka 消费者批量入库)。详见第 34 节。
- 抽 `BidLogWriter` 接口 + `DirectBidLogWriter`/`KafkaBidLogWriter` 两实现；`BidService` 去掉 KafkaTemplate 依赖改依赖接口；`BidLogConsumer` 加 `@ConditionalOnProperty(kafka)`(direct 模式不连 Kafka)。
- 微基准 `BidLogInsertBenchmarkTest`：批量 insertBatch 比循环单条 insert 快约 **7~10 倍**(100→7.5x / 500→10.5x / 1000→8.1x)，N 越大优势越明显。
- 默认改 direct 后开箱即用、不必开 Docker，解决了"必须开 Docker 才能跑"的痛点。

---
