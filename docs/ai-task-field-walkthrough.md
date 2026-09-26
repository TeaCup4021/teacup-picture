# AI 生图任务：逐字段走读

本文按「用户提交 → 返回 → 异步执行 → 终态」的时间顺序，逐步说明**每一步做了什么、操作哪张表的哪些字段**。
定位是回代码核对的底稿。链路分层与面试口径另见 `ai-task-implementation-walkthrough.md`，执行架构见 `ai-task-execution.md`。

约定：表名与字段名沿用代码中的命名。正文不区分「读」与「写」时会显式标注。时间字段除特别说明外均为数据库当前时间。

---

## 阶段 0 · 客户端

用户点「提交」时客户端的职责：

- 生成**幂等键**：8–128 位，字符集限定为字母、数字、`.`、`_`、`:`、`-`
- 把它缓存起来，本次意图的所有自动重试（网络超时、手动重试）**复用同一个键**；用户改了内容重新提交才换新键
- 放在请求头 `Idempotency-Key`
- 请求体字段：`type`、`modelCode`、`prompt`、`ratio`、`quality`、`background`、`outputFormat`、`outputCompression`、`sourcePictureId`、`referencePictureId`
- 操作表：无

> 幂等键不是任务标识。任务标识是数据库自增主键，客户端拿不到也生成不了。

---

## 阶段 1 · 接入层

`POST /api/v1/ai/tasks`

1. 鉴权，取当前用户；未登录直接拒绝
2. 幂等键为空或空白 → 400
3. 调服务层创建
4. 按「是否真的新建」决定响应码：**新建 201、命中已有 200**
- 操作表：仅会话解析可能读 `user`

---

## 阶段 2 · 事务边界

事务注解打在方法上，所以**进入方法即开启事务**，覆盖整个方法体。

Spring 默认在第一次真正访问数据库时才从连接池取连接，但事务语义覆盖全方法。因此下面的四组校验也在事务内——只是它们**全是只读查询，不加任何锁**，且行锁是第一个写操作之前的动作，所以不延长写锁持有时间。

- 操作表：无

---

## 阶段 3 · 四组只读校验

### 3.1 幂等键格式

- 去空白后匹配字符集与长度，不通过 → 400
- 操作表：无

### 3.2 任务类型

- 取值必须是 `generate`（生图）或 `outpaint`（扩图），否则 400
- 操作表：无

### 3.3 提示词

- 去空白后非空，且长度 ≤ 2000，否则 400
- 操作表：无

### 3.4 模型解析与能力校验

一次快照读（不加锁）：

```sql
SELECT * FROM ai_model WHERE code = ? AND enabled = 1 LIMIT 1
```

读出的字段：`id`、`code`、`displayName`、`provider`、`providerModel`、`capabilities`、`supportedRatios`、`supportedQualities`、`supportedBackgrounds`、`supportedOutputFormats`、`supportsOutputCompression`、`supportsReference`、`quotaCost`。

四条判定，任一不通过即 400：

| 判定 | 字段 |
| --- | --- |
| 模型不存在或已下架 | `code` + `enabled` |
| 模型能力不含本次任务类型 | `capabilities`（JSON 数组） |
| 不支持本次图片比例 | `supportedRatios` |
| 不支持本次清晰度 | `supportedQualities` |

- 操作表：`ai_model`（读）

### 3.5 输出选项归一与互斥校验

**先归一**（补默认值）：

- `background` 为空 → 取该模型 `supportedBackgrounds` 的第一项（默认 `auto`）
- `outputFormat` 为空 → 取该模型 `supportedOutputFormats` 的第一项（默认 `png`）
- 两者都做去空白 + 转小写

**再判八条**，任一不通过即 400：

1. `background` 不在全局枚举 {auto, opaque, transparent}
2. `outputFormat` 不在全局枚举 {png, jpeg, webp}
3. `background` 不在**该模型**的 `supportedBackgrounds`
4. `outputFormat` 不在**该模型**的 `supportedOutputFormats`
5. `outputCompression` 越界（小于 0 或大于 100）
6. 输出格式为 PNG 却带了压缩质量（PNG 无有损压缩语义）
7. 带了压缩质量但该模型 `supportsOutputCompression` ≠ 1
8. 背景为透明且输出格式为 JPEG（JPEG 不支持透明通道）

产出「归一后的背景 / 输出格式 / 压缩质量」三元组，后续写进任务行。
- 操作表：无（纯计算 + 复用 3.4 读到的模型字段）

### 3.6 图片来源与归属校验

- `sourcePictureId`：扩图类型必填，缺失 → 400；生图类型可为空
- `referencePictureId`：可选
- 两者共用同一套校验：能解析成数字 → 查 `picture` → 必须存在、`userId` 等于当前用户、`isDelete` = 0，否则 404
- 参考图能力：传了参考图但模型 `supportsReference` ≠ 1 → 400
- 操作表：`picture`（读 `id`、`userId`、`isDelete`、`objectKey`、`url`）

**到此为止没有任何写操作，也没有加任何锁。**

---

## 阶段 4 · 五步写操作

### 4.1 锁用户行

```sql
SELECT id FROM user WHERE id = ? FOR UPDATE
```

- 只取 `id` 一列，加行级排他锁
- 作用：让后面的「查任务 → 判 → 插任务」对**同一用户**串行化
- 为什么需要它：没有这把锁时，两个同键并发请求都会查不到已有任务，然后一个插入成功、另一个撞唯一键 `uk_ai_task_user_idempotency` 报错，只能给客户端一个异常；有了锁，第二个请求在查询阶段就命中，直接把并发冲突转成幂等命中
- 操作表：`user`（读，加排他锁）

### 4.2 幂等查重

```sql
SELECT * FROM ai_task WHERE userId = ? AND idempotencyKey = ? LIMIT 1
```

- **未命中** → 继续下一步
- **命中** → 逐字段比对请求内容是否一致，比对字段为：
  `taskType`、`modelCode`、`prompt`、`ratio`、`quality`、`background`、`outputFormat`、`outputCompression`、`sourcePictureId`、`referencePictureId`
  - 注意比对的是**归一后的值**：请求省略 `background` 时，用模型的第一项去比。因此「省略字段」与「显式写出该默认值」会被判为同一请求
  - 一致 → 返回原任务，HTTP 200，**不新建、不扣额度**
  - 不一致 → 409（幂等键已用于其他请求）
- 唯一键本身也能兜住并发插入，但撞唯一键只能得到异常；行锁的价值是把并发路径也走成幂等语义
- 操作表：`ai_task`（读，走 `uk_ai_task_user_idempotency`）

### 4.3 额度预占

三步，全部针对 `ai_quota_usage`：

**第一步 · 补建当天该类型的行**（幂等插入）

```sql
INSERT IGNORE INTO ai_quota_usage(userId, usageDate, taskType, usedCount, reservedCount)
VALUES (?, ?, ?, 0, 0)
```

- `usageDate` 取**配额时区**（默认 Asia/Shanghai）的当天，不是 UTC 日期
- `taskType` 用本次任务类型，生图与扩图**分别记账**

**第二步 · 锁行读**

```sql
SELECT * FROM ai_quota_usage
WHERE userId = ? AND usageDate = ? AND taskType = ? FOR UPDATE
```

**第三步 · 判限额并占用**

- 本次成本 = max(1, 模型 `quotaCost`)
- 判据：

  ```
  usedCount + reservedCount + cost  >  当日上限   →  409
  ```

  当日上限按类型取，生图默认 100、扩图默认 100。

  > **判据是当日上限，不是"剩余额度"。** 剩余额度 = 上限 − 已用 − 已占，拿它当阈值是恒等式，永远成立、拦不住任何请求。剩余额度只是返回给前端看的派生值。

- 通过 → `reservedCount += cost`，写回

- 操作表：`ai_quota_usage`（幂等插入 + 锁读 + 更新 `reservedCount`）

### 4.4 插入任务行

写 `ai_task`，一次写全（**不是"先插再改状态"，插入时状态就是排队中**）：

| 字段 | 写入值 |
| --- | --- |
| `idempotencyKey` | 本次幂等键 |
| `userId` | 当前用户 |
| `taskType` | 归一后的任务类型 |
| `modelId` / `modelCode` | 模型主键与编码（快照） |
| `provider` / `providerModel` | 上游标识与上游模型名（快照） |
| `prompt` | 去空白后的提示词 |
| `ratio` / `quality` | 本次参数 |
| `background` / `outputFormat` / `outputCompression` | 归一后的输出选项 |
| `sourcePictureId` / `referencePictureId` | 校验通过的图片主键，可为空 |
| `status` | `queued` |
| `nextAttemptAt` | 当前时间（立即可投递） |
| `quotaCost` | 本次成本 |
| `quotaRefunded` / `quotaSettled` / `invocationStarted` | 0 / 0 / 0 |
| `attemptCount` / `executionToken` / `workerId` / `leaseUntil` | 默认 0 / 0 / 空 / 空 |
| `providerTaskId` / `providerRequestId` / `resultPictureId` | 空 |
| `failureCode` / `failureReason` | 空 |
| `startTime` / `finishTime` | 空 |
| `createTime` / `updateTime` | 数据库默认 |

把上游标识、上游模型名与输出选项**拷进任务行**的意义：执行阶段不再依赖 `ai_model` 的当前值，管理员改模型配置不影响已提交的任务。

- 操作表：`ai_task`（插入）

### 4.5 插入发件箱行

写 `ai_task_outbox`，与 4.4 **同一事务**：

| 字段 | 写入值 |
| --- | --- |
| `eventId` | 随机唯一标识（唯一键） |
| `taskId` | 刚插入的任务主键 |
| `status` | `pending` |
| `attemptCount` | 0 |
| `nextAttemptAt` | 当前时间 |
| `lockOwner` / `lockUntil` / `publishedAt` / `lastError` | 空 |

- 为什么必须同事务：否则会出现「任务行写进去了、消息没发出去」的中间态，任务永远停在排队中、额度永远被占，没有任何东西会来救它
- `eventId` **不是幂等键**，只用于日志追踪；防重复执行靠任务行的状态机
- 代价：投递器可能在「发布确认成功后、更新状态前」崩溃，同一条事件被投两次，所以消费端必须幂等
- 操作表：`ai_task_outbox`（插入）

### 4.6 组装响应并提交

- 响应体为任务视图：任务标识、类型、模型视图、提示词、各项参数、状态、源图/参考图/结果图引用、失败码与失败原因、额度已归还与已结算标记、创建/开始/结束三个时间、下载地址（仅成功后有）
- 提交事务 → 释放 `user` 行锁与 `ai_quota_usage` 行锁
- 组装视图时会按 `modelId` 再读一次 `ai_model`、按图片主键逐个读 `picture`，均为只读

---

## 阶段 5 · 投递器（独立定时线程，每秒一轮）

**1. 扫描到期事件**

```sql
SELECT * FROM ai_task_outbox
WHERE status IN ('pending','failed')
  AND nextAttemptAt <= now
  AND (lockUntil IS NULL OR lockUntil <= now)
LIMIT 50
```

刻意**不按主键排序**：索引以 `status` 为前导列，加排序会让优化器放弃索引改走主键扫描，积压时实测慢约 200 倍。投递本来不要求先进先出。

**2. 抢占（只动锁列，不动重试次数）**

```sql
UPDATE ai_task_outbox SET lockOwner = ?, lockUntil = ?
WHERE id = ? AND status IN ('pending','failed')
  AND nextAttemptAt <= ? AND (lockUntil IS NULL OR lockUntil <= ?)
```

改到 0 行说明被别人抢走，跳过。锁有效期 30 秒。

**3. 发消息**：持久化投递到交换机，路由键指向主队列；消息体只有事件标识与任务标识，不带提示词、密钥、图片内容。

**4. 等发布确认**（默认 5 秒超时）：被确认且未被退回才算成功。

**5. 成功回写**

```sql
UPDATE ai_task_outbox SET status='published', publishedAt=now,
  lastError=NULL, lockOwner=NULL, lockUntil=NULL WHERE id=?
```

**6. 失败回写**

```sql
UPDATE ai_task_outbox SET status='failed', attemptCount=attemptCount+1,
  lastError=?, nextAttemptAt=now+退避, lockOwner=NULL, lockUntil=NULL WHERE id=?
```

退避为 2 的尝试次数次方，封顶 60 秒。

**7. 清理**：每小时删除「已投递且投递时间早于保留期（默认 7 天）」的记录，一次 1000 条。

- 操作表：`ai_task_outbox`（读 / 更新锁列 / 更新状态与投递时间 / 更新重试次数与退避 / 删除）

---

## 阶段 6 · 消费与执行

### 6.1 消费者层

1. 从消息体解析任务标识，解析失败 → 拒收且不重新入队（进死信）
2. 调执行入口
3. 拿到「需要重试」的结果 → 先发延迟重试消息；**发不出去就拒收并要求重新入队**，避免丢任务
4. 其余结果 → 确认应答
5. 执行入口抛异常 → 拒收且不重新入队（进死信）
- 操作表：无（只操作消息队列）

### 6.2 读快照与三道只读拦截

一次主键查询（不加锁）读 `ai_task`，然后三道纯内存判定，任一命中即中止：

| 拦截 | 条件 | 去向 |
| --- | --- | --- |
| 终态 | 行不存在，或状态为成功 / 失败 / 取消 | 确认丢弃（**重复消费的幂等屏障**） |
| 未到期 | 排队中且 `nextAttemptAt` 晚于当前 | 按剩余秒数就近取档（≤5 秒取 5、≤30 秒取 30、否则 120）重投 |
| 占用 | 执行中且 `leaseUntil` 晚于当前 | 固定 5 秒后重投 |

三道拦截**不含限流**（限流是下一步），且全部发生在获取上游许可之前，避免废消息挤占真任务产能。拦截是尽力而为，正确性由后面的条件更新与栅栏令牌保证。

### 6.3 全局限流（Redis，不碰数据库）

按上游标识区分两个键：

- **并发许可**：有序集合，成员是许可标识、分值是到期时刻。先按分数清掉过期成员，再数成员个数，达到上限（默认 12）即拒；否则加入自己并设置过期时间
- **速率令牌**：哈希，存令牌数与上次更新时间；按经过时间补令牌，桶容量默认 4、速率默认每分钟 60

顺序是**先并发、后速率**；速率没过必须把刚占的并发名额还回去。拿不到 → 5 秒后重投。Redis 不可用 → 也 5 秒后重投，由许可过期兜底。

### 6.4 原子抢占执行权

一条更新语句同时做六件事：

```sql
UPDATE ai_task SET
  status = 'running',
  invocationStarted = 1,
  attemptCount = attemptCount + 1,
  executionToken = executionToken + 1,
  workerId = ?,
  leaseUntil = ?,
  startTime = COALESCE(startTime, ?)
WHERE id = ?
  AND attemptCount < 最大尝试次数
  AND ((status = 'queued' AND (nextAttemptAt IS NULL OR nextAttemptAt <= ?))
    OR (status = 'running' AND leaseUntil IS NOT NULL AND leaseUntil <= ?))
```

- 改到 1 行 = 抢到；0 行 = 没抢到。**判定条件写在 WHERE 里做原子判定**，不是先查再改
- `executionToken` 每次抢占 +1，形成单调递增的**栅栏令牌**；`attemptCount` 只有在这里才递增
- 抢到后回读一行，取本次的 `executionToken`
- 没抢到 → 再读最新状态：
  - 已终态 → 确认丢弃
  - **仍是排队中且 `attemptCount` 已达上限 → 就地终态化并归还额度**（不判这一下，消息会无限重投、预占永久悬挂）
  - 其余 → 5 秒后重投
- 操作表：`ai_task`（读 / 原子更新）

### 6.5 调用上游

- 读源图与参考图的 `picture` 行，取可访问地址
- 组装请求：任务类型、上游模型名、提示词、比例、清晰度、背景、输出格式、压缩质量、两张图片地址
- 比例映射为尺寸、清晰度映射为上游取值，均为纯计算
- 当前上游实现只做生图，任务类型为扩图时直接抛「能力不支持」
- 操作表：`picture`（读）

### 6.6 落库前确认租约

```sql
UPDATE ai_task SET leaseUntil = ?
WHERE id = ? AND status = 'running' AND workerId = ? AND executionToken = ?
```

改到 0 行说明租约已丢 → 抛专用异常 → 走「**只确认、不置失败**」分支。不能置失败的原因：任务归属已不属于本实例，该由恢复服务重新入队。

### 6.7 取消检查

读 `ai_task` 状态，已取消 → 确认并丢弃结果（图片不落库）。这一步位于「调用上游之后、结果落盘之前」，正是「取消只停止入库、不退额度」的落点。

### 6.8 结果落盘

上游可能返回内联数据或图片地址，两种情况都落到对象存储，然后写 `picture` 行（走统一的图片保存服务）：

| 字段 | 写入值 |
| --- | --- |
| `id` | 预生成的雪花号 |
| `url` / `thumbnailUrl` | 站内私有地址 |
| `storageProvider` | `minio` |
| `objectKey` / `thumbnailObjectKey` | 对象存储键 |
| `contentType` / `checksum` | 内容类型与校验值 |
| `name` | `AI 绘图 <任务标识>` 或 `AI 扩图 <任务标识>` |
| `introduction` | 任务提示词 |
| `category` / `tags` | `AI 创作` / `["AI","绘图"]` 或 `["AI","扩图"]` |
| `picSize` / `picWidth` / `picHeight` / `picScale` / `picFormat` | 尺寸、大小、比例、格式 |
| `userId` / `spaceId` | 用户与个人空间 |
| `visibility` / `publishStatus` / `reviewStatus` / `isDelete` | `private` / `not_requested` / 0 / 0 |

同时更新空间用量 `space`：`totalSize += 字节数`、`totalCount += 1`，并创建编辑链路的初始版本记录。保存服务自带补偿：插入失败要删掉已写入对象存储的原图与缩略图。

- 操作表：`picture`（插入）、`space`（更新两个计数）、版本表（插入）

### 6.9 终态化

```sql
SELECT * FROM ai_task WHERE id = ? FOR UPDATE
```

三要素校验：`status` = running 且 `workerId` 匹配 且 `executionToken` 匹配。

- **不匹配** → 返回失败，调用方**回头删掉刚写的图片**（连同对象存储里的对象、空间用量、版本记录），避免孤儿对象
- **匹配** → 写：
  - `status` = `succeeded`
  - `providerTaskId` / `providerRequestId`（上游回执标识）
  - `resultPictureId` = 刚建的图片主键
  - `finishTime` = 当前
  - `workerId` / `leaseUntil` = 空
- 操作表：`ai_task`（锁读 + 更新）

### 6.10 额度结算（同一终态事务内）

- 幂等闸：`quotaSettled` = 1 直接返回
- 锁额度行：`ai_quota_usage` 按「任务归属日期 + 任务类型」锁读（归属日期由 `createTime` 按配额时区换算）
- 写：
  - `reservedCount -=` 本次成本（下界截 0）
  - `usedCount +=` 本次成本
- 标记 `ai_task.quotaSettled` = 1
- 写审计行 `ai_task_quota_audit`：`taskId` / `userId` / `taskType` / `quotaCost` / `usageDate` / `action` = `settled` / `reason`
- 操作表：`ai_quota_usage`（锁读 + 更新两个计数）、`ai_task`（更新标记）、`ai_task_quota_audit`（插入）

### 6.11 异常分支

**上游返回无权限（403）**：顺手把模型下架 —— `ai_model` 更新 `enabled` = 0。

**可重试码**（限流 / 超时响应 / 上游 5xx）且 `attemptCount` 未达上限：

- `ai_task` 更新：`status` = `queued`、`failureCode`、`failureReason`、`workerId` = 空、`leaseUntil` = 空、`nextAttemptAt` = 当前 + 延迟
- 返回「需重试」，由消费者发延迟消息。延迟档：第 1 次 5 秒、第 2 次 30 秒、之后 120 秒

**不可重试**：

- `ai_task` 更新：`status` = `failed`、`failureCode`、`failureReason`、`finishTime`、`workerId` = 空、`leaseUntil` = 空
- 归还额度：`ai_quota_usage.reservedCount -=` 成本、`ai_task.quotaRefunded` = 1、审计 `action` = `released`
- 其中**连接层异常要改写成「上游结果未知」**：错误可能发生在上游已经接受请求之后，盲目重试会导致上游被调用两次
- 其他运行时异常（如落盘失败）→ 置失败，错误码 `result_persistence_failed`

所有终态流转都必须走统一收尾入口，靠 `quotaSettled` / `quotaRefunded` 两个标记保证幂等。

---

## 阶段 7 · 四条兜底链路

事件驱动覆盖不了三种情况：**进程被强制杀死**、**消息进死信后无人处理**、**收尾分支漏调用**。因此必须有独立于事件的兜底。

### 7.1 租约续期（每 90 秒）

```sql
UPDATE ai_task SET leaseUntil = ?
WHERE status = 'running' AND workerId LIKE '实例标识:%'
```

- 启动时校验「续租周期 × 3 ≤ 租期」，不满足直接拒绝启动
- 续租只是「持有者还在干活」的声明，不能替代执行权校验
- 失败只记日志、不改任何状态，下一轮重试；租约到期后由恢复链路接管
- 操作表：`ai_task`（更新）

### 7.2 租约过期恢复（每 30 秒，一轮 50 条）

扫描 `ai_task`：`status` = running 且（`leaseUntil` 已过期，或老数据 `leaseUntil` 为空且 `startTime` 早于 15 分钟前）。

逐条在事务内处理：

```sql
SELECT * FROM ai_task WHERE id = ? FOR UPDATE
```

仍是执行中且租约确实过期 → 写 `status` = `queued`、`workerId` = 空、`leaseUntil` = 空、`nextAttemptAt` = 当前、`failureCode` = `worker_lease_expired`、`failureReason`，并在同事务插入一条新的发件箱事件。

- 操作表：`ai_task`（读 / 锁读 / 更新）、`ai_task_outbox`（插入）

### 7.3 死信消费（独立单并发容器，不占工作线程）

- 读 `ai_task` 状态；已终态 → 确认丢弃
- 非终态 → 终态化并归还：
  - `ai_task`：`status` = `failed`、`failureCode` = `dead_letter`、`failureReason`、`finishTime`、`workerId` = 空、`leaseUntil` = 空
  - `ai_quota_usage.reservedCount -=` 成本
  - `ai_task.quotaRefunded` = 1
  - `ai_task_quota_audit` 插入，`action` = `released`
- 确认后不重新投递，避免死循环

### 7.4 额度对账（每 10 分钟，一轮 200 条）

扫描：`ai_task` 中 `quotaSettled` = 0 且 `quotaRefunded` = 0 且 `createTime` 早于僵尸年龄（默认 11 分钟）。

**候选筛选门槛与僵尸判定门槛是同一个数**（默认 11 分钟＝4 次执行 × 读超时 120s ＋ 退避 5/30/120s ≈ 635s，向上取整）。拆成两个值必然出现「改一个忘一个」：候选门槛若大于僵尸年龄，后者就是永远不成立的死条件。

逐条在事务内锁读后判定：

- **已终态**
  - 成功 → 补结算：`reservedCount -=`、`usedCount +=`、`quotaSettled` = 1、审计 `settled`
  - 其他终态 → 补归还：`reservedCount -=`、`quotaRefunded` = 1、审计 `released`
- **非终态**
  - 执行中且租约未过期 → 跳过（正在正常干活）
  - 创建时间未超过僵尸年龄（默认 11 分钟）→ 跳过
  - 超了 → 置失败（`failureCode` = `reconcile_zombie`）+ 归还

日志输出「扫描数 / 修正数 / 耗时」。**修正数长期不为 0 意味着事件链路存在缺陷，应接入告警。**

- 操作表：`ai_task` / `ai_quota_usage` / `ai_task_quota_audit`

---

## 阶段 8 · 用户侧可见

**任务列表**：读 `ai_task`，条件 `userId` = 当前用户（可选叠加 `status`），按 `createTime` 倒序、`id` 倒序，分页。

**单条任务**：按主键读 + 归属校验，非本人 → 404。

**下载**：校验归属、状态为成功、`resultPictureId` 非空 → 读 `picture` 拿 `objectKey` → 从对象存储读流。响应带「禁止缓存」与「禁止内容嗅探」，附件名走下载头。

**取消**：锁读 `ai_task` 一行 → 归属校验 → 状态必须是排队中或执行中，否则 409 → 判定「是否已发起调用」（状态是排队中**且** `invocationStarted` = 0）：
- 是 → 归还额度（`reservedCount -=`、`quotaRefunded` = 1、审计 `released`）
- 否 → 只结算、不归还（上游成本已产生，属产品语义）
- 两种情况都写：`status` = `cancelled`、`finishTime`、`workerId` = 空、`leaseUntil` = 空

**额度查询**：读 `ai_quota_usage`（当前用户 + 当天 + 两个任务类型各一行），返回上限、已用、已占，以及剩余 = max(0, 上限 − 已用 − 已占)。

---

## 附 · 状态流转一览

| 起点状态 | 触发 | 终点状态 | 额度动作 |
| --- | --- | --- | --- |
| — | 提交 | `queued` | 预占 `reservedCount +=` |
| `queued` | 抢占成功 | `running` | 无 |
| `queued` | 取消（未发起调用） | `cancelled` | 归还 |
| `running` | 执行成功 | `succeeded` | 结算 |
| `running` | 上游可重试失败 | `queued`（延后 `nextAttemptAt`） | 无 |
| `running` | 上游不可重试 / 次数用尽 / 死信 / 僵尸回收 | `failed` | 归还 |
| `running` | 取消（已发起调用） | `cancelled` | 结算（不归还） |
| `running` | 租约过期 | `queued`（重新投递） | 无 |

终态只有三种：`succeeded`、`failed`、`cancelled`。终态之后不再接受任何执行者修改。
