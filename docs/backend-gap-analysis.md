# 后端缺口分析

更新日期：2026-08-11

本文以当前 `backend/` 实现和 `docs/product-prd.md` 为基线，记录前端接入前必须确认的后端事实、缺口和建议顺序。它不是已经实现的接口文档。

## 1. 结论与优先级

| 优先级 | 工作项 | 当前结论 |
| --- | --- | --- |
| P0 | 团队空间创建 | 创建顺序错误，团队空间本身未落库就写成员关系；当前流程不可用 |
| P0 | 新仓库凭据清理 | 可提交配置已改为环境变量；迁移前暴露的值仍须在首次共享前轮换 |
| P0 | `/api/v1` 契约基线 | 现有控制器仍为 `/api/**` 和 RPC 风格，不能直接作为新前端稳定契约 |
| P0 | 个人空间自动创建 | 注册只创建用户，没有默认个人空间，也没有事务和幂等补偿 |
| P0 | 公开只读接口 | 公开列表逻辑与私有接口混用，详情仍强制获取登录用户 |
| P1 | AI 任务与配额 | 只有阿里云扩图透传，没有统一任务、历史、状态、取消和配额模型 |
| P1 | 邀请、评论、分享、版本 | 数据表、实体、服务和接口均缺失 |
| P1 | 协作持久化模型 | 只有进程内 WebSocket 广播和单编辑者锁，不具备恢复、排序或审计能力 |
| P2 | 安全加固 | 除凭据清理外按本文安全闸门暂缓，但必须在公网部署前完成 |

此外，当前 `backend/sql/create_table.sql` 只创建 `user`、`picture` 及图片审核字段，连现有 `space`、`space_user` 都未形成可重复执行的完整数据库基线。应优先引入 Flyway，再开展新模型开发。

## 2. 团队空间创建问题

### 当前事实

- `SpaceServiceImpl.addSpace` 在团队分支中先读取尚未持久化的 `space.id`，随后直接保存 `SpaceUser`，但没有保存 `Space`。
- 因此 `spaceUser.spaceId` 为 `null` 或无效值，方法返回的空间 ID 也不可靠；事务无法弥补缺失的空间插入。
- 当前存在性检查按 `userId + spaceType` 限制每类空间只能有一个，与“每个用户最多创建 5 个团队空间”的产品规则冲突。
- 创建者被写为 `admin`，但产品模型还需要独立且唯一的 `owner` 概念，并支持后续所有权转让。
- 进程内 `String.intern()` 锁只对单实例有效，无法防止多实例并发超限。

### 目标改造

1. 在一个数据库事务中先插入 `space` 并取得 ID，再插入创建者的 `space_member` 记录。
2. 明确 `space.owner_id` 为所有权事实来源；成员表中的角色负责日常授权，不替代所有权。
3. 个人空间保持每用户唯一；团队空间按“拥有的未删除团队空间数不超过 5”校验。
4. 使用数据库唯一约束和事务解决并发一致性，应用层锁只能作为优化。
5. 增加创建成功、成员写入失败回滚、并发创建、达到上限、管理员创建高级套餐等测试。

### 验收标准

- 成功响应返回的 ID 能立即查询到空间和 owner 成员记录。
- 任一步失败时两张表均无残留数据。
- 同一用户最多一个个人空间，最多拥有五个团队空间。

## 3. `/api/v1` 接口迁移

### 当前事实

所有控制器仍使用 `/api/user`、`/api/picture`、`/api/space`、`/api/spaceUser`、`/api/space/analyze` 和 `/api/file`。路径多为 `/add`、`/edit`、`/list/page/vo` 等 RPC 风格；公开、登录用户和管理员接口没有清晰分区。当前响应包装成功码为 `0`，这一点可继续保留，但需要统一 HTTP 状态、业务错误码和 `requestId`。

### 已确定的 M1 契约

M1 目标契约已固定在 `docs/openapi/m1.yaml`，关键决策见 `docs/m1-api-contract.md`。当前 Spring 控制器尚未实现该契约，本节后续用于跟踪实现差异。

### 目标命名空间

```text
/api/v1/public/**  匿名、只读、可缓存
/api/v1/**         登录用户业务
/api/v1/admin/**   平台管理员业务
/api/v1/ws/**      WebSocket 握手入口
```

认证接口迁移到 `/api/v1/auth/register|login|logout|me`；图片、空间、成员、AI、邀请、评论、分享和版本按资源化路径组织。完整目标草案见 `docs/product-prd.md` 第 9 节。

### 迁移策略

1. 先冻结 DTO、分页、错误码、`BaseResponse<T>`、Cookie 和 Java `Long` 字符串序列化规则，生成第一版 OpenAPI。
2. 新建 v1 控制器或适配层复用现有服务，不在原控制器上做大范围路径替换。
3. 每迁移一个资源，补契约测试和鉴权测试，再让前端切换。
4. 旧 `/api/**` 仅在明确兼容期内保留并记录弃用时间；当前尚无已发布前端，可优先直接关闭旧入口。
5. 禁止把 `/api/file/test/**`、Bing 批量抓图和内部缓存调试接口迁入普通用户命名空间。

## 4. 个人空间自动创建

### 当前事实

`UserServiceImpl.userRegister` 只校验并保存 `User`，随后立即返回用户 ID；没有调用空间服务。用户第一次上传时也没有可靠的“获取或创建个人空间”兜底。

### 目标改造

- 注册事务同时创建默认个人空间，默认名称可由服务端生成，类型为 `PRIVATE`，等级为 `COMMON`。
- 对 `user_id + space_type=PRIVATE` 建唯一约束，提供幂等 `getOrCreatePersonalSpace(userId)` 领域方法。
- 若用户保存或空间保存任一失败，整个注册回滚；禁止出现“用户已注册但没有个人空间”的新数据。
- 为存量用户提供一次性补偿迁移，并输出创建数、跳过数和失败数。
- AI 成功结果、普通上传和 URL 导入都通过同一方法定位个人空间，不各自实现兜底逻辑。

## 5. 公开只读接口

### 当前事实

- `POST /api/picture/list/page/vo` 在未传 `spaceId` 时会限定为公共区且审核通过，但仍与私有查询共用控制器、请求模型和缓存逻辑。
- `GET /api/picture/get/vo` 无条件调用 `getLoginUser`，匿名访问公开图片详情会失败。
- 缺少独立的公开 DTO、缓存规则、详情可见性判断、评论列表以及稳定的 GET 查询参数。

### 目标接口与规则

```http
GET /api/v1/public/pictures
GET /api/v1/public/pictures/{pictureId}
GET /api/v1/public/pictures/{pictureId}/comments
```

- 仅返回审核通过、未撤回、未删除且允许公开的内容。
- DTO 不包含权限列表、内部审核备注、存储密钥或私有空间信息。
- 公开列表和详情允许匿名访问及受控缓存；私有空间、管理后台和分享口令页禁止公共缓存。
- 下载、评论、创建分享仍要求登录；“可看见”不等于“可执行操作”。
- 公开详情必须区分不存在与不可公开，并统一为不泄露资源状态的响应策略。

## 6. AI 任务和配额

### 当前事实

现有能力只有扩图创建和按供应商任务 ID 查询，控制器直接暴露供应商响应。系统没有 AI 任务表、文生图、模型能力接口、用户历史、取消、失败原因、重试、结果入库或配额扣减。

### 建议模型

`ai_task` 至少包含：`id`、`user_id`、`space_id`、`type`、`provider`、`model`、`request_payload`、`status`、`provider_task_id`、`result_picture_id`、`error_code`、`error_message`、`idempotency_key`、时间戳和版本号。状态固定为 `queued/running/succeeded/failed/cancelled`。

`ai_quota_usage` 至少按 `user_id + quota_type + quota_date` 唯一，记录 `limit_count`、`used_count` 和 `reserved_count`。文生图与扩图每天各 100 次，分别计数；提交时原子预占，确定失败或取消时按规则释放，成功后结算。

### 必要行为

- 提供模型与能力列表、我的配额、任务创建、列表、详情和取消接口。
- 用平台任务 ID 隔离供应商 ID 和响应结构，轮询/回调都更新统一状态机。
- 任务成功后把结果保存到个人空间并关联 `result_picture_id`；失败原因对用户可见但需脱敏。
- 创建接口支持幂等键，防止浏览器重试重复扣额和重复调用供应商。
- 明确取消、超时、供应商成功但本地保存失败、重试和日界线时区（Asia/Shanghai）的处理规则。

## 7. 邀请、评论、分享、版本与协作数据模型

当前 Java 实体只有 `User`、`Picture`、`Space`、`SpaceUser`；下列模型均未实现。表名仅为建议，最终通过领域评审和 Flyway 迁移确定。

| 模型 | 核心字段/约束 | 关键行为 |
| --- | --- | --- |
| `space_invitation` | space、inviter、invitee、role、status、expires_at；有效邀请唯一 | 创建、接受、拒绝、过期；接受时事务写成员并通知 |
| `notification` | user、type、actor、resource、payload、read_at | 邀请和评论通知；批量已读；不可依赖前端临时状态 |
| `picture_comment` | picture、version、author、parent、body、position、status | 评论、回复、删除、解决；位置批注绑定明确版本 |
| `picture_share` | picture、creator、token_hash、password_hash、expires_at、revoked_at、permissions | 原始 token 只返回一次；支持口令、有效期和撤销 |
| `picture_version` | picture、number、parent_version、asset_url、thumbnail_url、editor_state、creator、created_at | 版本不可覆盖；恢复旧版时创建新版本 |
| `collaboration_operation` | picture、version、operation_id、actor、server_seq、base_version、object_id、payload | 操作 ID 幂等、服务端排序、断线补偿和审计 |
| `collaboration_snapshot` | picture、version、last_seq、editor_state、created_at | 加速加入房间和历史恢复；与操作日志边界明确 |

现有 WebSocket 只在单进程内保存会话和“每张图一个编辑者”的锁，并广播有限编辑动作。它缺少服务端序号、操作幂等、快照、增量恢复、对象级冲突、跨实例广播和持久化，因此不能视为产品级多人协作实现。CRDT、OT 或服务端有序操作模型仍是待决策项；选型前先固定操作信封、快照和版本边界。

## 8. 暂缓的安全问题

### 首次提交或共享前的阻断项

- 迁移时发现的明文数据库/云服务/AI Provider 凭据已从可提交配置移除，基础配置改用环境变量，本地覆盖文件被 Git 忽略；已暴露的值仍必须轮换。
- 确认新 Git 历史中没有旧 `.git`、`target`、IDE 缓存或本地配置；提交前执行密钥扫描。

### 可暂缓到公网部署前，但不得遗忘

- 固定盐 MD5 密码哈希迁移到 Argon2id 或 BCrypt；同时统一注册与登录密码长度规则。
- Cookie 的 `HttpOnly`、`Secure`、`SameSite`、会话轮换和失效策略，以及写操作 CSRF 防护。
- 登录、注册、评论、分享口令和 AI 调用的用户/IP 限流、失败退避与审计。
- URL 导入的 SSRF 防护：协议、DNS/IP、重定向、端口、响应大小和超时限制。
- 上传文件的 MIME、魔数、解码和大小校验；当前上限 10 MB，产品目标为 20 MB。
- WebSocket Origin 白名单、Cookie 鉴权、连接限额、消息大小和频率限制。
- COS 私有对象、签名 URL、公开派生图、跨域响应头和分享访问控制。
- 查询排序字段白名单，避免客户端字段直接进入动态排序。
- 测试文件接口、批量抓图、调试缓存接口和 Knife4j 在生产环境关闭或仅管理员可见。
- 日志脱敏、统一 `requestId`、安全事件审计和依赖漏洞扫描。

本地 Docker 开发不能视为这些风险已经解决。任何公网部署评审都必须把本节转换为可验证的上线清单。

## 9. 测试与构建基线

- 后端以 Java 17 为目标，Maven 编译器现已与该版本对齐，主源码可以完成编译。
- 当前 POM 会选用过旧的 Surefire 2.12.4，不能正确运行 JUnit 5/Spring Boot 测试，表现为 Spring 扩展未生效和注入对象为 `null`。
- `RedisStringTest` 依赖真实 Redis；`PictureMockDataTest` 会向真实数据源批量写入 5000 条记录，不应属于默认测试套件。
- 后续应升级测试运行器，把纯单元测试、Testcontainers 集成测试和手工数据生成工具分开；默认 `mvn test` 必须无外部副作用并可重复执行。

## 10. 推荐实施顺序

1. 清理并轮换凭据，引入 Flyway，补齐当前 `user/picture/space/space_user` 基线。
2. 修复团队空间创建事务，同时实现注册自动创建个人空间和存量补偿。
3. 固定响应、错误、分页、ID、Cookie 和 OpenAPI 契约，建立 `/api/v1`。
4. 先交付认证、个人空间、图片上传/管理、公开只读和发布审核的 M1 闭环。
5. 实现 AI 任务与独立配额，再实现版本、邀请、评论、分享与通知。
6. 在版本和操作持久化模型稳定后，再重构实时协作协议。

每个阶段都必须有数据库迁移、服务层测试、控制器契约测试和鉴权测试；不能只以接口能返回成功作为完成标准。
