# 茶杯图库多级缓存重设计

> 状态：**已被简化版取代（2026-09-25）**。本文件描述的原方案（Cache Gateway + 事务发件箱 + RabbitMQ 失效事件 + Redisson 分布式锁）已下线并移除，现行实现为「Caffeine 本地缓存（键含共享版本号）+ Redis 共享缓存 + 共享版本号轮询同步」，详情类精确删除、列表类版本号切换。设计与迁移说明见 `docs/缓存方案简化设计.html`。
>
> 本文件保留作为决策过程记录：第 1、3、9.4 节（一致性立场、数据分类、依赖故障矩阵）仍然适用；第 4、5、7 节（统一网关、版本化 key、事务发件箱）与第 12 节（迁移步骤）描述的是旧实现，已过时。
>
> 更新日期：2026-09-21（架构） / 2026-09-25（状态标注）
>
> 适用范围：`backend/` 的 `/api/v1` 读路径、公开图库、图片元数据和后续空间/用户读模型。
>
> 非适用范围：登录 Session、AI 配额、评论/分享口令限流、RabbitMQ 消息本身、MinIO 原图持久化。这些系统可以使用 Redis 或消息队列，但不属于本方案的“缓存值”。

## 1. 结论先行

本方案采用 **Cache-Aside + 版本化命名空间 + 事务 Outbox 失效事件 + 本机单飞**，形成以下数据路径：

```text
浏览器/CDN HTTP 缓存（仅公开、可缓存资源）
        ↓ miss
L1：进程内 Caffeine（每实例，短 TTL，带权重上限）
        ↓ miss
L2：独立 Redis Cache 集群（跨实例共享，JSON Envelope）
        ↓ miss
L3：MySQL / MinIO（唯一事实来源）
```

核心原则：

1. **缓存不是事实来源。** MySQL 和 MinIO 是事实来源；缓存只保存可重建的派生结果。
2. **不再依赖 `SCAN` 清理缓存。** 任何失效都通过版本号或精确 key 完成，旧 key 自然过期。
3. **不把所有读请求都缓存。** 私有空间列表和权限相关 DTO 默认不进入共享缓存，避免把权限问题伪装成性能问题。
4. **不把所有问题都交给锁。** 本机请求合并解决单实例并发，Redis 分布式锁只负责跨实例选主；等待有上限，不能无限 `sleep`。
5. **缓存故障必须分级降级。** Redis 故障时公开数据允许短时间读本机 stale；权限、分享口令和限流数据必须失败关闭或直接访问其权威存储。
6. **一致性必须写成契约。** 公开图库接受有界最终一致；安全边界、私有权限、分享撤销不能依赖缓存放行。

## 2. 当前方案为什么推倒重来

当前仓库存在三套互不统一的缓存习惯：

| 现状 | 位置 | 问题 |
| --- | --- | --- |
| Caffeine Bean 直接暴露 | `backend/src/main/java/com/teacup/teacuppicturebackend/config/CacheConfig.java` | 三个 cache 的类型、TTL、容量和序列化约定分散，无法统一观测和限流。 |
| L1 → Redis → Redisson 锁 | `M1Service.cached` | L1/L2 使用同一个字符串 key；没有统一 Envelope、schema 版本和 stale 语义；Redis 失败后直接打 DB，热点故障时会放大流量。 |
| 失效观察者 + Redis `SCAN` | `PictureCacheClearObserver` | 更新一次图片会扫描并删除整类分页/搜索 key；复杂度与 key 数量相关，跨实例本地缓存也无法被可靠通知。 |
| 全量公开缓存版本 | `M1Service` | 公开列表和详情共用粗粒度版本；一次变化可能令全部详情失效，且版本读取、缓存读取和本地缓存命中没有统一协议。 |
| 方法内自行拼 key/TTL | `M1Service`、旧 `/api/**` | key 命名、随机 TTL、异常处理和锁等待不可复用，新增缓存容易复制缺陷。 |

这些问题不是“把 TTL 调大一点”可以解决的：

- `SCAN` 不是失效协议，不能证明缓存已经在所有实例被清除。
- 本机 Caffeine 没有广播机制，实例 A 的写入不会自动清掉实例 B 的 L1。
- Redis 故障时所有 miss 同时回源，会把数据库变成新的雪崩点。
- 缓存 DTO 与权限判断混在一起，缓存命中不等于用户有权看到或下载。
- 旧方案没有定义缓存键的 schema 版本、数据版本、负值和 stale 窗口，无法安全演进。

## 3. 缓存边界与数据分类

### 3.1 允许进入本方案的缓存

| 数据 | 缓存策略 | 一致性等级 | 说明 |
| --- | --- | --- | --- |
| 公开图片详情 | L1 + L2，版本化 key | 有界最终一致，目标 5 秒内 | 只包含公开 DTO，不包含权限、审核备注、私有空间信息。 |
| 公开图库游标页 | L1 + L2，目录版本 key | 有界最终一致，目标 10 秒内 | 只缓存固定 `limit` 范围；不缓存任意排序/过滤组合。 |
| 公开用户展示信息 | 可嵌入公开 DTO 或独立短缓存 | 有界最终一致 | 用户改名/头像通过用户版本失效。 |
| 只读标签/分类字典 | L1 + L2 | 分钟级最终一致 | 变更低频，允许主动预热。 |
| 图片公开派生资源的 HTTP 响应 | 浏览器/CDN | 由不可变版本 URL 保证 | 应用不把二进制塞进 Caffeine/Redis。 |

### 3.2 默认不进入共享缓存的数据

| 数据 | 原因 | 处理方式 |
| --- | --- | --- |
| 私有图片详情、个人空间列表 | 依赖用户/成员/角色权限，缓存键维度容易漏权限 | 先做权限校验，再读 MySQL；后续如有明确收益，只缓存“无权限信息的公共核心数据”。 |
| 管理员审核队列 | 写多、权限敏感、实时性要求高 | DB 查询 + 合理索引；不以缓存掩盖分页设计问题。 |
| 分享口令校验结果 | 失败次数、撤销、过期是安全状态 | Redis 仅作为限流/短期状态的权威组件，失败关闭；不使用普通 cache-aside。 |
| Session、AI 配额、分布式租约 | Redis 中的业务状态，不是可丢失缓存 | 使用独立 keyspace/实例和明确的持久化策略。 |
| MinIO 原图、缩略图二进制 | 大对象、内存成本高、生命周期独立 | MinIO 存储；公开资源使用带版本的 URL 和 HTTP 缓存。 |

### 3.3 一条必须坚持的安全规则

缓存命中后仍然要执行资源权限检查，除非该 DTO 明确标注为 `PUBLIC_READ_MODEL`。缓存不能替代：

- `spaceAccess` 的成员/角色判断；
- 分享 secret、密码、撤销时间和过期时间校验；
- 管理员权限；
- 私有 MinIO 对象的鉴权下载。

## 4. 统一 Cache Gateway

业务代码不再直接注入 `Cache<String, String>`、`StringRedisTemplate` 或 `RLock`。新增统一抽象：

```java
public interface CacheGateway {
    <T> CacheRead<T> get(CacheSpec<T> spec, CacheLoader<T> loader);
    void evict(CacheInvalidation invalidation);
    void publish(CacheInvalidation invalidation);
}
```

建议的核心对象：

```text
CacheSpec<T>
  namespace       // public-picture-detail / public-picture-list
  key             // 规范化业务 key，不含随机字符串
  consistency     // EVENTUAL / BYPASS_ON_CACHE_ERROR
  softTtl         // 允许后台刷新后的逻辑过期时间
  hardTtl         // 最晚可返回 stale 的时间
  negativeTtl     // 空值缓存 TTL
  loaderTimeout   // DB/下游最大等待时间
  serializer      // Jackson JSON，带 schemaVersion

CacheEnvelope<T>
  schemaVersion
  sourceVersion   // DB/领域版本或失效代数
  state           // HIT / NEGATIVE / STALE
  bornAt
  softExpireAt
  hardExpireAt
  value
```

L1 使用类型安全的对象或 Envelope，不保存裸 JSON 字符串；L2 使用 JSON Envelope，禁止 Java 原生序列化。这样可以：

- 在不清空整个 Redis 的情况下升级 DTO schema；
- 区分“没有值”和“值就是空列表”；
- 记录 source version，排查脏读；
- 明确 stale 是否允许返回；
- 让命中、负值、过期、回源和降级都有统一指标。

## 5. Key 设计

统一前缀：

```text
tp:cache:v2:{domain}:{resource}:{scope}:{identity}:g{generation}
```

示例：

```text
tp:cache:v2:public:picture:detail:id=123:g=42
tp:cache:v2:public:picture:list:cursor=first:limit=20:g=108
tp:cache:v2:public:user:summary:id=9:g=7
tp:cache:v2:meta:picture-taxonomy:all:g=3
```

规则：

1. `v2` 是缓存协议版本，不与接口版本混用。
2. `scope` 必须显式写出；不能把用户 ID、空间 ID 隐藏在 JSON 或调用上下文里。
3. 参数先 canonicalize：去除无意义空格、固定排序字段、限制枚举值、统一大小写，再参与 key 计算。
4. 长查询参数使用 SHA-256，而不是直接拼接用户输入；同时把 canonical query 作为 debug 字段记录。
5. 版本代数 `g` 变化时直接切换新 key，旧 key 仅等待 TTL；禁止通配符删除。
6. 锁 key 与数据 key 分离：`tp:lock:v2:{domain}:{hash}`，锁值带随机 token，释放必须校验 token。

### 5.1 版本代数

- `public-picture-list` 使用一个目录代数 `catalogGeneration`；公开、撤回、删除、影响排序的字段变化都递增它。
- `public-picture-detail:{id}` 使用每图片代数 `pictureGeneration:{id}`；只影响单图的元数据更新不必清空全部详情。
- 代数保存在 Redis 的专用 generation key，并由失效事件消费者原子递增。
- 旧代数 key 不删除，依靠短 TTL 回收，避免广播丢失时产生全库 `SCAN`。
- 读取 generation 失败时，按 `CacheSpec.consistency` 决定：公开数据可在 hard TTL 内返回本机 stale；敏感路径直接绕过缓存访问事实来源。

## 6. 读路径

### 6.1 正常命中

```text
请求
  → 参数规范化、鉴权边界确认
  → L1 查 Envelope
  → L1 HIT 且未过 soft TTL：直接返回
  → L1 miss/soft expired
  → 读取 generation + L2 payload（pipeline）
  → L2 HIT：回填 L1，返回
```

L1 的命中不代表无限期可信：

- 普通公开元数据：L1 5～15 秒，允许事件驱动提前失效；
- 公开可见性变更：L1 只保留极短窗口，generation 不可读时不返回旧值；
- 私有数据：不走共享 Cache Gateway。

### 6.2 miss、单飞与回源

```text
L2 miss
  → 本机 Caffeine single-flight（同 JVM 同 key 只允许一个 loader）
  → Redis 分布式锁（跨实例只选一个 owner）
  → double-check L2
  → DB/MinIO loader，设置 loaderTimeout
  → 写 L2（TTL + jitter）
  → 写 L1
  → 释放锁
```

等待策略：

- follower 最多等待 80～150ms，不做无限重试；
- owner 超时或异常时，公开数据可返回未超过 hard TTL 的 stale；
- 没有可用 stale 时返回受控错误或降级响应，不能让所有请求无锁并发打 DB；
- 锁使用租约和 token，禁止客户端强制删除不属于自己的锁。

### 6.3 stale-while-revalidate

Envelope 有两个时间点：

- `softExpireAt`：触发后台刷新，首个请求负责提交刷新任务；
- `hardExpireAt`：超过后不再返回 stale，必须回源或失败。

这比“随机 TTL + 过期后全部等待”更稳定：热点公开图片在刷新期间仍可读，数据库只承担一次回源。

## 7. 写路径与失效

### 7.1 事务边界

业务写入统一遵循：

```text
MySQL 业务表更新
  + cache_invalidation_outbox 写入
  → 同一事务提交
  → Outbox Publisher 发布 RabbitMQ Quorum Queue
  → Publisher Confirm 成功后标记已发布
  → Cache Invalidation Consumer:
       1. 原子递增 generation
       2. 删除当前实例 L1 精确 key
       3. 记录处理 offset / eventId（幂等）
```

缓存失效事件至少包含：

```text
eventId, aggregateType, aggregateId, changedFields,
sourceVersion, catalogGeneration, occurredAt, schemaVersion
```

事件消费者必须幂等。重复事件只允许重复递增到同一个或更高版本，不能把 generation 回退。

### 7.2 为什么不用“写 DB 后直接删 Redis”

直接双写存在经典竞态：

1. 请求 A 更新 DB；
2. 请求 B 读到旧值并回填 Redis；
3. 请求 A 删除 Redis；
4. 旧值重新出现。

本方案用 **事务 Outbox + generation key** 把“数据库提交”和“失效消息一定存在”绑定起来；缓存值本身仍是可丢失的，事件可以重放。

### 7.3 公开状态变更

以下操作必须递增 `public-picture-list` 目录代数：

- 审核通过；
- 撤回公开；
- 软删除/恢复；
- 修改会影响公开列表排序或展示的字段。

只改变私有字段的更新不应影响公开目录。详情代数按图片粒度递增。

## 8. TTL、容量与序列化基线

这是初始值，不是拍脑袋的永久常量；上线后按命中率和回源成本调参。

| CacheSpec | L1 | L2 soft TTL | L2 hard TTL | 负值 TTL | 容量原则 |
| --- | ---: | ---: | ---: | ---: | --- |
| 公开图片详情 | 10s | 30s | 180s | 15s | 按对象权重，优先保留热点 |
| 公开图库游标页 | 5s | 10s | 60s | 5s | 只允许 `limit <= 50`，限制 key cardinality |
| 公开用户摘要 | 30s | 2min | 10min | 30s | 头像/昵称 DTO 不含权限 |
| 标签/分类字典 | 30s | 5min | 30min | 1min | 发布后主动预热 |

TTL 计算：

```text
physicalTtl = baseTtl + random(0, 0.2 * baseTtl)
```

注意：随机 TTL 只是雪崩缓解，不是失效一致性方案。

L1 采用：

- `maximumWeight` 而非简单 entry count；
- `weigher` 按序列化大小或近似对象大小计权；
- `recordStats()`；
- 独立 cache name 和独立配额；
- 不缓存大于单项上限的 DTO；
- 不使用 `expireAfterAccess` 伪装热点永不过期。

L2 Redis 采用独立缓存实例或至少独立集群/容量池，不能与 Session、限流、AI 租约共享可淘汰空间。生产缓存实例的 eviction policy、内存上限、连接池和慢查询告警必须单独配置。

## 9. 穿透、击穿、雪崩与故障矩阵

### 9.1 穿透

- ID、limit、cursor、枚举值在入口严格校验；
- 公开详情的“不存在/不可公开”可以写短 TTL negative Envelope；
- 空列表是合法值，不能用 `null` 代表；
- 不在当前规模引入 Bloom Filter。只有当随机 ID 请求量和 DB 访问量证明必要时，才评估可重建的 Bloom Filter；
- 对高频随机 ID 同时做 IP/用户维度限流和异常审计。

### 9.2 击穿

- 本机 single-flight；
- 跨实例 Redis lock；
- soft/hard TTL + stale-while-revalidate；
- follower 有界等待，失败时按 consistency 降级；
- 热点 key 支持后台预热和人工刷新，但预热任务必须限速。

### 9.3 雪崩

- TTL jitter；
- 发布时分批预热，不在应用启动时同步加载整库；
- Redis、DB、MinIO 分别设置连接池和并发上限；
- Cache Gateway 熔断：Redis 连续失败时短路 L2，防止每个请求等待连接超时；
- 公开数据保留本机 stale；
- DB 回源使用 bulkhead，缓存失效不允许占满业务线程池。

### 9.4 依赖故障矩阵

| 故障 | 公开详情/列表 | 私有权限数据 | 分享/限流/Session |
| --- | --- | --- | --- |
| L1 故障 | 继续尝试 L2 | 直接 DB | 不影响权威组件 |
| Redis Cache 故障 | hard TTL 内可返回本机 stale；否则 DB 或 503 | 绕过缓存访问 DB；超时则失败 | 失败关闭，不能猜测放行 |
| RabbitMQ/Outbox 延迟 | 接受事件延迟，但 TTL/hard TTL 有上界；告警 | 不依赖缓存授权 | 不改变权威状态 |
| MySQL 故障 | 仅返回仍在 hard TTL 内的 stale | 失败，不返回未知权限结果 | 依赖各自权威策略 |
| Cache 数据损坏 | 校验失败即删除当前 key，单飞回源 | 不使用缓存放行 | 不吞掉安全状态错误 |

## 10. HTTP 层与图片资源

应用缓存和 HTTP 缓存必须分层设计：

- `GET /api/v1/public/pictures` 和公开详情只返回公开 DTO；`ETag` 使用 DTO 内容 hash 或 generation，`Cache-Control` 的 max-age 不得超过 hard TTL；
- 私有接口和预览接口继续 `no-store`；
- 公开图片内容 URL 必须包含 `currentVersionId` 或内容 checksum，资源替换生成新 URL；
- 对不可变公开资源可以使用长 `max-age`，撤回通过鉴权 API/manifest/generation 控制，不依赖删除 CDN key；
- 浏览器/CDN 命中不能绕过后端权限。私有 MinIO 对象始终由后端鉴权资源接口读取。

## 11. 观测、压测与告警

### 11.1 必须有的指标

按 `cacheName`、结果和实例记录：

```text
cache_l1_requests_total{cache, result=hit|miss|stale|negative|error}
cache_l2_requests_total{cache, result=hit|miss|error}
cache_load_seconds{cache}
cache_load_inflight{cache}
cache_lock_wait_seconds{cache}
cache_evictions_total{cache, reason}
cache_invalidation_lag_seconds{aggregate}
cache_generation{aggregate}
cache_stale_served_total{cache, reason}
cache_payload_bytes{cache}
```

日志只记录 cache name、hash 后的 key、generation、sourceVersion、eventId 和耗时，不记录 Cookie、分享 secret、完整 URL 或图片访问令牌。

### 11.2 SLO 建议

- 公开详情/列表 L1+L2 命中率：`>= 95%`（以稳定流量为准）；
- DB 回源比例：`< 5%`；
- cache load p99：`< 150ms`，不含真正的冷 DB 慢查询；
- invalidation event p99 lag：`< 5s`；
- stale 返回比例：正常小于 `0.1%`，Redis/DB 故障期间必须可观测；
- 单 key 回源并发：`<= 1` owner，follower 超时率告警；
- L2 错误率连续 1 分钟超过 `1%` 触发熔断评估。

### 11.3 必测故障演练

1. 1000 个并发请求同时命中一个过期热点 key，确认 DB 只收到一次 loader。
2. Redis 读超时、Redis 完全不可用、Redis 恢复，确认没有请求风暴。
3. RabbitMQ 延迟 30 秒和重复投递，确认 generation 单调、消费者幂等。
4. 图片审核通过后马上读取公开列表，验证事件延迟和 HTTP `ETag` 行为。
5. 图片撤回/删除期间访问旧详情和原图，确认不会因缓存命中绕过公开状态和资源鉴权。
6. 多实例同时更新同一图片，确认无 `SCAN`、无全库锁、无本地缓存长期脏读。
7. 反序列化 schema 升级和脏 payload，确认自动回源而不是 500 风暴。

## 12. 迁移步骤

### Phase 0：冻结边界（已完成）

- 把 `backend/docs/缓存设计与问题处理.md` 标记为历史说明；本文件作为新基线。
- 列出所有缓存/Redis key owner，禁止业务代码新增裸 key。
- 将 Session、限流、AI 租约和 Cache 使用不同前缀；生产环境规划独立 Redis 资源。

### Phase 1：建立基础设施（已完成）

- 新增 `CacheGateway`、`CacheSpec`、`CacheEnvelope`、`CacheSerializer`、`CacheMetrics`。
- 实现 Caffeine L1、Redis L2、single-flight、有限等待和故障降级。
- 增加统一 key canonicalizer 和 schema version。
- 先只接入公开详情，保留旧实现作为 feature flag fallback。

### Phase 2：建立可靠失效（已完成）

- 增加 `cache_invalidation_outbox` Flyway migration；不要修改已有 `create_table.sql` 作为事实来源。
- 在图片公开状态/公开字段写事务中写 outbox。
- 使用已有 RabbitMQ Quorum Queue 和 Publisher Confirm 机制发布；消费者递增 generation 并清理本机 L1。
- 增加 eventId 幂等表或可重放 offset，支持失败重试和死信告警。

### Phase 3：迁移公开列表和 HTTP 层（已完成）

- 将 `M1Service` 的公开列表/详情迁移到 Gateway；删除方法内的 JSON、Redisson 锁和 TTL 逻辑。
- 删除 `PictureCacheClearObserver` 中的 `SCAN`、全量 wildcard delete 和未使用的“精准清理 TODO”。
- 为公开接口加入 ETag、generation 和 hard TTL 对齐的 Cache-Control。

### Phase 4：扩展与收口（已完成）

- 公开图片详情/列表使用 Gateway；标签/分类、用户摘要保持按需接入原则，未因命中率目标强行缓存。
- 私有列表和空间实体缓存已关闭，继续依赖权限检查、SQL 索引和分页约束。
- 旧 `/api/**` 查询缓存、通配符删除和 `SCAN` 已移除。
- 默认 `mvn test` 已覆盖单飞、负值、版本失效、Outbox 记录、`ETag` 和降级边界；真实 Redis/RabbitMQ/Flyway 流程保留为部署环境的集成验收项。

## 13. 验收标准

实现完成前必须满足：

- 业务代码不再直接操作 Caffeine/Redis cache value；所有缓存读写通过 Gateway。
- 代码库不存在针对缓存业务 key 的 `KEYS`/`SCAN`/通配符删除。
- 同一热点 key 在单实例和多实例压测下都只有一个回源 owner。
- 缓存事件与业务写事务同提交、可重放、可幂等，generation 单调递增。
- L1、L2、DB、消息队列任一故障都有已测试的降级行为。
- 私有资源、分享口令、权限判断不由缓存命中直接放行。
- cache hit/miss/stale/negative/load/invalidation lag 均有指标和告警。
- schema version 升级可以自然 miss 回源，不要求手工清空整套 Redis。
- 公开数据的陈旧窗口、撤回/删除行为和 HTTP `Cache-Control` 有契约测试。

## 14. 面试官追问与标准回答

### 为什么不是“数据库更新后删缓存”？

因为删缓存不是原子操作，存在旧读回填覆盖删除的竞态；而且应用进程崩溃会留下数据库已提交、缓存未失效的状态。事务 Outbox 保证失效意图和业务提交绑定，generation 让失效可重放，不需要扫描删除。

### Redis 挂了，为什么不直接全部查数据库？

因为这会把缓存故障转换成数据库雪崩。公开读模型有 hard TTL 和 stale-while-revalidate，可以有限度保留本机旧值；权限和安全状态则不返回未知结果，按策略绕过缓存或失败关闭。

### 分布式锁过期后，旧 owner 又写回怎么办？

锁只负责选主，不负责数据正确性。写入 Envelope 时带 sourceVersion/generation，消费者和 writer 必须拒绝低版本；必要时使用带 token 的 fencing 语义。即使旧 owner 写入旧代数 key，也不会被当前 generation 读取。

### 为什么不缓存私有空间列表？

因为缓存键必须包含用户、空间、成员角色、权限版本和分页查询；成员变更会产生高复杂度失效。除非压测证明收益，否则把一致性和安全复杂度换来的几毫秒不值得。先做 SQL 索引和权限查询优化。

### 随机 TTL 能解决雪崩吗？

只能打散自然过期时间，不能解决热点 key 击穿、Redis 故障、批量发布或数据库慢查询。这里还需要 single-flight、stale-while-revalidate、回源 bulkhead、熔断和容量治理。

### 为什么不使用 Bloom Filter？

当前图片 ID 查询规模和数据生命周期不需要它。负值缓存、参数校验和限流更简单且没有误判；如果未来随机 ID 穿透成为主要成本，再引入可重建 Bloom Filter，并明确删除/重建和误判兜底。

### 这套方案是强一致吗？

不是。公开图库是有界最终一致，目标是事件延迟和 hard TTL 内收敛；私有权限和安全状态不使用缓存作为授权依据。把一致性等级写进 CacheSpec，才比口头声称“强一致”更诚实也更可运营。

### 为什么不使用 CDN 解决一切？

CDN 适合不可变公开二进制和公开 HTTP 响应，不适合私有权限判断、动态审核状态和分享口令。应用缓存、HTTP 缓存和对象存储各自解决不同层的问题，不能互相替代。

## 15. 明确放弃的做法

- 业务方法内自行创建 Caffeine/Redis key；
- `KEYS`、`SCAN`、通配符删除分页缓存；
- 把 Java 对象原生序列化进 Redis；
- 用一个 Redis 实例同时承载可淘汰 Cache、Session、限流和长租约而不做容量隔离；
- 用 `Thread.sleep` 无限等待分布式锁；
- 缓存带权限语义的 DTO 并跳过鉴权；
- 用“随机 TTL”代替失效协议；
- 为了追求缓存命中率，把所有查询都改成缓存查询。
