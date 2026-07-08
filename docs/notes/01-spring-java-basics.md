# Spring / Java 基础

> 本文件为分类整理后的主题笔记。

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

## 6. 异常处理

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

## 7. MyBatis-Plus 查询条件

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

## 8. 依赖注入

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

## 9. Controller 注解

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

## 10. Spring 配置类（@Configuration + @Bean）

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

## 11. Stream API

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

## 12. 内部类

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

## 13. Lambda 与函数式接口

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

## 14. 并发基础：ThreadLocalRandom / Thread.sleep / InterruptedException

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

## 15. JVM 系统属性 -D

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
