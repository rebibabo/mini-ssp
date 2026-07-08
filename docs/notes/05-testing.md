# 测试体系

> 本文件为分类整理后的主题笔记。

## 1. 集成测试

### 关键注解

| 注解 | 作用 |
|------|------|
| `@SpringBootTest` | 启动完整 Spring 容器 |
| `@AutoConfigureMockMvc` | 自动配置 MockMvc，可以发模拟 HTTP 请求 |
| `@Test` | 标记这是一个测试方法 |

### MockMvc 发请求

```java
mockMvc.perform(
    post("/api/v1/admin/slots")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(dto))
)
.andExpect(status().isOk())
.andExpect(jsonPath("$.code").value(0))
.andExpect(jsonPath("$.data.name").value("测试广告位"));
```

### 运行命令

```bash
./mvnw test                                              # 运行所有测试
./mvnw test -Dtest=SlotAdminControllerTest               # 运行单个测试类
./mvnw test -Dtest=SlotAdminControllerTest#testCreate    # 运行单个测试方法
```

---

## 2. 两种测试：单元测试 vs 集成测试

| | 单元测试（RateLimiterTest） | 集成测试（BidControllerTest） |
|---|---|---|
| 注解 | `@ExtendWith(MockitoExtension.class)` | `@SpringBootTest` + `@AutoConfigureMockMvc` |
| 启动容器 | 否，普通 Java 对象 | 是，完整 Spring 容器 |
| 真实环境 | 全 mock，不需要 DB/Redis | 需要真实 MySQL + Redis |
| 范围 | 单个类的单个方法 | 整条链路 HTTP→Controller→Service→DB |
| 速度 | 快（毫秒） | 慢（秒级） |
| 发起方式 | 直接调方法 `rateLimiter.tryAcquire(...)` | `mockMvc.perform(post(...))` |

**怎么选：** 测单个类的逻辑分支（算法、边界）→ 单元测试；测多组件拼起来能否跑通 → 集成测试。
（测试金字塔：底层大量单元测试，上层少量集成测试。）

**为什么 RateLimiter 用单元测试：** 想精确测"第11次必拒""首次设TTL"等分支。连真实 Redis 难构造场景（要 1 秒内打 11 次）、结果不稳定。mock 掉后可直接"假装 INCR 返回 11"，瞬间稳定验证。

---

## 3. Mockito

测试三件套（都在 `spring-boot-starter-test` 里）：

| 库 | 作用 | 例子 |
|----|------|------|
| JUnit 5 | 测试框架骨架 | `@Test`、`@ExtendWith` |
| Mockito | 造假对象、定行为、验调用 | `@Mock`、`when`、`verify` |
| AssertJ | 流式断言 | `assertThat(x).isTrue()` |

### 核心注解与方法

```java
@ExtendWith(MockitoExtension.class)   // 启用 Mockito
@Mock        RedisTemplate redis;     // 造一个假对象
@InjectMocks RateLimiter limiter;     // 把 @Mock 注入被测对象

when(x.foo()).thenReturn(11L);        // 规定假对象被调用时返回什么
verify(redis).expire(...);            // 验证某方法被调用过
verify(redis, never()).opsForValue(); // 验证从未被调用
```

### 两个坑

1. **两层调用要 mock 两层**：`redis.opsForValue().increment()`，中间 `opsForValue()` 返回的对象也得是 mock，否则 NPE。
2. **严格模式不允许多余 stub**：用不到的 `when` 会报错。把 stub 移到真正需要的测试里（或 `lenient()`）。

---

## 4. 测试有特殊依赖的类（BidServiceTest）

### 不用 @InjectMocks 的场景

`BidService` 依赖一个 `Executor bidExecutor`。如果让 Mockito mock 它，假 executor 不会真正执行 Runnable，异步竞价任务跑不起来。

解决：**手动 new，传入同步执行器 `Runnable::run`**（任务在当前线程立即跑完，结果确定可断言）：

```java
bidService = new BidService(slotCacheService, trackService, bidLogMapper,
        mockDspHandler, rateLimiter, Runnable::run);
```

### @Value 字段单测不会注入

`globalTimeoutMs` 是 `@Value` 从 yml 读的，单测无容器，默认 0。手动塞值：

```java
ReflectionTestUtils.setField(bidService, "globalTimeoutMs", 200L);
```

### 几个 Mockito 易错点

| 点 | 说明 |
|----|------|
| `eq()` 与 `any()` 不能混裸值 | 用了 matcher 后所有参数都得是 matcher：`bid(eq("dsp-A"), any())`，不能 `bid("dsp-A", any())` |
| BigDecimal 断言 | 用 `isEqualByComparingTo("3.5")`，`equals` 会因精度（3.5 vs 3.50）判不等 |
| `verify(x, never())` | 验证某方法从未被调用，如"无 DSP 时不发起竞价" |

---
