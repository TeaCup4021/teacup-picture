# AI 任务可靠性改造规格书

> 面向实施者（codex）。本文件描述要改什么、为什么改、改成什么样、怎么验收。
> 所有结论均已对照当前代码核实，文件路径与行号为核实时的真实值。

## 0. 背景与范围

当前 AI 生图任务链路（提交 → 排队 → 抢占执行 → 结果入库 → 配额结算）已经具备事务性发件箱、
Quorum 队列、分级重试队列、死信队列、租约抢占等基础设施，整体设计方向正确。

本规格书处理三个残留可靠性缺口：

1. **租约续期时机错误** —— 长耗时调用期间租约会过期，导致同一任务被重复执行，产生重复的付费 AI 调用
2. **配额预占缺少兜底归还** —— 所有归还路径都是事件驱动，进程被强制杀死或消息进入死信队列后预占永久泄漏
3. **幂等键契约未文档化，且提交路径存在热点行锁** —— 行为正确但缺少契约约束与并发优化

**非目标（本次不改）**：缓存层、图片上传链路、前端。

---

## 1. 现状核实结论

### 1.1 已正确、**禁止改动**的部分

实施者必须完整阅读本节。以下均为核实后的正确实现，如被"顺手优化"会引入回归。

| 项 | 位置 | 结论 |
|---|---|---|
| 预取数与消费并发 | `config/AiRabbitConfig.java:130-132` | **正确，禁止改动**。Spring AMQP 的 `prefetchCount` 是 **per-consumer** 语义。`concurrentConsumers=4` 会创建 4 个消费者，每个预取 1 条，合计 4 条在途，与 `aiWorkerExecutor` 的 4 个线程精确匹配。把 `prefetchCount` 调大 **不会** 提升吞吐，只会让第 5 条消息找不到线程而触发 `AbortPolicy` 抛出 `RejectedExecutionException` |
| 线程池容量 | `config/AiRabbitConfig.java:103-117` | `queueCapacity=0` + `AbortPolicy` 是刻意的背压设计，与上一条配套。禁止单独调整其中任一参数 |
| 幂等键唯一约束 | `V6__harden_m2_ai_workflow.sql:7` | `UNIQUE KEY uk_ai_task_user_idempotency (userId, idempotencyKey)` 正确 |
| 幂等语义 | `ai/AiTaskService.java:99-105` | 同键同内容 → 返回原任务；同键异内容 → 409 冲突。行为正确，无需修改逻辑 |
| 状态机 CAS 抢占 | `mapper/AiTaskMapper.java:11-17` | 条件更新 + 允许接管过期租约，写法正确 |
| 发件箱重试 | `ai/AiTaskOutboxPublisher.java:32-72` | `pending`/`failed` 无限重试、退避封顶 60s、只清理 `published` 且超 7 天的行。正确 |
| 终态归属校验 | `ai/AiTaskService.java:155,170`、`ai/AiTaskRunner.java:169-170` | `fail`/`retry`/`complete` 均校验 `workerId`，写法正确（见 1.2-D3 的加固说明） |

### 1.2 确认存在的缺陷

| 编号 | 缺陷 | 位置 | 严重度 |
|---|---|---|---|
| **D1** | 续租只在阻塞调用返回之后执行一次，调用期间的租约无人维护 | `ai/AiTaskRunner.java:90-94`、`:158-163` | **高**（重复付费调用） |
| **D2** | 租约丢失抛出的 `IllegalStateException` 被泛化 catch 吞掉，错误码被写成 `result_persistence_failed`，误导排查 | `ai/AiTaskRunner.java:120-123` | 中 |
| **D3** | 无单调递增的执行令牌，终态归属依赖 `workerId`；而 `workerId = 实例UUID + ":" + 线程名`，其可辨识性依赖"线程名恰好不同"这一偶然条件 | `ai/AiTaskMapper.java`、`AiTaskRunner.java:165-183` | 中 |
| **D4** | 抢占不校验 `attemptCount` 上限，反复租约过期可导致无限重投 | `mapper/AiTaskMapper.java:11-17` | **高** |
| **D5** | 消费者异常时 `basicReject(requeue=false)` 进死信队列，任务留在 `queued` 且发件箱行已是 `published`，无任何恢复路径；若该任务尚未终态，配额预占永久泄漏 | `ai/AiTaskConsumer.java:49-52` | **高** |
| **D6** | 无配额对账任务。所有归还路径（`settleQuota`/`releaseReservation`）都是事件驱动，缺少扫描兜底 | 全链路 | **高** |
| **D7** | 取消 `running` 任务会执行 `settleQuota`（即计费）。语义可辩，但需产品确认并写入接口文档 | `ai/AiTaskService.java:145-147` | 低 |
| **D8** | 提交路径用 `userMapper.lockById` 串行化同一用户的全部提交，锁粒度粗 | `ai/AiTaskService.java:99` | 低（并发优化） |

### 1.3 D1 的故障时序（核实过程）

```
T+0min    AiTaskRunner.markRunning → claimForExecution，leaseUntil = now + 300s
T+0min    providerRegistry...execute(...)  ← 阻塞调用，可能持续数分钟到数十分钟
T+5min    租约到期。此时 execute 仍未返回，renewLease 尚未被调用
T+5.5min  AiTaskRecoveryService.recoverExpiredLeases 扫到该任务
          → AiTaskOutboxService.recoverExpiredTask 重置为 queued 并重新入队
T+6min    另一个工作线程抢到同一任务，开始第二次 AI 调用
T+nmin    原执行返回，renewLease 返回 0 → 抛 IllegalStateException
          → 被 catch (RuntimeException) 捕获 → fail() 因 workerId 不匹配而返回 false
          本次 AI 调用已实际发生且已计费，但结果被丢弃
```

**实际后果**（不含夸大）：重复一次付费 AI 调用；配额只结算一次；结果只落一份。
**不是**重复扣配额，也**不是**双写结果——`complete`/`fail`/`retry` 的 `workerId` 校验挡住了这两点。
D1 的损失是**重复的付费外部调用**。

---

## 2. 改造方案

### 阶段一：租约（D1、D2、D3、D4）

#### 1.1 数据库迁移 —— 新增 `V22__harden_ai_task_lease.sql`

```sql
ALTER TABLE `ai_task`
    ADD COLUMN `executionToken` BIGINT NOT NULL DEFAULT 0 AFTER `workerId`;

ALTER TABLE `ai_task`
    ADD KEY `idx_ai_task_worker` (`status`, `workerId`),
    ADD KEY `idx_ai_task_quota_pending` (`quotaSettled`, `quotaRefunded`, `createTime`, `id`);

ALTER TABLE `ai_task`
    MODIFY COLUMN `workerId` VARCHAR(160) NULL;

CREATE TABLE `ai_task_quota_audit` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `taskId` BIGINT NOT NULL,
    `userId` BIGINT NOT NULL,
    `taskType` VARCHAR(32) NOT NULL,
    `quotaCost` INT NOT NULL,
    `usageDate` DATE NOT NULL,
    `action` VARCHAR(32) NOT NULL,
    `reason` VARCHAR(128) NOT NULL,
    `createTime` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ai_task_quota_audit_task` (`taskId`, `id`),
    KEY `idx_ai_task_quota_audit_time` (`createTime`, `id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
```

`action` 取值：`settled` | `released` | `repaired`。
`executionToken` 为迁移新增列，存量行默认为 0，无需回填。

#### 1.2 `model/entity/AiTask.java`

新增字段 `private Long executionToken;`，类型与 `workerId` 同级。

#### 1.3 `mapper/AiTaskMapper.java`

**改动 1：抢占时递增令牌并校验尝试次数上限**

```java
@Update("UPDATE ai_task SET status = 'running', invocationStarted = 1, "
        + "attemptCount = attemptCount + 1, executionToken = executionToken + 1, "
        + "workerId = #{workerId}, leaseUntil = #{leaseUntil}, "
        + "startTime = COALESCE(startTime, #{now}) "
        + "WHERE id = #{taskId} AND attemptCount < #{maxAttempts} "
        + "AND ((status = 'queued' AND (nextAttemptAt IS NULL OR nextAttemptAt <= #{now})) "
        + "OR (status = 'running' AND leaseUntil IS NOT NULL AND leaseUntil <= #{now}))")
int claimForExecution(@Param("taskId") long taskId, @Param("workerId") String workerId,
                      @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil,
                      @Param("maxAttempts") int maxAttempts);
```

**改动 2：续租携带令牌**

```java
@Update("UPDATE ai_task SET leaseUntil = #{leaseUntil} "
        + "WHERE id = #{taskId} AND status = 'running' "
        + "AND workerId = #{workerId} AND executionToken = #{executionToken}")
int renewLease(@Param("taskId") long taskId, @Param("workerId") String workerId,
               @Param("executionToken") long executionToken,
               @Param("leaseUntil") LocalDateTime leaseUntil);
```

**改动 3：按实例前缀批量续租（新增）**

```java
@Update("UPDATE ai_task SET leaseUntil = #{leaseUntil} "
        + "WHERE status = 'running' AND workerId LIKE CONCAT(#{instanceId}, ':%')")
int renewLeasesByInstance(@Param("instanceId") String instanceId,
                          @Param("leaseUntil") LocalDateTime leaseUntil);
```

> 说明：`workerId` 的格式为 `实例UUID:线程名`，实例 UUID 固定 36 字符，前缀匹配安全。
> 若实施者认为 `LIKE` 前缀匹配不理想，可改为新增 `workerInstance CHAR(36)` 列并直接等值匹配，
> 但不得改为 `LIKE '%...%'` 或任何无法走索引的写法。

#### 1.4 新增组件 `ai/AiTaskLeaseRenewer.java`

职责：在任务执行期间周期性地为**本实例持有的所有执行中任务**续租。

```java
@Slf4j
@Component
public class AiTaskLeaseRenewer {
    private final AiTaskMapper taskMapper;
    private final long leaseSeconds;
    private final String instanceId;

    // instanceId 必须与 AiTaskRunner 使用同一个值：
    // 建议抽出 AiWorkerIdentity 单例组件，供 AiTaskRunner 与本类共同注入，
    // 避免两处各自 UUID.randomUUID() 导致前缀不一致。

    @Scheduled(fixedDelayString = "${teacup.ai.worker.lease-renew-millis:90000}")
    public void renew() {
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(leaseSeconds);
        int renewed = taskMapper.renewLeasesByInstance(instanceId, leaseUntil);
        if (renewed > 0) log.debug("Renewed {} AI task leases, instanceId={}", renewed, instanceId);
    }
}
```

**关键约束**

- `instanceId` 必须与 `AiTaskRunner` 中生成 `workerId` 所用的实例 UUID **完全一致**。
  推荐做法：新增 `ai/AiWorkerIdentity.java`，暴露 `String instanceId()` 与 `String workerId()`，
  `AiTaskRunner` 与 `AiTaskLeaseRenewer` 共同注入，删除 `AiTaskRunner` 里私有的 `workerInstanceId` 字段。
- 默认续租间隔 `90000ms`，即租期（默认 300s）的三分之一。实施者必须保证
  `lease-renew-millis * 3 <= lease-seconds * 1000`，并在启动时校验，不满足则抛异常阻止启动。
- 续租失败的容错：本方法不抛异常。连续两轮续租返回 0 行意味着本实例已无持有任务，属正常。
  与数据库通信异常时记 warn 并让下一轮重试，**不要**在这里改变任何任务状态。

#### 1.5 `ai/AiTaskRunner.java` 改造

**改动 1：`markRunning` 传入尝试上限**

```java
if (taskMapper.claimForExecution(taskId, workerId, now, now.plusSeconds(leaseSeconds), maxAttempts) != 1) {
    return null;
}
```

**改动 2：抢占失败时识别"尝试次数耗尽"并终态化（配合 D4）**

`run(long taskId)` 中，`markRunning` 返回 `null` 的分支当前逻辑为：

```java
AiTask current = taskMapper.selectById(taskId);
return current == null || terminal(current.getStatus())
        ? ExecutionResult.ack() : ExecutionResult.retry(5, "task_claim_conflict");
```

改为：当 `current` 非空、状态为 `queued`、且 `attemptCount >= maxAttempts` 时，
调用新增的 `taskService.exhaust(taskId)` 将其置为 `failed` 并归还预占，然后 `ack`。

**改动 3：新增终态化方法 `AiTaskService.exhaust(long taskId)`**

```java
@Transactional(rollbackFor = Exception.class)
public boolean exhaust(long taskId) {
    AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>()
            .eq(AiTask::getId, taskId).last("FOR UPDATE"));
    if (task == null || terminal(task.getStatus())) return false;
    task.setStatus("failed");
    task.setFailureCode("attempts_exhausted");
    task.setFailureReason("AI 任务重试次数已用尽");
    task.setFinishTime(LocalDateTime.now());
    task.setWorkerId(null);
    task.setLeaseUntil(null);
    if (!Integer.valueOf(1).equals(task.getQuotaSettled())
            && !Integer.valueOf(1).equals(task.getQuotaRefunded())) {
        releaseReservation(task);
        task.setQuotaRefunded(1);
    }
    taskMapper.updateById(task);
    return true;
}
```

**改动 4：租约丢失用独立异常类型（D2）**

新增 `ai/AiTaskLeaseLostException.java`：

```java
public class AiTaskLeaseLostException extends RuntimeException {
    public AiTaskLeaseLostException(String message) { super(message); }
}
```

`renewLease` 改为抛出该类型：

```java
private void renewLease(long taskId, String workerId, long executionToken) {
    LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(leaseSeconds);
    if (taskMapper.renewLease(taskId, workerId, executionToken, leaseUntil) != 1) {
        throw new AiTaskLeaseLostException("AI task execution lease was lost, taskId=" + taskId);
    }
}
```

在 `run` 中于 `catch (AiProviderException)` **之前** 增加专门分支：

```java
} catch (AiTaskLeaseLostException exception) {
    log.warn("AI task lease lost before result persistence, taskId={}", taskId);
    return ExecutionResult.ack();
}
```

> 语义：租约已归属他人，本实例结果作废。任务已由 `recoverExpiredTask` 重新入队，无需本实例再做任何事。
> **不要**在这里调用 `fail`，因为该任务的归属已不属于本实例。

**改动 5：`complete` / `renewLease` 调用点携带令牌**

`markRunning` 返回的 `task` 已包含新令牌，将其透传：

```java
AiTask task = markRunning(taskId, workerId);
...
long token = task.getExecutionToken();
...
renewLease(taskId, workerId, token);
...
if (!complete(taskId, workerId, token, result, picture.getId())) {
    m1Service.discardGeneratedPicture(picture);
}
```

**改动 6：`complete` 增加令牌校验（D3）**

```java
public boolean complete(long taskId, String workerId, long executionToken,
                        AiProviderResult result, long pictureId) {
    Boolean completed = transactionTemplate.execute(status -> {
        AiTask task = taskMapper.selectOne(new LambdaQueryWrapper<AiTask>()
                .eq(AiTask::getId, taskId).last("FOR UPDATE"));
        if (task == null || !"running".equals(task.getStatus())
                || !Objects.equals(workerId, task.getWorkerId())
                || !Objects.equals(executionToken, task.getExecutionToken())) return false;
        ...
    });
    return Boolean.TRUE.equals(completed);
}
```

`AiTaskService.fail` 与 `AiTaskService.retry` 同样增加 `executionToken` 参数并纳入校验条件。
调用方 `AiTaskRunner` 的 `catch (AiProviderException)` 分支需相应透传令牌。

> **重要**：`AiTaskService.fail` 的现有签名 `fail(long, String, String, String)` 被 `AiTaskRunner:118` 使用；
> `retry` 的现有签名被 `AiTaskRunner:112` 使用。改动签名时同步更新全部调用点，不要保留重载。

#### 1.6 新增配置项

```yaml
teacup:
  ai:
    worker:
      lease-seconds: 300              # 已存在
      lease-renew-millis: 90000       # 新增，必须满足 renew*3 <= leaseSeconds*1000
      max-attempts: 4                 # 已存在
```

---

### 阶段二：配额预占与兜底归还（D5、D6）

#### 2.1 新增 `ai/AiTaskDlqService.java` —— 死信队列监控

职责：消费死信队列，让进入死信的任务有明确归宿，而不是静默悬挂。

```java
@Slf4j
@Component
public class AiTaskDlqService {
    // 注入 AiTaskMapper、AiTaskService、AiTaskOutboxService

    @RabbitListener(queues = AiRabbitConfig.DEAD_LETTER_QUEUE,
                    containerFactory = "aiRabbitListenerContainerFactory")
    public void onDeadLetter(AiTaskMessage payload, Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            long taskId = Long.parseLong(payload.taskId());
            AiTask task = taskMapper.selectById(taskId);
            if (task == null || terminal(task.getStatus())) {
                channel.basicAck(deliveryTag, false);
                return;
            }
            log.error("AI task routed to DLQ, taskId={}, status={}, attempts={}",
                    taskId, task.getStatus(), task.getAttemptCount());
            // 终态化并归还预占；不重新投递，避免死循环
            taskService.exhaust(taskId);
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException exception) {
            log.error("Failed to handle AI DLQ message", exception);
            channel.basicReject(deliveryTag, false);
        }
    }
}
```

**约束**

- 使用**独立的监听容器工厂或至少独立的并发度**，避免死信处理占用 `aiWorkerExecutor` 的 4 个任务线程。
  推荐新增 `aiDlqListenerContainerFactory`：`concurrentConsumers=1`、`prefetchCount=1`、`AcknowledgeMode.MANUAL`。
- 死信队列当前无消费者（`AiRabbitConfig.java:69-71` 仅声明队列），本组件是其首个消费者。
- 若确认为可重试场景，可改为重新 `outboxService.enqueue(taskId)` 而非终态化，但**必须先做尝试次数上限判断**，否则构成无限循环。

#### 2.2 新增 `ai/AiQuotaReconciliationService.java` —— 配额对账兜底

职责：唯一不依赖事件、能在进程被强制杀死后修复状态的手段。

```java
@Slf4j
@Component
public class AiQuotaReconciliationService {

    @Scheduled(initialDelay = 60_000L, fixedDelayString = "${teacup.ai.quota.reconcile-millis:600000}")
    public void reconcile() {
        // 步骤见下
    }
}
```

**扫描一：预占悬挂（主扫描）**

```
选取条件：quotaSettled = 0 AND quotaRefunded = 0 AND createTime < now - reconcileMinAge
批量上限：LIMIT 200，按 id 升序
```

对每个任务按状态分派：

| 任务状态 | 处理 |
|---|---|
| `succeeded` | `settleQuota(task)`，审计 `action=repaired, reason=settled_missing` |
| `failed` / `cancelled` | `releaseReservation(task)` + 置 `quotaRefunded=1`，审计 `reason=release_missing` |
| `queued` / `running` 且 `createTime < now - zombieAge` | 先 `exhaust(taskId)` 终态化，再归还预占，审计 `reason=zombie_task` |
| `queued` / `running` 但未超 `zombieAge` | 跳过，属于正常生命周期 |

**扫描二：计数漂移修复（可选，建议在扫描一验证稳定后再开）**

按 `(userId, usageDate, taskType)` 重新计算：

```
期望 reservedCount = SUM(quotaCost) FROM ai_task
   WHERE userId=? AND taskType=? AND 该任务归属日 = usageDate
     AND quotaSettled = 0 AND quotaRefunded = 0 AND status IN ('queued','running')
```

与 `ai_quota_usage.reservedCount` 比对，不一致时以计算值覆盖，并写审计记录。
**注意**：任务归属日的计算必须复用 `AiTaskService.lockedUsage` 的既有逻辑
（`createTime` 按 UTC 解释后转换到 `quotaZone`），不得另写一套时区转换。

**强制约束**

- 本服务对每个任务的每次修正，都必须写入 `ai_task_quota_audit`。
- 本服务自身必须幂等：靠任务上的 `quotaSettled`/`quotaRefunded` 标记判定，重复扫描不得重复归还。
- 本服务**不得**修改处于 `running` 且 `leaseUntil` 未过期的任务。
- 每次执行输出一条汇总日志：`扫描数 / 修正数 / 耗时`。修正数长期不为 0 是链路存在缺陷的信号，必须可被监控告警。

#### 2.3 新增配置项

```yaml
teacup:
  ai:
    quota:
      reconcile-millis: 600000              # 10 分钟
      reconcile-min-age-minutes: 360        # 预占超过 6 小时才纳入对账
      reconcile-zombie-age-minutes: 120     # 非终态且超 2 小时视为僵尸任务
      reconcile-batch-size: 200
```

> `reconcile-min-age-minutes` 必须大于任务的最长合法生命周期（队列等待 + 执行 + 全部重试 + 重试队列 TTL 合计）。
> 当前重试阶梯为 5s/30s/120s、最多 4 次，队列侧理论最长等待远小于 6 小时，默认值安全。
> 实施者如调整重试策略，必须同步复核该值。

#### 2.4 `AiTaskService` 抽取统一收尾路径

当前归还逻辑分散在 `cancel:143-147`、`fail:160-162` 两处（`exhaust` 将新增第三处）。
抽取私有方法，三处共用：

```java
private void finalizeQuota(AiTask task, boolean refund) {
    if (Integer.valueOf(1).equals(task.getQuotaSettled())) return;
    if (refund) {
        if (Integer.valueOf(1).equals(task.getQuotaRefunded())) return;
        releaseReservation(task);
        task.setQuotaRefunded(1);
    } else {
        settleQuota(task);
    }
}
```

**约束**：`settleQuota` 内部已有 `quotaSettled` 幂等判断，保持不变。
`finalizeQuota` 不负责写 `ai_task` 行，由调用方在事务内 `updateById`。
归还/结算的审计写入放在 `finalizeQuota` 内，保证不漏。

---

### 阶段三：幂等契约与提交路径并发（D7、D8）

#### 3.1 幂等契约文档化

在接口文档（`docs/` 下 M2 相关契约文件）中补充：

> **Idempotency-Key 契约**
>
> - 由客户端为**一次用户意图**生成（用户点击提交时），**不是**为一次 HTTP 请求生成
> - 客户端生成后必须缓存；**所有自动重试必须复用同一个键**
> - 用户修改内容后重新提交，才生成新键
> - 同键 + 内容相同 → 返回原任务，不新建、不扣配额
> - 同键 + 内容不同 → `409`，提示客户端更换幂等键
> - 键的格式约束：`^[A-Za-z0-9._:-]{8,128}$`（已有）
> - **服务端保留期等于任务记录的永久保留期**——本实现的幂等记录就是任务行本身，
>   键一旦使用即永久占用。客户端不得复用历史键。

最后一条是本实现与"窗口幂等"方案的重要差异，必须写明，否则客户端可能误以为键会在 24 小时后释放而重复使用。

#### 3.2 取消 `running` 任务的计费语义（D7）

**此项必须由产品确认后再实施，不得由实施者自行决定。**

现状：`AiTaskService.cancel:145-147` 对已开始调用的 `running` 任务执行 `settleQuota`，即照常计费。
建议在接口文档中明确：

> 取消仅在任务尚未开始调用 AI 服务时退还配额（`invocationStarted = 0` 且状态为 `queued`）。
> 一旦 AI 调用已发起，取消会停止结果入库，但配额不予退还——因为上游成本已经产生。

将该行为写入文档即可，代码逻辑保持不变。若产品决定改为退还，则是一个独立需求。

#### 3.3 移除用户行锁，改用条件更新（D8）

**现状**：`AiTaskService.create:99` 的 `userMapper.lockById(user.getId())` 把同一用户的**全部提交**串行化。

**改造**：把配额校验从"读—判—写"改为单条条件更新。

在 `AiQuotaUsageMapper` 新增：

```java
@Update("UPDATE ai_quota_usage SET reservedCount = reservedCount + #{cost} "
        + "WHERE userId = #{userId} AND usageDate = #{usageDate} AND taskType = #{taskType} "
        + "AND usedCount + reservedCount + #{cost} <= #{limit}")
int reserveIfWithinLimit(@Param("userId") long userId, @Param("usageDate") LocalDate usageDate,
                         @Param("taskType") String taskType, @Param("cost") int cost,
                         @Param("limit") int limit);
```

`reserveQuota` 改为：

```java
private void reserveQuota(long userId, String type, int cost) {
    LocalDate date = LocalDate.now(quotaZone);
    quotaMapper.ensureRow(userId, date, type);
    if (quotaMapper.reserveIfWithinLimit(userId, date, type, cost, limit(type)) != 1) {
        throw V1Exception.conflict("今日 AI 配额已用完");
    }
}
```

**幂等检查改由唯一索引承担**：

```java
AiTask existing = taskMapper.selectOne(...);   // 保留，快路径
if (existing != null) { ... }
try {
    reserveQuota(...);
    taskMapper.insert(task);
} catch (DuplicateKeyException exception) {
    // 并发下另一个请求已插入同键任务：回滚本次预占并返回既有任务
    throw V1Exception.conflict("Idempotency-Key 已用于其他请求");   // 或按既有语义返回原任务
}
```

**约束与风险提示**

- `ensureRow` 使用 `INSERT IGNORE`，与条件更新组合后，配合唯一键 `uk_ai_quota_user_date_type`，
  在并发下不会重复插入，也不会漏计。
- 移除 `lockById` 后，`selectForUpdate` 也可一并移除——`reserveIfWithinLimit` 已在单条语句内完成判断与写入。
- **`DuplicateKeyException` 的处理语义需要实施者确认**：若捕获到冲突时希望返回既有任务（而非报错），
  必须在 `catch` 中重新查询并返回 `CreateResult(existing, false)`。
  注意此时事务已被标记为 rollback-only 的场景——建议把幂等冲突处理放在**独立事务**中，
  或改用"先插入幂等占位、再执行后续逻辑"的顺序。
- 本项改动改变了并发行为，**必须补充并发测试**（见验收章节 T8）。
- 若时间紧张，**本项可整体延后**：它是并发优化，不影响正确性。

---

## 3. 实施顺序与依赖

```
阶段一（租约）
  1.1 迁移脚本 V22
  1.2 AiTask 实体新增字段
  1.3 AiTaskMapper 三个方法改造
  1.4 新增 AiWorkerIdentity（供 1.4 / 1.5 共用）
  1.5 新增 AiTaskLeaseRenewer
  1.6 AiTaskRunner / AiTaskService 改造 + 新增 AiTaskLeaseLostException
  1.7 新增 AiTaskService.exhaust
  → 验收 T1–T5

阶段二（配额兜底）
  2.1 AiTaskDlqService + aiDlqListenerContainerFactory
  2.2 AiQuotaReconciliationService
  2.3 AiTaskService.finalizeQuota 抽取
  → 验收 T6–T7

阶段三（幂等与并发）
  3.1 接口文档补充（无代码风险，可随时插入）
  3.2 产品确认（阻塞项，需人工确认）
  3.3 条件更新替换行锁
  → 验收 T8
```

阶段一与阶段二互相独立，可并行开发；阶段三 3.3 依赖阶段一、二完成后回归测试通过。

---

## 4. 验收标准

### 功能与并发

| 编号 | 场景 | 期望 |
|---|---|---|
| **T1** | 把 `lease-seconds` 临时改为 20、`lease-renew-millis` 改为 5000，构造一个耗时 90 秒的假 provider | 任务不被恢复服务重置，`attemptCount` 只增加 1 次，全程 `workerId` 不变，最终 `succeeded` |
| **T2** | 关闭 `AiTaskLeaseRenewer`（或改 `lease-seconds` 为 5 秒且不续租），构造耗时 60 秒的 provider | 任务被恢复服务重置为 `queued` 并重新执行；第二次执行正常完成；`complete` 的旧调用返回 `false`；日志中不出现 `result_persistence_failed` |
| **T3** | 构造一个持续抛可重试异常的 provider，观察直到 `attemptCount` 达到 4 | 第 5 次抢占失败；任务被 `exhaust` 置为 `failed`、`failureCode=attempts_exhausted`；`reservedCount` 归零、`quotaRefunded=1` |
| **T4** | 手动把某任务的 `attemptCount` 置为 4、`status` 置为 `queued`，投递消息 | 抢占失败 → `exhaust` 生效 → 不产生第 5 次 AI 调用 |
| **T5** | 在 `renewLease` 与 `storeResult` 之间人工抛 `AiTaskLeaseLostException` | 任务最终 `ack`，不调用 `fail`，`failureCode` **不**被写成 `result_persistence_failed`；已存储的图片按既有逻辑丢弃 |
| **T6** | 手工插入一条 `status='queued'`、`quotaSettled=0`、`quotaRefunded=0`、`createTime` 为 7 小时前的任务 | 对账任务在 `reconcile-zombie-age-minutes` 之后将其置为 `failed` 并归还预占；`ai_task_quota_audit` 出现 `reason=zombie_task` |
| **T7** | 向死信队列投递一条指向正常 `queued` 任务的消息 | `AiTaskDlqService` 消费后任务被终态化、预占归还、消息被 ack、**不**产生重新投递 |
| **T8** | 同一用户使用同一幂等键并发发起 20 个相同请求 | 只创建 1 个任务；1 个返回 `created=true`，其余 19 个返回既有任务；`reservedCount` 只增加 1 次；无 `DuplicateKeyException` 泄漏到接口层 |
| **T9** | 同一用户并发发起 50 个**不同**幂等键的请求，配额上限设为 10 | 恰好 10 个成功，40 个返回 `409 今日 AI 配额已用完`；`usedCount + reservedCount` 不超过 10 |
| **T10** | 重启应用（模拟进程被强制杀死），期间存在 `running` 任务 | 重启后该任务被恢复服务接管并最终到达终态，预占被结算或归还，`reservedCount` 无残留 |

### 不变量（每次测试后都必须校验）

以下 SQL 的返回结果必须为空：

```sql
-- 1. 终态任务不得仍有未结算的预占（超过对账最小年龄）
SELECT id, status, quotaSettled, quotaRefunded, createTime
FROM ai_task
WHERE status IN ('succeeded','failed','cancelled')
  AND quotaSettled = 0 AND quotaRefunded = 0
  AND createTime < NOW() - INTERVAL 6 HOUR;

-- 2. 执行中任务必须有租约
SELECT id FROM ai_task WHERE status = 'running' AND leaseUntil IS NULL;

-- 3. 已结算与已归还不可同时为真
SELECT id FROM ai_task WHERE quotaSettled = 1 AND quotaRefunded = 1;

-- 4. 令牌不得为负
SELECT id FROM ai_task WHERE executionToken < 0;
```

### 回归范围

- `cd backend && mvn test` 必须全绿
- 手工回归：提交生图任务、查询任务列表、查询配额、取消排队中任务、取消执行中任务、下载成功任务的结果
- 确认 `AiRabbitConfig.java` 中 `prefetchCount`、`concurrentConsumers`、线程池参数**未被改动**（见 1.1）

---

## 5. 明确禁止的改动

实施者**不得**做以下任何一项，除非单独提出并获批准：

1. 调整 `AiRabbitConfig` 的 `prefetchCount`（当前 1 是 per-consumer 语义，与 4 个消费者精确匹配）
2. 调整 `aiWorkerExecutor` 的 `corePoolSize`/`maxPoolSize`/`queueCapacity`/拒绝策略
3. 修改 Quorum 队列、重试队列 TTL 阶梯（5s/30s/120s）、死信交换机配置
4. 修改发件箱的重试与清理逻辑（`AiTaskOutboxPublisher`）
5. 修改幂等键的唯一约束或格式正则
6. 在 `AiTaskLeaseLostException` 分支中调用 `fail`
7. 使用 `DELETE` 语句清理任务或对账记录（任务行是永久记录，也是幂等键的载体）
8. 新增任何第三方依赖（不引入 ShedLock 等分布式调度框架；多实例下的调度重复执行由各服务的幂等设计消化）
