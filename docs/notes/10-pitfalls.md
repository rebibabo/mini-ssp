# 踩坑记录

> 本文件为分类整理后的主题笔记。

## 1. 踩坑记录

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
