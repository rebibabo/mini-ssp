# Mini-SSP 后续开发计划（Phase 6-9）

## 现状回顾

Phase 1-5 完成后，已具备：
- 完整竞价链路（接收请求 → 缓存查询 → 并发竞价 → 决策 → 埋点）
- Redis 缓存（Cache-Aside 模式）
- DSP QPS 限流
- AOP 请求日志
- bid_log 查询接口
- 压测验证（缓存 vs 无缓存）

DSP 竞价目前是**进程内模拟**（方案 A）——`DspBidClient` 不发真实 HTTP 请求，直接 sleep + 返回随机出价。

下一阶段目标：从"模拟"走向"真实"，并补齐工程化短板。

---

## Phase 6：真实 Mock DSP 服务（方案 B）

### 6.1 目标

把方案 A 的进程内模拟，换成方案 B——启动独立的 Spring Boot 项目作为 Mock DSP，SSP 通过真实 HTTP 调用它们。这是从"模拟并发"到"真实网络调用"的关键一步。

```
Mini-SSP (8080)
  ├── HTTP → Mock DSP-A (8081)  随机延迟 30-100ms，随机出价
  ├── HTTP → Mock DSP-B (8082)  随机延迟 50-200ms，随机出价
  └── HTTP → Mock DSP-C (8083)  10% 概率超时/异常
```

### 6.2 Mock DSP 项目结构

新建一个独立 Maven 项目 `mock-dsp`，可以通过启动参数控制端口和行为，一份代码跑三个实例：

```
mock-dsp/
├── pom.xml
└── src/main/java/com/example/mockdsp/
    ├── MockDspApplication.java
    └── controller/
        └── BidController.java
```

```java
@RestController
@RequestMapping("/dsp")
public class BidController {

    @Value("${mock.dsp.min-delay-ms:30}")
    private int minDelay;

    @Value("${mock.dsp.max-delay-ms:100}")
    private int maxDelay;

    @Value("${mock.dsp.error-rate:0.0}")
    private double errorRate;

    @PostMapping("/bid")
    public DspBidResponse bid(@RequestBody DspBidRequest request) throws InterruptedException {
        // 模拟网络延迟
        int delay = minDelay + new Random().nextInt(maxDelay - minDelay);
        Thread.sleep(delay);

        // 模拟偶尔异常
        if (Math.random() < errorRate) {
            throw new RuntimeException("DSP internal error");
        }

        // 模拟出价（70% 概率出价，30% 不出价）
        if (Math.random() < 0.3) {
            return DspBidResponse.noBid(request.getRequestId());
        }
        double price = request.getFloorPrice() + Math.random() * 5;
        return DspBidResponse.of(request.getRequestId(), price);
    }
}
```

### 6.3 启动三个实例

用不同的启动参数区分三个 DSP：

```bash
# DSP-A：低延迟，不出错
java -jar mock-dsp.jar --server.port=8081 \
  --mock.dsp.min-delay-ms=30 --mock.dsp.max-delay-ms=100

# DSP-B：中等延迟
java -jar mock-dsp.jar --server.port=8082 \
  --mock.dsp.min-delay-ms=50 --mock.dsp.max-delay-ms=200

# DSP-C：高延迟 + 10% 异常率（用来验证容错）
java -jar mock-dsp.jar --server.port=8083 \
  --mock.dsp.min-delay-ms=100 --mock.dsp.max-delay-ms=300 \
  --mock.dsp.error-rate=0.1
```

### 6.4 SSP 端改造：DspBidClient 改用 WebClient

```java
@Component
@RequiredArgsConstructor
public class DspBidClient {

    private final WebClient webClient;

    public DspBidResponse bid(DspConfig dsp, DspBidRequest request) {
        return webClient.post()
                .uri(dsp.getBidUrl())  // 数据库里配的 http://localhost:8081/dsp/bid
                .bodyValue(request)
                .retrieve()
                .bodyToMono(DspBidResponse.class)
                .timeout(Duration.ofMillis(dsp.getTimeoutMs()))
                .block();  // 在 CompletableFuture.supplyAsync 里调用，阻塞是安全的
    }
}
```

### 6.5 WebClient 配置

```java
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(1024 * 1024))  // 1MB 响应体上限
                .build();
    }
}
```

### 6.6 数据库配置更新

`dsp_config` 表的 `bid_url` 字段从占位符改成真实地址：

```sql
UPDATE dsp_config SET bid_url = 'http://localhost:8081/dsp/bid' WHERE dsp_id = 'DSP-A';
UPDATE dsp_config SET bid_url = 'http://localhost:8082/dsp/bid' WHERE dsp_id = 'DSP-B';
UPDATE dsp_config SET bid_url = 'http://localhost:8083/dsp/bid' WHERE dsp_id = 'DSP-C';
```

### 6.7 验收标准

- [ ] 三个 Mock DSP 实例独立启动成功
- [ ] SSP 通过 WebClient 发起真实 HTTP 竞价请求
- [ ] DSP-C 的超时/异常不影响整体竞价结果（容错验证）
- [ ] 用 `curl` 单独测试每个 Mock DSP 接口
- [ ] 重新跑一次 Phase 5 的压测，对比真实 HTTP 调用 vs 进程内模拟的耗时差异

---

## Phase 7：测试与容器化

### 7.1 单元测试：BidService 核心决策逻辑

用 JUnit 5 + Mockito，mock 掉 Mapper 和 RedisTemplate，只测竞价决策逻辑：

```java
@ExtendWith(MockitoExtension.class)
class BidServiceTest {

    @Mock
    private DspBidClient dspBidClient;

    @Mock
    private SlotCacheService slotCacheService;

    @InjectMocks
    private BidService bidService;

    @Test
    void shouldReturnNoFill_whenAllDspsTimeout() {
        // given：所有 DSP 都返回超时
        // when：调用 processBid
        // then：返回 code=1, data=null
    }

    @Test
    void shouldSelectHighestBid_whenMultipleValidBids() {
        // given：DSP-A 出价 3.5，DSP-B 出价 2.8
        // when：调用 processBid
        // then：winDsp=DSP-A, winPrice=3.5
    }

    @Test
    void shouldFilterOut_whenBidBelowFloorPrice() {
        // given：广告位底价 3.0，DSP 出价 2.5
        // then：该 DSP 结果被过滤，不参与决策
    }

    @Test
    void shouldReturnNoFill_whenSlotHasNoDsp() {
        // given：广告位没有关联任何 DSP
        // then：直接返回 no fill，不发起任何竞价请求
    }
}
```

覆盖你笔记里"6. 竞价流程"列出的所有 no fill 情况，这是最值得写测试的地方——业务逻辑分支多，回归测试价值最高。

### 7.2 Docker 化

#### Dockerfile（SSP 应用）

```dockerfile
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./mvnw package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/mini-ssp-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

#### docker-compose.yml

```yaml
version: '3.8'
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: mini_ssp
    ports:
      - "3306:3306"
    volumes:
      - ./schema.sql:/docker-entrypoint-initdb.d/schema.sql

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  mock-dsp-a:
    build: ./mock-dsp
    ports:
      - "8081:8081"
    environment:
      SERVER_PORT: 8081
      MOCK_DSP_MIN_DELAY_MS: 30
      MOCK_DSP_MAX_DELAY_MS: 100

  mock-dsp-b:
    build: ./mock-dsp
    ports:
      - "8082:8082"
    environment:
      SERVER_PORT: 8082
      MOCK_DSP_MIN_DELAY_MS: 50
      MOCK_DSP_MAX_DELAY_MS: 200

  mock-dsp-c:
    build: ./mock-dsp
    ports:
      - "8083:8083"
    environment:
      SERVER_PORT: 8083
      MOCK_DSP_ERROR_RATE: 0.1

  ssp:
    build: .
    ports:
      - "8080:8080"
    depends_on:
      - mysql
      - redis
      - mock-dsp-a
      - mock-dsp-b
      - mock-dsp-c
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/mini_ssp
      SPRING_DATA_REDIS_HOST: redis
```

一条命令启动整个系统：

```bash
docker-compose up --build
```

### 7.3 验收标准

- [ ] `BidService` 核心决策逻辑单元测试覆盖率 > 80%
- [ ] `docker-compose up` 一键启动所有服务（MySQL + Redis + 3个DSP + SSP）
- [ ] 容器化环境下重新跑一次完整竞价流程，验证服务间网络通信正常

---

## Phase 8：缓存一致性与限流升级

### 8.1 配置热更新（Redis Pub/Sub）

**问题**：现在改了 `ad_slot` 或 `dsp_config`，要等 10 分钟 TTL 过期后缓存才会更新。

**方案**：管理后台修改配置时，发布一条消息；SSP 实例订阅频道，收到消息后主动删除对应缓存。

```java
// 管理后台修改配置后，发布消息
@Service
@RequiredArgsConstructor
public class SlotAdminService {

    private final StringRedisTemplate redisTemplate;

    public void updateSlot(AdSlot slot) {
        adSlotMapper.updateById(slot);
        // 发布缓存失效通知
        redisTemplate.convertAndSend("ssp:cache:invalidate", "slot:" + slot.getSlotId());
    }
}
```

```java
// SSP 订阅频道，收到消息后清缓存
@Component
@RequiredArgsConstructor
public class CacheInvalidationListener implements MessageListener {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String key = message.toString();  // "slot:slot-1001"
        redisTemplate.delete("ssp:" + key);
    }
}
```

```java
@Configuration
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer container(
            RedisConnectionFactory factory,
            CacheInvalidationListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(listener, new ChannelTopic("ssp:cache:invalidate"));
        return container;
    }
}
```

### 8.2 接口级限流（令牌桶）

**现状**：DSP 级限流已实现（防止某个 DSP 被打爆）。

**新增**：`/api/v1/bid` 整体限流，防止突发流量打垮 SSP 自身。

用 Redis 实现简化版令牌桶——每秒固定生成 N 个令牌，请求消耗一个令牌，没有令牌则拒绝：

```java
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final StringRedisTemplate redisTemplate;

    @Value("${ssp.bid.qps-limit:200}")
    private int qpsLimit;

    public boolean tryAcquire() {
        String key = "ssp:bid:rate:" + (System.currentTimeMillis() / 1000);
        Long count = redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, 2, TimeUnit.SECONDS);
        return count <= qpsLimit;
    }
}
```

在 `BidController` 入口处调用：

```java
@PostMapping("/bid")
public ApiResponse<BidResponse> bid(@RequestBody @Valid BidRequest request) {
    if (!rateLimiter.tryAcquire()) {
        return ApiResponse.error(429, "Too Many Requests");
    }
    return ApiResponse.success(bidService.processBid(request));
}
```

### 8.3 验收标准

- [ ] 修改管理后台广告位配置后，立即生效（不等 TTL）
- [ ] `/api/v1/bid` 超过 QPS 限制时返回 429
- [ ] 压测验证限流生效（用 ab 打超过限制的 QPS，观察 429 比例）

---

## Phase 9：异步日志与二价拍卖

### 9.1 异步批量写 bid_log

**问题**：每次竞价产生 N 条 bid_log（N = 关联的 DSP 数量），高并发下同步写入数据库压力大。

**方案**：用 `BlockingQueue` 缓冲日志，后台线程定时批量写入。

```java
@Component
public class BidLogWriter {

    private final BlockingQueue<BidLog> queue = new LinkedBlockingQueue<>(10000);
    private final BidLogMapper bidLogMapper;

    public BidLogWriter(BidLogMapper bidLogMapper) {
        this.bidLogMapper = bidLogMapper;
    }

    // 生产者：竞价完成后调用，非阻塞
    public void offer(BidLog log) {
        if (!queue.offer(log)) {
            log.warn("bid_log queue full, dropping log");
        }
    }

    // 消费者：每秒批量 flush
    @Scheduled(fixedRate = 1000)
    public void flush() {
        List<BidLog> batch = new ArrayList<>();
        BidLog log;
        while ((log = queue.poll()) != null && batch.size() < 500) {
            batch.add(log);
        }
        if (!batch.isEmpty()) {
            bidLogMapper.insertBatch(batch);  // MyBatis-Plus 批量插入
        }
    }
}
```

`BidService` 里把 `bidLogMapper.insert(log)` 替换为 `bidLogWriter.offer(log)`，从同步写变成异步写。

需要在启动类加 `@EnableScheduling` 才能让 `@Scheduled` 生效。

### 9.2 二价拍卖

**当前**：一价拍卖，`win_price = 中标者 bid_price`。

**改造**：二价拍卖，`win_price = 第二高价 + 0.01`，只有一个有效出价时按业务规则处理（如 `floor_price + 0.01`）。

```java
private BidResponse decideWinner(List<DspBidResult> validBids, AdSlot slot) {
    if (validBids.isEmpty()) {
        return null;  // no fill
    }

    validBids.sort(Comparator.comparing(DspBidResult::getBidPrice).reversed());

    DspBidResult winner = validBids.get(0);
    double winPrice;

    if (validBids.size() >= 2) {
        // 二价：第二高价 + 0.01
        winPrice = validBids.get(1).getBidPrice() + 0.01;
    } else {
        // 只有一个出价：按底价 + 0.01
        winPrice = slot.getFloorPrice() + 0.01;
    }

    // 边界：二价不能超过中标者的实际出价
    winPrice = Math.min(winPrice, winner.getBidPrice());

    return BidResponse.of(winner.getDspId(), winPrice, ...);
}
```

补充单元测试用例：

```java
@Test
void shouldUseSecondPrice_whenTwoValidBids() {
    // DSP-A 出价 5.0，DSP-B 出价 3.0
    // winDsp = DSP-A, winPrice = 3.01
}

@Test
void shouldUseFloorPricePlusOne_whenOnlyOneValidBid() {
    // 只有 DSP-A 出价 5.0，floor_price = 2.0
    // winDsp = DSP-A, winPrice = 2.01
}
```

### 9.3 验收标准

- [ ] `bid_log` 写入改为异步批量，竞价接口响应时间不受日志写入影响
- [ ] 二价拍卖逻辑实现，单元测试覆盖单出价/多出价场景
- [ ] 压测验证：异步日志下高 QPS 场景，数据库写入压力明显降低

---

## 整体优先级与时间估算

| Phase | 内容 | 难度 | 预估时间 | 价值 |
|-------|------|------|---------|------|
| 6 | 真实 Mock DSP + WebClient | 中 | 1-2 天 | 高（真实网络调用、容错验证） |
| 7 | 单元测试 + Docker | 中 | 1-2 天 | 高（工程化基本功，面试常问） |
| 8 | 缓存一致性 + 接口限流 | 中 | 1 天 | 中（系统设计深度） |
| 9 | 异步日志 + 二价拍卖 | 中低 | 1 天 | 中（并发应用 + 业务逻辑） |

**建议顺序**：6 → 7 → 9 → 8

理由：
- Phase 6 是"从模拟到真实"的关键一步，优先做
- Phase 7 的测试应该尽早建立，后续改动才有保障
- Phase 9 的二价拍卖是业务逻辑，9.1 异步日志依赖 Phase 7 的测试框架验证
- Phase 8 的 Pub/Sub 涉及多实例场景，单机验证价值有限，可以放最后

完成 Phase 6-9 后，Mini-SSP 已经覆盖：完整业务链路、真实网络调用、缓存策略、限流、异步处理、容器化、单元测试——足以作为一个完整的后端项目在面试中展示。