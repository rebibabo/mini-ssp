# Mini-SSP 功能需求文档

## 1. 项目背景

### 1.1 什么是 SSP

SSP（Supply-Side Platform，供给方平台）是程序化广告生态中代表**媒体方**的平台。它接收来自 App/网站的广告请求，向多个 DSP（需求方平台）发起竞价，选出出价最高的广告返回给媒体展示。

### 1.2 系统定位

```
媒体（App/网站）
    │ 广告请求
    ▼
┌─────────────┐
│   Mini-SSP  │  ← 本项目
└──────┬──────┘
       │ 并发竞价请求
       ├──────────────┐──────────────┐
       ▼              ▼              ▼
   ┌──────┐      ┌──────┐      ┌──────┐
   │ DSP-A│      │ DSP-B│      │ DSP-C│
   └──────┘      └──────┘      └──────┘
       │              │              │
       └──────────────┼──────────────┘
                      ▼
              选出最高出价者
                      │
                      ▼
              返回中标广告给媒体
```

### 1.3 项目目标

构建一个简化版 SSP，覆盖核心竞价链路，作为 Java 后端技术栈的综合练习项目。

---

## 2. 功能需求

### 2.1 核心功能

| 编号 | 功能 | 说明 |
|------|------|------|
| F1 | 接收广告请求 | 媒体通过 HTTP POST 发送广告请求，包含广告位、设备、用户信息 |
| F2 | 广告位校验 | 校验广告位 ID 是否合法、是否启用 |
| F3 | 查询 DSP 配置 | 根据广告位匹配可竞价的 DSP 列表 |
| F4 | 并发竞价 | 向所有匹配的 DSP 同时发起竞价请求，设置超时 |
| F5 | 竞价决策 | 收集所有有效响应，选出出价最高的 DSP |
| F6 | 返回广告 | 将中标广告信息返回给媒体 |
| F7 | 曝光/点击回调 | 提供曝光上报和点击跳转接口，记录日志 |

### 2.2 管理后台功能

| 编号 | 功能 | 说明 |
|------|------|------|
| M1 | 广告位管理 | CRUD 广告位（名称、尺寸、类型、底价、状态） |
| M2 | DSP 管理 | CRUD DSP 配置（名称、竞价地址、超时时间、QPS 上限、状态） |
| M3 | 广告位-DSP 关联 | 配置每个广告位可以向哪些 DSP 发起竞价 |
| M4 | 竞价日志查询 | 查询历史竞价记录，支持按时间、广告位、DSP 筛选 |

---

## 3. 非功能需求

| 维度 | 要求 | 说明 |
|------|------|------|
| 响应时间 | 竞价链路 ≤ 200ms | 包括向 DSP 请求的耗时，DSP 超时阈值设为 150ms |
| 并发量 | 支持 500 QPS | 单机目标，通过线程池 + 异步处理 |
| 可用性 | DSP 超时或异常不影响整体 | 部分 DSP 失败时仍返回有效结果 |
| 限流 | DSP 级别 QPS 限流 | 每个 DSP 有独立的 QPS 上限，超过则跳过 |
| 缓存 | 广告位和 DSP 配置缓存 | 配置信息优先从 Redis 读取，减少数据库查询 |
| 日志 | 竞价全链路日志 | 每次竞价记录请求、各 DSP 响应、最终决策 |

---

## 4. API 设计

### 4.1 竞价接口（核心）

**POST /api/v1/bid**

媒体发送广告请求，SSP 返回中标广告。

请求体（JSON key 对齐 OpenRTB 2.5；Java 内部字段名经 `@JsonProperty` 解耦，见 9.x）：

```json
{
    "id": "req-20260612-001",
    "tagid": "slot-1001",
    "device": {
        "os": "Android",
        "osv": "14",
        "model": "OPPO Find X7",
        "ip": "223.104.1.100",
        "ua": "Mozilla/5.0 ..."
    },
    "user": {
        "id": "user-abc-123",
        "age": 25,
        "gender": "M",
        "keywords": ["gaming", "tech"]
    }
}
```

> 字段映射（对外 JSON ↔ Java 内部字段）：
> `id`↔`requestId`、`tagid`↔`adSlotId`、`device.osv`↔`osVersion`、`user.id`↔`userId`、`user.keywords`↔`interests`。
> 通过 `@JsonProperty` 实现：内部保留可读命名，对外协议遵循 OpenRTB。`age` 暂保留（OpenRTB 用 `yob` 出生年，留待后续）。
> 注意：SSP→媒体的响应（下方）属私有协议，不在 OpenRTB 范围，沿用 `requestId`/`adSlotId`。

成功响应（200）：

```json
{
    "code": 0,
    "message": "success",
    "data": {
        "requestId": "req-20260612-001",
        "adSlotId": "slot-1001",
        "winDsp": "DSP-A",
        "winPrice": 3.50,
        "adContent": {
            "title": "新品首发",
            "description": "限时优惠，立即抢购",
            "imageUrl": "https://cdn.example.com/ad/banner-001.jpg",
            "clickUrl": "https://www.example.com/landing",
            "impressionTrackUrl": "http://localhost:8080/api/v1/track/impression?rid=req-20260612-001",
            "clickTrackUrl": "http://localhost:8080/api/v1/track/click?rid=req-20260612-001"
        }
    }
}
```

无广告填充（200）：

```json
{
    "code": 1,
    "message": "no fill",
    "data": null
}
```

### 4.2 曝光上报接口

**GET /api/v1/track/impression?rid={requestId}**

媒体展示广告后调用，记录曝光日志。

响应：204 No Content

### 4.3 点击跳转接口

**GET /api/v1/track/click?rid={requestId}**

用户点击广告后调用，记录点击日志并跳转到落地页。

响应：302 Redirect → 落地页 URL

### 4.4 广告位管理接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/slots` | 列表查询（分页） |
| GET | `/api/v1/admin/slots/{id}` | 查询单个 |
| POST | `/api/v1/admin/slots` | 新增 |
| PUT | `/api/v1/admin/slots/{id}` | 修改 |
| DELETE | `/api/v1/admin/slots/{id}` | 删除 |

### 4.5 DSP 管理接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/admin/dsps` | 列表查询（分页） |
| GET | `/api/v1/admin/dsps/{id}` | 查询单个 |
| POST | `/api/v1/admin/dsps` | 新增 |
| PUT | `/api/v1/admin/dsps/{id}` | 修改 |
| DELETE | `/api/v1/admin/dsps/{id}` | 删除 |

### 4.6 竞价日志查询接口

**GET /api/v1/admin/logs**

查询参数：

| 参数 | 类型 | 说明 |
|------|------|------|
| startTime | String | 开始时间（yyyy-MM-dd HH:mm:ss） |
| endTime | String | 结束时间 |
| adSlotId | String | 广告位 ID（可选） |
| dspId | String | DSP ID（可选） |
| page | int | 页码，默认 1 |
| pageSize | int | 每页条数，默认 20 |

### 4.7 统一响应格式

所有接口使用统一的响应包装：

```json
{
    "code": 0,
    "message": "success",
    "data": { }
}
```

错误码：

| code | 含义 |
|------|------|
| 0 | 成功 |
| 1 | 无广告填充 |
| 400 | 参数错误 |
| 404 | 资源不存在 |
| 429 | 请求过于频繁（限流） |
| 500 | 服务器内部错误 |

---

## 5. 数据库设计

### 5.1 广告位表 ad_slot

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键，自增 |
| slot_id | VARCHAR(64) | 广告位 ID，唯一索引 |
| name | VARCHAR(128) | 广告位名称 |
| width | INT | 宽度（px） |
| height | INT | 高度（px） |
| type | TINYINT | 类型：1=横幅 2=插屏 3=开屏 4=信息流 |
| floor_price | DECIMAL(10,4) | 底价（CPM），低于此价不接受 |
| status | TINYINT | 状态：0=禁用 1=启用 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 5.2 DSP 配置表 dsp_config

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键，自增 |
| dsp_id | VARCHAR(64) | DSP ID，唯一索引 |
| name | VARCHAR(128) | DSP 名称 |
| bid_url | VARCHAR(512) | 竞价请求地址 |
| timeout_ms | INT | 超时时间（毫秒），默认 150 |
| qps_limit | INT | QPS 上限，0 表示不限 |
| status | TINYINT | 状态：0=禁用 1=启用 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

### 5.3 广告位-DSP 关联表 slot_dsp_rel

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键，自增 |
| slot_id | VARCHAR(64) | 广告位 ID |
| dsp_id | VARCHAR(64) | DSP ID |
| created_at | DATETIME | 创建时间 |

联合唯一索引：(slot_id, dsp_id)

### 5.4 竞价日志表 bid_log

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键，自增 |
| request_id | VARCHAR(64) | 请求 ID，索引 |
| slot_id | VARCHAR(64) | 广告位 ID |
| dsp_id | VARCHAR(64) | DSP ID |
| bid_price | DECIMAL(10,4) | 出价 |
| response_time_ms | INT | 响应耗时（毫秒） |
| status | TINYINT | 状态：0=超时 1=有效出价 2=无出价 3=异常 |
| win | TINYINT | 是否中标：0=否 1=是 |
| created_at | DATETIME | 创建时间 |

索引：request_id, (slot_id, created_at), (dsp_id, created_at)

### 5.5 事件日志表 event_log

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键，自增 |
| request_id | VARCHAR(64) | 请求 ID，索引 |
| event_type | TINYINT | 事件类型：1=曝光 2=点击 |
| slot_id | VARCHAR(64) | 广告位 ID |
| dsp_id | VARCHAR(64) | 中标 DSP ID |
| win_price | DECIMAL(10,4) | 中标价格 |
| ip | VARCHAR(45) | 用户 IP |
| ua | VARCHAR(512) | User-Agent |
| created_at | DATETIME | 创建时间 |

### 5.6 建表 SQL

```sql
CREATE DATABASE IF NOT EXISTS mini_ssp DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_general_ci;

USE mini_ssp;

CREATE TABLE ad_slot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    slot_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    type TINYINT NOT NULL DEFAULT 1 COMMENT '1=横幅 2=插屏 3=开屏 4=信息流',
    floor_price DECIMAL(10,4) NOT NULL DEFAULT 0.0000,
    status TINYINT NOT NULL DEFAULT 1 COMMENT '0=禁用 1=启用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_slot_id (slot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE dsp_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dsp_id VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    bid_url VARCHAR(512) NOT NULL,
    timeout_ms INT NOT NULL DEFAULT 150,
    qps_limit INT NOT NULL DEFAULT 0 COMMENT '0=不限',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '0=禁用 1=启用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_dsp_id (dsp_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE slot_dsp_rel (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    slot_id VARCHAR(64) NOT NULL,
    dsp_id VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE INDEX idx_slot_dsp (slot_id, dsp_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE bid_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    slot_id VARCHAR(64) NOT NULL,
    dsp_id VARCHAR(64) NOT NULL,
    bid_price DECIMAL(10,4) DEFAULT NULL,
    response_time_ms INT DEFAULT NULL,
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=超时 1=有效出价 2=无出价 3=异常',
    win TINYINT NOT NULL DEFAULT 0 COMMENT '0=否 1=是',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_request_id (request_id),
    INDEX idx_slot_time (slot_id, created_at),
    INDEX idx_dsp_time (dsp_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE event_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(64) NOT NULL,
    event_type TINYINT NOT NULL COMMENT '1=曝光 2=点击',
    slot_id VARCHAR(64) NOT NULL,
    dsp_id VARCHAR(64) NOT NULL,
    win_price DECIMAL(10,4) DEFAULT NULL,
    ip VARCHAR(45) DEFAULT NULL,
    ua VARCHAR(512) DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_request_id (request_id),
    INDEX idx_event_time (event_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

---

## 6. 缓存设计（Redis）

### 6.1 广告位配置缓存

| Key | Value | 过期时间 | 说明 |
|-----|-------|---------|------|
| `ssp:slot:{slotId}` | 广告位 JSON | 10 分钟 | 查询时先查 Redis，未命中再查 DB 并回填 |
| `ssp:slot:all` | 所有启用广告位列表 JSON | 10 分钟 | 管理后台修改时主动删除此 key |

### 6.2 DSP 配置缓存

| Key | Value | 过期时间 | 说明 |
|-----|-------|---------|------|
| `ssp:dsp:{dspId}` | DSP 配置 JSON | 10 分钟 | 同上 |
| `ssp:slot_dsps:{slotId}` | 该广告位关联的 DSP 列表 JSON | 10 分钟 | 竞价时用，避免每次查关联表 |

### 6.3 DSP 限流计数器

| Key | Value | 过期时间 | 说明 |
|-----|-------|---------|------|
| `ssp:rate:{dspId}:{yyyyMMddHHmmss}` | 当前秒请求计数 | 2 秒 | 滑动窗口限流，INCR + TTL |

限流逻辑：

```
发起竞价前检查：
  INCR ssp:rate:{dspId}:{当前秒}
  → 返回值 > qpsLimit → 跳过该 DSP
  → 返回值 ≤ qpsLimit → 正常发起竞价
```

### 6.4 竞价结果临时缓存

| Key | Value | 过期时间 | 说明 |
|-----|-------|---------|------|
| `ssp:bid_result:{requestId}` | 中标结果 JSON | 5 分钟 | 曝光/点击回调时用，避免查 DB |

---

## 7. 系统架构

### 7.1 技术选型

| 组件 | 选型 | 说明 |
|------|------|------|
| 语言 | Java 17 | LTS 版本 |
| 框架 | Spring Boot 3.x | REST API + 自动配置 |
| 构建工具 | Maven | 依赖管理 |
| 数据库 | MySQL 8.0 | 存储配置和日志 |
| ORM | MyBatis / MyBatis-Plus | SQL 映射 |
| 缓存 | Redis | 配置缓存 + 限流 |
| HTTP 客户端 | WebClient（Spring WebFlux） | 异步非阻塞请求 DSP |
| JSON | Jackson | 序列化/反序列化 |
| 日志 | SLF4J + Logback | 结构化日志 |
| 接口文档 | Swagger / SpringDoc | 自动生成 API 文档 |

### 7.2 项目结构

```
mini-ssp/
├── pom.xml
├── src/main/java/com/example/ssp/
│   ├── MiniSspApplication.java          # 启动类
│   ├── config/                          # 配置类
│   │   ├── ThreadPoolConfig.java        # 线程池配置
│   │   ├── RedisConfig.java             # Redis 配置
│   │   └── WebClientConfig.java         # HTTP 客户端配置
│   ├── controller/                      # 接口层
│   │   ├── BidController.java           # 竞价接口
│   │   ├── TrackController.java         # 曝光/点击回调
│   │   ├── SlotAdminController.java     # 广告位管理
│   │   └── DspAdminController.java      # DSP 管理
│   ├── service/                         # 业务层
│   │   ├── BidService.java              # 竞价编排（核心）
│   │   ├── DspBidClient.java            # 向 DSP 发请求
│   │   ├── SlotService.java             # 广告位业务
│   │   ├── DspService.java              # DSP 业务
│   │   └── TrackService.java            # 曝光/点击业务
│   ├── model/                           # 数据模型
│   │   ├── entity/                      # 数据库实体
│   │   │   ├── AdSlot.java
│   │   │   ├── DspConfig.java
│   │   │   ├── SlotDspRel.java
│   │   │   ├── BidLog.java
│   │   │   └── EventLog.java
│   │   ├── dto/                         # 请求/响应对象
│   │   │   ├── BidRequest.java
│   │   │   ├── BidResponse.java
│   │   │   ├── DspBidRequest.java
│   │   │   └── DspBidResponse.java
│   │   ├── vo/                          # 视图对象（返回前端）
│   │   │   └── ApiResponse.java
│   │   └── enums/                       # 枚举
│   │       ├── AdSlotType.java
│   │       ├── BidStatus.java
│   │       └── EventType.java
│   ├── mapper/                          # MyBatis Mapper
│   │   ├── AdSlotMapper.java
│   │   ├── DspConfigMapper.java
│   │   ├── SlotDspRelMapper.java
│   │   ├── BidLogMapper.java
│   │   └── EventLogMapper.java
│   ├── cache/                           # 缓存层
│   │   ├── SlotCacheService.java
│   │   ├── DspCacheService.java
│   │   └── RateLimiter.java
│   ├── aspect/                          # 切面
│   │   └── LogAspect.java               # 接口耗时日志
│   └── exception/                       # 异常处理
│       ├── BizException.java            # 业务异常
│       └── GlobalExceptionHandler.java  # 全局异常处理器
└── src/main/resources/
    ├── application.yml                  # 配置文件
    ├── mapper/                          # MyBatis XML
    └── schema.sql                       # 建表语句
```

### 7.3 整体竞价流程

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
                                DspBidResponse  DspBidResponse
                                    │               │               │
                                    └───────────────┼───────────────┘
                                                    ▼
                                            SSP 选最高价 DSP-A
                                                    │
       App 展示广告 ◀──BidResponse(广告内容, 追踪URL)──┘
```

### 7.4 核心竞价流程

```
BidController.bid(BidRequest)
  │
  ▼
BidService.processBid(request)
  │
  ├── 1. SlotCacheService.getSlot(slotId)
  │      先查 Redis，未命中查 DB 并回填
  │      校验广告位是否存在且启用
  │
  ├── 2. DspCacheService.getDspsBySlot(slotId)
  │      获取该广告位关联的所有启用 DSP
  │
  ├── 3. 对每个 DSP 并发发起竞价（CompletableFuture.supplyAsync）
  │      │
  │      ├── RateLimiter.tryAcquire(dspId)
  │      │    检查 Redis 计数器，超限则跳过
  │      │
  │      ├── DspBidClient.bid(dsp, request)
  │      │    通过 WebClient 异步请求 DSP 竞价接口
  │      │    设置超时（dsp.timeoutMs）
  │      │
  │      └── 异常处理：超时/网络错误 → 返回空响应，不影响其他 DSP
  │
  ├── 4. CompletableFuture.allOf() 等待全部完成
  │
  ├── 5. 收集所有有效响应
  │      过滤：出价 > 0 且 出价 ≥ 广告位底价
  │      排序：按出价降序
  │      选出最高价
  │
  ├── 6. 记录竞价日志（异步写入 DB）
  │      每个 DSP 的请求结果都记录到 bid_log 表
  │
  ├── 7. 缓存中标结果到 Redis
  │      供后续曝光/点击回调查询
  │
  └── 8. 返回 BidResponse
         有中标 → 返回广告内容 + 追踪 URL
         无中标 → 返回 no fill
```

---

## 8. DSP 竞价对接方案

本项目提供两种 DSP 对接方式，可根据开发阶段选择。

### 方案 A：进程内模拟（快速验证）

不启动真实的 DSP 服务，在 SSP 内部用模拟类替代 HTTP 调用：

```
BidService → DspBidClient.bid()
              → 不发 HTTP，而是调用 MockDspHandler
              → sleep 随机耗时（模拟网络延迟）
              → 返回随机出价
```

优点：零依赖，一个 Spring Boot 项目就能跑通。
缺点：没有真实的 HTTP 交互，练不到 WebClient。

### 方案 B：独立 Mock DSP 服务（推荐）

启动 2-3 个独立的 Spring Boot 项目作为 Mock DSP，SSP 通过 HTTP 调用它们：

```
Mini-SSP (8080)
  ├── HTTP → Mock DSP-A (8081)  返回随机出价，随机延迟 30-100ms
  ├── HTTP → Mock DSP-B (8082)  返回随机出价，随机延迟 50-200ms
  └── HTTP → Mock DSP-C (8083)  模拟偶尔超时/异常
```

优点：完整覆盖 HTTP 客户端、超时处理、异常容错。
缺点：需要同时运行多个服务。

建议先用方案 A 跑通核心逻辑，再切到方案 B 练习 HTTP 调用和异常处理。

---

## 9. 枚举定义

### 9.1 广告位类型

```java
public enum AdSlotType {
    BANNER(1, "横幅"),
    INTERSTITIAL(2, "插屏"),
    SPLASH(3, "开屏"),
    NATIVE_FEED(4, "信息流");

    private final int code;
    private final String desc;
}
```

### 9.2 竞价状态

```java
public enum BidStatus {
    TIMEOUT(0, "超时"),
    VALID_BID(1, "有效出价"),
    NO_BID(2, "无出价"),
    ERROR(3, "异常");

    private final int code;
    private final String desc;
}
```

### 9.3 事件类型

```java
public enum EventType {
    IMPRESSION(1, "曝光"),
    CLICK(2, "点击");

    private final int code;
    private final String desc;
}
```

---

## 10. 配置文件（application.yml）

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mini_ssp?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8mb4
    username: root
    password: your_password
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: localhost
      port: 6379
      timeout: 3000ms

mybatis-plus:
  mapper-locations: classpath:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl

# 竞价配置
ssp:
  bid:
    global-timeout-ms: 200     # 整体超时
    default-dsp-timeout-ms: 150 # 单个 DSP 默认超时
    thread-pool:
      core-size: 8
      max-size: 16
      queue-capacity: 200
      keep-alive-seconds: 60
  cache:
    slot-ttl-minutes: 10
    dsp-ttl-minutes: 10
    bid-result-ttl-minutes: 5

logging:
  level:
    com.example.ssp: DEBUG
```

---

## 11. 开发计划

### Phase 1：核心骨架（1-2 天）

- [ ] Spring Boot 项目搭建 + Maven 依赖
- [ ] 数据库建表 + MyBatis Mapper
- [ ] 广告位/DSP 管理 CRUD 接口
- [ ] 统一响应格式 + 全局异常处理

### Phase 2：竞价链路（2-3 天）

- [ ] 竞价接口 BidController + BidService
- [ ] 方案 A 进程内模拟 DSP
- [ ] CompletableFuture 并发竞价 + 超时控制
- [ ] 竞价决策（选最高价）
- [ ] 竞价日志异步写入

### Phase 3：缓存与限流（1-2 天）

- [ ] Redis 配置缓存（广告位、DSP 配置）
- [ ] Redis 限流计数器
- [ ] 缓存更新策略（管理后台修改时删缓存）

### Phase 4：完善与监控（1-2 天）

- [ ] 曝光/点击回调接口
- [ ] AOP 切面日志（接口耗时）
- [ ] 方案 B Mock DSP 服务 + WebClient 对接
- [ ] Swagger 接口文档

### Phase 5：优化（可选）

- [ ] 竞价日志异步批量写入（BlockingQueue + 定时 flush）
- [ ] 接口限流（全局 QPS 限制）
- [ ] 配置热更新（Redis Pub/Sub 通知）
- [ ] 单元测试（JUnit 5 + Mockito）