# Mini-SSP 学习笔记索引

> 后续直接维护这里的分类笔记；如需重排标题编号，运行 `python3 scripts/renumber_notes.py`。

| 文件 | 主题 | 小节数 |
|---|---|---:|
| [00-progress.md](./00-progress.md) | 进度记录与阶段总结 | 1 |
| [01-spring-java-basics.md](./01-spring-java-basics.md) | Spring / Java 基础 | 15 |
| [02-auction-core.md](./02-auction-core.md) | 竞价主链路与业务策略 | 5 |
| [03-concurrency-threading.md](./03-concurrency-threading.md) | 并发、异步与线程池 | 4 |
| [04-cache-rate-limit.md](./04-cache-rate-limit.md) | Redis 缓存与限流 | 2 |
| [05-testing.md](./05-testing.md) | 测试体系 | 4 |
| [06-observability.md](./06-observability.md) | 日志、Metrics 与 TraceId | 3 |
| [07-bid-log-kafka.md](./07-bid-log-kafka.md) | bid_log、Kafka 与写入链路 | 3 |
| [08-tooling-api-docs.md](./08-tooling-api-docs.md) | 联调脚本与接口文档 | 2 |
| [09-performance-benchmark.md](./09-performance-benchmark.md) | 压测与性能排障 | 2 |
| [10-pitfalls.md](./10-pitfalls.md) | 踩坑记录 | 1 |

## 推荐阅读路径

1. 先看 `00-progress.md` 了解项目演进。
2. 再看 `02-auction-core.md` 把竞价主流程串起来。
3. 性能相关优先看 `03-concurrency-threading.md`、`07-bid-log-kafka.md`、`09-performance-benchmark.md`。
4. 面试复盘可重点看 `06-observability.md` 和 `10-pitfalls.md`。
