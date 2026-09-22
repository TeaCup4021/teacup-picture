# AI 图片生成功能实现走读

> 依据 `backend/` 当前代码，核对日期 2026-09-21。所有结论均可回溯到文件与行号。
> 配套文档：`docs/ai-task-execution.md`（设计约定）、`docs/backend-ai-task-reliability.md`（改造规格）、`docs/openapi/m2.yaml`（对外契约）。

---

## 一、定位与边界

这是一个**异步任务型**功能：用户提交生图请求后不等待结果，立即拿到任务 ID，由后台 Worker 拉取 Provider 生成图片，结果落对象存储并登记为一张图库图片。

一句话概括架构：**同步提交（事务内落库 + 事件表）→ 异步投递（事件表轮询发布到消息队列）→ 异步执行（消费者抢占任务 → 全局限流 → 调用 Provider → 结果落盘 → 终态化）→ 多路兜底（恢复扫描 / 死信回收 / 配额对账）**。

明确不做的事：

- 不宣称跨 MySQL、RabbitMQ、Provider、MinIO 的 exactly-once。系统承诺 **at-least-once 投递**，正确性来自幂等键、条件更新抢占、执行令牌栅栏、配额事务。
- 不提供任务的「实时进度百分比」。状态是离散的：排队 / 执行 / 成功 / 失败 / 取消。
- 已发起的 Provider 调用**不可撤销**。取消只能阻止结果入库，不能阻止上游成本发生。

---

## 二、组件清单

| 组件 | 文件 | 职责 |
| --- | --- | --- |
| `M2Controller` | `api/v1/M2Controller.java` | 8 个 REST 端点，鉴权与参数解析 |
| `AiTaskService` | `ai/AiTaskService.java` | 提交、查询、取消、终态化、配额记账、模型配置 |
| `AiTaskOutboxService` | `ai/AiTaskOutboxService.java` | 与任务同事务写事件；租约过期任务重新入队 |
| `AiTaskOutboxPublisher` | `ai/AiTaskOutboxPublisher.java` | 轮询事件表发布消息；清理已发布记录 |
| `AiMessagePublisher` | `ai/AiMessagePublisher.java` | 发布 + **publisher confirm 校验** + 不可路由校验 |
| `AiTaskConsumer` | `ai/AiTaskConsumer.java` | 主队列消费者，手动 ACK，重试转发 |
| `AiTaskRunner` | `ai/AiTaskRunner.java` | 执行主体：抢占、调用、落盘、终态化 |
| `AiExecutionLimiter` | `ai/AiExecutionLimiter.java` | Redis 全局并发闸 + 令牌桶速率限制（4 段 Lua） |
| `AiProviderRegistry` / `AiProvider` / `OpenAiImagesProvider` | `ai/` | Provider 抽象与 OpenAI Images 实现 |
| `AiTaskLeaseRenewer` | `ai/AiTaskLeaseRenewer.java` | 每 90 秒批量续租本实例持有的任务 |
| `AiTaskRecoveryService` | `ai/AiTaskRecoveryService.java` | 每 30 秒扫描过期租约 → 重新入队 |
| `AiTaskDlqService` | `ai/AiTaskDlqService.java` | 死信队列消费者 → 终态化 + 归还配额 |
| `AiQuotaReconciliationService` | `ai/AiQuotaReconciliationService.java` | 每 10 分钟对账悬挂预占（唯一非事件驱动路径） |
| `AiWorkerIdentity` | `ai/AiWorkerIdentity.java` | 进程身份：`实例UUID:线程名` |
| `M1Service` / `PictureStorage` | `api/v1/M1Service.java`、`storage/` | 结果落 MinIO、生成图片记录、失败补偿删除 |

---

## 三、数据模型

### 3.1 `ai_task`（任务主体，`model/entity/AiTask.java`）

分五组字段：

| 分组 | 字段 |
| --- | --- |
| 归属与幂等 | `userId`、`idempotencyKey`（唯一键 `(userId, idempotencyKey)`） |
| 请求参数 | `taskType`、`modelId`、`modelCode`、`provider`、`providerModel`、`prompt`、`ratio`、`quality`、`background`、`outputFormat`、`outputCompression`、`sourcePictureId`、`referencePictureId` |
| 状态机 | `status`、`failureCode`、`failureReason`、`nextAttemptAt`、`startTime`、`finishTime` |
| 结果 | `providerTaskId`、`providerRequestId`、`resultPictureId` |
| 并发控制 | `workerId`、`executionToken`、`leaseUntil`、`attemptCount`、`invocationStarted` |
| 配额记账 | `quotaCost`、`quotaReserved`→`reservedCount` 由额度表承担、`quotaSettled`、`quotaRefunded` |

关键点：**幂等记录就是任务行本身**。同一个 `(userId, idempotencyKey)` 只能存在一条任务，键一旦用过永久占用，没有过期回收。

### 3.2 其余表

| 表 | 结构要点 |
| --- | --- |
| `ai_task_outbox` | `eventId`(UUID)、`taskId`、`status`(pending/failed/published)、`attemptCount`、`nextAttemptAt`、`lockOwner`、`lockUntil`、`publishedAt` |
| `ai_quota_usage` | 唯一键 `(userId, usageDate, taskType)`；`usedCount` + `reservedCount` |
| `ai_task_quota_audit` | V22 新增。`taskId`、`userId`、`taskType`、`quotaCost`、`usageDate`、`action`(settled/released)、`reason` |
| `ai_model` | 能力集与参数白名单（JSON 列）、`quotaCost`、`enabled`、`supportsReference`、`supportsOutputCompression` |

### 3.3 V22 迁移（`db/migration/V22__harden_ai_task_lease.sql`）

- 新增 `executionToken BIGINT NOT NULL DEFAULT 0`（栅栏令牌）
- 新增索引 `idx_ai_task_worker(status, workerId)` —— 支撑按实例批量续租
- 新增索引 `idx_ai_task_quota_pending(quotaSettled, quotaRefunded, createTime, id)` —— 支撑配额对账扫描
- `workerId` 扩容到 `VARCHAR(160)`（容纳 `36 位实例 UUID + ':' + 线程名`）
- 新建 `ai_task_quota_audit`

---

## 四、链路一：提交（同步，`AiTaskService.create:90-124`）

整个方法在**一个事务**内完成，失败全回滚。执行顺序：

1. **幂等键格式校验**：`^[A-Za-z0-9._:-]{8,128}$`（`:92`）
2. **类型与提示词校验**：类型 ∈ {generate, outpaint}，提示词 1–2000 字（`:93-95`）
3. **模型解析与能力校验**：按 `modelCode` 查启用模型；能力集必须含请求类型；比例、清晰度必须在白名单内（`:96-99`）
4. **输出选项归一与互斥校验**（`outputOptions:411-438`）：
   - 背景 ∈ {auto, opaque, transparent}，格式 ∈ {png, jpeg, webp}，且必须在模型白名单内
   - 压缩质量 0–100；PNG 不允许有损压缩；模型不支持压缩时传了压缩值直接拒绝
   - `transparent + jpeg` 互斥
5. **原图与参考图归属校验**（`picture:372-378`）：必须属于当前用户、且未被逻辑删除；扩图任务必须带原图；模型不支持参考图时传了参考图直接拒绝
6. **锁用户行**：`userMapper.lockById(user.getId())`（`:104`）
7. **幂等判定**（`:105-110`）：按 `(userId, key)` 查已有任务
   - 存在且**请求内容一致** → 返回原任务，`created=false`（HTTP 200）
   - 存在但**内容不一致** → `409`，`Idempotency-Key 已用于其他请求`
   - 请求内容比对字段见 `sameRequest:401-410`：类型、模型、提示词、比例、清晰度、归一化后的背景/格式/压缩、两张图片 ID
8. **配额预占**：`reserveQuota`（`:338-343`）—— 先 `ensureRow` 保证当日行存在（懒创建），再 `selectForUpdate` 拿行锁，校验 `used + reserved + cost <= limit`，通过则 `reservedCount += cost`。超限抛 `409`
9. **插入任务**：状态 `queued`、`nextAttemptAt = now`、`invocationStarted = 0`、`quotaCost` 记录本次成本（`:113-121`）
10. **同事务写事件**：`outboxService.enqueue(task.getId())`（`:122`）—— 这是事务性的边界：**任务存在则事件必然存在**

为什么要第 6 步的用户行锁？配额预占本身已有行锁（额度行），但「查幂等 → 预占 → 插任务」这三步跨越了不同的行，需要一把**用户维度的串行化闸门**来避免两个并发请求都判定"幂等键不存在"然后各插一条。代价是单用户提交被串行化。

---

## 五、链路二：投递（事件表 → 消息队列）

**为什么需要事件表**：任务入库（MySQL）与消息发布（RabbitMQ）是两个系统，没有共同事务。如果先入库再发消息，发消息失败就丢任务；先发消息再入库，消息可能指向不存在的任务。**事件表（Outbox 模式）把"要不要发消息"这件事变成数据库的本地事务**，由独立轮询器负责真正投递。

`AiTaskOutboxPublisher.publishDue:32-40`（每 1 秒）：

1. 查询 `status IN (pending, failed)`、`nextAttemptAt <= now`、且锁已释放/超时的记录，`LIMIT 50`
2. 对每条执行 `claim`（`AiTaskOutboxMapper.claim`）：条件更新抢占，条件含 `status` 与锁窗口 → 多实例并发安全，只有一条能拿到
3. 调用 `AiMessagePublisher.publishTask` 发布，消息体只有 `eventId` + `taskId`
4. 发布成功 → 置 `published`；失败 → `attemptCount += 1`、退回 `failed`、退避 `min(60, 2^attempts)` 秒后重试，**无限重试不退化为丢弃**

`AiMessagePublisher.publish:35-58` 的两个关键动作：

- **publisher confirm**：等待 broker 确认，`!confirm.isAck()` 抛异常。没有这个确认，"发布成功"只是写进 TCP 缓冲区
- **不可路由校验**：`correlation.getReturned() != null` 抛异常。交换机收到但没有任何队列绑定，消息会被静默丢弃，必须显式检测

清理任务 `cleanupPublished:42-49` 每小时删除保留期（默认 7 天）之前已发布的事件。

---

## 六、链路三：执行（`AiTaskRunner.run:64-133`）

### 6.1 前置三道拦截（不消耗任何资源）

| 判断 | 处理 |
| --- | --- |
| 任务不存在或已是终态 | 直接 ACK，丢弃消息 |
| `queued` 且 `nextAttemptAt` 未到 | 按剩余时间选退避桶（5/30/120 秒）重新投递，**不调用 Provider** |
| `running` 且租约未过期 | 投递 `task_already_claimed`，5 秒后重试 —— 这是重复消息的第一道去重 |

设计要点：**重复投递是常态，必须便宜地挡在外面**。上面三种情况都发生在任何有效工作之前。

### 6.2 全局限流（`AiExecutionLimiter.tryAcquire:79-104`）

两层限制，都用 Lua 保证原子性：

- **并发闸**：Redis ZSET，成员是 permit token，score 是到期时间戳。每轮先 `ZREMRANGEBYSCORE` 清掉过期成员，再判 `ZCARD >= limit` 则拒绝。默认上限 12
- **令牌桶**：Redis HASH 存 `tokens` 与 `updated`，按时间差补充，默认 60 次/分钟、桶容量 4

顺序上**先拿并发闸再拿速率令牌**，令牌失败时回滚已拿的并发许可（`:91-94`）。任何 Redis 异常都抛 `AiLimiterUnavailableException`，由调用方转成重试，**绝不降级为"无限放行"**。

`Permit` 是 `AutoCloseable`，用 try-with-resources 保证释放。释放失败只记不抛——租约到期是最终安全网。

拿不到许可 → `retry(5, provider_capacity_exhausted)`，回队列而不是原地等待，**避免线程被不可控的等待时间占住**。

### 6.3 原子抢占（`markRunning:173-179` → `AiTaskMapper.claimForExecution`）

```sql
UPDATE ai_task SET status = 'running', invocationStarted = 1,
       attemptCount = attemptCount + 1, executionToken = executionToken + 1,
       workerId = ?, leaseUntil = ?, startTime = COALESCE(startTime, ?)
WHERE id = ?
  AND attemptCount < ?                                   -- 尝试上限，防无限重投
  AND ((status = 'queued' AND (nextAttemptAt IS NULL OR nextAttemptAt <= ?))
    OR (status = 'running' AND leaseUntil IS NOT NULL AND leaseUntil <= ?))
```

四个设计点：

1. **判断与写入在同一条语句里**，所以不需要额外的行级锁——`affectedRows == 1` 就是"抢到了"
2. `attemptCount + 1` 和 `executionToken + 1` 都在 SQL 里自增，**不做读-改-写**，天然无竞态
3. `attemptCount < maxAttempts` 把上限判断也放进 `WHERE`，抢不到且次数耗尽时由调用方终态化
4. 抢占与随后的 `selectById` 包在同一个 `TransactionTemplate` 里（`:174-178`），保证读到的必然是本次抢占后的状态

抢占失败走 `handleClaimFailure:139-147` 分两种：任务已终态 → ACK；`queued` 且尝试耗尽 → `exhaust` 终态化并归还配额；其余 → 5 秒后重试。

### 6.4 调用 Provider（`AiTaskRunner:92-95`）

请求带上全部生成参数，以及源图/参考图的**临时访问 URL**（`providerUrl:167-171`：优先用 `storage.temporaryUrl(objectKey)` 生成签名 URL，拿不到才回落到持久 URL）。

`OpenAiImagesProvider` 的映射关系：

| 内部参数 | 上游参数 |
| --- | --- |
| ratio | `size`：1:1→1024x1024，3:2→1536x1024，2:3→1024x1536 |
| quality | `quality`：hd→high，其余→medium |
| background / outputFormat / outputCompression | 同名参数 |
| — | `n` 固定为 1 |

响应解析**优先取 base64，回落取 URL 两种形态都支持**（`:72-77`）。错误码分两层：HTTP 状态映射（401→auth_failed、403→permission_denied、408/504→timeout、429→rate_limited、其余→http_<status>），上游 `error.code` 优先于映射结果。

### 6.5 结果落盘与终态化（`:96-108`）

顺序是刻意的：

1. **落库前再确认一次租约归属**：`renewLease:181-186` 带令牌条件更新，影响行数为 0 即抛 `AiTaskLeaseLostException`
2. **取消检查**：`isCancelled` → 直接 ACK，不入库（上游成本已发生，但结果丢弃）
3. **启动 MinIO 写入**：`storeResult:149-165` —— 支持 base64（剥掉 `data:` 前缀后解码，扩展名按 contentType 推导）与远程 URL 导入（`importUrl`）两种形态；base64 解码失败抛 `provider_invalid_image`
4. **登记为图片**：`m1Service.saveGeneratedPicture` —— 走 M1 的带补偿写入路径，创建 Picture 记录、更新空间计数、生成缩略图
5. **终态化**：`complete:188-207` 在事务内 `SELECT ... FOR UPDATE`，校验三件事——状态仍是 `running`、`workerId` 匹配、`executionToken` 匹配 —— 然后置 `succeeded`、写 `resultPictureId`、调用 `settleQuota` 把预占转为已用
6. **补偿**：`complete` 返回 false（说明执行权已丢失）→ `discardGeneratedPicture` 反向删除刚写入的图片与对象

**第 5 步的三重校验是整条链路的正确性支点**：它保证"只有一个执行者能写成功"，且"只有仍持权的那一个"。

### 6.6 异常分支（`:109-132`）

| 异常 | 处理 |
| --- | --- |
| `AiTaskLeaseLostException` | 只记日志后 ACK。**不调用 `fail`** —— 任务归属已属他人，本实例无权改状态 |
| `AiProviderException` 且可重试（429 / 超时 / 5xx）且未达上限 | `retry` 回到 `queued`，退避 5→30→120 秒，并转发一条延迟消息 |
| `AiProviderException` 且 403 | 顺带把该模型置为禁用（`disableModel`），避免全员持续失败 |
| 网络异常（无法判断上游是否已受理） | 错误码改写为 `provider_outcome_unknown`，**不自动重试**，直接失败，避免重复计费 |
| 其余 `RuntimeException` | `fail(..., result_persistence_failed)` |

`retry:231-244` 也带令牌与执行者校验，且**不改动 `invocationStarted`**（保持 1），这样后续取消能正确判定"已发起过调用"。

### 6.7 消费者（`AiTaskConsumer.consume:25-53`）

- `MANUAL` ACK 模式，`defaultRequeueRejected=false`
- 消息体解析失败 → `basicReject(requeue=false)` → 进死信队列
- 收到 `ExecutionResult.retry` → **先发布延迟重试消息，再 ACK**。如果发布失败，则 `basicNack(requeue=true)` 把原消息退回队列。**顺序不能反**：先 ACK 再发布，发布失败就永久丢任务
- 其他异常 → `basicReject(requeue=false)` → 进死信队列

### 6.8 队列拓扑（`config/AiRabbitConfig.java`）

- 全部队列为 **quorum 队列**（多数派持久化，比经典镜像队列更稳）
- 主队列 + 三个 TTL 延迟队列（5s / 30s / 120s），延迟队列不设消费者，靠 `x-dead-letter-exchange` 到期回投主队列 —— **用队列 TTL 实现延迟，不需要额外插件**
- 主队列与延迟队列都设了 `x-dead-letter-strategy: at-least-once` + `overflow: rejectPublish`，队列写不下时拒绝发布而不是静默丢弃
- 死信队列独立，**专用容器工厂 `aiDlqListenerContainerFactory`：单并发、不占用工作线程池**

消费者并发 = 工作线程数 = 4，预取数 = 1。Spring AMQP 的预取数是**每消费者**语义，4 个消费者各预取 1 条 → 在途 4 条，与 4 个线程严格对齐。工作线程池 `queueCapacity=0` + `AbortPolicy`，线程满时直接拒绝，靠预取数保证不会溢出。

---

## 七、状态机

```
                    ┌──────────────────────────────────────┐
                    │                                      │
   [提交]           │  租约过期 / 可重试异常                  │
     │              ↓                                      │
     └──→ queued ────┴──→ running ──┬──→ succeeded          │
           ↑                │       │                       │
           │                │       ├──→ failed  ←──────────┘
           └────────────────┘       │      （重试上限 / 死信 / 兜底）
              （重试回投）            └──→ cancelled
```

终态集合：`succeeded` / `failed` / `cancelled`（`AiTaskService.terminal:453`）。

状态流转的**唯一入口**都在 `AiTaskService`，且都带归属校验（`ownedBy:455-458`：状态 + 执行者 + 令牌三者同时匹配）：

| 方法 | 触发者 | 归属校验 |
| --- | --- | --- |
| `fail` | Worker | 需要 |
| `retry` | Worker | 需要 |
| `complete` | Worker | 需要（在 `AiTaskRunner` 内联实现） |
| `exhaust` | 抢占失败者 / 死信消费者 / 对账 | **不需要**（此时任务已无有效持有者） |
| `reconcile` | 对账任务 | **不需要**（同上） |
| `cancel` | 用户 | 按 `userId` 校验，走 `FOR UPDATE` |

---

## 八、七道可靠性防线

| # | 防线 | 防的问题 | 实现位置 |
| --- | --- | --- | --- |
| 1 | 事件表（Outbox） | 入库成功但消息丢失 | `AiTaskOutboxService.enqueue` |
| 2 | Publisher Confirm + 不可路由检测 | "发布成功"是假象 | `AiMessagePublisher.publish` |
| 3 | 条件更新抢占 + 尝试上限 | 同一任务被多 Worker 同时执行 / 无限重投 | `claimForExecution` |
| 4 | 租约 + 周期续租 | 进程崩溃后任务永久卡在 `running`；存活者被误判死亡 | `AiTaskLeaseRenewer`、`AiTaskRecoveryService` |
| 5 | 执行令牌（栅栏） | 租约失效的旧 Worker 回来后覆盖新结果 | `executionToken` 三处校验 |
| 6 | 死信消费者 | 消息进死信后静默悬挂、配额永久泄漏 | `AiTaskDlqService` |
| 7 | 配额对账 | 进程被强杀，事件路径整体失效 | `AiQuotaReconciliationService` |

**租约机制的两个前提**（缺一个就比不加更糟）：

1. **必须续租**：`AiTaskLeaseRenewer` 每 90 秒用一条 `UPDATE ... WHERE status='running' AND workerId LIKE '实例UUID:%'` 批量推进本实例全部任务。构造期强校验 `续租周期 × 3 ≤ 租期`（`AiTaskLeaseRenewer:28-32`），参数配错**直接拒绝启动**
2. **续租周期远小于租期**：租期 300 秒、续租 90 秒 → 容忍连续 2 次续租失败

**令牌为什么不等于执行者标识**：同一个执行者（同一实例同一线程）可能先后两次被派到同一个任务，标识相同但属于两段不同的执行。递增令牌才能区分。

**恢复服务与续租的分工**：`AiTaskRecoveryService`（每 30 秒）扫 `status='running'` 且租约已过期（或没有租约但 `startTime` 超时）的任务，重置回 `queued` 并写一条新事件。它只在**续租真的失败时**才会命中，是兜底而非主路径。

---

## 九、配额：两阶段记账

### 9.1 记账模型

| 动作 | `usedCount` | `reservedCount` |
| --- | --- | --- |
| 提交任务 | — | **+cost** |
| 执行成功 | **+cost** | **−cost** |
| 失败 / 归还 | — | **−cost** |

可用额度 = `limit − usedCount − reservedCount`（`quotaView:362-369`）。

**为什么要预占而不是成功后再扣**：`检查-使用` 之间存在时间窗口，10 个并发请求会同时读到"已用 5 < 100"然后全部放行，形成超卖。预占把这个窗口消掉。

### 9.2 统一收尾入口（`finalizeQuota:313-324`）

所有终态流转必须走同一个方法，**幂等**（靠任务上的 `quotaSettled` / `quotaRefunded` 标记），且每次都写一条审计记录：

| 终态 | 处理 | 审计 `action` | 审计 `reason` |
| --- | --- | --- | --- |
| `succeeded` | 结算（预占转已用） | `settled` | 由调用方传入 |
| `failed` / `exhaust` | 归还预占 | `released` | `failed_<code>` / `exhausted_from_<status>` |
| 取消（未发起调用） | 归还预占 | `released` | `cancelled_before_invocation` |
| 取消（已发起调用） | 结算（成本已产生） | `settled` | `cancelled_after_invocation` |
| 对账补结算 | 结算 | `settled` | `settled_missing` |
| 对账补归还 / 僵尸回收 | 归还 | `released` | `release_missing` / `zombie_task` |

审计表是排查配额的**唯一可查证据**：额度对不上时，`WHERE taskId = ?` 就能看出这笔账是谁在什么原因下动过。

### 9.3 额度日期归属（`usageDate:357-360`）

任务的额度日期由 `createTime` 决定（按 UTC 解释后转换到 `quotaZone`，默认 `Asia/Shanghai`），**不由结算时刻决定**。否则跨零点执行的任务会把额度记到第二天。

### 9.4 对账（`AiQuotaReconciliationService`）

每 10 分钟扫一批 `quotaSettled=0 AND quotaRefunded=0 AND createTime < now − 360 分钟` 的任务（走 `idx_ai_task_quota_pending`），逐个调用 `reconcile:198-228`：

- 任务已终态 → 按终态补结算/补归还（说明收尾漏了）
- 任务 `running` 且租约未过期 → **跳过**，不误伤正在执行的任务
- 任务创建时间未超 `zombieAgeMinutes`（120 分钟）→ 跳过
- 其余（僵尸任务）→ 置 `failed`，code `reconcile_zombie`，归还预占

`minAgeMinutes`（360）必须大于任务的**最长完整生命周期**（排队 + 执行 + 全部重试）；`zombieAgeMinutes`（120）必须大于租期（300 秒）与 Provider 超时（120 秒）。修复数长期不为 0 意味着事件链路有缺陷，是应当告警的指标。

---

## 十、幂等契约（`docs/openapi/m2.yaml`）

| 情形 | 行为 |
| --- | --- |
| 同键 + 内容一致 | 返回原任务，HTTP 200，**不新建、不扣额度** |
| 同键 + 内容不一致 | **409 冲突**，提示客户端更换幂等键 |
| 新键 + 内容一致 | 放行，新建任务（这是新的用户意图） |

契约的**根因层要求**：幂等键属于「一次用户意图」，不属于「一次 HTTP 请求」。客户端在用户点击提交时生成键并缓存在内存，所有自动重试复用同一个键；只有用户改了内容重新提交才生成新键。这是 Stripe / 支付宝的标准契约。

**后端做不到让客户端守规矩**。若客户端每次重试都换新键，后端会真实创建多条任务并多次计费 —— 这是契约违背，不是后端缺陷。

幂等记录的保留期是**永久的**（记录即任务行）。这与常见的"窗口幂等"不同，必须在客户端契约中显式声明。

---

## 十一、取消语义（`cancel:141-156`）

1. `FOR UPDATE` 读取，校验归属（非本人 → 404）
2. 仅 `queued` / `running` 可取消，其余 → 409
3. 判定「是否已发起过调用」：`queued && invocationStarted != 1` → 未发起
4. 置 `cancelled`，清空 `workerId` / `leaseUntil`（**注意 `executionToken` 不重置**，Worker 后续带旧令牌写入必然失败，形成自然栅栏）
5. 未发起 → 归还预占；已发起 → 结算（上游成本已产生）

Worker 侧的配合：`run` 在结果入库前检查 `isCancelled`，命中则丢弃结果并 ACK。

**诚实说明边界**：取消无法中断已经发出的 Provider HTTP 请求，也无法取回已计费的上游成本。这是产品语义的选择，已写入文档待产品最终确认。

---

## 十二、配置参数

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `AI_WORKER_CONCURRENCY` | 4 | 消费者数 = 工作线程数 |
| `AI_WORKER_LEASE_SECONDS` | 300 | 任务租约时长 |
| `AI_WORKER_LEASE_RENEW_MILLIS` | 90000 | 续租周期（须满足 `×3 ≤ 租期`） |
| `AI_WORKER_MAX_ATTEMPTS` | 4 | 最大执行尝试次数 |
| `AI_PROVIDER_GLOBAL_CONCURRENCY` | 12 | 跨实例 Provider 并发上限 |
| `AI_PROVIDER_REQUESTS_PER_MINUTE` | 60 | Provider 平均速率 |
| `AI_PROVIDER_BURST_CAPACITY` | 4 | 令牌桶容量 |
| `AI_RUNNING_TIMEOUT_MINUTES` | 15 | 无租约的老任务判死阈值 |
| `AI_GENERATE_DAILY_QUOTA` / `AI_OUTPAINT_DAILY_QUOTA` | 100 / 100 | 每用户每日额度 |
| `AI_QUOTA_RECONCILE_MILLIS` | 600000 | 对账周期 |
| `AI_QUOTA_RECONCILE_MIN_AGE_MINUTES` | 360 | 对账扫描最小年龄 |
| `AI_QUOTA_RECONCILE_ZOMBIE_AGE_MINUTES` | 120 | 僵尸任务判死年龄 |
| `AI_OUTBOX_POLL_MILLIS` | 1000 | 事件表轮询周期 |
| `AI_OUTBOX_RETENTION_DAYS` | 7 | 已发布事件保留期 |
| `teacup.ai.openai.timeout-ms` | 120000 | Provider HTTP 超时 |

**参数之间的关系必须成立**（不是各自拍的数字）：

- `租期 > Provider HTTP 超时`：300 > 120 ✓
- `续租周期 × 3 ≤ 租期`：90 × 3 = 270 ≤ 300 ✓（构造期强制校验）
- `对账最小年龄 > 任务最长生命周期`
- `僵尸判死年龄 > 租期`

**吞吐量核算**（Little's Law：吞吐 = 并发 ÷ 单任务耗时）：

- 4 并发 × 单任务 30 秒 → **8 任务/分钟**
- 每日产能 = 8 × 60 × 24 = **11520 任务**
- 配额上限 = 100 次 × 活跃用户数。100 个活跃用户用满即 10000 任务，**容量利用率 87%**

结论：当前配置下，活跃用户超过约 115 人（或单任务耗时上升）时队列必然积压，等待时间线性增长。**配额设计未做过容量校验**，这是已知的产品级边界。

---

## 十三、面试口径

### 能主动讲出来的六个设计点

1. **为什么用事件表而不是直接发消息**：跨系统无共同事务，用数据库本地事务把"要不要发消息"持久化，把分布式问题降级成"轮询本地表"。
2. **为什么条件更新就够了、不需要行级锁**：判断条件与写入动作写进了同一条 SQL 的 `WHERE`，`affectedRows` 即仲裁结果。反例是配额预占 —— 那是"读-判断-写"跨语句的复合逻辑，必须靠行锁。
3. **为什么需要执行令牌**：租约只能推断"大概率已死"，无法证明。老执行者随时可能回来写结果，必须由接收方校验令牌来拒绝迟到者。这就是 fencing token。
4. **为什么所有归还路径要收敛到一个方法**：三个终态出口各写一遍必然漏，未来加新出口还会漏，用统一入口 + 状态幂等闸门从结构上消除。
5. **为什么连续两个"看似矛盾"的数字是对齐的**：预取数 1 × 消费者 4 = 在途 4 = 工作线程 4。Spring AMQP 预取数是每消费者语义。
6. **配额为什么要先预占**：消除"检查-使用"竞态；代价是引入"占了没还"的残留，必须配对账兜底。

### 已知边界（主动说出来比被问出来好）

- 队列吞吐与配额设计未做容量校验，当前配置对应约 100 活跃用户的天花板。
- 取消不中断已发出的 Provider 调用，已发生的上游成本照常计入。
- 幂等键保留期永久，`ai_task` 表随用户量线性增长，未做归档策略。
- `usageDate` 依据 `createTime` 而非结算时刻，跨零点任务的额度归属对用户可感知。
- 完整链路（RabbitMQ + Provider）依赖外部服务，本地 `@Tag("integration")` 上下文测试默认排除，需在预发环境补端到端验证。
