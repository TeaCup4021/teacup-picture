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

Worker 先读取任务终态和 `nextAttemptAt`，再获取 Redis 全局许可。数据库通过条件更新写入 `workerId`、`leaseUntil`，递增 `attemptCount` 与 `executionToken`；未抢到租约的重复消息不会调用 Provider。完成、失败和重新排队都必须同时匹配当前 `workerId` 与 `executionToken`，防止租约过期的旧 Worker 覆盖新结果。

条件更新同时限制 `attemptCount < AI_WORKER_MAX_ATTEMPTS`。抢占失败时若任务仍为 `queued` 且尝试次数已用尽，Worker 会将该任务终态化为 `failed`（`attempts_exhausted`）并归还配额预占，避免无限重投。

### 租约续期与执行令牌

- `AiTaskLeaseRenewer` 以 `AI_WORKER_LEASE_RENEW_MILLIS` 为周期，按本实例标识批量续租自己持有的全部执行中任务。启动时校验 `renew * 3 <= leaseSeconds * 1000`，不满足直接拒绝启动。
- 续租只是「持有者还在干活」的声明，不能替代执行权校验。每次抢占都会把 `executionToken` 加一，形成单调递增的栅栏令牌（fencing token）；结果落库时令牌对不上即丢弃本次结果，不写库、不改状态。
- 租约在结果落库前丢失时抛出 `AiTaskLeaseLostException`，该分支只 ACK、不调用失败收尾——任务归属已不属于本实例，由恢复服务重新入队。

默认参数：

| 配置 | 默认值 | 含义 |
| --- | ---: | --- |
| `AI_WORKER_CONCURRENCY` | 4 | 单实例固定消费者数 |
| `AI_PROVIDER_GLOBAL_CONCURRENCY` | 12 | Provider 跨实例最大并发 |
| `AI_PROVIDER_REQUESTS_PER_MINUTE` | 60 | Provider 平均速率 |
| `AI_PROVIDER_BURST_CAPACITY` | 4 | 令牌桶突发容量 |
| `AI_WORKER_LEASE_SECONDS` | 300 | 数据库与 Redis 执行租约 |
| `AI_WORKER_LEASE_RENEW_MILLIS` | 90000 | 续租周期，必须满足 `renew * 3 <= leaseSeconds * 1000` |
| `AI_WORKER_MAX_ATTEMPTS` | 4 | 实际 Provider 调用最大次数 |
| `AI_OUTBOX_RETENTION_DAYS` | 7 | 已发布 Outbox 记录保留时间 |
| `AI_QUOTA_RECONCILE_MILLIS` | 600000 | 配额对账周期 |
| `AI_QUOTA_RECONCILE_MIN_AGE_MINUTES` | 360 | 预占超过该时长才纳入对账 |
| `AI_QUOTA_RECONCILE_ZOMBIE_AGE_MINUTES` | 120 | 非终态任务超过该时长视为僵尸任务 |
| `AI_QUOTA_RECONCILE_BATCH_SIZE` | 200 | 单轮对账扫描上限 |

租期由续租器持续延长，因此不要求大于单次 Provider 耗时；但必须大于 Provider HTTP 超时与结果落盘时间之和。生产线程数应根据 Provider 并发额度、最大实例数、HTTP 连接池和压测结果调整，不能按 CPU 核数直接推导。

## 重试与恢复

- 明确收到 `429`、HTTP 超时响应或 `5xx` 时按 5、30、120 秒退避。
- 网络连接异常可能发生在 Provider 已接受请求之后，任务标记为 `provider_outcome_unknown`，不盲目重试。
- 重试时间同时写入 `ai_task.nextAttemptAt`；即使原消息在发布重试消息前被重新投递，也不能提前调用 Provider。
- 超过 `leaseUntil` 的 `running` 任务会回到 `queued` 并生成新的 Outbox 事件。
- 坏消息和消费者未处理异常进入 DLQ；达到业务最大尝试次数的任务落为 `failed` 并正常 ACK。

系统承诺 at-least-once 投递，不宣称跨 RabbitMQ、Provider、MinIO 和 MySQL 的 exactly-once。正确性来自 API 幂等键、消息重复消费检查、数据库条件抢占、Worker fencing 和配额事务。

已发布 Outbox 记录按保留期分批清理。Outbox Confirm、任务租约恢复和存储清理运行在独立的多线程调度器中，避免单个外部依赖阻塞全部定时任务。

## 配额归还的兜底链路

配额采用「预占 → 结算 / 归还」两阶段记账。正常路径由事件驱动（成功结算、失败归还、取消归还、尝试次数耗尽归还），但事件驱动天生覆盖不了三种情况：**进程被强制杀死**、**消息进入死信队列后无人处理**、**收尾分支漏调用**。因此必须有独立于事件的兜底。

- **死信消费者**：`AiTaskDlqService` 消费 `teacup.ai.task.dlq.v1`，把进入死信且仍非终态的任务终态化并归还预占。它使用独立的监听容器（单并发、不占用工作线程池），ACK 后不重新投递，避免死循环。
- **配额对账**：`AiQuotaReconciliationService` 周期扫描「仍未结算且未归还、且创建时间超过 `MIN_AGE`」的任务。已终态的按终态修正（`succeeded` 补结算，其余补归还）；非终态且超过 `ZOMBIE_AGE` 的视为僵尸任务，终态化后归还。正在执行且租约未过期的任务一律跳过。
- 所有归还 / 结算 / 修正都会写入 `ai_task_quota_audit`（`action = settled | released | repaired`），使「额度为什么是这个数」完全可追溯。
- 对账串行化依赖任务行上的 `quotaSettled` / `quotaRefunded` 标记，重复扫描不会重复归还。
- 对账日志输出 `扫描数 / 修正数 / 耗时`。修正数长期不为 0 意味着事件链路存在缺陷，应接入告警。

`AI_QUOTA_RECONCILE_MIN_AGE_MINUTES` 必须大于任务的最长合法生命周期（队列等待 + 执行 + 全部重试 + 重试队列 TTL 合计）。调整重试策略时必须同步复核该值。

## 幂等契约

`Idempotency-Key` 的语义是**一次用户意图，一个键**，不是**一次 HTTP 请求，一个键**：

- 客户端在用户点击提交时生成并缓存该键；所有自动重试（网络超时、手动重试）必须复用同一个键；用户修改内容后重新提交才生成新键。
- 同键 + 请求内容一致 → 返回既有任务（200），不新建、不扣配额。
- 同键 + 请求内容不一致 → `409`，客户端应更换幂等键。
- 新键 + 内容相同 → 正常新建（201）。内容相同不是去重依据，键才是。

本实现的幂等记录**就是任务行本身**：`uk_ai_task_user_idempotency(userId, idempotencyKey)` 一旦占用即永久生效，不存在「若干小时后释放」的窗口。客户端不得复用历史键。这是与「窗口幂等」方案的关键差异，必须在客户端契约中明确。

若客户端在重试时更换了幂等键，服务端无法识别为重复提交，会产生两条任务并扣两次配额。服务端只能靠「短时间内相同内容去重」兜底，那是次优解，责任边界在客户端。

## 取消与计费

取消仅在任务尚未开始调用 AI 服务时（`invocationStarted = 0` 且状态为 `queued`）退还配额。一旦调用已发起，取消会停止结果入库，但配额不予退还——上游成本已经产生。该行为属于产品语义，变更需产品确认。

## 发布与停机

应用关闭时先停止消费者，再等待正在执行的 Worker。`AI_WORKER_SHUTDOWN_AWAIT_SECONDS` 应大于 Provider 超时；仍未 ACK 的消息由 RabbitMQ 重新投递。Redis 许可和数据库租约均有过期时间，进程崩溃不会永久占用容量。
