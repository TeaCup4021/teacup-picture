# AI 生图任务链路与设计详解

本文整合以下三份材料，并按一条完整链路重新组织：

- `ai-task-design-notes.md`：机制原理、设计取舍与已知边界
- `ai-task-field-walkthrough.md`：各阶段操作的表、字段与状态
- `ai-task-interview-script.md`：适合快速复述的主线与关键口径

本文只使用上述三份材料。目标是同时回答四个问题：

1. 用户提交一次 AI 生图请求后，系统按什么顺序工作？
2. 每一步读写哪些数据，任务状态和配额如何变化？
3. 为什么需要幂等、Outbox、RabbitMQ、双重限流、租约、栅栏令牌和对账？
4. 失败、重试、取消、进程退出和消息死信时，系统如何收尾？

---

## 一、链路总览

AI 生图不是一个由 HTTP 请求同步等待图片返回的接口，而是一条分段执行的异步任务链路：

```text
客户端生成幂等键并提交
  -> API 鉴权与参数校验
  -> MySQL 事务内预占配额、创建任务、写 Outbox
  -> 接口返回 queued
  -> Outbox Publisher 投递 RabbitMQ
  -> Consumer 读取消息
  -> Redis 并发许可 + 速率令牌
  -> MySQL 原子抢占执行权
  -> Worker 同步调用上游 Images API
  -> 结果写入 MinIO、图片表、空间和版本表
  -> MySQL 事务内完成任务并结算配额
  -> 用户在任务中心查看、下载或继续编辑
```

这条链路可以概括成四段：

| 阶段 | 主要目标 | 核心组件 |
| --- | --- | --- |
| 同步提交 | 校验请求，可靠记录用户意图 | API、MySQL、幂等键、配额预占、Outbox |
| 异步投递 | 把数据库事件可靠转换成消息 | Outbox Publisher、RabbitMQ Publisher Confirm |
| 异步执行 | 控制产能，调用上游，保存结果 | Consumer、Redis、Worker、Provider、MinIO |
| 终态与兜底 | 保证状态、图片和配额最终一致 | 栅栏令牌、租约、死信、恢复任务、配额对账 |

核心设计思想是：

> 同步提交负责把任务和事件一起可靠落库，异步执行负责真正生图，租约和对账负责覆盖事件驱动无法覆盖的故障。

---

## 二、核心对象与状态

### 2.1 核心数据对象

| 对象 | 作用 |
| --- | --- |
| `ai_model` | 保存模型能力、上游标识、支持参数和单次配额成本 |
| `ai_task` | 保存平台任务、请求快照、状态、执行权、上游回执和结果图片 |
| `ai_quota_usage` | 按用户、日期和任务类型记录已用与已预占配额 |
| `ai_task_outbox` | 保存尚待投递或需要重试投递的任务事件 |
| `ai_task_quota_audit` | 记录配额结算、归还和修正证据 |
| `picture` | 保存生成结果对应的业务图片记录 |
| `space` | 保存个人空间容量和图片数量使用情况 |
| RabbitMQ 消息 | 只携带事件标识和任务标识，不携带提示词、密钥或图片内容 |

### 2.2 任务状态机

```text
                      +----------------------+
                      |                      v
queued -> running -> succeeded             failed
   |         |           ^                    ^
   |         |           |                    |
   |         +-- 可重试失败 --> queued -------+
   |         |
   |         +-- 租约过期 --> queued
   |         |
   |         +-- 用户取消 --> cancelled
   |
   +-- 用户取消 --> cancelled
```

终态只有三种：

- `succeeded`
- `failed`
- `cancelled`

状态与配额动作的关系：

| 起点 | 触发 | 终点 | 配额动作 |
| --- | --- | --- | --- |
| 无 | 创建任务 | `queued` | `reservedCount += cost` |
| `queued` | 抢占成功 | `running` | 无 |
| `queued` | 未发起调用前取消 | `cancelled` | 归还预占 |
| `running` | 执行成功 | `succeeded` | 预占转已用 |
| `running` | 可重试错误 | `queued` | 无 |
| `running` | 租约过期 | `queued` | 无 |
| `running` | 不可重试错误、次数用尽、死信或僵尸回收 | `failed` | 归还预占 |
| `running` | 已发起调用后取消 | `cancelled` | 预占转已用 |

重试和租约恢复都不是终态，因此不会重复预占、结算或归还。

---

## 三、关键时间参数

| 机制 | 默认时间或数量 | 作用 |
| --- | --- | --- |
| Outbox 扫描 | 1 秒 | 尽快把数据库事件投递到 RabbitMQ |
| Outbox 单批 | 50 条 | 限制单次扫描和投递压力 |
| Outbox 抢占锁 | 30 秒 | 防止多个 Publisher 同时处理同一事件 |
| Publisher Confirm 等待 | 5 秒 | 确认消息已被 RabbitMQ 接受且可路由 |
| 已发布 Outbox 保留 | 7 天 | 保留短期追踪证据，之后每小时分批清理 |
| Worker 本地并发 | 4 | 控制单实例工作线程数 |
| RabbitMQ 预取 | 1 | 每个消费者一次只持有一条未确认消息 |
| Provider 全局并发 | 12 | 限制所有实例同时在飞的上游请求数 |
| Provider 速率 | 60 次/分钟 | 限制长期平均调用速率 |
| 令牌桶容量 | 4 | 允许少量瞬时突发 |
| 上游 HTTP 超时 | 120 秒 | 防止工作线程无限阻塞 |
| 任务租期 | 300 秒 | Worker 消失后允许其他实例接管 |
| 租约续期 | 90 秒 | 周期性声明本实例仍持有执行中的任务 |
| 优雅停机等待 | 180 秒 | 尽量让执行中的任务在停机前完成 |
| 最大尝试次数 | 4 | 防止任务无限执行 |
| 重试延迟 | 5 / 30 / 120 秒 | 对可重试错误做分档退避 |
| 租约恢复扫描 | 30 秒，每轮 50 条 | 回收过期的执行权并重新投递 |
| 配额对账 | 10 分钟，每轮 200 条 | 修复终态未结算、未归还和僵尸任务 |
| 僵尸年龄 | 11 分钟 | 覆盖 4 次调用超时和 5/30/120 秒退避的最长合法生命周期 |

必须保持两个重要关系：

```text
续租周期 × 3 <= 任务租期
任务租期 > 上游超时 + 结果落盘时间
```

第一条由启动校验保证，第二条目前是设计约定。如果上游调用还没结束，任务租约却先到期，恢复服务可能把同一任务重新入队，造成重复上游调用。

---

## 四、完整工作链路

### 4.1 客户端生成的是幂等键，不是任务 ID

用户点击提交时，客户端：

1. 生成 8 到 128 位幂等键，只允许字母、数字、`.`、`_`、`:`、`-`。
2. 把它放进请求头 `Idempotency-Key`。
3. 同一次用户意图的自动重试复用同一个键。
4. 用户修改内容并重新提交时才生成新键。

请求体包含：

```text
type
modelCode
prompt
ratio
quality
background
outputFormat
outputCompression
sourcePictureId
referencePictureId
```

任务 ID 不是客户端生成的，它是 `ai_task` 的数据库自增主键。幂等键一旦被某个用户使用就永久占用，没有时间窗口，客户端不能把旧键复用于新的用户意图。

### 4.2 接入层鉴权并进入事务

接口为：

```http
POST /api/v1/ai/tasks
```

接入层先完成：

1. 登录鉴权，取当前用户。
2. 检查 `Idempotency-Key` 是否为空。
3. 调用任务创建服务。
4. 新任务返回 201；幂等命中原任务返回 200。

事务注解作用在整个创建方法上，所以进入方法时事务语义已经开始。前面的校验虽然位于事务内，但都是只读操作，不持有写锁。

### 4.3 提交阶段的只读校验

#### 4.3.1 基础参数

- 幂等键先去空白，再校验字符集和长度。
- `type` 只能是 `generate` 或 `outpaint`。
- `prompt` 去空白后必须非空，长度不超过 2000。

#### 4.3.2 模型能力

按 `code + enabled` 读取 `ai_model`，使用的主要字段包括：

```text
id, code, displayName
provider, providerModel
capabilities
supportedRatios, supportedQualities
supportedBackgrounds, supportedOutputFormats
supportsOutputCompression, supportsReference
quotaCost
```

必须同时满足：

- 模型存在且已启用。
- 模型能力包含本次任务类型。
- 模型支持本次比例。
- 模型支持本次清晰度。

#### 4.3.3 输出参数归一

先补默认值：

- `background` 为空时取模型支持列表第一项，通常为 `auto`。
- `outputFormat` 为空时取模型支持列表第一项，通常为 `png`。
- 背景和格式都去空白并转成小写。

再检查：

1. 背景属于 `auto / opaque / transparent`。
2. 格式属于 `png / jpeg / webp`。
3. 模型支持这个背景和格式。
4. 压缩质量位于 0 到 100。
5. PNG 不能携带有损压缩质量。
6. 模型声明支持时才能传压缩质量。
7. 透明背景不能使用 JPEG。

归一后的值既用于写任务，也用于幂等请求内容比较。因此省略默认值和显式传入同一个默认值，会被视为同一个请求。

#### 4.3.4 图片来源与归属

- 扩图必须提供 `sourcePictureId`。
- 参考图可选，但模型必须支持参考图。
- 源图和参考图必须存在、属于当前用户且没有被软删除。

到这里为止没有写数据库，也没有加锁。

### 4.4 同一事务内的五步写操作

#### 第一步：锁用户行

```sql
SELECT id FROM user WHERE id = ? FOR UPDATE
```

它让同一用户后续的“查任务、判断、插任务”串行化。数据库唯一键仍是最终约束，但用户行锁可以把两个同键并发请求从“一个成功、一个唯一键异常”转换成正常的幂等命中。

#### 第二步：幂等查重

```sql
SELECT * FROM ai_task
WHERE userId = ? AND idempotencyKey = ?
LIMIT 1
```

- 没命中：继续创建。
- 命中且请求内容一致：返回原任务，不创建、不预占新额度。
- 命中但内容不同：返回冲突。

比较字段为：

```text
taskType, modelCode, prompt, ratio, quality
background, outputFormat, outputCompression
sourcePictureId, referencePictureId
```

#### 第三步：预占配额

配额按“用户 + 配额日期 + 任务类型”独立记录。生图和扩图分别记账。

先幂等补建当天的 `ai_quota_usage` 行，再对该行加锁：

```sql
SELECT * FROM ai_quota_usage
WHERE userId = ? AND usageDate = ? AND taskType = ?
FOR UPDATE
```

成本为：

```text
cost = max(1, ai_model.quotaCost)
```

权威判据是：

```text
usedCount + reservedCount + cost > 当日上限
```

超过则拒绝，否则执行：

```text
reservedCount += cost
```

不能拿“剩余额度”当判定阈值，因为剩余额度本身就是 `上限 - 已用 - 已占`，把它代回判断会变成错误的恒等关系。剩余额度只用于展示。

#### 第四步：插入任务

任务插入时状态直接是 `queued`，不是先插入再修改状态。主要字段快照如下：

| 字段组 | 写入内容 |
| --- | --- |
| 请求身份 | `userId`、`idempotencyKey`、`taskType` |
| 模型快照 | `modelId`、`modelCode`、`provider`、`providerModel` |
| 请求参数 | `prompt`、`ratio`、`quality`、背景、格式、压缩质量 |
| 图片引用 | `sourcePictureId`、`referencePictureId` |
| 调度字段 | `status=queued`、`nextAttemptAt=当前时间` |
| 配额字段 | `quotaCost`、`quotaRefunded=0`、`quotaSettled=0` |
| 执行字段 | `invocationStarted=0`、尝试次数、执行令牌、执行者和租约初始为空或 0 |
| 结果字段 | 上游回执、结果图片、失败码和失败原因初始为空 |

任务保存模型和参数快照，因此管理员之后修改 `ai_model`，不会改变已经提交任务的执行语义。

#### 第五步：插入 Outbox

同一事务插入 `ai_task_outbox`：

| 字段 | 初始值 |
| --- | --- |
| `eventId` | 随机唯一事件标识 |
| `taskId` | 新任务主键 |
| `status` | `pending` |
| `attemptCount` | 0 |
| `nextAttemptAt` | 当前时间 |
| 锁、发布时间、错误 | 空 |

任务在 MySQL，消息在 RabbitMQ，两者无法共享本地事务。Outbox 把“必须发送消息”先变成数据库事实，使任务创建和待发送事件同时提交或同时回滚。

Outbox 保证的是“至少投递一次”，不是“只投递一次”。如果 RabbitMQ 已确认消息，但进程在更新 Outbox 状态前退出，同一事件会再次发布。因此消费端必须幂等。

### 4.5 Outbox 投递到 RabbitMQ

Publisher 每秒扫描一次：

```sql
SELECT * FROM ai_task_outbox
WHERE status IN ('pending', 'failed')
  AND nextAttemptAt <= now
  AND (lockUntil IS NULL OR lockUntil <= now)
LIMIT 50
```

扫描刻意不按主键排序。查询索引以状态为前导列，额外排序会在积压时诱导优化器改走低效扫描，而消息投递并不要求严格 FIFO。

每条事件的处理过程：

1. 用条件更新抢占事件，写入 `lockOwner` 和 30 秒 `lockUntil`。
2. 发布持久化消息，消息体只有 `eventId` 和 `taskId`。
3. 最多等待 5 秒 Publisher Confirm。
4. Confirm 成功且消息未被退回，事件改成 `published`。
5. 失败则改成 `failed`，记录错误和次数，以指数退避重试，最长 60 秒。
6. 已发布事件保留 7 天，每小时最多清理 1000 条。

### 4.6 消费者先做三道便宜拦截

Consumer 收到消息后先读取一次任务快照：

| 条件 | 处理 |
| --- | --- |
| 任务不存在或已经终态 | 直接 ACK，作为重复消费的幂等屏障 |
| `queued` 但 `nextAttemptAt` 尚未到 | 按剩余时间投入 5、30 或 120 秒延迟队列 |
| `running` 且租约未过期 | 说明其他 Worker 正在执行，5 秒后再投 |

这三道检查发生在申请 Redis 许可之前，因为上游并发许可比一次数据库主键查询更稀缺。

它们只是性能优化，不是正确性保证。读取完成后状态可能马上变化，真正的执行权由下一步的条件更新决定。

### 4.7 Redis 双重限流

Worker 必须依次通过两道闸：

1. 全局并发许可。
2. 速率令牌。

任意一道失败都不原地等待，而是返回 5 秒延迟重投，让工作线程服务其他任务。

#### 4.7.1 并发许可

并发许可用 Redis 有序集合表示：

- 成员：每次申请生成的随机许可标识。
- 分值：许可到期时间。
- 成员数量：当前占用的全局并发数。

一段 Lua 原子完成：

```text
取 Redis 当前时间
  -> 删除分值已过期的成员
  -> 检查成员数是否达到上限
  -> 未达到则加入自己的许可
  -> 设置键的过期时间
```

使用 Redis 时间可以避免多实例应用时钟偏差。使用随机标识可以避免同一线程连续申请时覆盖旧许可。Lua 把清理、计数、判断和写入合成原子操作，避免多个实例同时看到最后一个空位并一起放行。

许可对象只保存在执行线程的调用栈中，记录 Redis 键、随机标识和防重复释放标志。退出执行块时主动释放；进程退出或 Redis 释放失败时，由许可租期最终回收。

#### 4.7.2 速率令牌

速率限制采用惰性补充令牌桶。Redis 哈希只保存：

- `tokens`：上次操作后的令牌余量，可以是小数。
- `updated`：上次更新时间。

每次申请时计算：

```text
当前余量 = min(容量, 旧余量 + 时间差 × 每毫秒补充量)
```

余量至少为 1 就扣除一个并放行，否则拒绝。没有定时器真正往桶里放令牌，补充量由时间差即时计算。

当前 60 次/分钟等价于平均每秒补 1 个令牌，容量 4 允许空闲后瞬时放行最多 4 个请求。容量只影响突发，不改变长期平均速率。

只做并发限制会在请求很快时突破每分钟配额；只做速率限制会在请求很慢时积累大量同时在飞的连接。两者解决的是正交问题。

顺序必须是先并发、后速率。速率没有通过时，要释放刚取得的并发许可；并发没有通过时，速率桶尚未被消耗。

### 4.8 MySQL 原子抢占执行权

通过一条条件更新完成抢占：

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
  AND (
    (status = 'queued' AND (nextAttemptAt IS NULL OR nextAttemptAt <= ?))
    OR
    (status = 'running' AND leaseUntil IS NOT NULL AND leaseUntil <= ?)
  )
```

更新一行表示抢占成功，零行表示抢占失败。判断写在 `WHERE` 中，避免“先查后改”的竞态。

字段职责：

- `workerId`：当前执行者身份。
- `leaseUntil`：当前执行权的租约截止时间。
- `executionToken`：每次抢占加一的栅栏令牌。
- `attemptCount`：只有真正抢占执行权时才加一。
- `invocationStarted`：用于判断取消时是否已经产生上游调用成本。

抢占失败后必须再读最新状态：

- 已终态：ACK 丢弃。
- 仍为 `queued` 且次数已用完：直接终态化并归还配额。
- 其他情况：5 秒后重投。

如果遗漏“次数已用完”的处理，任务会永远保持 `queued`，消息每 5 秒重投一次，配额预占也永远无法释放。

### 4.9 Worker 同步调用上游

Worker 根据任务快照组装上游请求：

```text
任务类型
providerModel
prompt
ratio -> 上游尺寸
quality -> 上游清晰度值
background
outputFormat
outputCompression
源图临时地址
参考图临时地址
```

源图和参考图优先使用临时签名地址。上游调用是同步阻塞 HTTP：工作线程发送请求后在 socket 上等待响应，直到收到结果或触发 120 秒超时。

这里没有“后台监听上游”的线程，也不是由本系统轮询上游任务。当前上游实现只支持生图，扩图会直接返回能力不支持。

### 4.10 落盘前重新确认执行权

上游返回后、写图片之前，Worker 先执行一次带 `workerId + executionToken` 条件的租约更新：

```sql
UPDATE ai_task SET leaseUntil = ?
WHERE id = ?
  AND status = 'running'
  AND workerId = ?
  AND executionToken = ?
```

更新失败说明当前 Worker 已经失去任务归属。此时只 ACK 消息，不把任务置失败，因为任务已经属于新的执行者或等待恢复服务处理。

这一检查能避免无权 Worker 继续做对象存储写入、图片建表和空间计数更新，但它只是前置优化。检查与最终终态事务之间仍有时间窗口，最终正确性必须由终态事务再次校验。

随后再读一次任务状态。如果用户已经取消，结果直接丢弃，不进入图片存储。因为上游调用已经发生，取消不会返还配额。

### 4.11 结果统一落入 MinIO 和图片模型

上游可能返回 Base64 图片，也可能返回图片 URL。两者都必须经过后端 `PictureStorage`：

```text
Base64 -> 解码 -> PictureStorage
URL    -> 后端下载 -> PictureStorage
```

保存过程包括：

1. 校验图片格式、大小、尺寸和内容。
2. 写入 MinIO 原图。
3. 生成并写入缩略图。
4. 创建 `picture` 记录。
5. 增加个人空间的总大小和图片数量。
6. 创建图片初始版本记录。

主要图片字段：

| 字段组 | 内容 |
| --- | --- |
| 站内访问 | `url`、`thumbnailUrl`，均为后端私有资源地址 |
| 存储定位 | `storageProvider=minio`、原图键、缩略图键 |
| 文件属性 | 内容类型、校验值、大小、宽高、比例、格式 |
| 业务属性 | 名称、提示词简介、`AI 创作` 分类和 AI 标签 |
| 归属 | 当前用户和个人空间 |
| 可见性 | 默认私有、未申请公开 |

如果图片记录或空间用量更新失败，保存服务会删除已经写入 MinIO 的原图和缩略图，避免只留下对象存储垃圾。

### 4.12 终态化和配额结算必须同事务

结果图片创建完成后，终态事务锁定任务行：

```sql
SELECT * FROM ai_task WHERE id = ? FOR UPDATE
```

权威校验三要素：

```text
status == running
workerId == 当前 Worker
executionToken == 本次抢占取得的令牌
```

全部匹配才允许写：

- `status = succeeded`
- 上游任务标识和请求标识
- `resultPictureId`
- `finishTime`
- 清空 `workerId` 和 `leaseUntil`

同一事务内结算配额：

```text
reservedCount -= cost
usedCount += cost
quotaSettled = 1
写 quota audit，action = settled
```

结算不重新检查上限，因为预占时已经锁定了额度。结算只是把同一份额度从“占用中”搬到“已消耗”，`usedCount + reservedCount` 不会增加。

如果三要素不匹配，说明当前 Worker 已失权。刚创建的图片必须整体补偿删除，包括图片行、MinIO 原图和缩略图、空间用量和版本记录。

### 4.13 用户侧查询、取消和下载

任务列表按当前用户读取 `ai_task`，可以叠加状态过滤，按创建时间和主键倒序分页。

任务中心读取任务状态、失败原因和结果引用。成功任务返回结果图片引用和鉴权下载地址。

下载流程：

```text
校验任务归属
  -> 校验 status=succeeded 且 resultPictureId 非空
  -> 读取 picture.objectKey
  -> 从 MinIO 加载文件流
  -> 通过后端附件响应返回
```

取消流程会锁定任务行：

- `queued` 且 `invocationStarted=0`：上游尚未调用，取消并归还预占。
- 已发起调用：取消但不归还，将预占结算为已用。

无论哪一种取消，都会写完成时间并清理执行者和租约。

---

## 五、失败与重试

### 5.1 可重试错误

以下错误在未达到最大尝试次数时可以重试：

- 上游限流。
- 上游明确返回超时，如 HTTP 408 或 504。
- 上游 5xx。

任务更新为：

```text
status = queued
记录 failureCode / failureReason
清空 workerId / leaseUntil
nextAttemptAt = 当前时间 + 5、30 或 120 秒
```

Consumer 必须先成功发布延迟重试消息，再 ACK 原消息。如果重试消息发布失败，就 NACK 原消息并要求重新入队，避免任务丢失。

### 5.2 上游结果未知不能盲目重试

必须区分两种超时：

| 情况 | 处理 |
| --- | --- |
| 上游明确返回超时 | 可重试 |
| 本地 socket 等待超时或连接层异常 | 改写为“上游结果未知”，不重试 |

连接层异常时，上游可能已经收到请求、完成生成甚至产生费用，只是响应没有成功到达本系统。盲目重试会造成重复生成和重复上游成本。

保守策略的代价是：任务失败并归还平台配额，但上游若实际成功，那张图片也无法找回。

### 5.3 其他失败

| 情况 | 处理 |
| --- | --- |
| 上游 403 | 下架模型，任务失败并归还预占 |
| 尝试次数用尽 | 任务失败并归还预占 |
| 图片落盘失败 | `result_persistence_failed`，任务失败并归还预占 |
| 不可重试 Provider 错误 | 任务失败并归还预占 |
| 租约已经丢失 | 当前 Worker 只 ACK，不再修改任务 |

所有结算和归还都通过统一收尾逻辑执行，并使用 `quotaSettled`、`quotaRefunded` 保证幂等。

---

## 六、四条独立兜底链路

事件驱动无法覆盖进程被强杀、消息进入死信和某个收尾分支遗漏，因此系统另外提供四条链路。

### 6.1 租约续期

每 90 秒按实例标识批量更新本实例持有的 `running` 任务：

```sql
UPDATE ai_task SET leaseUntil = ?
WHERE status = 'running'
  AND workerId LIKE '实例标识:%'
```

续租采用“重置”语义：

```text
leaseUntil = 当前时间 + 租期
```

不能采用“在原到期时间上累加”的语义。累加会使频繁续租不断扩大未来租期，进程真正退出后可能要等待数小时才能恢复，失去租约作为故障回收上界的意义。

### 6.2 租约过期恢复

每 30 秒扫描最多 50 个租约过期的 `running` 任务。扫描只是候选快照，逐条处理时必须在事务中锁行并再次检查状态和租约，防止误杀刚刚续租或已经终态化的任务。

确认过期后：

```text
status = queued
清空 workerId / leaseUntil
nextAttemptAt = 当前时间
failureCode = worker_lease_expired
同事务插入新的 Outbox 事件
```

恢复不增加 `attemptCount`，也不归还配额，因为它只是在回收执行权，任务还没有失败。

### 6.3 死信消费

死信队列使用独立单并发消费者，不占用正常 Worker 线程：

- 已终态任务直接 ACK。
- 非终态任务改为 `failed`。
- 错误码记为 `dead_letter`。
- 归还预占并写审计记录。
- ACK 后不再投递，避免死循环。

### 6.4 配额对账

每 10 分钟扫描最多 200 条：

```text
quotaSettled = 0
quotaRefunded = 0
createTime 早于 11 分钟前
```

锁行后按真实状态处理：

- `succeeded`：补做结算。
- 其他终态：补做归还。
- `running` 且租约未过期：跳过。
- 非终态且超过僵尸年龄：置失败并归还。

候选门槛和僵尸判定必须使用同一个配置值。如果候选门槛比僵尸年龄更晚，僵尸判断就会失去实际意义。

对账日志记录扫描数、修正数和耗时。修正数长期不为零，说明主事件链路存在持续遗漏，应当触发监控告警。

---

## 七、关键机制为什么这样设计

### 7.1 Outbox 解决跨系统原子性

直接“数据库提交后发消息”会在消息发送失败时留下永久排队任务；直接“先发消息再提交数据库”会让消息指向不存在的任务。

Outbox 不尝试让 MySQL 和 RabbitMQ 共享事务，而是：

1. 在 MySQL 本地事务里同时保存任务和待发送事件。
2. 由独立 Publisher 重复尝试投递。
3. 由消费端状态机吸收重复消息。

这是“本地原子写 + 至少一次投递 + 幂等消费”的组合。

### 7.2 配额使用两阶段记账

配额分成：

- `reservedCount`：已经接受但尚未确定成功的任务。
- `usedCount`：已经成功或按产品语义应计费的任务。

提交时预占，成功时结算，确定失败时归还。预占把并发竞争前移到任务创建时，因此任务完成时只做记账，不会出现“图片已经生成并产生成本，但完成时额度被其他任务抢完”的无解状态。

任务跨越午夜时，配额始终归属任务创建日。结算、归还、审计和对账都必须通过同一套时区换算得到该日期。

每日重置不需要修改旧行，只需要新日期使用新的 `ai_quota_usage` 行，因此 `usedCount` 只增不减。

### 7.3 租约和栅栏令牌解决不同问题

| 机制 | 回答的问题 |
| --- | --- |
| 租约 | 当前执行者失联多久后，其他实例可以接管？ |
| `workerId` | 当前是哪一个执行者？ |
| `executionToken` | 这是第几代执行权，旧执行者是否已经过期？ |

栅栏令牌必须存放在 MySQL 任务行，不能复用 Redis 并发许可标识：

- Redis 标识用于释放共享产能，不用于任务归属。
- Redis 键可能过期或丢失，执行权不能建立在易失数据上。
- MySQL 可以在同一条件更新或事务中原子校验状态、执行者和令牌。

令牌使用自增值而不是随机值，除了支持相等性判断，还能直观看出任务经历了第几次抢占。

### 7.4 前置检查与权威判定分离

系统里有两组相同模式：

| 前置检查 | 权威判定 |
| --- | --- |
| 消费端任务快照和三道只读拦截 | MySQL 条件更新抢占 |
| 图片落盘前确认租约 | 终态事务三要素校验 |

前置检查便宜，用于尽早停止无效重活；权威判定必须原子执行，用于保证正确性。前置检查可能因为并发立即过时，因此不能替代权威判定。

### 7.5 六类“过期”不能混淆

| 名称 | 默认值 | 存储位置 | 回收方式 | 效果 |
| --- | --- | --- | --- | --- |
| 速率令牌键存活时间 | 8 秒 | Redis 哈希 | Redis 删除键 | 下次按满桶开始 |
| 并发许可键存活时间 | 600 秒 | Redis 有序集合键 | Redis 删除键 | 所有许可成员消失 |
| 并发许可成员租期 | 300 秒 | Redis 集合成员分值 | 下次申请时按分值清理 | 释放一个并发名额 |
| 任务租约 | 300 秒 | MySQL `ai_task` | 恢复服务扫描 | 任务重新排队 |
| 延迟消息 TTL | 5 / 30 / 120 秒 | RabbitMQ 延迟队列 | TTL 到期后死信回投 | 回到主任务队列 |
| 幂等键 | 永不过期 | MySQL 唯一键 | 不回收 | 同一用户不能复用旧键创建不同请求 |

速率键和并发许可键都使用基础时长的两倍作为键 TTL，但含义不同：

- 速率键 TTL 只要不短于从空桶补满所需时间，就不会凭空增加令牌；乘二是安全余量。
- 并发许可键 TTL 必须长于成员租期，否则整个键可能在仍有有效许可时被删除，导致并发限制短暂失效。

---

## 八、从字段角度看一条成功任务

假设某个模型成本为 1，用户当天初始状态是：

```text
usedCount = 10
reservedCount = 2
dailyLimit = 100
```

### 8.1 提交成功

```text
ai_quota_usage.reservedCount: 2 -> 3

ai_task:
  status = queued
  quotaCost = 1
  quotaSettled = 0
  quotaRefunded = 0
  invocationStarted = 0

ai_task_outbox:
  status = pending
```

### 8.2 消息发布并抢占

```text
ai_task_outbox.status: pending -> published

ai_task:
  status: queued -> running
  invocationStarted: 0 -> 1
  attemptCount: 0 -> 1
  executionToken: 0 -> 1
  workerId: null -> 当前执行者
  leaseUntil: null -> 当前时间 + 300 秒
  startTime: null -> 当前时间
```

### 8.3 图片保存

```text
MinIO:
  写入原图对象
  写入缩略图对象

picture:
  插入私有 AI 图片

space:
  totalSize += 图片大小
  totalCount += 1

版本表:
  插入初始版本
```

### 8.4 任务成功并结算

```text
ai_task:
  status: running -> succeeded
  resultPictureId = 新图片主键
  providerTaskId / providerRequestId = 上游回执
  quotaSettled: 0 -> 1
  finishTime = 当前时间
  workerId / leaseUntil = null

ai_quota_usage:
  reservedCount: 3 -> 2
  usedCount: 10 -> 11

ai_task_quota_audit:
  action = settled
```

整个过程中：

```text
usedCount + reservedCount
提交前 = 12
预占后 = 13
结算后 = 13
```

结算只是从 `reserved` 搬到 `used`，不会再次消耗一份额度。

---

## 九、已知边界

### 9.1 活进程中的线程假死

租约续期按实例批量执行，它能判断 JVM 是否还活着，却不能判断某个任务线程是否仍在推进。

如果线程进入死循环或不可中断等待，而 JVM 仍能运行定时任务，续租器会一直延长租约，恢复服务和配额对账都会跳过这个任务。

可选改进：

- 记录任务进展时间，只为近期有进展的任务续租。
- 增加从首次开始执行计算的最大总时长，到期后无视活跃租约强制回收。

最大总时长不能从创建时间计算，否则队列积压时可能误杀刚开始执行的任务。

### 9.2 Redis 并发许可没有接入续期

许可对象提供续期能力，但执行路径没有调用。当前依赖“120 秒上游超时 + 数秒落盘”显著小于 300 秒许可租期。

如果未来把上游超时提高到 5 分钟以上，必须同时调整许可租期或接入许可续期，否则许可会在请求仍执行时过期，实际并发可能短暂超过上限。

### 9.3 上游超时与任务租期缺少代码级联校验

代码只强制校验“续租周期乘三不超过租期”，没有强制校验“上游超时加落盘时间小于租期”。修改任一时间参数时必须联动检查。

### 9.4 令牌桶允许短时突发

容量 4、速率 60 次/分钟时，某个严格 60 秒窗口内可能出现 64 次放行。因此内部配置必须低于上游真实限额，为桶容量、统计窗口差异和时钟偏差留余量。

### 9.5 同步上游调用受 HTTP 超时硬限制

当前 Provider 不是“提交后轮询”或“回调通知”的异步上游模式。上游若持续超过本地 HTTP 超时，任务无法成功；连接中断后又无法确认结果，系统只能按“结果未知”失败收尾。

更根本的改进需要上游支持任务提交标识、结果轮询或回调。

---

## 十、快速复述

可以用下面一段话概括整个设计：

> 用户提交时，后端在一个 MySQL 事务里完成幂等校验、配额预占、任务创建和 Outbox 写入，然后立即返回排队状态。Outbox Publisher 使用 RabbitMQ Confirm 做至少一次投递，Consumer 先过滤重复或未到期消息，再经过 Redis 全局并发和速率限制，通过 MySQL 条件更新抢占任务并取得租约与栅栏令牌。Worker 同步调用上游，把结果统一写入私有 MinIO 和个人空间图片模型，最后在一个事务里校验执行权、完成任务并把预占额度结算为已用。租约续期与恢复、死信消费者和配额对账覆盖进程退出、死信和收尾遗漏。前置检查负责减少浪费，数据库里的条件更新和终态事务负责最终正确性。
