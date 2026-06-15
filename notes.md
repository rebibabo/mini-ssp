# 学习笔记

## 0. 进度记录

| 状态 | 阶段 | 内容 |
|------|------|------|
| ✅ | Phase 1-5 | 核心骨架 / 竞价链路 / 缓存限流 / 埋点 + AOP 等(已完成) |
| ✅ | Phase 6 | 真实 Mock DSP 服务 + WebClient(本次完成,见下方 2026-06-14) |
| 🔶 | Phase 7 | 单元测试(部分完成) |
| 🔶 | Phase 10 | Metrics：埋点 + Prometheus/Grafana 已跑通，大盘面板还在加(见第 35 节) |

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

## 1. 分层架构

### 三层架构职责

| 层 | 作用 | 不做什么 |
|----|------|---------|
| Controller | 接收 HTTP 请求，调用 Service，返回 ApiResponse | 不写业务逻辑 |
| Service | 处理业务逻辑，调用 Mapper | 不直接处理 HTTP |
| Mapper | 只负责数据库读写 | 不写业务逻辑 |

### 数据流向

```
前端
 │  DTO（接收请求）
 ▼
Controller
 │
Service（业务逻辑、转换）
 │  Entity（读写数据库）
 ▼
数据库
 │  Entity（返回）
 ▼
Service（转换成 VO）
 │  VO（返回）
 ▼
Controller
 │  ApiResponse<VO>（包装）
 ▼
前端
```

### DTO / VO / Entity 区别

| 类型 | 全称 | 方向 | 说明 |
|------|------|------|------|
| DTO | Data Transfer Object | 前端 → 后端 | 接收请求参数 |
| VO | View Object | 后端 → 前端 | 返回响应数据 |
| Entity | - | 后端 ↔ 数据库 | 对应数据库表结构 |

---

## 2. MyBatis-Plus 注解

| 注解 | 作用 |
|------|------|
| `@TableName("表名")` | 指定对应的数据库表，类名和表名一致时可省略 |
| `@TableId(type = IdType.AUTO)` | 标记主键，AUTO 表示数据库自增 |

### IdType 类型

| 类型 | 说明 | 适用场景 |
|------|------|---------|
| `AUTO` | 数据库自增 | 单库单表 |
| `ASSIGN_ID` | 雪花算法生成 | 分布式系统 |
| `INPUT` | 手动赋值 | 业务自定义 ID |

### BaseMapper 内置方法（不需要写 XML）

```java
mapper.insert(entity)
mapper.deleteById(id)
mapper.updateById(entity)
mapper.selectById(id)
mapper.selectList(wrapper)
mapper.selectPage(page, wrapper)
```

复杂 SQL（如多表 join）才需要写 XML。

---

## 3. 数据库类型与 Java 类型对照

| 数据库类型 | Java 类型 | 注意点 |
|-----------|----------|--------|
| `TINYINT` | `Integer` | |
| `INT` | `Integer` | |
| `BIGINT` | `Long` | 不能用 int，会溢出 |
| `VARCHAR` | `String` | |
| `DECIMAL` | `BigDecimal` | 价格/金额必须用，double 有精度丢失 |
| `DATETIME` | `LocalDateTime` | 新项目统一用 LocalDateTime |

MyBatis-Plus 配置 `map-underscore-to-camel-case: true` 后，数据库下划线命名（`slot_id`）自动映射到 Java 驼峰命名（`slotId`）。

---

## 4. 参数校验注解

| 注解 | 用途 | 说明 |
|------|------|------|
| `@NotBlank` | String 不能为空 | null、""、"   " 都报错 |
| `@NotNull` | 对象不能为 null | 只检查 null，不检查内部字段 |
| `@Valid` | 级联校验嵌套对象 | 不加则嵌套对象内部的注解不生效 |

Controller 方法参数需要加 `@Valid` 才触发校验：
```java
public ApiResponse<BidResponse> bid(@RequestBody @Valid BidRequest request)
```

---

## 5. 枚举设计

### 什么时候用枚举

- 取值固定、有限（如广告位类型、竞价状态）
- 每个值有明确业务含义
- 代码里会频繁判断这个值

### 标准写法

```java
public enum AdSlotType {
    BANNER(1, "横幅"),
    INTERSTITIAL(2, "插屏");

    private final int code;
    private final String desc;

    // fromCode() 根据数字反查枚举
    public static AdSlotType fromCode(int code) { ... }
}
```

### Entity 为什么不直接用枚举类型

数据库存的是数字，读出来是数字，需要转换。转换放在 **Service 层**：
```java
AdSlotType type = AdSlotType.fromCode(slot.getType());
```

---

## 6. 竞价流程

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

## 7. 异常处理

### 两个类的职责

| 类 | 作用 |
|----|------|
| `BizException` | 自定义业务异常，主动抛出，表示正常的业务错误（如"广告位不存在"） |
| `GlobalExceptionHandler` | 捕获所有异常，统一转成 `ApiResponse` 返回 |

### 三类异常处理

| 异常 | 触发时机 | 返回码 |
|------|---------|--------|
| `BizException` | Service 主动 throw | 自定义 |
| `MethodArgumentNotValidException` | `@Valid` 校验失败时 Spring 自动抛出 | 400 |
| `Exception` | 兜底，所有未预期异常 | 500 |

### 什么时候 throw，什么时候返回 null

| 情况 | 处理方式 |
|------|---------|
| 广告位不存在（客户端传错参数） | throw BizException |
| 没有关联 DSP（正常业务情况） | return null（no fill） |
| DSP 超时 | 记录 warn 日志，继续用已完成结果 |
| DSP 调用异常 | 记录 error 日志，丢弃该 DSP 结果 |

### BizException 为什么继承 RuntimeException

继承 `RuntimeException` 不需要强制 try-catch，直接 `throw` 即可。继承 `Exception` 的话调用方必须处理，代码很繁琐。

---

## 8. MyBatis-Plus 查询条件

### LambdaQueryWrapper

用来拼 `WHERE` 条件，避免手写字段名字符串出错：

```java
new LambdaQueryWrapper<DspConfig>()
    .eq(DspConfig::getDspId, dto.getDspId())
// 等价于 WHERE dsp_id = ?
```

常用方法：

| 方法 | SQL | 说明 |
|------|-----|------|
| `.eq(字段, 值)` | `WHERE 字段 = ?` | 等于 |
| `.in(字段, 集合)` | `WHERE 字段 IN (...)` | 属于集合中的任意一个 |
| `.gt(字段, 值)` | `WHERE 字段 > ?` | 大于 |
| `.lt(字段, 值)` | `WHERE 字段 < ?` | 小于 |
| `.like(字段, 值)` | `WHERE 字段 LIKE ?` | 模糊查询 |
| `.orderByDesc(字段)` | `ORDER BY 字段 DESC` | 降序排列 |

### selectOne vs selectList

- `selectOne`：业务上唯一的查询用（如按 slotId 查），结果不唯一会报错
- `selectList`：查多条记录用

---

## 9. 依赖注入

### @Autowired vs @RequiredArgsConstructor

| 方式 | 写法 | 推荐 |
|------|------|------|
| 字段注入 | `@Autowired private XxxMapper mapper` | 不推荐 |
| 构造方法注入 | `final` 字段 + `@RequiredArgsConstructor` | 推荐 |

构造方法注入更好的原因：
- 字段是 `final`，不可变，更安全
- 方便单元测试，可以直接 `new Service(mockMapper)` 传入 mock

### 原理

`@RequiredArgsConstructor` 为所有 `final` 字段生成构造方法，Spring 启动时自动把对应的 Bean 传进去（依赖注入 DI）。

---

## 10. Controller 注解

| 注解 | 作用 |
|------|------|
| `@RestController` | 标记这是 Controller，返回值自动转 JSON |
| `@RequestMapping("/路径")` | 该 Controller 所有接口的路径前缀 |
| `@PostMapping` | 处理 HTTP POST 请求 |
| `@GetMapping` | 处理 HTTP GET 请求 |
| `@PutMapping` | 处理 HTTP PUT 请求 |
| `@DeleteMapping` | 处理 HTTP DELETE 请求 |
| `@PathVariable` | 从路径里取参数，如 `/{id}` → `@PathVariable Long id` |
| `@RequestParam` | 从 URL 参数里取值，如 `?page=1` → `@RequestParam int page` |
| `@RequestBody` | 从请求体里取 JSON，配合 `@Valid` 触发校验 |

---

## 11. 集成测试

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

## 12. Spring 配置类（@Configuration + @Bean）

### 作用链路

```
application.yml
      ↓ @Value
@Configuration 类（如 ThreadPoolConfig）
      ↓ @Bean("bidExecutor")
   Spring 容器（统一管理对象）
      ↓ @Autowired
   使用方（如 BidService）
```

### 关键注解

| 注解 | 作用 |
|------|------|
| `@Configuration` | 标记这是配置类，Spring 会扫描里面的 @Bean |
| `@Bean("名字")` | 把方法返回的对象交给 Spring 管理，起个名字 |
| `@Value("${key:默认值}")` | 从 application.yml 读取配置，`:` 后面是默认值 |
| `@Qualifier("名字")` | 注入时指定用哪个 Bean（同类型有多个时必须加） |

### 为什么不直接 new？

线程池是"重"资源，全局只需要一个，Spring 管理可以保证单例复用。自己 new 每次都是新对象，无法控制并发资源。

---

## 13. 线程池

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

## 14. CompletableFuture 异步编程

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

## 15. Stream API

### toList() vs collect(Collectors.toList())

| | `toList()` | `collect(Collectors.toList())` |
|---|---|---|
| Java 版本 | 16+ | 全版本 |
| 返回结果 | 不可修改的 List | 可修改的 List |
| 推荐 | 只读场景用这个 | 需要增删时用这个 |

### Optional 和 orElse

Stream 的 `max()`、`min()`、`findFirst()` 等方法返回 `Optional<T>`，因为集合可能为空找不到结果。

```java
// Optional 表示"可能有，可能没有"
Optional<DspBidResult> winner = results.stream().max(...);

// orElse：有就用，没有就用默认值
DspBidResult winner = results.stream().max(...).orElse(null);
```

怎么判断一个方法是否返回 Optional：**IDEA 鼠标悬停看返回类型**，看到 `Optional<T>` 就要处理空值。

---

## 16. 内部类

只在一个类里用的数据结构，不需要单独建文件，直接写成内部类：

```java
public class BidService {
    // DspBidResult 只有 BidService 用，写成内部类
    @Data
    private static class DspBidResult {
        private String dspId;
        private DspBidResponse response;
        private int responseTimeMs;
        private boolean error;
    }
}
```

**不要过度抽象**：现在只有一个地方用，就放内部类。以后多个地方都用了，再提出去建独立文件。

---

## 17. 踩坑记录

### 坑 1：Spring Boot 版本太新，生态不兼容

**现象：** `Property 'sqlSessionFactory' or 'sqlSessionTemplate' are required`

**原因：** Spring Boot 4.x 是新版本，MyBatis-Plus 等第三方库还未适配。

**解决：** 降到 Spring Boot 3.x（当前 LTS 版本，生态最成熟）

**经验：** 新项目不要用最新版本，用次新的稳定版，等生态跟上再升级。

---

### 坑 2：Mapper 没被 Spring 扫描到

**现象：** `No qualifying bean of type 'XxxMapper' available`

**原因：** `@Mapper` 只是标记，Spring Boot 不一定主动扫描 MyBatis 注解。

**解决：** 在启动类加 `@MapperScan`：
```java
@SpringBootApplication
@MapperScan("com.example.ssp.mapper")
public class MiniSspApplication { ... }
```

---

### 坑 3：MySQL 连接字符串 utf8mb4 不支持

**现象：** `Unsupported character encoding 'utf8mb4'`

**解决：** 把 `characterEncoding=utf8mb4` 改成 `characterEncoding=UTF-8`

---

### 坑 4：测试数据重复导致失败

**现象：** 测试第二次跑时返回 400，提示"ID 已存在"

**解决：** 用时间戳生成唯一 ID：
```java
String slotId = "slot-test-" + System.currentTimeMillis();
```

---

## 18. Redis 缓存

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

## 19. AOP 切面日志

### 核心注解

| 注解 | 作用 |
|------|------|
| `@Aspect` | 标记这是一个切面类 |
| `@Pointcut("execution(...)")` | 定义"切在哪里"（哪些方法），起个名字复用 |
| `@Around` | 环绕通知，能在目标方法**前后**都插入逻辑，还能拿到返回值/异常 |
| `ProceedingJoinPoint` | 代表"被拦截的方法"，`joinPoint.proceed()` = 真正执行原方法 |

### execution 表达式

```java
@Pointcut("execution(* com.example.ssp.controller..*.*(..))")
```

- `*` 第一个：返回值任意类型
- `com.example.ssp.controller..*`：controller 包及其子包下所有类
- `.*(..)`：任意方法名，任意参数

### @Around 的固定写法

```java
@Around("controllerMethods()")
public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
    long start = System.currentTimeMillis();
    try {
        Object result = joinPoint.proceed();  // 执行原方法，拿到返回值
        // ... 记录耗时、返回值
        return result;  // 必须返回，否则调用方收不到结果
    } catch (Exception e) {
        // ... 记录异常
        throw e;  // 必须重新抛出，否则异常被吞掉
    }
}
```

### ThreadLocal 与 RequestContextHolder

**问题：** `LogAspect` 里调用 `RequestContextHolder.getRequestAttributes()` 没传任何参数，怎么知道是"当前这个请求"？

**答案：靠线程。**

```
HTTP 请求进来
    ↓
Tomcat 分配一个工作线程处理（如 nio-8080-exec-1）
    ↓
DispatcherServlet 在最开始把当前请求存进 ThreadLocal
    ↓
... Controller、AOP 切面、Service 全部在同一个线程里执行 ...
    ↓
LogAspect 调用 getRequestAttributes() → 从 ThreadLocal 取出当前线程绑定的请求
```

`ThreadLocal` 的特点：**同一个静态变量，不同线程访问到的是各自独立的副本**。可以类比成"每个线程口袋里的一张纸条"——DispatcherServlet 往当前线程的口袋塞纸条，同线程后续代码伸手就能掏到。

**注意：不是"线程间共享"，而是"线程内全局、线程间隔离"**

```
线程 A 的 ThreadLocal 副本: { request: ReqA }
线程 B 的 ThreadLocal 副本: { request: ReqB }
```

线程 A 调用栈上的任何方法（Controller → AOP → Service → 工具类...）调用 `getRequestAttributes()`，取到的都是 `ReqA`；线程 B 同时调用，取到的是 `ReqB`，两者互不影响。

所以更准确的理解是：**ThreadLocal 是"线程的全局变量"**——同一线程的整条调用链上，任何方法不用传参就能取到同一份数据；换一个线程，这份数据就"消失"了（变成那个线程自己的空副本）。

完整生命周期：

```
Tomcat 工作线程 nio-8080-exec-1 接到请求
    ↓
DispatcherServlet.doService() 最开始执行 setRequestAttributes(请求信息)
    ↓
这个线程接下来执行的所有代码（Controller.bid() → LogAspect → BidService → ...）
都跑在 nio-8080-exec-1 这同一个线程上，任何地方 getRequestAttributes() 都拿到同一份
    ↓
请求处理完，DispatcherServlet 在 finally 里执行 resetRequestAttributes() 清空
```

**为什么要判 null：** `BidService.asyncSaveBidLogs()` 用 `CompletableFuture.runAsync(..., bidExecutor)` 另开了一个线程（如 `bidExecutor-1`），这个新线程没有经过 DispatcherServlet，它的 ThreadLocal 副本里没有请求信息，调用 `getRequestAttributes()` 会返回 `null`。`LogAspect` 只拦截 controller 包方法，理论上不会发生，判空是防御性写法。

### 切面记录什么

```
[Req]  uri  方法签名  args=[参数...]
   ↓ joinPoint.proceed() 执行真正的业务逻辑
[Resp] uri  方法签名  cost=耗时ms  result=返回值
```

异常时记录 `[Resp] ... error=异常信息` 并重新抛出，交给 `GlobalExceptionHandler` 处理。

---

## 20. DSP QPS 限流

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

## 21. bid_log 查询接口

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

## 22. 两种测试：单元测试 vs 集成测试

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

## 23. Mockito

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

## 24. 测试有特殊依赖的类（BidServiceTest）

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

## 25. Lambda 与函数式接口

### 函数式接口

只有一个抽象方法的接口 = 函数式接口，可以用 lambda / 方法引用实现，不用写完整的类。

```java
// 传统匿名类
Executor e = new Executor() {
    public void execute(Runnable command) { command.run(); }
};
// lambda（等价）
Executor e = (command) -> command.run();
// 方法引用（等价，最简）
Executor e = Runnable::run;
```

**核心：lambda 不认接口的名字，只认"形状"（方法签名：入参类型 + 返回值）。** 形状对得上就能塞进去。所以同一段 lambda 赋给谁、谁形状匹配，它就当谁。

### 常用函数式接口（看"有没有入参 / 有没有返回值"）

|              | 有返回值 | 无返回值 |
|--------------|---------|---------|
| **有入参**   | `Function<T,R>` / `Predicate<T>`(返回boolean) | `Consumer<T>` |
| **无入参**   | `Supplier<T>` | `Runnable` |

| 接口 | 抽象方法 | 一句话 | 例子 |
|------|---------|--------|------|
| `Supplier<T>` | `T get()` | 只产出 | `() -> "hi"` |
| `Consumer<T>` | `void accept(T)` | 只消费 | `x -> println(x)` |
| `Function<T,R>` | `R apply(T)` | 转换 | `x -> x.length()` |
| `Predicate<T>` | `boolean test(T)` | 判断 | `x -> x > 0` |
| `Runnable` | `void run()` | 纯执行 | `() -> doSth()` |

变体：`BiFunction`/`BiConsumer`（两个入参）、`UnaryOperator`/`BinaryOperator`（入参与返回同类型）。

### CompletableFuture 里的应用

```java
// supplyAsync：第一个参数是 Supplier（有返回值的任务），第二个是 executor
CompletableFuture.supplyAsync(() -> callDsp(dsp, req), bidExecutor);  // callDsp 返回 DspBidResult

// runAsync：第一个参数是 Runnable（无返回值的任务）
CompletableFuture.runAsync(() -> bidLogMapper.insert(log), bidExecutor);
```

**第一个参数 = 跑什么（任务 lambda），第二个参数 = 谁来跑（executor）。** 任务不变、只换 executor，正是测试能用 `Runnable::run` 替换线程池的原因。

### 四种方法引用

| 种类 | 写法 | 展开 | 例子 |
|------|------|------|------|
| ① 静态方法 | `类名::静态方法` | `(x) -> 类名.方法(x)` | `Integer::parseInt` |
| ② 特定对象实例方法 | `对象::方法` | `(x) -> 对象.方法(x)` | `System.out::println` |
| ③ 任意对象实例方法 | `类名::方法` | `(obj,x) -> obj.方法(x)` | `Runnable::run`、`String::length` |
| ④ 构造方法 | `类名::new` | `(x) -> new 类名(x)` | `ArrayList::new` |

### 重点：③ 为什么"凭空多一个参数"

`类名::实例方法`（第③种）规则是：**把第一个参数当成"调用该方法的对象"**。

```java
Runnable::run   →   (command) -> command.run()
//                    ↑ command 就是"要调用 run() 的那个 Runnable"
String::length  →   (s) -> s.length()
```

`run()` 本身没参数，但"谁来调 run"需要一个对象，这个对象就补位成 lambda 的第一个参数。
对比 ②（`System.out::println`）：对象是现成固定的，参数直接传方法，不会多参数。

### 快速判断 ::左边是类还是对象

看 `::` 左边首字母（命中 Java 命名规范）：
- **小写 → 对象**（第②种，对象固定）
- **大写 → 类**，再细分：右边是 `new`→④构造；静态方法→①；实例方法→③（第一个参数当调用者）

经验法则 90% 够用，剩下靠 IDE 悬停看展开。

---

## 26. 并发基础：ThreadLocalRandom / Thread.sleep / InterruptedException

（写 Mock DSP 模拟"随机出价 + 随机延迟"时用到。）

### 为什么用随机数

Mock DSP 要模拟真实 DSP 的不确定性：出价多少、延迟多久、要不要出价，真实场景每次都不一样。如果返回固定值，SSP 的"选最高价""超时容错"就测不出来。用随机数制造变化。

### ThreadLocalRandom vs Random

| | `java.util.Random` | `ThreadLocalRandom` |
|---|---|---|
| 种子 | 多线程**共享一个**种子 | **每个线程一份**独立种子 |
| 并发 | 内部 CAS 自旋争抢，慢 | 互不干扰，快 |
| 用法 | `new Random()` | `ThreadLocalRandom.current()`（构造私有，不能 new） |

原理就是第 19 节的 ThreadLocal：**线程内全局、线程间隔离**。Web 服务（Tomcat 多线程并发处理请求）优先用 `ThreadLocalRandom`。

```java
ThreadLocalRandom rnd = ThreadLocalRandom.current();  // 当前线程专属的那一份
rnd.nextDouble();    // [0.0, 1.0) 随机小数
rnd.nextInt(1, 6);   // [1, 6) 随机整数
```

### .current() 的含义

静态方法，意思是"**拿到当前线程专属的随机数实例**"。同一线程多次调拿到同一个，不同线程拿到各自的。

> 坑：不要把 `current()` 的返回值存成共享字段给别的线程用。每次就地 `ThreadLocalRandom.current()` 调。

### Thread.sleep 为什么能睡"当前线程"

`Thread.sleep` 是 **static 方法**，语义被定死为"**让正在执行这行代码的那个线程暂停**"。不用指定哪个线程——**谁执行到这行，睡的就是谁**（和 `RequestContextHolder` 取"当前请求"同理：代码跑在某个线程上，"当前线程"就是它）。

```
线程 nio-8081-exec-3 执行到 sleep(100)
   → 就是它被挂起 100ms，腾出 CPU
   → 100ms 后唤醒继续执行
```

Mock DSP 用它模拟网络延迟：睡多久，SSP 那边就等多久。

### InterruptedException 必须处理 + 为什么重新 interrupt()

```java
try {
    Thread.sleep(ms);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();  // 把被清掉的中断标志补回去
}
```

- **为什么必须 try-catch：** 线程睡觉时，别的线程可调 `thread.interrupt()` 打断它（如"服务要关了，别睡了"），此时 `sleep` 抛受检异常 `InterruptedException`，不 catch 编译不过。
- **为什么 catch 里要再 `interrupt()`：** `sleep` 抛异常时，JVM 会**自动把中断标志清掉（置 false）**。但"有人想中断我"这个信号很重要，不该被吞。重新 `interrupt()` 把标志设回 true，让上层代码 `isInterrupted()` 仍能感知，从而正确收尾退出。

**口诀：捕获到 `InterruptedException`，要么继续往上抛，要么重新 `interrupt()` 恢复标志，别静默吞掉。**

---

## 27. Phase 6：真实 Mock DSP（WebClient + 策略模式 + 多实例）

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

## 28. Bash 联调自动化脚本（scripts/test-modeB.sh）

把手动联调固化成脚本：**起 4 个服务 → 等就绪 → 发 N 个请求 → 等异步落库 → 汇总+导出 → 自动停**。

### 为什么用 bash 而不是 Java 测试

跨进程端到端联调要启动 4 个独立 JVM、轮询端口、curl —— bash 干这个最直接，和手动命令一一对应。Java `@SpringBootTest` 适合单进程内集成测试（已有 `BidControllerTest`），让它 spawn 外部进程反而绕。

### 防御性开头：set -euo pipefail

| 开关 | 作用 |
|------|------|
| `-e` | 命令返回非 0 立刻退出（避免某步失败还往下跑） |
| `-u` | 用到未定义变量就报错 |
| `-o pipefail` | 管道里任意一段失败，整条管道算失败 |

### trap：保证退出时清理

```bash
trap cleanup EXIT   # 不管正常结束/报错/Ctrl+C，都执行 cleanup
```

写"会启动后台服务"的脚本必配 `trap ... EXIT`，否则中途失败会残留进程占端口。**先把 trap 架上，再启动服务**，这样从第一个服务起任何退出都能收尾。

### 坑：spring-boot:run 会 fork 子 JVM

```
记录的 pid = maven/spring-boot:run 父进程
   └─ 默认 fork 出独立子 JVM 真正监听端口（另一个 pid）
kill 父 pid → 子 JVM 还活着占端口！
```

cleanup 日志能看到铁证：启动的是 pid 22805，占端口的却是 22636。

**解法**：cleanup 不只杀记录的 pid，**再按端口兜底清**（`lsof -ti:port | xargs kill`，温和→`kill -9`）。按端口清最稳——不管 fork 不 fork，真正监听端口的进程都被 `lsof` 找得到。把按端口清抽成 `kill_port`，开跑前的 `free_ports` 和退出时的 `cleanup` 复用。

### 等就绪：轮询，不要瞎 sleep

服务启动耗时不定，`sleep 10` 拍脑袋不靠谱。写 `wait_until_ready`：循环 curl 探活，HTTP 通了（200/500 都算起来了）才往下走，超时则报错退出。

```bash
code="$(curl -s -o /dev/null -w '%{http_code}' "$@" || true)"
[[ "${code}" == "200" || "${code}" == "500" ]] && return 0
```

DSP 探活用 `POST /bid`，SSP 用 `GET /api/v1/admin/dsps`，传不同 curl 参数即可复用同一个函数。

### 异步数据要等落库

`bid_log` 是 `CompletableFuture.runAsync` 异步写的（第 14 节）。发完请求**立刻查会漏**，要 `sleep` 一两秒等落库，**且必须在 cleanup 杀 SSP 之前**导出。

### 结果归档与两种视角

| 文件 | 来源 | 视角 |
|------|------|------|
| `requests.jsonl` / `summary.txt` | SSP 对外响应 | **结果**：成了几次、谁中标（只有赢家） |
| `bid_log.tsv` | MySQL bid_log 表 | **过程**：每个 DSP 出价/状态/耗时（一次请求多条） |

两者用 `requestId` 串起来：summary 说"req-5 谁中标"，bid_log 查"req-5 时其他 DSP 在干嘛"。

**JSONL**（一行一个 JSON 对象）比一个大数组更适合逐行追加和 `grep`。

### 纯 shell 做统计（不依赖 jq/python）

```bash
grep -c '"code":0' f.jsonl                                  # 数成功
grep -o '"winDsp":"[^"]*"' f | sed 's/.*:"//;s/"//' | sort | uniq -c   # 中标分布
```

`grep -o` 只输出匹配片段，`sort | uniq -c` 是经典"分组计数"组合。

### 几个 bash 语法点

| 写法 | 含义 |
|------|------|
| `${1:-20}` | 取第1个参数，没传用默认 20 |
| `arr=()` / `arr+=(x)` | 空数组 / 追加元素 |
| `"${arr[@]:-}"` | 展开数组，`:-` 防 `set -u` 在空数组时报错 |
| `kill -0 "$pid"` | 不真杀，只探测进程是否存在 |
| `( cd dir && exec "$@" ) > log 2>&1 &` | 子 shell 切目录跑命令；重定向在 cd 前打开，故 log 路径要用绝对路径 |
| `cmd \| tee f` | 输出同时打屏 + 写文件 |

### 脚本支持选拍卖方式（第 4 个参数）

```bash
./scripts/test-modeB.sh 20 test-results slot-test-001 second   # 二价
./scripts/test-modeB.sh                                        # 默认一价
```

### 坑：给 spring-boot:run 传多个启动参数

`-Dspring-boot.run.arguments=a,b` 的逗号**本环境没被拆成两个参数**，整串被当成第一个属性的值。结果 `ssp.dsp.mode` 变成 `http,--ssp.bid.auction-type=second`，既非 `http` 也非 `mock` → 两个 `DspCaller` 实现都没注册 → SSP 启动报 `No qualifying bean of type 'DspCaller'`。

**解法**：用 `jvmArguments` 以 `-D` 系统属性传，空格分隔，Spring 同样从 Environment 读到：

```bash
./mvnw spring-boot:run \
  "-Dspring-boot.run.jvmArguments=-Dssp.dsp.mode=http -Dssp.bid.auction-type=second"
```

教训：单参数用 `run.arguments=--xxx` 没问题；**多参数改用 `jvmArguments` 的 `-Dkey=val` 空格分隔最稳**。

---

## 29. Swagger / OpenAPI 接口文档（springdoc）

### Swagger ≠ OpenAPI

| 词 | 是什么 |
|----|--------|
| OpenAPI | 一种**规范**：用一份 JSON 描述所有接口（路径/参数/请求体/响应）——机器可读的说明书 |
| Swagger UI | 一个**网页**：读那份 JSON，渲染成可视化、能在线点测的文档页 |

本质：程序自动生成 OpenAPI JSON → Swagger UI 渲染成网页。

### 版本兼容（关键坑）

| 库 | 适用 | 说明 |
|----|------|------|
| springdoc-openapi **2.x** | Spring Boot **3.x** ✅ | Jakarta 命名空间，本项目用 2.8.6 |
| ~~springfox~~ | 只到 Boot 2.x | 已停维护，**Boot 3 上跑不起来**，别用 |

呼应坑1的教训：**Boot 3 → 必须 springdoc 2.x**。一个依赖即可：
`org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.6`

### 零代码就有文档

加依赖启动后，springdoc 扫描所有 `@RestController` + 映射注解，自动生成文档，暴露两个地址：

| 地址 | 用途 |
|------|------|
| `/v3/api-docs` | 机器读的 OpenAPI JSON |
| `/swagger-ui.html` | 人看的网页（302 跳到 `/swagger-ui/index.html`） |

### 注解都是“文档元数据”，不影响业务逻辑

| 注解 | 加在哪 | 作用 |
|------|--------|------|
| `@Tag(name, description)` | Controller 类 | 接口分组名（UI 左侧分组） |
| `@Operation(summary, description)` | 方法 | 接口标题 + 详细说明 |
| `@Schema(description, example, requiredMode)` | DTO 字段/类 | 字段含义 + 示例值（Try it out 会用 example 预填） |
| `@Parameter(description)` | 方法参数 | 单个 query/path 参数说明 |

不加也能跑、也有文档，只是没中文说明。`@Schema` 的 `example` 让在线调试能一键预填请求体。

### 全局标题：定义一个 OpenAPI Bean

```java
@Bean
public OpenAPI sspOpenAPI() {
    return new OpenAPI().info(new Info().title("Mini-SSP API").version("v1").description("..."));
}
```
springdoc 优先用容器里的 `OpenAPI` Bean，没有才用默认 "OpenAPI definition"。

### 怎么用（启动 + 访问）

Swagger 内嵌在 SSP 这一个 Spring Boot 应用里，不是独立服务，**无需任何额外启动步骤**：

```bash
./mvnw spring-boot:run                      # 1. 启动 SSP(根目录)
# 2. 浏览器打开 http://localhost:8080/swagger-ui.html
```

| 问题 | 答案 |
|------|------|
| 要单独起 swagger 服务吗 | 不用，springdoc 是库，跟 SSP 同进程，SSP 一起来文档就在 |
| 要起 3 个 mock DSP 吗 | 看文档/测 CRUD 不用；只有真的点 `/api/v1/bid` 的 Execute 且 SSP 跑在 `--ssp.dsp.mode=http` 时才需要 DSP 在线 |
| 要连 MySQL/Redis 吗 | SSP 启动本身需要(它要连 DB/Redis 才起得来)，与 swagger 无关 |

两个地址：`/swagger-ui.html`（可视化、能在线 Execute）、`/v3/api-docs`（原始 OpenAPI JSON，可导入 Postman）。

### 本项目注意点

- 统一响应 `ApiResponse<T>` 会如实展示外层 `{code,message,data}`，正好让调用方看到真实格式。
- UI 整体样式是自带的，改配色/布局要塞自定义 CSS，成本高、不值得；我们只控制“内容”（标题/分组/字段说明）。
- 若以后加 Spring Security，要放行 `/swagger-ui/**`、`/v3/api-docs/**`（现在没 Security，不用管）。

---

## 30. 二价拍卖与计价策略（策略模式 2.0）

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

## 31. JVM 系统属性 -D

`-D` 是 **java 命令的标准选项，作用是定义一个“系统属性”(system property)**。

### 语法

```bash
java -Dkey=value -jar app.jar       # 定义一个：key=value
java -Da=1 -Db=2 -jar app.jar       # 多个用空格分隔，每个一个 -D
```

- `-D` 后**紧贴** key，不能有空格（`-Dname=Tom` ✓ / `-D name=Tom` ✗）。
- 值省略默认空串。

### 系统属性是什么

JVM 启动时设置的一组**全局键值对**（本质是 `java.util.Properties`），运行期任何代码可读。Java 内置一堆（`java.version`/`os.name`/`user.dir`…），`-D` 就是往里塞自己的。

```java
String mode = System.getProperty("ssp.dsp.mode");      // "http"
String x = System.getProperty("not.set", "默认值");      // 没设返回默认值
```

### 和 Spring 的关系（关键）

Spring 把多个配置来源汇总进统一的 `Environment`，**JVM 系统属性是其中之一**。优先级（高→低）：

```
命令行参数(--xxx) > JVM系统属性(-Dxxx) > 环境变量 > application.yml > 默认值
```

所以 `-Dssp.dsp.mode=http` → 进系统属性 → 进 Environment → `@Value`/`@ConditionalOnProperty` 读得到，**且优先级高于 yml**（等于临时覆盖配置文件）。这就是用它切 mock/http、一价/二价的原理。

### 三种传配置方式对比

| 方式 | 写法 | 谁解析 |
|------|------|--------|
| JVM 系统属性 | `-Dssp.dsp.mode=http` | JVM，存进系统属性 |
| 程序参数 | `--ssp.dsp.mode=http`（双横杠） | Spring Boot 解析 `--` 开头的 |
| 环境变量 | `SSP_DSP_MODE=http`（大写+下划线） | 操作系统 |

三种 Spring 都能读进 Environment，效果一样。`-D` 最通用（任何 Java 程序都认）。

### 易混点

| 写法 | 是什么 |
|------|--------|
| `-Dkey=val` | JVM 系统属性 |
| `-Xmx512m` / `-jar` / `-cp` | JVM 其他选项（各有专门含义，不是 -D） |
| `--key=val` | 传给 `main(String[] args)` 的程序参数 |

> 呼应第 28 节的坑：给 fork 出的应用 JVM 传 `-D` 要包在 `-Dspring-boot.run.jvmArguments=...` 这个“信封”里——外层 `-D` 给 Maven JVM，内层 `-D` 给应用子 JVM，同一个 `-D` 语法作用在两个 JVM 上。

---

## 32. Kafka 异步写 bid_log（消息队列解耦）

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

## 33. 用户频次控制（Frequency Cap）

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

## 34. bid_log 落库策略开关（direct / kafka）+ 写入压测

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

## 35. Metrics：Micrometer 埋点（Phase 10 第一步）

### 整体链路

```
代码里打点 (Micrometer)  →  /actuator/prometheus 端点  →  Prometheus(拉取+存储)  →  Grafana(画图)
```

- **Micrometer** 是 metrics 的"门面"（类比 SLF4J）：业务代码只调用 Micrometer 的 API，底层后端(Prometheus/Datadog/...)可换不改代码。
- **MeterRegistry**：Spring Boot 自动注册的"总注册表"，所有指标汇总到这里，由 Actuator 暴露成 Prometheus 文本格式。
- **Prometheus 是拉模式**：应用暴露快照端点，Prometheus 按间隔主动来抓，每次抓到的值带时间戳存成时间序列。Counter 是"累加值"，画图时用 `rate()` 算每秒增长率才是 QPS。
- **Grafana** 不存数据，连 Prometheus 当数据源，用 PromQL 查询画图拼 Dashboard。

### 四种指标类型

| 类型 | 含义 | 本项目例子 |
|---|---|---|
| Counter | 只增不减 | 竞价请求数、各 DSP 出价结果数 |
| Timer | 耗时分布(次数+总耗时+分位数) | 竞价总耗时、单个 DSP 调用耗时 |
| Gauge | 瞬时值，可增可减 | (未用)线程池队列长度等 |
| DistributionSummary | 数值分布(非时间) | (未用)中标价格分布 |

### Tag(标签)：让一个指标能分组

`meterRegistry.counter("ssp_bid_requests_total", "result", "fill")` 中：
- `"ssp_bid_requests_total"` 是指标名
- `"result"` 是 tag **key**（类似数据库的列名/分组维度）
- `"fill"` 是 tag **value**（这一维度下的具体取值）

`result=fill` 和 `result=no_fill` 是同一指标名下两条**独立的时间序列**，Prometheus/Grafana 可以按 `result` 分组、算占比(如 no_fill 率 = no_fill/(fill+no_fill))。

`meterRegistry.counter(name, tags...)`/`meterRegistry.timer(name, tags...)` 内部按 `(name + tags 组合)` 做 key 查找/创建——**相同组合复用同一个 Counter/Timer 实例**（这也是测试里能直接读到业务代码 increment 过的值的原因），不同组合各自独立计数。

### 落地步骤 1：依赖 + 端点

- `pom.xml` 加 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`
- `application.yml` 加：
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,prometheus
  ```
- 验证：`curl localhost:8080/actuator/prometheus` 能看到 JVM/HikariCP/磁盘等框架自带指标（零代码）。

### 落地步骤 2：BidService 埋点

`BidService` 新增 `MeterRegistry meterRegistry` 依赖（第 9 个，`@RequiredArgsConstructor` 自动处理）。

**1) `ssp_bid_requests_total`（Counter，tag: result=fill/no_fill）**

`processBid()` 的 4 个出口（无关联DSP / 全部被频控 / 无赢家 / 正常返回）各打一次，`result` 取 `fill` 或 `no_fill`。

**2) `ssp_dsp_bid_total`（Counter，tag: dsp_id, result）**

`result` 取值映射自 `bid_log.status` + 是否中标：

| bid_log status | isWinner | result |
|---|---|---|
| 1(有效出价) | true | win |
| 1(有效出价) | false | lose |
| 2(无出价/低于底价) | - | no_bid |
| 3(异常) | - | error |
| 4(限流) | - | rate_limited |

在 `writeBidLogs()` 循环里，每条 bidLog 构造完后调用 `dspResultTag(status, isWinner)` 算出 `result`，打一次计数。

**timeout 单独处理**：超时的 DSP 不会进入 `writeBidLogs()` 遍历的 `allResults`（`processBid()` 步骤5会把未 `isDone()` 的 future 直接丢弃）。`futures` 和 `dsps` 是同一个 for 循环按下标一一对应生成的，所以在 `allOf.get()` 超时的 `catch` 块里，遍历下标找出 `!futures.get(i).isDone()` 的，按 `dsps.get(i).getDspId()` 打 `result=timeout`。

**3) `ssp_dsp_call_duration_seconds`（Timer，tag: dsp_id）**

`callDsp()` 里本来就在算 `elapsedMs`(写进 bid_log 用)，success/error 两个分支直接 `meterRegistry.timer(...).record(Duration.ofMillis(elapsedMs))`，复用现成的耗时数据。

**4) `ssp_bid_duration_seconds`（Timer，整次竞价耗时）**

`processBid()` 有 4 个 return，不能在某一行算耗时。用 `Timer.Sample sample = Timer.start(meterRegistry)` 包住整个方法体（`try/finally`），`finally` 里 `sample.stop(meterRegistry.timer("ssp_bid_duration_seconds"))`——不管从哪个 return 出去都会记录，4 个 return 一行不用改。

### 测试

`BidServiceTest` 用 `SimpleMeterRegistry`（内存实现，无需 mock）：
- `setUp()` 里 `meterRegistry = new SimpleMeterRegistry()`，传给 `new BidService(...)`
- 验证 fill/no_fill、win/lose/no_bid 计数：直接 `meterRegistry.counter(name, tags...).count()` 断言
- 验证 timeout：用真实线程池(`Executors.newFixedThreadPool`) + `globalTimeoutMs=100` + 让一个 DSP `Thread.sleep(300)` 制造真实超时，断言 `result=timeout` 计数为 1

### Prometheus + Grafana 容器

在 `docker/kafka/docker-compose.yml`（与现有 kafka 服务同一个 compose 文件）里新增两个服务：

```yaml
prometheus:
  image: prom/prometheus:v2.55.1
  container_name: mini-ssp-prometheus
  ports:
    - "9090:9090"          # http://localhost:9090
  volumes:
    - ../prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro

grafana:
  image: grafana/grafana:11.4.0
  container_name: mini-ssp-grafana
  ports:
    - "3000:3000"          # http://localhost:3000，admin/admin
  environment:
    GF_SECURITY_ADMIN_PASSWORD: admin
```

新建 `docker/prometheus/prometheus.yml`：

```yaml
global:
  scrape_interval: 5s

scrape_configs:
  - job_name: 'mini-ssp'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']   # Docker Desktop 访问宿主机的特殊地址
```

启动：`cd docker/kafka && docker compose up -d prometheus grafana`。验证抓取成功：`http://localhost:9090/targets` 里 `mini-ssp` job 应为 `UP`（前提是 SSP 应用在宿主机 8080 跑着）。

**Grafana 接入 Prometheus**：UI 登录后 Connections → Data sources → Add → Prometheus，URL 填 `http://prometheus:9090`（容器间用 compose service 名互相访问，不是 `localhost`）。

### 第一张大盘：竞价大盘

新建 Dashboard，加面板（查询框切到 Code 模式输入 PromQL）：

1. **fill QPS**：`sum(rate(ssp_bid_requests_total{result="fill"}[1m])) by (result)`
   - `rate()` = Counter 在时间窗口内的"每秒平均增量"，单位是次/秒，不是占比，可以 >1。
2. **No Fill 率**：`sum(rate(ssp_bid_requests_total{result="no_fill"}[1m])) / sum(rate(ssp_bid_requests_total[1m]))`
   - 这才是 0~1 的占比，面板 Unit 设成 `Percent (0.0-1.0)`。

验证用的测试请求：`slot-test-001`(有 DSP，能 fill) 和 `slot-no-dsp`(无关联 DSP，必 no_fill)，循环 `curl POST /api/v1/bid` 制造持续流量。

### 下一步(未做)

继续加面板：各DSP中标率(`ssp_dsp_bid_total` 的 win/lose/no_bid/timeout 等 tag)、DSP调用耗时P99(`ssp_dsp_call_duration_seconds`)。
