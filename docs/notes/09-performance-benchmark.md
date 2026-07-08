# 压测与性能排障

> 本文件为分类整理后的主题笔记。

## 1. 压测实验：基础设施对比 + 并发扫描 + 连接池对照（Phase 12）

### 压测专用配置（MockDspHandler）

为了让压测结果只反映基础设施差异、排除业务随机性，给 `MockDspHandler` 加了两个开关
（默认值保证正常开发行为不变）：

| 配置 | 默认 | 作用 |
|---|---|---|
| `ssp.dsp.mock-random-seed` | -1（真随机） | 固定随机种子 |
| `ssp.dsp.mock-latency-ms`  | -1（用各 DSP 原始延迟） | 覆盖所有 DSP 延迟；设 0 = 消除 DSP 等待，纯测基础设施 |

**坑**：`-D` 系统属性传不进 Spring 应用，必须用
`./mvnw spring-boot:run -Dspring-boot.run.arguments="--ssp.dsp.mock-latency-ms=0"`。

**坑**：固定种子在并发压测下没用——`Random` 线程安全但多线程调用顺序不确定，
即使固定种子，出价/no_bid 序列也无法复现。种子只在单线程下有意义。

### 实验一：三层基础设施对比（latency=0，50 并发，60s）

固定 latency=0 隔离基础设施差异，三组只改 cache 和 bid_log 写入方式：

| 场景 | 配置 | 吞吐 req/s | P50 | P95 | P99 | max | fill |
|---|---|---|---|---|---|---|---|
| ① Baseline | no-cache + DB 直写 | 129.8 | 419.7 | 621.3 | 720.1 | 1025 | 99.3% |
| ② +Redis Cache | cache + DB 直写 | 133.2 | 401.0 | 631.6 | 746.5 | 962 | 99.4% |
| ③ Full Stack | cache + Kafka 异步 | **142.5** | 401.6 | **570.1** | **647.0** | **768** | 99.4% |

**结论（和直觉不同）**：
- ① → ②（加 Redis 缓存）**差异在噪声级**：吞吐 +3.4、P50 -19ms，但 P95/P99 反而略升。
  原因：slot/DSP 配置表小、有索引，省下那两次 SQL 收益有限。
- ② → ③（bid_log 改 Kafka 异步）**才是清晰赢家**：P95 -61ms、P99 -100ms。
  把"写日志"从同步 DB insert 摘到异步 Kafka，砍掉了尾部卡在写库上的时间。
- **真正压住尾延迟的是把写操作异步化，不是缓存读路径。**

### 实验二：并发梯度扫描（② 配置，pool=10）

固定 ② 配置，并发 25→400，每档 30s，找饱和点：

| 并发 | 吞吐 req/s | P50 | P95 | P99 | error |
|---|---|---|---|---|---|
| 25  | **157.0** | 150.5 | 304.8 | 381.5 | 0% |
| 50  | 108.2 | 448.3 | 648.6 | 861.6 | 0% |
| 100 | 104.5 | 949.9 | 1385.6 | 1687.8 | 0% |
| 200 | 105.1 | 1889.9 | 2661.2 | 3496.2 | 2.8% |
| 400 | 109.5 | 2508.7 | — | — | **96.4%** |

**曲线特征 = 早饱和**：吞吐在 c=25 见顶（157），之后**先掉后平**稳定在 ~105，
延迟随并发**线性暴涨**。c=400 时延迟全顶破压测脚本 3s 超时墙 → 96% error。

### 实验三：连接池对照（pool 10 vs 50）

假设瓶颈是同步写 bid_log 的 HikariCP 连接池（默认 max=10）。
只改 `--spring.datasource.hikari.maximum-pool-size=50`，其它不变，重跑扫描。
（用 `curl /actuator/prometheus | grep hikaricp_connections_max` 确认改到了 50.0）

| 并发 | 吞吐@10 | 吞吐@50 | P99@10 | P99@50 |
|---|---|---|---|---|
| 25  | 157.0 | 158.5 | 381 | 363 |
| 50  | 108.2 | 116.0 | 862 | 763 |
| 100 | 104.5 | 115.9 | 1688 | 1419 |
| 200 | 105.1 | 114.5 | 3496 | 2784 |

**结论：假设只对了一半。**
- ✅ 连接池**确实是约束**：放大 5 倍 → 吞吐平台抬高 ~10%、P99 降 ~16%。
- ❌ 但**不是主瓶颈**：饱和点没动（峰值还在 c=25），曲线形状不变。
  若它是主瓶颈，5 倍连接池应让饱和点大幅右移。
- 真正的瓶颈更深，两个嫌疑：
  1. **DSP fan-out 线程池**（`ThreadPoolConfig`）——每次竞价并发 fan-out 给 3 个 DSP，
     有界池直接限制"同时能跑几场竞价"。
  2. **单机 CPU 争用**——压测客户端(Python 50~400 线程) + SSP + MySQL + Redis 全挤在
     同一台 16GB Mac。"c=25 见顶后掉再平"这种先掉后平，是整机 CPU 争用的特征
     （纯排队会是平的）。**测到的"饱和"有一部分是测试台子本身的极限，不全是 SSP 的。**

### 坑：Docker Desktop 在这台 Mac 上反复崩

跑 ③（依赖 Kafka 容器）时 Docker VM 反复挂（UI 活着、daemon socket 没了）：

| Docker 内存 | 结果 | 原因 |
|---|---|---|
| 3GB | 压测时崩 | VM 内部扛不住 latency=0 的负载 → VM 内 OOM |
| 7GB | 空转也崩 | 16GB Mac 给 Docker 7GB，本机不够 → macOS 杀 VM |
| 5GB | 空转仍崩（free 还有 80%） | 与内存无关，纯 Docker Desktop 稳定性问题 |

**关键发现**：Redis 和 MySQL 都是 **host 原生(brew)**，从没崩过；只有 Kafka/Prometheus/Grafana
在 Docker 里。所以并发扫描（找 SSP 饱和点，不需要 Kafka）改用 ② 配置，绕开 Docker，全程稳定。
教训：**实验依赖的基础设施越少越稳**，能用 host 原生就别塞进不稳定的 Docker。

---

## 2. ab 压测工具对照 + bid_log 写入模式拆分

### 背景

前面用 `scripts/stress_test.py` 做压测时，出现过「并发 200 只有 100 多 QPS」的问题。
进一步验证后发现：**Python `urllib` 压测脚本本身会成为瓶颈**，不适合用来冲极限 QPS。

后续极限压测统一改用 ApacheBench：

```bash
ab -n 20000 -c 200 -p scripts/bid_body.json -T application/json http://127.0.0.1:18080/api/v1/bid
```

`scripts/bid_body.json` 使用 OpenRTB 字段：

```json
{
  "id": "ab-benchmark-request",
  "tagid": "slot-test-001",
  "device": {
    "os": "iOS"
  }
}
```

### 压测专用 perf profile

新增 `application-perf.yml`，用于本机高吞吐压测：

- 关闭 MyBatis SQL 控制台日志
- 降低应用日志级别
- 开启 Redis cache
- `ssp.dsp.mode=mock`
- `ssp.dsp.mock-latency-ms=${SSP_MOCK_LATENCY_MS:0}`
- 默认 `ssp.bid-log.mode=none`

也就是说，未额外设置环境变量时：

```text
DSP mock latency = 0ms
```

这组结果测的是「SSP 主竞价链路纯处理能力」，不是模拟真实 DSP 延迟。

### bid_log 写入模式

为把瓶颈拆清楚，新增/使用了几种模式：

| 模式 | 含义 | 用途 |
|---|---|---|
| `none` | 不写 bid_log | 测 SSP 主链路上限 |
| `kafka` | 每条 DSP 日志发一条 Kafka 消息 | 测原异步日志链路 |
| `kafka-batch` | 一次 bid 只发一条 Kafka 消息，内部包含多条 DSP 日志 | 降低 Kafka 消息数 |
| `direct` | 同步写 MySQL | 测同步写库成本 |

`kafka-batch` 的变化：

```text
旧模式：1 个 bid × 3 个 DSP -> 3 次 kafkaTemplate.send()
新模式：1 个 bid × 3 个 DSP -> 1 次 kafkaTemplate.send()
```

### Python vs ab 对照

同样是 `perf + none + mock latency=0`，Python 脚本和 ab 的结果差异非常大：

| 工具 | 并发 | QPS | 现象 |
|---|---:|---:|---|
| Python `stress_test.py` | 50 | 约 224 | 客户端自身开销很大 |
| Python `stress_test.py` | 100 | 约 96 | 并发升高后吞吐反降 |
| ab | 50 | 2368 | 明显更接近服务端能力 |
| ab | 100 | 5308 | 吞吐继续上升 |
| ab | 200 | 10984 | 主链路接近 1.1w QPS |
| ab | 400 | 11740 | 吞吐接近平台期 |
| ab | 800 | 11846 | QPS 基本不涨，延迟增加 |

**结论**：以后测极限 QPS 用 `ab`；Python 脚本更适合做随机流量模拟，不适合测服务端极限。

### none / kafka / kafka-batch 对照

测试条件：

- `ab`
- `mock latency=0ms`
- 临时实例跑在 `18080`
- 每轮压测后检查 Kafka consumer lag

#### 主链路 none

| 并发 | QPS | 平均延迟 | P99 |
|---:|---:|---:|---:|
| 50 | 2368 | 21ms | 48ms |
| 100 | 5308 | 19ms | 53ms |
| 200 | 10984 | 18ms | 56ms |
| 400 | 11740 | 34ms | 123ms |
| 800 | 11846 | 68ms | 168ms |

结论：单机主链路上限约 1.1w QPS，拐点大约在并发 400 左右；
之后 QPS 不再明显上涨，只是排队延迟增加。

#### 普通 Kafka

| 并发 | QPS | 平均延迟 | P99 | lag |
|---:|---:|---:|---:|---:|
| 50 | 1971 | 25ms | 68ms | 0 |
| 100 | 3810 | 26ms | 79ms | 0 |
| 200 | 6530 | 31ms | 91ms | 0 |
| 400 | 7943 | 50ms | 148ms | 0 |

结论：Kafka 异步日志链路会吃掉一部分吞吐，但这组短压测里 consumer 能跟上，lag 一直是 0。

#### Kafka batch

| 并发 | QPS | 平均延迟 | P99 | lag |
|---:|---:|---:|---:|---:|
| 50 | 2201 | 23ms | 50ms | 0 |
| 100 | 4326 | 23ms | 72ms | 0 |
| 200 | 6513 | 31ms | 97ms | 0 |
| 400 | 7263 | 55ms | 118ms | 0 |

低中并发下，`kafka-batch` 比普通 `kafka` 更好，因为 Kafka 消息数从 `QPS × DSP数`
降到了 `QPS × 1`。高并发下优势不稳定，说明瓶颈不再只是 Kafka 消息条数，
还包括 consumer 批量写 MySQL、CPU 调度和单机资源竞争。

### Kafka lag 的含义

Kafka lag 表示：

```text
Kafka 已生产消息位置 - Consumer 已消费位置
```

在本项目里就是：

```text
SSP 写入 Kafka 的 bid_log 数量 - BidLogConsumer 已消费并写入 MySQL 的数量
```

判断方式：

| lag 表现 | 含义 |
|---|---|
| `lag = 0` | 下游消费/写库跟得上 |
| 短暂升高后归零 | 正常削峰，能追上 |
| 持续升高 | Consumer 或 MySQL 写入跟不上 |
| 很高 + rebalancing | Consumer 不稳定，压测结果会被污染 |

### 为什么 Kafka 是异步仍会降低 QPS

Kafka 只是把「等 MySQL 写完」从请求线程中移走，不代表零成本。
请求线程仍然要做：

- 构造 `BidLog`
- JSON 序列化
- 创建 Kafka 消息
- 放入 Producer buffer
- 处理 producer 元数据和批次追加

同时 Kafka broker、consumer、MySQL、ab、SSP 都在同一台 Mac 上，会抢 CPU、内存和 IO。

所以：

```text
none 约 1.1w QPS
kafka 约 8k QPS
```

这是合理结果。`none` 是主链路上限；`kafka` 是完整异步日志链路能力。

### 单机实验结论

只有一台机器时，不要假装是分布式环境，而要把单机干扰拆清楚：

| 实验 | 目的 |
|---|---|
| `bid-log.mode=none` | 测 SSP 主链路上限 |
| `bid-log.mode=kafka` | 测完整异步日志链路 |
| `bid-log.mode=kafka-batch` | 测减少 Kafka 消息数是否有效 |
| `bid-log.mode=direct` | 测同步写 MySQL 成本 |

面试/复盘说法：

> 在单机环境下，我把压测拆成主链路、异步日志链路、批量 Kafka 链路和同步写库链路。
> 主链路约 1.1w QPS；接入 Kafka 后约 8k QPS，但 lag 为 0，说明 Kafka 链路可承受短时冲击。
> 瓶颈主要来自同机资源竞争、序列化/send 成本和日志写入链路，而不是竞价线程池。

### 二次实验：perf 内存配置 + 不走 MySQL 读配置

为了测更纯的 SSP 主链路，在 `perf` profile 下增加：

- `ssp.perf.static-config-enabled=true`
- 广告位和 3 个 DSP 配置直接从内存返回
- 不查 MySQL 配置表
- 不依赖 Redis 配置缓存是否预热
- `spring.datasource.hikari.minimum-idle=0`
- `spring.datasource.hikari.initialization-fail-timeout=-1`

这组实验的含义：

```text
入口 HTTP + JSON + SSP 竞价逻辑 + 3 个 mock DSP + 指标统计 + 响应构造
```

#### none：纯 SSP 主链路

| 并发 | QPS | 平均延迟 | P99 |
|---:|---:|---:|---:|
| 50 | 10990 | 4.5ms | 14ms |
| 100 | 19417 | 5.1ms | 9ms |
| 200 | 23267 | 8.6ms | 43ms |
| 400 | 26056 | 15.4ms | 51ms |
| 800 | 22435 | 35.7ms | 189ms |

长跑 `c=400, n=500000`：

| QPS | 平均延迟 | P99 |
|---:|---:|---:|
| 27438 | 14.6ms | 99ms |

CPU 观察：

| 指标 | 现象 |
|---|---|
| Java CPU | 约 520%~580%，相当于 5~6 个核心 |
| system idle | 约 8%~13% |

结论：绕开配置读路径后，纯 SSP 主链路甜点区在 `c=400`，约 2.6w~2.7w QPS。
`c=800` 吞吐下降、尾延迟变差，已经过了合理并发点。

#### kafka：每个 DSP 一条 Kafka 消息

| 并发 | QPS | 平均延迟 | P99 | lag |
|---:|---:|---:|---:|---:|
| 50 | 7356 | 6.8ms | 25ms | 0 |
| 100 | 13386 | 7.5ms | 15ms | 0 |
| 200 | 15808 | 12.7ms | 44ms | 0 |
| 400 | 18084 | 22.1ms | 83ms | 0 |

#### kafka-batch：每个 bid 一条 Kafka 消息

| 并发 | QPS | 平均延迟 | P99 | lag |
|---:|---:|---:|---:|---:|
| 50 | 7336 | 6.8ms | 21ms | 0 |
| 100 | 12404 | 8.1ms | 20ms | 0 |
| 200 | 15208 | 13.2ms | 47ms | 0 |
| 400 | 17173 | 23.3ms | 84ms | 0 |

这次 `kafka-batch` 没有明显胜出，说明单机瓶颈不只是 Kafka 消息条数。
Kafka broker、consumer、MySQL、SSP 和 ab 都在同一台机器抢资源，batch 减少了消息数，
但 consumer 写 MySQL、序列化对象大小、调度和系统开销仍然存在。

#### direct：主请求直接写 MySQL

| 并发 | QPS | 平均延迟 | P99 |
|---:|---:|---:|---:|
| 50 | 2256 | 22.2ms | 190ms |
| 100 | 3463 | 28.9ms | 202ms |
| 200 | 4352 | 46.0ms | 188ms |
| 400 | 5402 | 74.1ms | 142ms |

结论：同步写 MySQL 会显著拖慢主链路。即使吞吐随并发还能上升，平均延迟和尾延迟已经不可接受。

### 二次实验总表

| 模式 | 最高观察 QPS | 对纯 SSP 的影响 | 解释 |
|---|---:|---|---|
| `none` | 27438 | 基准 | 不写日志、不查配置库 |
| `kafka` | 18084 | 约为 `none` 的 66% | producer send + broker/consumer/MySQL 同机竞争 |
| `kafka-batch` | 17173 | 约为 `none` 的 63% | 消息数减少，但单机写库/调度仍是瓶颈 |
| `direct` | 5402 | 约为 `none` 的 20% | 请求线程直接承担 MySQL insert |

面试/复盘说法：

> 我进一步做了一个更纯的本机基准：perf 模式下广告位和 DSP 配置直接走内存，不查 MySQL，也不依赖 Redis 预热。
> 纯 SSP 主链路在这台 Mac 上约 2.7w QPS，CPU 已经接近打满。
> 加 Kafka 后约 1.8w QPS，lag 为 0，说明下游能追上，但异步链路不是零成本。
> 直接写 MySQL 只有约 5.4k QPS，说明日志写入绝不能压在主请求线程上。

### 三次实验：关闭结果缓存和 metrics

为了进一步拆 `none` 模式的主链路成本，新增两个开关：

| 配置 | 默认 | 作用 |
|---|---|---|
| `ssp.track.save-bid-result-enabled` | `true` | 是否把中标结果写入 Redis，供曝光/点击追踪 |
| `ssp.metrics.enabled` | `true` | 是否记录 Bid/DSP 的 Micrometer counter/timer |

测试条件：

- `bid-log.mode=none`
- `perf` 内存广告位/DSP 配置
- `mock latency=0ms`
- `ab -l -n 200000 -c 400`

| 实验 | 结果缓存 | metrics | QPS | 平均延迟 | P99 |
|---|---|---|---:|---:|---:|
| baseline | 开 | 开 | 17122 | 23.4ms | 110ms |
| 只关结果缓存 | 关 | 开 | 22899 | 17.5ms | 125ms |
| 只关 metrics | 开 | 关 | 17269 | 23.2ms | 98ms |
| 两者都关 | 关 | 关 | 21583 | 18.5ms | 137ms |
| 两者都关复测 | 关 | 关 | 31798 | 12.6ms | 130ms |

结论：

- 关闭 `saveBidResult` 后，QPS 从约 1.7w 提升到约 2.3w，说明 Redis 写中标结果是主链路的重要成本。
- 只关闭 metrics 几乎没有提升，说明当前瓶颈不是 Micrometer。
- 两者都关的结果波动较大，复测能到约 3.2w QPS；本机压测受背景负载、连接建立和系统调度影响明显。
- 真正值得保留的优化方向是：把 bid result 缓存也异步化，或在极限压测/无追踪场景关闭它。

### 四次实验：异步保存 bid_result

为了保留曝光/点击追踪能力，同时减少主请求线程等待 Redis 的时间，新增：

| 配置 | 默认 | 作用 |
|---|---|---|
| `ssp.track.save-bid-result-async` | `false` | 是否把 bid_result Redis 写入交给后台线程 |
| `ssp.track.executor.core-size` | `2` | 后台保存线程池核心线程数 |
| `ssp.track.executor.max-size` | `4` | 后台保存线程池最大线程数 |
| `ssp.track.executor.queue-capacity` | `10000` | 后台保存队列容量 |

测试条件：

- `bid-log.mode=none`
- `perf` 内存广告位/DSP 配置
- `mock latency=0ms`
- `ab -l -n 200000 -c 400`

| 模式 | QPS | 平均延迟 | P99 | 说明 |
|---|---:|---:|---:|---|
| 同步保存 Redis | 15392 | 26.0ms | 139ms | 主请求线程等待 Redis SET |
| 异步保存 Redis | 16934 | 23.6ms | 118ms | 主请求线程只提交后台任务 |
| 关闭保存 Redis | 21534 | 18.6ms | 136ms | 理论上限，不保留追踪上下文 |

结论：

- 异步保存比同步保存提升约 10%，说明它确实减少了主线程等待。
- 异步保存没有接近关闭保存的上限，说明后台 Redis 写入仍然会通过 CPU、Redis、网络栈和线程调度影响同机压测。
- 在真实部署里，如果 Redis 独立部署、机器资源更充足，异步保存收益可能更明显。
- 需要注意语义变化：极端情况下曝光/点击回调可能早于后台 Redis 写入完成，追踪查询会短暂 miss。

### 五次实验：bidExecutor 队列和线程数

为了验证线程池不是越大越好，将 `ThreadPoolConfig` 的队列从 `LinkedBlockingQueue` 改成
固定容量的 `ArrayBlockingQueue`，减少链表节点分配和 GC 压力。

测试条件：

- `bid-log.mode=none`
- `perf` 内存广告位/DSP 配置
- `mock latency=0ms`
- `save-bid-result-async=true`
- `ab -l -n 200000 -c 400`

| bid core/max/queue | QPS | 平均延迟 | P99 | 观察 |
|---|---:|---:|---:|---|
| 200/300/300 | 18226 | 21.9ms | 114ms | 原 perf 大线程池 |
| 32/64/100 | 19734 | 20.3ms | 115ms | 比大线程池更好 |
| 64/128/100 | 18049 | 22.2ms | 121ms | 线程更多反而下降 |
| 16/32/100 | 20283 | 19.7ms | 118ms | 本轮最高 QPS |
| 16/32/50 | 19899 | 20.1ms | 108ms | QPS 略低，尾部更稳 |

结论：

- 在 `mock latency=0` 的本机压测里，DSP 任务几乎不阻塞，线程池过大会放大调度成本。
- 16/32/100 和 32/64/100 都明显优于 200/300/300，说明原 perf 线程数偏大。
- queue=50 比 queue=100 的 QPS 略低，但 P99 和最长请求更稳；真实 200ms SLA 下，小队列更符合低延迟目标。
- 当前推荐压测起点：`core=16, max=32, queue=50~100`。

公平对比历史最高上限：

- `core=16, max=32, queue=100`
- `save-bid-result-enabled=false`
- `metrics.enabled=false`
- `ab -l -n 200000 -c 400`

| 轮次 | QPS | 平均延迟 | P99 | 说明 |
|---|---:|---:|---:|---|
| 第 1 轮 | 22675 | 17.6ms | 139ms | 未超过历史最好 |
| 第 2 轮 | 34404 | 11.6ms | 127ms | 超过历史最好 31798 |

结论：在纯上限场景下，`16/32/100` 可以刷新历史最高 QPS，但本机短压测波动明显。
因此更稳妥的表述是：线程池从 `200/300/300` 降到 `16/32/50~100` 能降低调度成本，
在保留功能的压测里有约 10% 提升；在关闭 Redis 结果缓存和 metrics 的极限场景里，最高观察到约 3.4w QPS。

补测 `8/16/100`：

- `core=8, max=16, queue=100`
- `save-bid-result-enabled=false`
- `metrics.enabled=false`
- `ab -l -n 200000 -c 400`

| 轮次 | QPS | 平均延迟 | P99 |
|---|---:|---:|---:|
| 第 1 轮 | 21689 | 18.4ms | 148ms |
| 第 2 轮 | 31084 | 12.9ms | 126ms |
| 第 3 轮 | 32844 | 12.2ms | 126ms |

结论：`8/16/100` 也能跑到 3.1w~3.3w QPS，但没有超过 `16/32/100` 的最高 3.44w。
目前 `16/32/100` 仍是这台 Mac 上观察到的最佳纯上限配置。

### 六次实验：单请求时间线拆解

新增 `ssp.trace.timeline-enabled=true`，只在打开时输出单请求时间线。

完整链路：

- Redis 配置缓存
- Kafka bid_log
- Kafka consumer 写 MySQL
- Redis 保存 bid_result
- metrics 开启
- `mock latency=0ms`

#### 粗粒度完整链路 trace

第一次先只在 `BidService`、Kafka producer、Kafka consumer、`TrackService` 打粗粒度时间线。

第一次请求包含冷启动，不适合判断稳态：

| 环节 | 耗时 |
|---|---:|
| slot lookup | 327.84ms |
| dsp list lookup | 13.73ms |
| submit DSP tasks | 3.24ms |
| wait DSP tasks | 3.75ms |
| collect/filter results | 0.39ms |
| select winner | 0.13ms |
| pricing | 0.07ms |
| Kafka producer 初始化 + send 返回 | 32.48ms |
| build response | 0.19ms |
| Redis 保存 bid_result | 1.22ms |
| processBid 总耗时 | 383.38ms |
| Kafka consumer 写 MySQL | 32.52ms + 2.99ms |

第二次请求更接近热启动稳态：

| 环节 | 耗时 |
|---|---:|
| slot lookup，Redis hit | 7.09ms |
| dsp list lookup，Redis hit | 2.03ms |
| frequency cap check | 0.06ms |
| submit DSP tasks | 0.43ms |
| wait DSP tasks | 0.17ms |
| collect/filter results | 0.17ms |
| select winner | 0.06ms |
| pricing | 0.16ms |
| Kafka producer accepted bid_log | 0.64ms |
| build response | 0.03ms |
| Redis 保存 bid_result | 0.58ms |
| processBid 总耗时 | 11.52ms |
| Kafka consumer 写 MySQL | 11.02ms + 1.89ms |

粗粒度 trace 的初步判断：

- 冷启动里最慢的是配置读取、Kafka producer 初始化和 MySQL/Hikari 初始化。
- 热启动里主链路约 11.52ms，按 `并发 400 / 0.01152s` 可反推理论 QPS 约 3.47w，和历史最好压测 3.44w 接近。
- 粗粒度 trace 显示配置读取占比最高，但还不能判断是 Redis GET 还是对象转换，所以继续做下面的 Redis 细分 trace。

#### Redis 读取细分 trace

进一步在 `SlotCacheService` 拆分：

- Redis GET
- cached object convert
- DB select
- Redis SET

第一次请求包含冷启动，不适合判断稳态：

| 环节 | 耗时 |
|---|---:|
| slot lookup | 118.74ms |
| slot redis GET | 113.06ms |
| slot object convert | 5.49ms |
| Kafka producer 初始化 + send 返回 | 31.21ms |
| Redis 保存 bid_result | 5.57ms |
| processBid 总耗时 | 161.68ms |
| Kafka consumer 第一次写 MySQL | 111.72ms |

慢的主要原因是冷启动：Redis/JDBC/Kafka producer/Hikari 等首次连接和初始化。

第二次热请求更接近稳态：

| 环节 | 耗时 |
|---|---:|
| slot redis GET | 0.70ms |
| slot object convert | 1.12ms |
| slot lookup 总计 | 1.93ms |
| slot_dsps redis GET | 0.63ms |
| slot_dsps object convert | 0.77ms |
| dsp list lookup 总计 | 1.51ms |
| submit DSP tasks | 0.26ms |
| wait DSP tasks | 0.15ms |
| Kafka producer accepted bid_log | 0.60ms |
| Redis 保存 bid_result | 0.49ms |
| processBid 总耗时 | 5.29ms |
| Kafka consumer 写 MySQL | 2.76ms + 2.11ms |

结论：

- 不能只凭第一次请求判断瓶颈，冷启动会严重放大 Redis/Kafka/MySQL 初始化耗时。
- 热请求里 Redis GET 和对象转换都在 1ms 左右，配置读取总计约 3.44ms，是主链路里最大的块，但不是单点灾难。
- Kafka producer 对主请求影响约 0.6ms；Kafka consumer 写 MySQL 发生在响应之后，不阻塞 `/bid`。
- 这说明配置读取路径值得继续优化，但需要用热请求/压测平均值判断，不能把冷启动当稳态瓶颈。

#### ObjectMapper 单例化实验

前面的 Redis 细分 trace 发现，热请求里对象转换也有可见成本。原实现每次 Redis cache hit 后都会：

- new `ObjectMapper`
- register `JavaTimeModule`
- 再做 `convertValue`

这会把“反序列化/对象转换”的固定开销放到每次请求主链路里。因此将 `SlotCacheService` 改为注入 Spring 单例 `ObjectMapper`，复用已经配置好的 mapper。

改造后取两条热请求样本：

| 环节 | 样本 1 | 样本 2 |
|---|---:|---:|
| 客户端总耗时 | 17.01ms | 5.68ms |
| slot redis GET | 4.93ms | 0.54ms |
| slot object convert | 1.06ms | 0.14ms |
| slot lookup 总计 | 6.29ms | 0.78ms |
| slot_dsps redis GET | 0.51ms | 0.41ms |
| slot_dsps object convert | 0.70ms | 0.18ms |
| dsp list lookup 总计 | 1.47ms | 0.69ms |
| Kafka producer accepted bid_log | 0.76ms | 0.47ms |
| Redis 保存 bid_result | 0.67ms | 0.39ms |
| processBid 总耗时 | 10.49ms | 3.27ms |

对比单例化前的第二次热请求：

| 指标 | 单例化前 | 单例化后稳态样本 |
|---|---:|---:|
| slot object convert | 1.12ms | 0.14ms |
| slot_dsps object convert | 0.77ms | 0.18ms |
| 两段对象转换合计 | 1.89ms | 0.32ms |
| 两段 Redis GET 合计 | 1.33ms | 0.95ms |
| 配置读取合计 | 3.44ms | 1.47ms |
| processBid 总耗时 | 5.29ms | 3.27ms |

结论：

- 单例 `ObjectMapper` 有明确收益，主要降低了 Redis 命中后的对象转换成本。
- 稳态下 Redis GET 本身仍然不是最大单点；更像是 Redis GET、对象转换、Kafka producer、bid_result Redis SET、线程调度共同累加。
- 第一条样本里 slot Redis GET 抖到 4.93ms，说明本机压测/本机服务共享资源时会有偶发抖动，不能只看单条请求。
- 如果继续优化配置读取路径，可以考虑让 Redis cache 反序列化直接返回强类型对象，或在压测模式/生产热点配置中使用本地内存快照，进一步减少每次请求的 Redis 读取与对象转换。

#### 本地内存配置缓存

为了继续减少 `/bid` 主链路上的 Redis GET 和对象转换，新增本地配置快照：

| 配置 | 默认值 | 说明 |
|---|---:|---|
| `ssp.cache.local-enabled` | `false` | 是否把 slot/dsp/slot-dsp 关系加载到 JVM 内存 |
| `ssp.cache.local-refresh-ms` | `5000` | 本地快照定时兜底刷新间隔 |

实现方式：

- 启动时从 DB 加载启用的 `ad_slot`、启用的 `dsp_config` 和全部 `slot_dsp_rel`，构建本地 Map。
- `/bid` 查询 slot 和 slot 对应 DSP 时，如果 `local-enabled=true`，直接读本地 Map，不再访问 Redis。
- 刷新时先构建新 Map，构建完成后一次性替换引用；请求线程读缓存不加锁，也不会读到半更新状态。
- `SlotService` 新增/更新/删除 slot 后主动刷新。
- `DspService` 新增/更新/删除 dsp 后主动刷新整份快照。
- 定时刷新作为兜底，覆盖直接改 DB 或未来 slot-dsp 关系变更但没有主动刷新入口的情况。

一致性语义：

- 通过后台 API 修改配置：写 DB 成功后刷新本地快照，通常立即生效。
- 绕过应用直接改 DB：最多等待 `local-refresh-ms` 后生效。
- 刷新失败：保留旧快照，避免竞价线程读到空的半成品数据；日志记录错误。
- 单机内本地缓存一致性比较好；多实例部署时，每个实例都需要主动刷新或依赖定时刷新。生产环境可以进一步用 Redis Pub/Sub、Kafka 配置变更事件或版本号轮询来广播刷新。

当前 perf profile 已改为：

| 配置 | 值 |
|---|---|
| `ssp.perf.static-config-enabled` | `false` |
| `ssp.cache.local-enabled` | `true` |

这样后续压测测到的是“真实 DB 加载一次，主请求读 JVM 本地内存”的路径，而不是手写静态假配置。功能验证：启动 perf 服务后单条 `/api/v1/bid` 返回 `200`。

本地缓存 QPS 对照实验：

实验条件：

- `ab -n 30000 -c 400`
- `ssp.bid-log.mode=none`
- `ssp.track.save-bid-result-enabled=true`
- `ssp.track.save-bid-result-async=true`
- `ssp.perf.static-config-enabled=false`
- `ssp.dsp.mode=mock`
- `ssp.dsp.mock-latency-ms=0`

| 配置读取方式 | 第 1 轮 | 第 2 轮 | 第 3 轮 | 热身后最高 |
|---|---:|---:|---:|---:|
| 本地内存缓存 `local-enabled=true` | 11051 QPS | 28186 QPS | 32214 QPS | 32214 QPS |
| Redis cache `local-enabled=false` | 9791 QPS | 14229 QPS | 17212 QPS | 17212 QPS |

延迟对比：

| 配置读取方式 | 最好一轮 mean | P50 | P95 | P99 |
|---|---:|---:|---:|---:|
| 本地内存缓存 | 12.42ms | 6ms | 37ms | 128ms |
| Redis cache | 23.24ms | 20ms | 52ms | 94ms |

说明：

- `ab` 显示的 `Failed requests` 是响应体长度不同导致的 `Length` 统计；竞价有 win/no-fill，响应长度天然可能不同，不代表 HTTP 请求失败。
- 本地缓存热身后最高约 3.22w QPS，Redis cache 热身后最高约 1.72w QPS，本地缓存提升约 87%。
- 第 1 轮受 JVM/JIT、连接和缓存热身影响，不适合作为稳态结果。
- 这说明配置读取从 Redis 移到 JVM 本地内存，对当前单机压测非常有效；主链路少了 Redis GET、对象转换和相关调度成本。

#### bid_result Redis 批量异步写实验

新增 `ssp.track.save-bid-result-mode`：

| 模式 | 说明 |
|---|---|
| `sync` | 主线程同步写 Redis |
| `async` | 每个请求提交一个异步任务写 Redis |
| `batch` | 主线程入队，后台线程用 Redis pipeline 批量 SET |

`batch` 模式配置：

| 配置 | 默认值 | 说明 |
|---|---:|---|
| `ssp.track.bid-result-batch-size` | `200` | 单批最多写入条数 |
| `ssp.track.bid-result-flush-interval-ms` | `5` | 最长等待多久刷一批 |
| `ssp.track.bid-result-queue-capacity` | `50000` | 内存队列长度 |

实现语义：

- 主请求线程只 `offer` 到内存队列。
- 后台 `bid-result-batch-writer` 线程批量取出，用 Redis pipeline 写入。
- 队列满或 writer 未启动时，回退同步写 Redis，优先保证追踪数据不丢。
- pipeline 写失败时，回退逐条同步写。

对照实验：

- `ab -n 30000 -c 400`
- 本地内存配置缓存开启
- `ssp.bid-log.mode=none`
- `ssp.dsp.mock-latency-ms=0`

| bid_result 写入模式 | 第 1 轮 | 第 2 轮 | 第 3 轮 | 热身后最高 |
|---|---:|---:|---:|---:|
| `sync` | 11423 QPS | 18245 QPS | 22821 QPS | 22821 QPS |
| `batch` | 11453 QPS | 24700 QPS | 30964 QPS | 30964 QPS |
| `async` | 10323 QPS | 27202 QPS | 31355 QPS | 31355 QPS |

结论：

- `sync` 明显慢于异步模式，说明主线程直接等待 Redis SET 会压低吞吐。
- 当前单机环境和参数下，`batch` 没有超过普通 `async`，最好结果略低于 `async`。
- 可能原因是主线程 `ArrayBlockingQueue.offer` 的竞争、单后台 writer 的消费能力、Redis pipeline 批大小/flush 间隔与当前请求节奏不匹配。
- 因为 `batch` 没有赢，perf profile 默认保留 `save-bid-result-mode=async`；`batch` 作为可切换实验模式保留。
- 如果后续继续调 batch，可尝试更大 batch size、多 writer 分片队列、或 Disruptor/MPSC 队列，但这已经不是当前最确定的收益点。
