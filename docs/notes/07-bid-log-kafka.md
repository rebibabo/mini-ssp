# bid_log、Kafka 与写入链路

> 本文件为分类整理后的主题笔记。

## 1. bid_log 查询接口

### 用途

`GET /api/v1/admin/logs` 查 `bid_log` 表（每个 DSP 的竞价流水），给运营/开发排查问题用：

- 拿 requestId 看某次竞价为什么 no fill（全超时？出价低于底价？）
- 按 dspId 看某个 DSP 的响应时间、中标率、限流情况

只读接口（只有 GET），区别于 Slot/Dsp 的增删改查配置接口。

### 动态条件查询

`LambdaQueryWrapper` 的条件方法支持一个 boolean 开关，为 true 才拼这个 WHERE：

```java
.eq(StringUtils.hasText(requestId), BidLog::getRequestId, requestId)  // 空参数自动跳过
.eq(status != null, BidLog::getStatus, status)
.orderByDesc(BidLog::getCreatedAt)
```

参数都 `@RequestParam(required = false)`，不传就不参与过滤。

---

## 2. Kafka 异步写 bid_log（消息队列解耦）

### 为什么从「线程池写库」换成 Kafka

原来 `asyncSaveBidLogs` 用 `CompletableFuture.runAsync(..., bidExecutor)` 逐条 insert，三个问题：

1. **和竞价抢线程**：写日志(不敏感)和竞价(延迟敏感)共用 `bidExecutor`。
2. **CallerRunsPolicy 暗坑**：池满时任务回到调用线程跑 → 给竞价响应加上 DB 写入耗时。
3. **N 条 insert**：一次竞价多个 DSP = 多次 DB 往返。

Kafka 方案：竞价线程只 `send`(非阻塞)，消费者另一头批量入库，彻底解耦。

### 架构

```
BidService.sendBidLogs ──send(requestId, BidLog)──▶ Kafka topic "bid-log" ──▶ BidLogConsumer
  竞价线程，send 非阻塞                    JSON，持久          offset 追踪      @KafkaListener 批量收 List
  (只入 producer 缓冲，                                                              │
   客户端后台线程发，不占竞价线程)                                        bidLogMapper.insertBatch(一条SQL多行)
```

### 生产者（KafkaTemplate）

```java
kafkaTemplate.send(topic, requestId, bidLog);   // key=requestId
```

- **非阻塞**：只往 producer 内部缓冲区追加，由 Kafka 客户端的 I/O 线程异步发出 → 竞价线程立刻返回，不再需要 `bidExecutor` 写日志。
- **key 的作用**：Kafka 按 key 哈希选分区。用 `requestId` 当 key → 同一次竞价的多条进**同一分区**，保证有序、好排查。

### 消费者（@KafkaListener + 批量）

```java
@KafkaListener(topics = "${ssp.kafka.bid-log-topic}")
public void consume(List<BidLog> logs) { bidLogMapper.insertBatch(logs); }
```

- 配 `spring.kafka.listener.type=batch` 后，方法入参直接是 `List`（一次回调一批）→ 配合 `insertBatch` 一条 SQL 多行。
- 批量插入用 `@Insert` + `<foreach>` 写（`BaseMapper` 没现成的批量方法）。

### 核心概念

| 概念 | 一句话 |
|------|--------|
| topic | 消息的分类频道（这里是 `bid-log`） |
| partition | topic 的分片，决定并行度和顺序（同 key 同分区内有序） |
| consumer group | 同组的多个消费者**分摊**消息；换组名可从头重新消费 |
| offset | “消费到哪了”的书签，消费者按 offset 推进；`LAG=末尾offset−当前offset`=积压条数 |
| `auto-offset-reset` | 没有 offset 记录时(首次/新组)：`earliest`从头 / `latest`只读新的 |

### 序列化

- 生产者 `JsonSerializer` 把对象转 JSON，并默认写入**类型头**。
- 消费者 `JsonDeserializer` 按类型头反序列化回对象，需配 `spring.json.trusted.packages`(安全限制，只信任指定包，防反序列化任意类)。

### offset 提交与可靠性（至少一次）

batch 模式默认：监听方法**正常返回后**才提交 offset；若 `insertBatch` 抛异常、offset 不提交 → 这批消息重投（**at-least-once**，可能重复，但不丢）。

### 本地环境

`docker/docker-compose.yml` 单节点 Kafka(KRaft，无 Zookeeper)，~316MiB 内存。
坑：单节点要把各 `*_REPLICATION_FACTOR` 设 1；`advertised.listeners` 不能用 `0.0.0.0`(用 localhost)。

### 对比之前的内存队列方案（第 30 节讨论过的方案 B）

| | 内存 BlockingQueue | Kafka |
|---|---|---|
| 进程崩溃 | 未 flush 的丢失 | 消息已持久，重启接着消费 |
| 跨进程/扩展 | 不行 | 可独立扩消费者 |
| 依赖 | 无 | 需要 broker |
| 真实项目 | 小场景够用 | 日志/事件流业界标准 |

### MQ 还能解决什么（看家本领）

| 作用 | 说明 |
|------|------|
| 解耦 | 生产者/消费者互不关心对方 |
| 异步 | 调用方发完即走，响应更快 |
| **削峰填谷** | 突发洪峰先囤进队列，消费者按自己/下游能扛的速度慢慢消化，**保护下游(DB)不被冲垮** |
| 广播 fan-out | 一条消息多个消费者各取所需（用不同 group-id 订阅同一 topic）——加个实时大盘消费者不用动现有代码 |
| 持久化/重放 | 消息存着，改了逻辑可重置 offset 重新消费历史数据 |

### 削峰填谷的两个前提（重要）

1. **削的是尖峰，不是无限扩容**：持续流量长期超过消费速度，队列只会越积越多(LAG 一直涨)。要扛持续高压得**加消费者**(加分区 + 同组多消费者并行)或扩下游。MQ 给的是“缓冲时间 + 保护下游”，不是凭空变快。
2. **只有“调用方不需要同步等结果”的链路才能异步化**：

| 链路 | 能丢 MQ 削峰吗 | 原因 |
|------|---------------|------|
| 写 bid_log / 埋点 | ✅ | 媒体不等这个，异步落库无妨 |
| 核心竞价 `/api/v1/bid` | ❌ | 媒体在**同步等**中标广告(200ms 内返回)，不能排队待会儿处理 |

所以高并发下 MQ 帮 SSP 把**竞价的副产品(日志/埋点/计费)**异步化削峰、保护 DB；**竞价主链路的延迟**仍靠线程池/缓存/限流/多实例水平扩容来扛。

---

## 3. bid_log 落库策略开关（direct / kafka）+ 写入压测

### 用策略模式给"怎么落库"加开关

第 32 节把 bid_log 改成只走 Kafka，带来一个副作用：**不开 Docker/Kafka 就没法正常用**。于是再加一层策略，按 `ssp.bid-log.mode` 切换：

```
BidLogWriter(接口)  void write(List<BidLog>)
 ├─ DirectBidLogWriter   @ConditionalOnProperty(havingValue="direct", matchIfMissing=true)  同步单条 insert
 └─ KafkaBidLogWriter    @ConditionalOnProperty(havingValue="kafka")                          发 Kafka
```

- BidService 算完竞价 → 拼好 `List<BidLog>` → `bidLogWriter.write(list)`，不关心底层。第三个策略模式了（前两个：DspCaller、PricingStrategy）。
- **关键：Kafka 那条链路要整条开关**——`KafkaBidLogWriter`(生产者)和 `BidLogConsumer`(消费者)都加 `havingValue="kafka"`。这样 direct 模式下消费者不注册 → 不连 Kafka → 不刷连接日志。
- 默认 `direct`：开箱即用、无需 Docker；想练 Kafka 时 `--ssp.bid-log.mode=kafka`。

### 写入压测：单条 vs 批量（BidLogInsertBenchmarkTest）

造 N 条 BidLog，分别给「循环单条 insert」和「一次 insertBatch」计时（不开事务，真实 commit）：

| N | 单条(ms) | 批量(ms) | 提速 |
|---|---------|---------|------|
| 100 | 90 | 12 | 7.5x |
| 500 | 283 | 27 | 10.5x |
| 1000 | 276 | 34 | 8.1x |

**批量快约 7~10 倍，且 N 越大优势越明显。**

为什么：

| | 单条 × N | insertBatch(N) |
|---|---------|----------------|
| 网络往返 | **N 次** | **1 次** |
| SQL | N 条独立语句 | 1 条多 VALUES |
| 随 N 增长 | 线性涨 | 几乎不涨(12→34ms) |

瓶颈在**网络往返次数**：单条写每条都要和 MySQL 一来一回，批量把 N 次往返压成 1 次。这也印证了 Kafka 方案的价值——消费者攒一批 `insertBatch`，享受的就是这倍数的提速。

> 基准测试要点：不开 `@Transactional`(要真实 commit)；先预热一轮避开 JIT/连接池冷启动；造的数据用 `request_id` 前缀标记、跑完清理；手动跑 `-Dtest=BidLogInsertBenchmarkTest`。本地数字有噪声，看量级和趋势即可。

### 两种模式怎么选

| | direct | kafka |
|---|--------|-------|
| 依赖 | 仅 MySQL | + Kafka(Docker) |
| 写入 | 同步逐条，占竞价线程 | 异步批量，不占竞价线程 |
| 适合 | 本地/小流量/图省事 | 高并发/解耦/接近真实 |

---
