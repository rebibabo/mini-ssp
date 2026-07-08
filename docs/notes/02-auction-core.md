# 竞价主链路与业务策略

> 本文件为分类整理后的主题笔记。

## 1. 竞价流程

```
用户打开 App
    │
    ▼
App ──BidRequest(adSlotId, 设备信息, 用户信息)──▶ SSP
                                                    │
                                    ┌───────────────┼───────────────┐
                                    ▼               ▼               ▼
                                  DSP-A           DSP-B           DSP-C
                                DspBidRequest   DspBidRequest   DspBidRequest
                                (含底价)         (含底价)         (含底价)
                                    │               │               │
                                    ▼               ▼               ▼
                                出价 3.50        出价 2.80        超时/不出价
                                    │               │               │
                                    └───────────────┼───────────────┘
                                                    ▼
                                            SSP 选最高价 DSP-A
                                                    │
    App 展示广告 ◀──BidResponse(广告内容, 追踪URL)──┘
```

### DSP 为什么需要设备信息和用户信息

广告精准投放的核心。DSP 根据用户画像决定：
- 要不要对这个用户出价
- 出多少钱（有画像的用户价值更高）

### requestId 由谁生成

由**媒体（App）生成**，贯穿整个链路（BidRequest → DspBidRequest → bid_log → event_log），用于全链路追踪和问题排查。

### 竞价定价方式

| 方式 | 规则 | 说明 |
|------|------|------|
| 一价拍卖 | win_price = 中标者 bid_price | 实现简单 |
| 二价拍卖 | win_price = 第二高价 + 0.01 | DSP 直接报真实心理价，更公平 |

两种都已实现，用策略模式按 `ssp.bid.auction-type`(first/second)切换，详见第 30 节。

### no fill 的情况

```
1. 广告位没有关联任何 DSP
2. 所有 DSP 都超时
3. 所有 DSP 都不出价
4. 所有出价都低于底价
5. 所有 DSP 调用都异常
```

---

## 2. Phase 6：真实 Mock DSP（WebClient + 策略模式 + 多实例）

### Mode A vs Mode B

| | Mode A 进程内模拟 | Mode B 真实 HTTP |
|---|---|---|
| 实现 | `MockDspHandler`（同进程，直接调方法） | 独立 Mock DSP 服务 + WebClient HTTP |
| 依赖 | 零，一个项目跑通 | 需另起 2-3 个服务 |
| 练到什么 | 核心竞价逻辑 | HTTP 客户端、超时、跨进程容错 |
| 用途 | 快速验证 / 单元测试 | 接近真实链路 |

策略：先用 A 跑通逻辑，再切 B 练 HTTP。两者**用配置开关切换，不改代码**。

### 策略模式 + @ConditionalOnProperty 切换实现

让 `BidService` 只依赖一个接口，不关心底层是模拟还是 HTTP：

```java
public interface DspCaller {
    DspBidResponse bid(DspConfig dsp, DspBidRequest request);
}
```

两个实现，靠 `@ConditionalOnProperty` 按配置**二选一注册成 Bean**：

```java
// Mode A：ssp.dsp.mode=mock 或没配（matchIfMissing=true）时启用
@ConditionalOnProperty(name = "ssp.dsp.mode", havingValue = "mock", matchIfMissing = true)
public class MockDspCaller implements DspCaller { ... }

// Mode B：ssp.dsp.mode=http 时启用
@ConditionalOnProperty(name = "ssp.dsp.mode", havingValue = "http")
public class DspBidClient implements DspCaller { ... }
```

| 关键点 | 说明 |
|---|---|
| `name` | 看哪个配置 key |
| `havingValue` | 值等于它才注册 |
| `matchIfMissing = true` | 没配这个 key 时也注册（拿来做默认实现） |

**为什么这样设计：** 容器里同一时刻只会有一个 `DspCaller` Bean，`BidService` 注入接口即可。切换只改 `application.yml` 的 `ssp.dsp.mode`，零代码改动。单元测试还能 mock 这个接口（见第 24 节）。

> 注意两个实现的 `havingValue` 不能同时命中，否则两个 Bean 都注册 → 注入接口时报"找到多个候选"。

### WebClient 真实调用 DSP

```java
return webClient.post()
        .uri(dsp.getBidUrl())                       // 目标 DSP 地址（存在 dsp_config.bid_url）
        .bodyValue(request)                          // 请求体 DspBidRequest → JSON
        .retrieve()
        .bodyToMono(DspBidResponse.class)            // 响应 JSON → DspBidResponse
        .timeout(Duration.ofMillis(timeoutMs))       // 每个 DSP 独立超时
        .block(Duration.ofMillis(timeoutMs + 50));   // 异步 Mono 转同步
```

| 点 | 说明 |
|---|---|
| 为什么 `.block()` | `callDsp` 跑在 `bidExecutor` 线程池里本身是同步语义（第 14 节），用 block 把 WebClient 的异步 Mono 取成结果 |
| 超时/异常怎么办 | block 超时或网络错抛异常 → 被 `callDsp` 的 try-catch 兜成 error 结果，不影响其他 DSP |
| 每 DSP 独立超时 | 取 `dsp.getTimeoutMs()`，没配用兜底默认值 |

### 这条调用链逐步拆解（重点：Mono）

```java
.retrieve()                                  // 发请求，声明要取响应
.bodyToMono(DspBidResponse.class)            // 响应体 JSON → DspBidResponse，包成 Mono
.timeout(Duration.ofMillis(timeoutMs))       // 给这个 Mono 设端到端总超时
.block(Duration.ofMillis(timeoutMs + 50));   // 阻塞等结果，把 Mono 拆成真对象
```

**Mono 是什么：** `Mono<T>` = "**一个未来才会到达的、最多一个的结果**"，可类比"取餐小票"。发请求那一刻响应还没回来，WebClient 不让你干等，先给你一张小票（Mono），结果到了再往里填值。（`Flux<T>` 是 0~N 个结果的流，我们一个请求对一个响应，用 Mono。）

| 方法 | 干什么 |
|---|---|
| `.retrieve()` | 发起请求并声明"我要取响应"；HTTP 4xx/5xx 时自动抛异常（mock DSP-C 返 500 就走这里 → 被 callDsp 兜底） |
| `.bodyToMono(X.class)` | **body→to→Mono**：把响应体 JSON 反序列化成 `X` 对象，包进 `Mono<X>`。传 `.class` 是告诉 Jackson 按哪个类的字段去匹配 JSON |
| `.timeout(d)` | 给 Mono 设最长等待，超时发 `TimeoutException`（端到端总超时，非连接超时） |
| `.block(d)` | 订阅 Mono（请求才真正发出，响应式是 lazy 的）+ 阻塞当前线程等到结果，把 `Mono<X>` 拆出真正的 `X` 返回 |

**为什么最后要 block：** `callDsp` 跑在线程池里是同步语义（第 14 节），需要一个实实在在的 `DspBidResponse` 返回值，不能返回"小票"。`block()` 把异步世界接回同步世界。

> 当前认知够用了，不必深挖 Reactor 全家桶：知道 **Mono = 异步结果占位符**、**block = 拆开它拿值** 即可。等以后做全链路响应式（WebFlux，全程不 block）再系统学。

### 独立服务 + profile 多实例

**一份代码当 3 个 DSP 用**：行为（端口/出价区间/延迟/不出价率/异常率）抽成配置，用 profile 切换，启动 3 个实例。

```java
@ConfigurationProperties(prefix = "mock.dsp")   // 绑定 application.yml 的 mock.dsp.*
public class DspBehaviorProperties { ... }
```

`application.yml` 用 `---` 分隔多个 profile 段：

```yaml
---
spring:
  config:
    activate:
      on-profile: dsp-a      # 这段只在 dsp-a profile 生效
server:
  port: 8081
mock:
  dsp:
    name: dsp-001
    latency-min-ms: 30
    latency-max-ms: 100
```

启动不同实例：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dsp-a   # 8081
./mvnw spring-boot:run -Dspring-boot.run.profiles=dsp-b   # 8082
./mvnw spring-boot:run -Dspring-boot.run.profiles=dsp-c   # 8083，配高异常率/超时率练容错
```

**为什么这样而不是写 3 个项目：** 代码完全一样，只是行为参数不同，复制 3 份会重复维护。一份代码 + profile 驱动，加个 DSP 只是加段配置。

| 概念 | 一句话 |
|---|---|
| `@ConfigurationProperties` | 把 `yml` 里一组配置批量绑定到一个对象（比逐个 `@Value` 省事） |
| profile | 一套命名的配置环境，启动时用 `--spring.profiles.active` / `-Dspring-boot.run.profiles` 选 |
| `---` + `on-profile` | 单文件里写多套 profile 配置，靠分隔符切段 |

### Mode B 联调清单

1. `dsp_config.bid_url` 改成 `http://localhost:8081/bid` 等（HTTP 模式才读这个字段）
2. 改完清 `ssp:dsp:*` / `ssp:slot_dsps:*` 缓存或等 TTL，否则读到旧配置
3. SSP 用 `--ssp.dsp.mode=http` 启动，先起好 3 个 mock DSP

---

## 3. 二价拍卖与计价策略（策略模式 2.0）

### 一价 vs 二价

| | 一价(first) | 二价(second / GSP) |
|---|---|---|
| 中标者 | 出价最高者 | 出价最高者（不变） |
| 实付价 | 自己的出价 | **第二高价 + 增量** |
| 例(6.32/3.32/0.72) | 付 6.32 | 付 3.33 |
| DSP 行为 | 倾向博弈压价 | 报**真实心理价**最优，更公平稳定 |

中标者和实付价**解耦**：bid_log 仍记各 DSP 真实出价，`winPrice` 是策略算出的成交价。

### 二价的边界（容易错）

| 情况 | 算法 |
|------|------|
| ≥2 个有效出价 | `第二高 + 增量`，且不超过赢家自己出价（取 `min`，防并列最高时超出） |
| 只有 1 个有效出价 | 没有第二高 → **付底价**（行业惯例） |
| 会低于底价吗 | 不会，第二高竞价前已按底价过滤过 |

### 策略模式（和 DspCaller 同款套路）

```
PricingStrategy(接口)
 ├─ FirstPricePricing   @ConditionalOnProperty(..., havingValue="first", matchIfMissing=true)
 └─ SecondPricePricing  @ConditionalOnProperty(..., havingValue="second")
```

BidService 只依赖接口，选出赢家后把「有效出价(降序) + 底价」交给策略算价：

```java
List<BigDecimal> sortedBids = results.stream()
        .map(r -> r.getResponse().getBidPrice())
        .sorted(Comparator.reverseOrder()).toList();
BigDecimal winPrice = pricingStrategy.computeWinPrice(sortedBids, floorPrice);
```

**接口入参只传 `List<BigDecimal>` 而非内部类 `DspBidResult`**：策略只需要价格，不暴露 BidService 的私有内部类，耦合更低。

### 成交价持久化（win_price）

`bid_log` 原本只记各 DSP 的 `bid_price`，**没有成交价**。一价时还能拿中标者的 bidPrice 凑合，二价下 `winPrice ≠ 任何单个 DSP 的出价`，光查 DB 就看不到实付价。

加列 `win_price DECIMAL(10,4) NULL`，写入要点：

| 点 | 说明 |
|----|------|
| winPrice 算的时机 | 提前到「写 bid_log 之前」算好，一份用于写日志、一份用于构造响应；no fill 时为 null |
| 写到哪条 | 只写 `win==1` 的中标记录，其余 DSP 记 NULL（二价下成交价不属于任何单个 DSP 的出价，记赢家行语义最清晰） |
| 效果 | 光查 bid_log 就能回答「谁中标 + 实付多少」，可审计，不用再翻 requests.jsonl |

### 单测要点（PricingStrategyTest）

- 纯逻辑无依赖，直接 `new` 出来测各分支（测试金字塔底层）。
- `@Value increment` 单测不注入 → `ReflectionTestUtils.setField` 手动设（同第 24 节 globalTimeoutMs）。
- BigDecimal 断言用 `isEqualByComparingTo`，避免 3.01 vs 3.0100 精度判不等（同第 24 节）。

---

## 4. 用户频次控制（Frequency Cap）

### 频次控制 ≠ QPS 限流（两条轴，别混）

| | QPS 限流(第 20 节) | 频次控制(本节) |
|---|---|---|
| 限制对象 | **每个 DSP**:每秒接几个请求 | **每个用户**:某 DSP 广告每天看几次 |
| 目的 | 保护 DSP 别被压垮 | 保护用户体验,别让广告刷屏 |
| 维度 | per-DSP / per-second | per-(user,dsp) / per-day |
| key | `ssp:rate:{dspId}:{秒}` | `ssp:freq:{userId}:{dspId}:{日}` |

两者都是 Redis 固定窗口,只是粒度从「秒」变「天」——进入新的一天 key 变了自动清零。

### 两个动作分两个时机：检查 vs 计数

```
检查(isCapped)：竞价时——筛掉该用户今日看够的 DSP，不让它参与
计数(increment)：曝光埋点时——用户真看到了，对(user,dsp)今日 +1
```

**计数放曝光而非中标**:频次 = 真正「看到」的次数,所以在 impression 回调里 +1,不是竞价返回时。

### 整体流程（两个时机靠 Redis 计数器串起来）

**流程一 · 竞价时——检查 + 把 userId 带下去**

```
媒体发 BidRequest(带 user.userId)
  → BidService 查到关联 DSP 列表 [dsp-001, dsp-002, dsp-003]
  → 【检查】对每个 DSP 调 isCapped(userId, dspId, cap)
        读 Redis: GET ssp:freq:{userId}:{dspId}:{今天}，计数 ≥ cap 的被剔除
  → 用"没看够"的 DSP 竞价 → 选 winner
  → buildBidResponse 把 userId 塞进 BidResponse
  → saveBidResult: BidResponse(含 userId、winDsp)缓存进 Redis ssp:bid_result:{rid}
  → 返回广告
```

**流程二 · 曝光时——计数**

```
用户真看到广告 → GET /track/impression?rid={rid}
  → TrackService 从 Redis 取 ssp:bid_result:{rid} → 拿到 userId + winDsp
  → 写 event_log
  → 【计数】increment(userId, winDsp): INCR ssp:freq:{userId}:{winDsp}:{今天}(首次设 TTL)
```

**闭环**

```
第 1~3 次看 dsp-002 广告 → 每次曝光 INCR → 计数 1→2→3
第 4 次竞价 → isCapped 读到 3 ≥ cap → dsp-002 被剔除，不再投给该用户
进入新的一天 → key 里日期变 → 计数从 0 重新开始
```

一句话:**看一次记一笔(曝光计数),竞价前查账本(检查),看够了就不再投,每天账本翻新页(按天 key)。**

| 角色 | 职责 |
|------|------|
| `FrequencyCapService` | 管 Redis 账本:`isCapped` 查、`increment` 记 |
| `BidService` | 竞价前查账本剔除看够的 DSP;把 userId 寄存进结果 |
| `TrackService` | 曝光时记账 |
| `BidResponse.userId` | 把"是谁"从竞价时带到曝光时的载体 |

### 为什么 BidResponse 要加 userId

曝光回调只带 `requestId`,要知道「是哪个用户看的」才能计数。所以竞价时把 `userId` 一起塞进缓存的 `BidResponse`(Redis `ssp:bid_result:{rid}`),曝光时取出来用。

### 为什么用 StringRedisTemplate

计数器是纯数字。项目主 `RedisTemplate` 配的是 JSON 序列化器(给对象用),拿来做 `INCR`/`GET` 数字会有类型转换的别扭。Spring Boot 自动配了 `StringRedisTemplate`(key/value 都纯字符串),`INCR` 返回 Long、`GET` 返回可直接 `Long.parseLong` 的字符串,最干净。

### 边界

- **匿名用户**(无 userId):没法追踪 → 跳过频控,正常参与竞价。
- 所有关联 DSP 都被 cap → 该次竞价 no fill。
- cap 配置 `ssp.freq.daily-cap`(0=不限);进阶可做成每 DSP 不同(放 dsp_config)。

---

## 5. OpenRTB 字段对齐（@JsonProperty 解耦内部模型与线上协议）

### 背景

DTO 字段原本是自定义命名（`requestId`/`adSlotId`/`floorPrice`...），跟着 OpenRTB 2.5 规范化，
让对外 JSON 用协议标准 key（`id`/`tagid`/`bidfloor`...）。

### 核心手法：`@JsonProperty`

```java
@JsonProperty("id")
private String requestId;   // Java 内部仍叫 requestId，对外 JSON 收发是 "id"
```

- **双向**：序列化输出用新 key，反序列化输入也只认新 key（旧 key 会收不到值）。
- **解耦**：内部代码 `getRequestId()` 一行不用改，只有 JSON 协议层变。
- 这正是生产做法——**内部领域模型命名 ≠ 线上契约命名**，互不绑架。

### 哪些该改、哪些不该

| 边界 | DTO | 是否 OpenRTB | 处理 |
|---|---|---|---|
| 媒体→SSP | `BidRequest`/`Device`/`User` | 建模自 OpenRTB | 改：`id`/`tagid`/`osv`/`keywords` |
| **SSP↔DSP** | `DspBidRequest`/`DspBidResponse` | **正是 OpenRTB 接口** | 改：`id`/`tagid`/`bidfloor`/`price` |
| SSP→媒体 | `BidResponse` | ✗ 私有协议 | 不动 |
| DB 落库 | `BidLog`/`EventLog` | ✗ 内部存储 | 不动（改了还连累列映射） |

### 易踩的坑

1. **跨项目要两边同步**：SSP 和 mock-dsp 各有一份 `DspBidRequest/Response`，
   一边加 `@JsonProperty` 另一边不加 → 反序列化丢字段。
2. **改名不是改语义**：`age→yob`（年龄→出生年）值含义不同，不属于纯改名，单独处理。
3. **示例/脚本要同步**：README、`test-modeB.sh` 里 curl 的请求体 key 也得改，否则 400。

### 一句话（面试可讲）

> OpenRTB 只规范 SSP↔DSP 的竞价交互；我用 `@JsonProperty` 让对外 JSON 遵循 OpenRTB 2.5，
> 同时保留内部可读命名，做到协议层与领域模型解耦。

---
