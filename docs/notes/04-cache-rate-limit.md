# Redis 缓存与限流

> 本文件为分类整理后的主题笔记。

## 1. Redis 缓存

### 什么时候适合缓存

同时满足以下三点才值得缓存：
- 读多写少（广告位配置几乎只读）
- 数据允许短暂不一致（改了配置延迟几分钟生效可接受）
- 查询频繁（每次竞价都要查）

### SSP 里哪些需要缓存

| 数据 | 是否缓存 | 原因 |
|------|---------|------|
| 广告位配置（ad_slot） | ✅ 缓存 | 每次竞价都查，几乎不变 |
| 广告位关联 DSP（slot_dsp_rel + dsp_config） | ✅ 缓存 | 每次竞价都查，很少变 |
| 竞价结果（bid_result） | ✅ 缓存 | 埋点回调时需要查，写一次读一次 |
| DSP QPS 计数器 | ✅ Redis INCR | 需要原子计数，天然适合 Redis |
| bid_log | ❌ 不缓存 | 每次都不同，写多读少 |
| event_log | ❌ 不缓存 | 每次都不同，写多读少 |

### Cache-Aside 旁路缓存模式

最常用的缓存策略：

```
读：先查 Redis → 命中直接返回 → 未命中查 DB → 写入 Redis（设 TTL）→ 返回
写：先更新 DB → 删除缓存（evict）
```

代码结构：
```java
public AdSlot getSlot(String slotId) {
    // 1. 查 Redis
    Object cached = redisTemplate.opsForValue().get(key);
    if (cached != null) return convertToAdSlot(cached);  // 命中，直接返回

    // 2. 查 DB
    AdSlot slot = adSlotMapper.selectOne(...);

    // 3. 写入 Redis，设过期时间
    redisTemplate.opsForValue().set(key, slot, 10, TimeUnit.MINUTES);
    return slot;
}
```

### TTL（Time To Live）

TTL 是缓存的有效期，过期后 Redis 自动删除，下次请求重新从 DB 读取最新数据。

设 TTL 的原因：防止缓存不一致——如果不设，数据库更新了但 Redis 里还是旧数据，用户一直看到旧的。

### RedisTemplate 操作

```java
// 存（带过期时间）
redisTemplate.opsForValue().set(key, value, 10, TimeUnit.MINUTES);

// 取
Object value = redisTemplate.opsForValue().get(key);

// 删
redisTemplate.delete(key);

// 计数器（原子 +1）
redisTemplate.opsForValue().increment(key);
```

### 序列化问题

Redis 存对象时，Jackson 把对象转成 JSON 存入。取出来时类型信息丢失，变成 `LinkedHashMap`，需要手动转换回原来的类型：

```java
// 单个对象
mapper.convertValue(cached, AdSlot.class);

// List（不能直接用 List.class，泛型擦除导致不知道装的是什么）
mapper.convertValue(cached,
    mapper.getTypeFactory().constructCollectionType(List.class, DspConfig.class));
```

### @SuppressWarnings("unchecked")

强制类型转换时编译器会给 `unchecked cast` 警告，加这个注解告诉编译器"我知道有风险，别警告了"。前提是自己已经确认类型安全。

### 埋点追踪流程

requestId 是贯穿整个链路的"线索"：

```
竞价完成（BidService）
    ↓
把 BidResponse 存进 Redis（key: ssp:bid_result:{requestId}，TTL 5分钟）
    ↓
返回广告给 App

App 展示广告
    ↓
曝光埋点 GET /track/impression?rid=req-xxx
    ↓
用 requestId 从 Redis 取出 BidResponse → 写 event_log → 返回 204

用户点击广告
    ↓
点击埋点 GET /track/click?rid=req-xxx
    ↓
用 requestId 从 Redis 取出 BidResponse → 写 event_log → 302 重定向到 clickUrl
```

**为什么用 Redis 存竞价结果而不查 DB：**
- clickUrl 根本没存进 DB，只在内存/Redis 里
- DB 查询需要 join 多张表，慢
- 竞价结果是临时数据，5 分钟后无意义，不值得长期存储

**Redis 过期后埋点怎么办：** 直接返回 204/忽略，5 分钟后的曝光点击数据意义不大。

### TrackController 新知识点

**`@ResponseStatus(HttpStatus.NO_CONTENT)`**

让接口固定返回 HTTP 204，方法返回 void，表示"成功但没有响应体"，曝光追踪用这个。

**`HttpServletRequest`**

Spring 自动注入的请求对象，从里面取用户信息：
```java
request.getRemoteAddr()        // 用户 IP
request.getHeader("User-Agent") // 浏览器/App 标识
```

**`RedirectView`**

返回 302 重定向，构造参数是目标 URL，用户点击广告后浏览器自动跳转到落地页。

**`@RequestParam String rid`**

从 URL 参数里取值，`rid` 是 `requestId` 的缩写：
```
GET /api/v1/track/impression?rid=req-123
                              ↑ 取这个值
```
没有 `defaultValue` 说明是必填，不传返回 400。

### 压测对比实验

用 Apache Bench（ab）对比有无缓存的性能差异，模拟 DB 慢查询（sleep 50ms）：

| 指标 | 有缓存 | 无缓存 |
|------|--------|--------|
| QPS | 45.23 | 36.03 |
| 平均响应时间 | 206ms | 259ms |
| 最快 | 122ms | 185ms |

**结论：** 缓存减少了 53ms 响应时间，QPS 提升 25%。数据库越慢、并发越高，缓存效果越显著。

**为什么不加慢查询时效果不明显：** 瓶颈在 Mock DSP 等待时间（~120ms），DB 查询只需 1-2ms，Redis 也差不多，差距淹没在 DSP 等待里。

**压测命令：**
```bash
ab -n 200 -c 10 -p /tmp/bid_body.json -T application/json http://localhost:8080/api/v1/bid
```

### Redis Key 规范

| Key | TTL | 说明 |
|-----|-----|------|
| `ssp:slot:{slotId}` | 10 分钟 | 广告位配置缓存 |
| `ssp:dsp:{dspId}` | 10 分钟 | DSP 配置缓存（暂未实现） |
| `ssp:slot_dsps:{slotId}` | 10 分钟 | 广告位关联的 DSP 列表 |
| `ssp:rate:{dspId}:{yyyyMMddHHmmss}` | 2 秒 | DSP 限流计数器 |
| `ssp:bid_result:{requestId}` | 5 分钟 | 中标结果，供曝光/点击回调查询 |

---

## 2. DSP QPS 限流

### QPS 限流 ≠ 按小时统计

| 概念 | 窗口 | 语义 | 实现难度 |
|------|------|------|---------|
| QPS 限流 | **1 秒** | 每秒最多处理几个请求，防止把 DSP 压垮 | 简单（固定窗口） |
| 长周期限流 | 1 小时/1 天 | 每小时最多调用 N 次（如配额限制） | 复杂（滑动窗口/令牌桶） |

SSP 场景做的是 **QPS 限流**：窗口只有 1 秒，语义是"这个 DSP 每秒最多接多少竞价请求"。不需要滑动窗口那么复杂。

### 固定窗口（按秒）的核心思路

key 里带"精确到秒的时间戳"，**每进入新的一秒就是一个全新的 key**，靠 key 名字自动切换实现"每秒重新计数"，不需要手动清零：

```
key   = ssp:rate:dsp001:20260613135905   ← dspId + 精确到秒的时间
value = 3                                 ← 这一秒内 dsp001 已被请求 3 次
```

```
13:59:05 这一秒  →  key = ...135905
13:59:06 这一秒  →  key = ...135906   ← 不同 key，value 从 0 重新数
13:59:07 这一秒  →  key = ...135907   ← 又是新 key
```

进入新的一秒，key 变了，这个新 key 在 Redis 里还不存在，第一次 INCR 返回 1——自动"清零"。

### TTL 为什么是 2 秒

- 旧 key（如 `...135905`）过了它对应的那一秒就再也没人访问，但**还躺在 Redis 内存里**。不设 TTL 会越积越多撑爆内存，所以 TTL 的作用是**自动清理用完的旧 key**。
- 理论上 TTL=1 秒就够（这一秒过完 key 就该死）。留到 2 秒是**安全垫**：怕请求在 13:59:05.999（这一秒快结束时）才来 INCR，TTL 卡太死可能导致 key 刚设上就因边界时机判过期、漏算。多 1 秒缓冲确保这一秒内所有请求都累加到同一个 key 上。对内存几乎无影响（key 本就秒级淘汰）。

### 实现（RateLimiter.tryAcquire）

```java
public boolean tryAcquire(String dspId, Integer qpsLimit) {
    // qpsLimit 为 null 或 0 表示不限流
    if (qpsLimit == null || qpsLimit <= 0) return true;

    // key 精确到秒，每秒一个新 key
    String key = "ssp:rate:" + dspId + ":" + now.format("yyyyMMddHHmmss");

    // INCR：原子 +1，返回累加后的值；key 不存在时首次返回 1
    Long count = redisTemplate.opsForValue().increment(key);

    // 第一次创建 key（返回 1）时设 TTL，避免旧 key 堆积
    if (count != null && count == 1) {
        redisTemplate.expire(key, 2, TimeUnit.SECONDS);
    }

    return count != null && count <= qpsLimit;  // 超过 limit 返回 false
}
```

**为什么用 INCR：** Redis 的 INCR 是**原子操作**，并发场景下不会出现"读-加-写"竞态导致计数错误，天然适合做计数器。

### 完整流程示例（qpsLimit=10）

```
13:59:05.100  调 dsp001
   INCR ...135905 → 1，是第一次 → 设 TTL=2秒
   1 > 10? 否 → 放行

13:59:05.500  又调 dsp001（同一秒）
   INCR ...135905 → 2，2 > 10? 否 → 放行
   ... 累加到 11 ...

13:59:05.900  第 11 个请求
   INCR ...135905 → 11，11 > 10? 是 → 限流！跳过 dsp001

13:59:06.100  新的一秒
   INCR ...135906 → 1（新 key，自动清零）→ 放行
   旧 key ...135905 在 13:59:07 左右 TTL 到期自动删除
```

### 边界突刺问题（了解即可）

固定窗口在窗口交界处理论上可能出现"瞬时 2 倍流量"（如 05.999 来 10 个、06.001 又来 10 个），是固定窗口的固有缺陷。但这是业界对 QPS 限流的常见简化做法，足够实用，比滑动窗口/令牌桶简单太多。

### 集成到竞价流程

`BidService.callDsp()` 在真正请求 DSP **之前**先 `tryAcquire`，被限流就直接返回 `DspBidResult.rateLimited()`，不发起调用：

```java
private DspBidResult callDsp(DspConfig dsp, DspBidRequest req) {
    if (!rateLimiter.tryAcquire(dsp.getDspId(), dsp.getQpsLimit())) {
        return DspBidResult.rateLimited(dsp.getDspId());  // 跳过
    }
    // ... 正常调用 DSP ...
}
```

bid_log 的 status 新增一个 `4=限流`，与 `3=异常`、`2=无出价` 区分开，方便后台日志分析。

| status | 含义 |
|--------|------|
| 0 | 超时 |
| 1 | 有效出价 |
| 2 | 无出价 |
| 3 | 异常 |
| 4 | 限流 |

---
