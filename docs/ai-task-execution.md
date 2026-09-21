# AI 任务可靠执行

本文描述 AI 生图任务的内部执行架构。对外 API 和任务状态仍以 `openapi/m2.yaml` 为准。

## 组件职责

```text
API -> MySQL(ai_task + ai_task_outbox) -> RabbitMQ -> AI Worker
                                                   -> Redis 限流
                                                   -> AI Provider
                                                   -> MinIO + MySQL
```

- MySQL 是任务、配额和 Outbox 的事实来源。
- RabbitMQ Quorum Queue 保存待执行消息，采用 Publisher Confirm、持久消息和手动 ACK。
- Worker 使用固定并发、`prefetch=1`，不使用进程内业务积压队列。
- Redis 使用令牌桶限制 Provider RPM，使用带过期时间的许可限制多实例全局并发。
- MinIO 是 AI 结果唯一图片存储；消息中只传 `taskId`，不传提示词、密钥或图片内容。

## 提交与投递

创建任务时在同一数据库事务中完成用户锁定、配额预占、`ai_task` 插入和 `ai_task_outbox` 插入。Outbox Publisher 抢占到期记录，消息收到 RabbitMQ Confirm 后才将记录标记为 `published`。Publisher 在 Confirm 后、更新数据库前崩溃会产生重复消息，因此消费端必须幂等。

## 消费与并发

Worker 先读取任务终态和 `nextAttemptAt`，再获取 Redis 全局许可。数据库通过条件更新写入 `workerId`、`leaseUntil` 并递增 `attemptCount`；未抢到租约的重复消息不会调用 Provider。完成、失败和重新排队都必须匹配当前 `workerId`，防止租约过期的旧 Worker 覆盖新结果。

默认参数：

| 配置 | 默认值 | 含义 |
| --- | ---: | --- |
| `AI_WORKER_CONCURRENCY` | 4 | 单实例固定消费者数 |
| `AI_PROVIDER_GLOBAL_CONCURRENCY` | 12 | Provider 跨实例最大并发 |
| `AI_PROVIDER_REQUESTS_PER_MINUTE` | 60 | Provider 平均速率 |
| `AI_PROVIDER_BURST_CAPACITY` | 4 | 令牌桶突发容量 |
| `AI_WORKER_LEASE_SECONDS` | 300 | 数据库与 Redis 执行租约 |
| `AI_WORKER_MAX_ATTEMPTS` | 4 | 实际 Provider 调用最大次数 |
| `AI_OUTBOX_RETENTION_DAYS` | 7 | 已发布 Outbox 记录保留时间 |

租约必须大于 Provider HTTP 超时和结果落盘所需时间。生产线程数应根据 Provider 并发额度、最大实例数、HTTP 连接池和压测结果调整，不能按 CPU 核数直接推导。

## 重试与恢复

- 明确收到 `429`、HTTP 超时响应或 `5xx` 时按 5、30、120 秒退避。
- 网络连接异常可能发生在 Provider 已接受请求之后，任务标记为 `provider_outcome_unknown`，不盲目重试。
- 重试时间同时写入 `ai_task.nextAttemptAt`；即使原消息在发布重试消息前被重新投递，也不能提前调用 Provider。
- 超过 `leaseUntil` 的 `running` 任务会回到 `queued` 并生成新的 Outbox 事件。
- 坏消息和消费者未处理异常进入 DLQ；达到业务最大尝试次数的任务落为 `failed` 并正常 ACK。

系统承诺 at-least-once 投递，不宣称跨 RabbitMQ、Provider、MinIO 和 MySQL 的 exactly-once。正确性来自 API 幂等键、消息重复消费检查、数据库条件抢占、Worker fencing 和配额事务。

已发布 Outbox 记录按保留期分批清理。Outbox Confirm、任务租约恢复和存储清理运行在独立的多线程调度器中，避免单个外部依赖阻塞全部定时任务。

## 发布与停机

应用关闭时先停止消费者，再等待正在执行的 Worker。`AI_WORKER_SHUTDOWN_AWAIT_SECONDS` 应大于 Provider 超时；仍未 ACK 的消息由 RabbitMQ 重新投递。Redis 许可和数据库租约均有过期时间，进程崩溃不会永久占用容量。
