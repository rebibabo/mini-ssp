# 日志、Metrics 与 TraceId

> 本文件为分类整理后的主题笔记。

## 1. AOP 切面日志

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

## 2. Metrics：Micrometer 埋点（Phase 10 第一步）

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

启动：`cd docker && docker compose up -d`（compose 文件已整合到 `docker/docker-compose.yml`，kafka/prometheus/grafana 三合一）。
验证抓取：`http://localhost:9090/targets` 里 `mini-ssp` job 应为 `UP`（前提是 SSP 在宿主机 8080 跑着）。

### Grafana Provisioning（配置即代码）

不需要手动点 UI，启动时自动配好数据源和 Dashboard，配置文件在 `docker/grafana/provisioning/`：

```
docker/grafana/provisioning/
├── datasources/
│   └── prometheus.yml   # 数据源：uid=prometheus，url=http://prometheus:9090
└── dashboards/
    ├── dashboards.yml   # 扫描本目录下所有 .json 文件加载为 Dashboard
    └── mini-ssp.json    # 竞价大盘：4 个面板的 PromQL + 布局定义
```

`docker-compose.yml` 里挂载：`./grafana/provisioning:/etc/grafana/provisioning:ro`。

**注意**：数据源 `uid` 必须固定（填 `uid: prometheus`），否则 Grafana 每次启动随机生成，Dashboard JSON 里的引用就对不上。

### 竞价大盘：4 个面板

| 面板 | PromQL | 单位 |
|---|---|---|
| 竞价 QPS | `sum(rate(ssp_bid_requests_total[1m])) by (result)` | reqps |
| No Fill 率 | `sum(rate(...{result="no_fill"}[1m])) / sum(rate(...[1m]))` | percentunit |
| DSP 出价结果 | `sum(rate(ssp_dsp_bid_total[1m])) by (dsp_id, result)` | reqps |
| DSP 调用耗时 P99 | `histogram_quantile(0.99, sum(rate(ssp_dsp_call_duration_seconds_bucket[1m])) by (le, dsp_id)) * 1000` | ms |

**P99 要开 histogram bucket**：Micrometer Timer 默认只输出 `_count`/`_sum`（summary 类型），`histogram_quantile()` 需要 `_bucket`，要在 `application.yml` 显式开启：

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        ssp_dsp_call_duration_seconds: true
        ssp_bid_duration_seconds: true
      minimum-expected-value:
        ssp_dsp_call_duration_seconds: 1ms
      maximum-expected-value:
        ssp_dsp_call_duration_seconds: 500ms
```

### 流量生成脚本

`scripts/load_gen.py`：在爆发期（10~30 req/s，ThreadPoolExecutor 并发）和冷却期（0.5~2 req/s，sleep 间隔）之间随机切换，混合 `slot-test-001`(70% fill) 和 `slot-no-dsp`(30% no_fill)，Ctrl+C 优雅停止并打印统计。

```bash
python3 -u scripts/load_gen.py
```

### 数据持久化说明

- Dashboard JSON 在 Git 里 → 永久不丢
- Prometheus 时序数据 / Grafana 密码 → 存容器内，`docker compose down` 后清空（学习项目够用，不加 volume）
- 若需持久化，在 docker-compose.yml 加 named volume：`prometheus_data:/prometheus` 和 `grafana_data:/var/lib/grafana`

### 本次新增的坑

- `docker/` 目录历史上被 git 以 gitlink(submodule, mode 160000) 记录，GitHub 上显示为链接而非目录。修复：`git rm --cached docker && git add docker/`，重新以普通目录纳入追踪。

### 启停顺序

```bash
# 启动
cd docker && docker compose up -d       # Kafka + Prometheus + Grafana
./mvnw spring-boot:run                  # SSP 应用
python3 -u scripts/load_gen.py          # 流量生成（可选）

# 停止
Ctrl+C                                  # 停流量生成
# kill SSP 进程
cd docker && docker compose down        # 停容器
```

## 3. TraceId：MDC 日志追踪（Phase 11）

### 核心概念

**MDC（Mapped Diagnostic Context）**：SLF4J/Logback 提供的 `ThreadLocal<Map>`，同一线程里 `put` 一次，后续所有 `log.xxx()` 自动把值插入日志，不用每次手动传参。

**为什么不直接用 requestId**：
- requestId 是竞价业务字段，只有 `/api/v1/bid` 接口有；admin/track/actuator 等接口没有业务 id，也需要能查日志
- Filter 比 Controller 先执行，此时 JSON body 还没解析，拿不到 requestId，只能先生成 UUID 占位
- 对竞价接口，Controller 解析完 body 后用业务 requestId 覆盖 UUID，最终 traceId == requestId，grep requestId 就能串联整条链路

### 日志格式配置

`logback-spring.xml` 的 pattern 里加 `%X{traceId}`，Logback 自动从 MDC 取值：

```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} [%X{traceId}] - %msg%n</pattern>
```

有值时输出 `[trace-test-001]`，没值时输出 `[]`（不影响非竞价链路的日志）。

### TraceFilter

`filter/TraceFilter.java`，继承 `OncePerRequestFilter`（每个请求只执行一次）：

```
HTTP 请求进来
  → 取 X-Request-Id header，没有则 UUID.randomUUID()
  → MDC.put("traceId", traceId)
  → filterChain.doFilter(...)   放行，进入 Controller
  → finally: MDC.remove("traceId")   必须清除！线程池复用线程，不清会污染下一个请求
```

### BidController 覆盖

Filter 执行时 body 还没解析，生成的是 UUID。Controller 拿到 `BidRequest` 后：

```java
MDC.put("traceId", request.getRequestId());  // 覆盖成业务 id
```

### 线程池子线程的 MDC 传递

`MDC` 是 `ThreadLocal`，`bidExecutor` 线程池的子线程创建时不继承父线程的值。
解决：提交任务前在父线程拍快照，子线程启动时恢复：

```java
Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();  // 父线程拍快照

supplyAsync(() -> {
    if (mdcSnapshot != null) MDC.setContextMap(mdcSnapshot);  // 子线程恢复
    try {
        return callDsp(dsp, dspRequest);
    } finally {
        MDC.clear();  // 子线程任务结束清空，防止线程复用时污染下一个任务
    }
}, bidExecutor)
```

### 效果

```
08:34:38.093 [http-nio-exec-3]  INFO  BidController    [trace-test-001] - [Bid] requestId=trace-test-001
08:34:38.209 [pool-3-thread-1]  DEBUG MockDspHandler   [trace-test-001] - [MockDSP] dsp-001 bid 4.44
08:34:38.239 [pool-3-thread-2]  DEBUG MockDspHandler   [trace-test-001] - [MockDSP] dsp-002 bid 5.06
08:34:38.277 [pool-3-thread-3]  DEBUG MockDspHandler   [trace-test-001] - [MockDSP] dsp-003 bid 1.6
```

三个子线程的日志和主线程 traceId 一致，`grep "trace-test-001" ssp.log` 把整条链路全捞出来。

### 与分布式追踪的区别

当前实现是**单服务内**的日志追踪，traceId 不会随 WebClient 调用传到 DSP 服务。
跨服务追踪需要 Micrometer Tracing + Zipkin/Jaeger，traceId 通过 HTTP header 跨服务传播——对当前单体项目意义不大，留作后续扩展方向。
