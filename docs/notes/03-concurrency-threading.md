# 并发、异步与线程池

> 本文件为分类整理后的主题笔记。

## 1. 线程池

### 5 个核心参数

```
请求进来
    ↓
有空闲线程？ → 直接用（核心线程，最多 coreSize 个）
    ↓ 没有
放进队列等待（最多 queueCapacity 个）
    ↓ 队列满了
临时扩充线程（最多扩到 maxSize 个）
    ↓ 还满
执行拒绝策略
```

| 参数 | 说明 |
|------|------|
| `coreSize` | 核心线程数，平时保持随时待命 |
| `maxSize` | 最大线程数，高峰期临时扩充 |
| `queueCapacity` | 队列容量，线程都忙时任务排队 |
| `keepAliveSeconds` | 临时线程闲置多久后销毁 |
| `CallerRunsPolicy` | 拒绝策略：队列满了让调用方自己执行，不丢任务 |

### SSP 为什么需要线程池

并发向多个 DSP 发请求，总耗时 = 最慢的那个 DSP，而不是所有 DSP 耗时之和。

---

## 2. CompletableFuture 异步编程

### 核心方法

```java
// 提交异步任务到线程池，立即返回 Future（不等结果）
CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> doSomething(), executor);

// 任务抛异常时兜底，返回替代值，保证 Future 不崩
future.exceptionally(ex -> fallbackValue);

// 给多个 Future 设置"闹钟"，等所有完成或超时
CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                 .get(200, TimeUnit.MILLISECONDS);

// 从 Future 取结果（unchecked 异常，Stream 里用更简洁）
T result = future.join();

// 判断 Future 是否已完成（超时后用来过滤未完成的）
future.isDone();
```

### supplyAsync vs runAsync

- `supplyAsync`：有返回值的任务
- `runAsync`：无返回值的任务（如写日志）

### allOf 是闹钟，不是结果容器

```
allOf.get(200ms)  →  只是等待，返回 Void，不存结果
结果在各自的 Future 里，通过 .join() 取出
```

### join vs get

| | `join()` | `get()` |
|---|---|---|
| 异常类型 | unchecked，不用 try-catch | checked，必须 try-catch |
| Stream 里 | 简洁，推荐 | 需要套 try-catch，难看 |

---

## 3. 工程收尾：一键启动 + ThreadPoolConfig 分析（Phase 13）

### Docker Compose 重构

**目标**：MySQL/Redis 保留 brew（不丢数据），Docker 只管 Kafka 和监控，用 profiles 按需开关。

**文件结构**：
```
mini-ssp/
├── start.sh                        # 入口：brew + Docker + Java app
└── docker/
    └── docker-compose.yml          # profiles: kafka / metrics
```

**核心：Docker Compose profiles**

给 service 加 `profiles: [名字]` 后，该 service **默认不启动**，只有命令行传 `--profile 名字` 才激活：

```yaml
services:
  kafka:
    profiles: [kafka]      # docker compose up 时跳过
    ...
  prometheus:
    profiles: [metrics]
  grafana:
    profiles: [metrics]    # 同标签的两个 service 一起激活
```

```bash
docker compose --profile kafka up -d            # 只起 Kafka
docker compose --profile kafka --profile metrics up -d  # 全起
```

`start.sh` 把用户的 `--kafka / --metrics` 参数动态拼成 `--profile` 传给 compose。

**各服务启动方式对比**：

| 服务 | 方式 | 理由 |
|---|---|---|
| MySQL / Redis | brew 原生 | 已有数据，稳定，不想迁移 |
| Kafka | Docker + profile | 按需，不用就不起 |
| Prometheus / Grafana | Docker + profile | 按需 |
| Java 应用 | `./mvnw spring-boot:run` | 本机有 Java，最简单，改代码立即生效 |

**Dockerfile 的定位**：只在"部署到服务器/给别人用"时才有价值（对方不需要装 Java）。
本地开发直接 mvnw 跑，Dockerfile 备用。

---

### ThreadPoolConfig 分析

```java
// core=8, max=16, queue=200, CallerRunsPolicy
// 每次 bid 请求 → 提交 3 个 DSP 任务 → allOf().get() 阻塞等待
```

**两种场景下的瓶颈角色不同**：

| 场景 | 线程池角色 | 原因 |
|---|---|---|
| latency=0（压测用） | 不是瓶颈 | 任务瞬间完成，线程池基本空转 |
| 真实 DSP 延迟 ~100ms | **是瓶颈**，上限 ~53 req/s | 16线程 × (1000ms/100ms) / 3 DSPs |

**CallerRunsPolicy 的副作用**：queue(200) 满时（c > 72），提交任务的 Tomcat 线程**自己跑 DSP 任务**，
既要等 allOf()，又要亲自执行，等于强制背压——新请求进不来，天然限速。这是设计意图，不是 Bug。

**生产建议**：`maxSize` 应按 `目标并发 × DSP数` 计算（c=50 至少需要 150）；
或 Java 21 Virtual Threads 直接绕开有界线程池的限制。

---

## 4. 线程池 max-size 扫描实验（排查"并发200、QPS只有100多"）
### 起因

真实 DSP 延迟场景下并发压测 200，QPS 却只有 100 出头，怀疑 `ThreadPoolConfig`（`bidExecutor`）配置太小。

### 第一步：调大线程池参数

原配置 `core=8, max=16, queue=200`。按"目标并发 × DSP 数"估算（c=200 × 3 DSP ≈ 600）调整为：

```yaml
thread-pool:
  core-size: 200
  max-size: 600
  queue-capacity: 300
```

**坑**：`queue-capacity` 不能无脑调太大——`ThreadPoolExecutor` 只有在队列**满了**之后才会把线程数扩到 `max`。如果 `queue=1000` 而 `core=100`，要等 1000 个任务排队后才扩容，等于 `max-size` 调了等于没调。所以 `core` 要跟着一起提高，`queue` 保持适中（几百量级）做缓冲即可。

同时顺带把 `ssp.cache.enabled` 从 `false` 改成 `true`（原来一直是关的，每次竞价都查 DB），并用 `start.sh --kafka` 全量起服务（MySQL/Redis brew + Kafka Docker + bid_log 异步落库）对照。

**效果（并发200，30s）**：QPS 105 → 162（+54%），错误率从有 error 降到 0。线程池调整确实有用。

### 第二步：max-size 扫描实验，量化"多大才够"

固定 `core=max-size`（让线程立即建满，纯测"fan-out 线程数上限"这一个变量），`queue-capacity=50`，并发 200 跑 20s，扫描 16/50/100/200/300/600：

| max-size | 吞吐 QPS | fill 率 | P50 | P95 | P99 |
|---|---|---|---|---|---|
| 16  | **189.1** | 86.7% | 989ms | 1792ms | 2074ms |
| 50  | 123.4 | 98.6% | 1481ms | 2618ms | 2969ms |
| 100 | 126.8 | 99.4% | 1587ms | 2034ms | 2370ms |
| 200 | 129.7 | 99.5% | 1516ms | 1992ms | 2246ms |
| 300 | 128.0 | 99.5% | 1520ms | 2198ms | 2263ms |
| 600 | 128.5 | 99.2% | 1553ms | 2001ms | 2198ms |

**反直觉发现**：

1. **size=16 时"QPS"反而最高（189），但质量最差**——fill 率只有 86.7%，13.3% 的请求因线程不够根本没等到 DSP 出价就被迫走 no_fill 快速返回。**QPS 数字被"提前放弃"的请求撑高了**，不是真的处理能力强。看 QPS 一定要连着 fill 率一起看，否则会得出"线程越少越快"的错误结论。
2. **size ≥ 50 之后 QPS 稳定在 123~130，几乎不再随线程数变化**——50 一路加到 600，吞吐纹丝不动。**说明线程池在 ≥50 之后已经不是瓶颈**，继续加线程没有收益。
3. P50 延迟在 size≥50 反而比 size=16 更高（1500ms+ vs 989ms），因为瓶颈已经转移——更多请求真正排上队等 CPU，而不是被线程池拒绝提前返回。

### 结论

- 线程池从 16 调到 50 左右就已经打开容量上限；继续加到 600 是浪费，没有额外收益。
- 当前 ~127 QPS 的天花板大概率是**单机 CPU 争用**（压测客户端 + SSP + MySQL + Redis + Kafka 全挤在一台 16GB Mac，呼应第 37 节"c=25 见顶后掉再平"的结论），不是线程池或连接池能解决的。
- 下一步验证方向：压测时用 `top` 看 CPU 是否跑满；或把压测客户端换到另一台机器/换 `wrk` 降低客户端自身开销，排除"测试台子自己是瓶颈"的干扰。

### 第三步：jstack 实测——max-size 根本没被摸到

怀疑"max=600 是不是根本没生效"，压测中途直接 `jstack` 抓 `bidExecutor` 实际线程数验证。

配置 `core=200, max=600, queue=300`，并发 200 压测过程中每秒采样：

```
bidExecutor 线程总数稳定在 269，从没涨到过 600
其中约 94 个在忙(Thread.sleep 模拟调 DSP)、约 177 个在等队列(空闲)
```

**原因（`ThreadPoolExecutor` 扩容规则的关键细节）**：线程池**不是**"队列有堆积就开新线程"，而是"提交任务时发现队列**已经满**（塞不进）才开新线程"。`queue-capacity=300` 把绝大部分波动都吸收了，200 核心线程 + 队列缓冲足够消化负载，只有短暂突刺会把队列塞满、多开几十个线程，很快又回落到平衡点 269，**永远冲不到 max=600**。

这也说明之前"按 目标并发 × DSP 数 ≈ 600 估算 max"是错的——那个公式假设"任务瞬间全部涌入、需要同时处理"，但真实请求是分批到达、边处理边释放，`queue` 缓冲掉了绝大多数并发峰值，实际所需线程数远小于理论峰值。**真正的容量旋钮是 `core-size`（加上突刺时溢出 queue 的那部分），不是 `max-size`。**

### 第四步：SynchronousQueue 探针——强行开满线程，验证瓶颈不在线程数

为彻底排除"是不是 queue 吸收了压力才导致 max 没生效"这个变量，改用 `SynchronousQueue`（容量 0）：它不排队，来一个任务要么马上有空闲线程接、要么立刻开新线程直到 max，逼线程池直接扩到上限。

`ThreadPoolConfig` 临时加了 `queue-capacity=0 → SynchronousQueue` 的支持（实验后已回滚）。
干净对照：唯一变量是队列类型，均 `core=8 / max=600`，并发 200 跑 20s：

| 队列类型 | 实测线程数 | QPS | fill 率 | P50 | P99 |
|---|---|---|---|---|---|
| `LinkedBlockingQueue(300)` | 300 | 193.1 | 99.4% | 953ms | 2068ms |
| `SynchronousQueue`(不排队) | **534** | 189.5 | 99.4% | 1063ms | 2217ms |

**决定性结论**：

1. `SynchronousQueue` 确实把线程逼到接近 max——线程数从 300 飙到 **534**（逼近 600），证明它"不排队、来任务立刻开新线程"的机制生效。
2. **但线程数翻倍，QPS 纹丝不动**（193 → 189，还略降，在噪声内），延迟甚至微升。这一锤定音：
   > **把并发处理线程从 300 加到 534，吞吐量零提升 → 线程数根本不是瓶颈。**
3. 为什么 `SynchronousQueue` 的 P50/P99 反而略差？534 个线程抢同一批 CPU 核心，线程越多**上下文切换开销越大**，在 CPU 已打满的机器上，多开线程是负收益。与第 37 节"单机 CPU 争用是真瓶颈"闭环。

### 排查方法论总结（可复用）

- **量，不要猜**：怀疑线程池，就 `jstack` 抓真实线程数；怀疑连接池，就看 `hikaricp_connections_*` 指标。参数写了多大 ≠ 实际用了多大。
- **一次只改一个变量**：core=max 排除队列干扰测线程数、固定 core/max 只换队列类型测队列机制。
- **看 QPS 必须连着 fill 率一起看**：size=16 时 QPS 虚高（189）但 fill 只有 86.7%，是"提前放弃"撑出来的假象。
- **加资源不涨吞吐 = 该资源不是瓶颈**：这是判断瓶颈位置最硬的证据（线程 300→534 零提升 → 瓶颈在别处 → 单机 CPU）。

### 踩坑：stress_test.py 字段名过期

`scripts/stress_test.py` 请求体还在用旧字段名 `requestId`/`adSlotId`，但 DTO 已经在第 39 节 OpenRTB 改造中换成 `id`/`tagid`，脚本发出去直接 400。已同步改成新字段名。**教训**：改协议字段时，配套脚本/工具也要一起搜一遍，不能只看 controller/DTO。

---

### 项目总结

**从零做完了一个完整的 mini-SSP**，覆盖：

| 模块 | 关键点 |
|---|---|
| 核心竞价流程 | BidController → BidService → CompletableFuture 并发扇出 → 选胜者 |
| 缓存 | Redis TTL，slot/DSP 配置缓存，bid_result 缓存 |
| 限频 | Redis INCR QPS 限制 + 日频次上限 |
| 异步日志 | Kafka 生产/消费 bid_log，批量入库 |
| 追踪回调 | impression/click tracking，302 重定向 |
| 监控 | Prometheus histogram + Grafana 看板 |
| 链路追踪 | MDC + TraceId Filter |
| 压测分析 | 三场景对比、并发扫描（饱和点 c=25）、连接池对照实验、线程池 max-size 扫描（第 40 节） |
| 工程化 | Docker Compose profiles、start.sh 一键启动、8080 端口自动清理 |
