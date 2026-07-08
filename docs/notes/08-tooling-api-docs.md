# 联调脚本与接口文档

> 本文件为分类整理后的主题笔记。

## 1. Bash 联调自动化脚本（scripts/test-modeB.sh）

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

## 2. Swagger / OpenAPI 接口文档（springdoc）

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
