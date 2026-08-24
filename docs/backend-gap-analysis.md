# 后端缺口分析

更新日期：2026-08-24

本文以当前 `backend/` 实现和 `docs/product-prd.md` 为基线，记录前端接入前必须确认的后端事实、缺口和建议顺序。它不是已经实现的接口文档。

## 1. 结论与优先级

| 优先级 | 工作项 | 当前结论 |
| --- | --- | --- |
| P0 | 团队空间创建 | 创建顺序错误，团队空间本身未落库就写成员关系；当前流程不可用 |
| P0 | 新仓库凭据清理 | 可提交配置已改为环境变量；迁移前暴露的值仍须在首次共享前轮换 |
| 完成 | M1 `/api/v1` 契约 | 认证、个人空间、上传、图片管理、发布审核和公开只读接口已由 v1 适配层实现 |
| 完成 | M1 个人空间 | 新注册事务、幂等创建及本地/URL 上传的默认空间定位已完成；存量补偿另行处理 |
| 完成 | M1 公开审核闭环 | 独立申请记录、管理员审核/撤回、匿名公开列表与详情已实现 |
| 完成 | M2 AI 任务与配额 | 统一任务、模型能力、独立额度、幂等、取消、超时、结果入库和鉴权下载已实现；公网部署安全闸门仍未完成 |
| 完成 | M3-R 版本与单人编辑器 | EditorState v3（兼容 v2）、裁切、完整调节、统一预览/导出、草稿、不可变版本和前端生产验收已闭环 |
| 部分完成 | 邀请、评论、分享 | M6 评论、批注、下载和分享已闭环；M4 邀请流程仍待完成 |
| 完成（单实例） | 协作持久化模型 | M5 已有持久化基线、serverSeq、operationId 幂等、分页补偿、序号缺口检测、snapshot、hash checkpoint、显式对象锁和恢复 epoch 切换；跨实例广播/分布式锁/日志压缩仍是生产扩展 |
| P2 | 安全加固 | 除凭据清理外按本文安全闸门暂缓，但必须在公网部署前完成 |

### M1 实施进度（2026-08-12）

- 已引入 Flyway，`V1__create_m1_core_schema.sql` 建立 `user`、`space`、`space_user`、`picture` 的完整新环境基线；`backend/sql/create_table.sql` 不再作为结构事实来源。
- 已为活跃个人空间增加数据库唯一约束，并实现幂等 `getOrCreatePersonalSpace(userId)`。
- 注册已在同一事务中创建用户和默认个人空间；空间创建失败会触发注册回滚。注册密码长度已与 M1 契约统一为至少 8 位。
- 默认 Maven 测试已升级为 JUnit 5 可执行基线；依赖真实 MySQL/Redis 或写入压测数据的测试标记为 `integration`，不进入默认 `mvn test`。
- `V2__add_m1_publish_workflow.sql` 增加图片可见性/发布状态和独立 `publish_request` 审核记录，数据库约束保证同一图片最多一个待审核申请。
- `/api/v1` 已实现 OpenAPI 中的 M1 认证、个人空间、图片上传/URL 导入、个人图片、发布申请、管理员审核/撤回和匿名公开图库接口；统一 HTTP 状态、字符串 ID 和 `X-Request-Id`。
- 前端已从 `localStorage` 原型数据切换到真实 API，新增注册页，并通过真实注册、Cookie 登录、上传、申请、管理员审核和匿名公开读取 Playwright E2E。
- M1 之外尚未完成：存量用户个人空间补偿、团队空间事务修复，以及公网部署前安全加固。

### M3-R 完成情况（2026-08-16）

- `V10__add_m3_editor_versions.sql` 新增 `picture_draft` 和 `picture_version`；`V11__m3_editor_state_v2.sql` 将默认 schema 版本切换为 2；`V12__add_m3_draft_revision.sql` 增加草稿乐观并发 revision。草稿按图片唯一，版本按 `(pictureId, versionNumber)` 唯一且不可覆盖。
- `/api/v1/pictures/{pictureId}/editor-state` 支持读取和自动保存 `EditorState v3`，读取 v2 时自动升级；服务端拒绝 v1、数组、字符串、空值和超过 4,000,000 字符的状态。
- `/api/v1/pictures/{pictureId}/versions` 支持版本列表和 multipart 创建，创建时先写 MinIO 预览图，数据库写入失败会补偿删除对象。
- `editor-saves` 支持用扁平结果替换当前图片或在同一空间另存私有副本；替换自动保存 `original`/`user_save` 历史，重置公开状态并按差值更新空间容量。
- 版本详情、鉴权版本内容和恢复接口已实现；恢复旧版本会创建恢复历史、替换主图片并清除草稿，不删除后续历史。
- 草稿保存、条件删除、图片保存和版本恢复由图片行锁串行化并校验 `expectedRevision`，陈旧请求返回 409；编辑器退出可保存草稿、回退本次会话或继续编辑，且固定返回图片详情页。
- M3Service/M3Controller 已补所有权、格式错误、schema v3/v2 兼容、存储补偿、版本号递增和恢复闭环测试；前端已切换 Fabric.js MIT、自有 EditorState v3 和统一 Canvas 导出管线。
- 裁切、14 项调节、内部编辑对象、精细擦除、预览/导出一致性、替换/另存、编辑器 Playwright 和三视口视觉走查纳入 M3-R 闭环；界面不再展示独立图层列表。
- 版本资产随未来图片物理删除/空间删除联动清理仍是独立生命周期缺口；当前 M3 不提供物理删除能力，不影响本次编辑与版本关闭标准。

Flyway 默认拒绝自动接管“已有业务表但没有迁移历史”的非空旧库，避免将不完整结构误标为已迁移。旧库接入前必须先审计并编写专用基线/补偿迁移。

## 2. 团队空间创建问题

### 当前事实（M2 已实现）

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

M1 已新增独立 v1 适配层，旧控制器仍保留在 `/api/**` 兼容命名空间。M1 前端只调用 `/api/v1`；团队、AI、分析和协作等后续资源仍需逐步迁移，旧入口的最终关闭时间尚未确定。

### 已确定的 M1 契约

M1 契约固定在 `docs/openapi/m1.yaml`，关键决策见 `docs/m1-api-contract.md`。Spring v1 适配层已经实现其中 16 个业务操作；本节后续用于跟踪非 M1 资源迁移和旧入口下线。

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

新注册流程已事务化创建默认个人空间，并提供幂等查询/创建服务。本地上传和 URL 导入在未传 `spaceId` 时统一调用该服务。存量用户补偿迁移仍未完成。

### 目标改造

- [x] 注册事务同时创建默认个人空间，默认名称由服务端生成，类型为 `PRIVATE`，等级为 `COMMON`。
- [x] 对活跃个人空间建立唯一约束，提供幂等 `getOrCreatePersonalSpace(userId)` 领域方法。
- [x] 若用户保存或空间保存任一失败，整个注册回滚；禁止出现“用户已注册但没有个人空间”的新数据。
- 为存量用户提供一次性补偿迁移，并输出创建数、跳过数和失败数。
- [x] 普通上传和 URL 导入通过同一方法定位个人空间，不各自实现兜底逻辑。
- AI 成功结果在 M2 接入同一定位服务。

## 5. 公开只读接口

### 当前事实

- M1 已提供 `GET /api/v1/public/pictures` 游标列表和 `GET /api/v1/public/pictures/{pictureId}` 匿名详情。
- 公开 DTO 与私有图片 DTO 分离，不返回权限、空间和审核备注；非公开图片统一返回 404。
- 评论属于 M6，不在 M1 契约范围；生产缓存策略仍需在部署阶段完成。

### 目标接口与规则

```http
GET /api/v1/public/pictures
GET /api/v1/public/pictures/{pictureId}
评论接口在 M6 增加
```

- 仅返回审核通过、未撤回、未删除且允许公开的内容。
- DTO 不包含权限列表、内部审核备注、存储密钥或私有空间信息。
- 公开列表和详情允许匿名访问及受控缓存；私有空间、管理后台和分享口令页禁止公共缓存。
- 下载、评论、创建分享仍要求登录；“可看见”不等于“可执行操作”。
- 公开详情必须区分不存在与不可公开，并统一为不泄露资源状态的响应策略。

## 6. AI 任务和配额

### 当前事实

M2 已通过 `V3__add_m2_ai_workflow.sql`、`V6__harden_m2_ai_workflow.sql`、`V7__switch_ai_provider_to_openai_images.sql`、`V9__add_ai_image_output_options.sql` 落地 `ai_model`、`ai_task`、`ai_quota_usage`。供应商任务被平台任务 ID 隔离；OpenAI Images API 兼容适配器调用后端配置的兼容端点，兼容 URL 和 Base64 图片结果，支持严格尺寸、背景、输出格式和有损格式质量参数，结果统一经 `PictureStorage` 导入 MinIO 后关联个人空间图片。阿里云 provider 和旧直连接口已移除。

### 实现模型

`ai_task` 至少包含：`id`、`user_id`、`space_id`、`type`、`provider`、`model`、`request_payload`、`status`、`provider_task_id`、`result_picture_id`、`error_code`、`error_message`、`idempotency_key`、时间戳和版本号。状态固定为 `queued/running/succeeded/failed/cancelled`。

`ai_quota_usage` 至少按 `user_id + quota_type + quota_date` 唯一，记录 `limit_count`、`used_count` 和 `reserved_count`。文生图与扩图每天各 100 次，分别计数；提交时原子预占，确定失败或取消时按规则释放，成功后结算。

### 已实现行为

- 提供模型与能力列表、我的配额、任务创建、列表、详情和取消接口。
- 用平台任务 ID 隔离供应商 ID 和响应结构，轮询/回调都更新统一状态机。
- 任务成功后把结果保存到个人空间并关联 `result_picture_id`；失败原因对用户可见但需脱敏。
- 创建接口支持幂等键，防止浏览器重试重复扣额和重复调用供应商。
- 明确取消、超时、供应商成功但本地保存失败、重试和日界线时区（Asia/Shanghai）的处理规则。

实现细节：创建事务锁定用户和配额行；queued 取消释放 reserved，running 取消结算 used；供应商失败、超时及结果保存失败释放 reserved；进程重启会恢复 queued，超时 running 进入 failed。取消与成功保存竞争时，成功任务不会留下未关联图片。

## 7. 邀请、评论、分享与协作数据模型

M5 协作和 M6 评论/分享模型已经实现；邀请流程仍待完成。当前结构以 Flyway 和实体为准：

| 模型 | 核心字段/约束 | 关键行为 |
| --- | --- | --- |
| `space_invitation` | space、inviter、invitee、role、status、expires_at；有效邀请唯一 | 创建、接受、拒绝、过期；接受时事务写成员并通知 |
| `notification` | user、type、actor、resource、payload、read_at | 邀请和评论通知；批量已读；不可依赖前端临时状态 |
| `picture_comment` / `comment_mention` | picture、version、author、root/reply、body、position、status；comment/user 复合主键 | M6 已实现评论、回复、结构化提及、软删除和解决；位置批注绑定明确版本 |
| `picture_share` | picture、creator、public_id、secret_hash、password_hash、expires_at、revoked_at | M6 已实现；原始片段密钥只返回一次，支持密码、有效期、撤销和单活动链接约束 |
| `collaboration_operation` | picture、version、operation_id、actor、server_seq、base_version、object_id、payload | 操作 ID 幂等、服务端排序、断线补偿和审计 |
| `collaboration_snapshot` | picture、version、last_seq、editor_state、created_at | 加速加入房间和历史恢复；与操作日志边界明确 |

M5 新增 `/api/v1/ws/pictures/{pictureId}/collaboration`、`V15__m5_collaboration.sql` 和 `V16__m5_collaboration_reliability.sql`。Yjs update 以 base64 写入 `collaboration_update`，服务端在数据库事务中分配房间 `serverSeq`，再 ACK/广播；加入房间时按持久化基线、最新 snapshot 和分页增量日志恢复，并拒绝序号缺口。前端使用 Yjs/Y.Text/字段级 Y.Map/Y.Array，并用 IndexedDB 保存本地文档和未确认更新。对象锁已具备显式申请、续期、释放和 token 校验；正式替换/恢复在事务内轮换 epoch。当前广播和锁仍是单个 Spring 进程内实现，不能在多实例部署中保证全局互斥；Redis Pub/Sub/分布式锁、更新压缩、连接限流和真实多人 E2E 仍是生产验收项。

M6 新增 `V17__establish_current_picture_version.sql` 和 `V18__add_m6_shares_and_comments.sql`，为现有图片回填当前不可变版本，并创建分享、讨论和提及关系。`/api/v1` 已提供分享管理、片段密钥校验、Session 授权内容、登录下载、公开撤回、讨论分页、回复、提及、解决和删除接口。分享密码和评论频率使用 Redis 限流，依赖不可用时返回 `50300` 并失败关闭；图片内容仍只经 `PictureStorage` 读取私有 MinIO。V17/V18 已在 Docker Compose 的 MySQL 8 上由真实 Flyway 启动成功执行，Redis、MinIO 参与的受密码分享、评论和下载流程也已通过完整 Playwright。后端 101 项测试、前端 46 项测试、生产构建和两个页面的三视口人工走查均通过，M6 不再保留本地基础设施验收缺口。

## 8. 暂缓的安全问题

### 首次提交或共享前的阻断项

- 迁移时发现的明文数据库/云服务/AI Provider 凭据已从可提交配置移除，基础配置改用环境变量，本地覆盖文件不再包含 COS/provider 密钥；已暴露的值仍必须轮换。
- 确认新 Git 历史中没有旧 `.git`、`target`、IDE 缓存或本地配置；提交前执行密钥扫描。

### 可暂缓到公网部署前，但不得遗忘

- 固定盐 MD5 密码哈希迁移到 Argon2id 或 BCrypt；同时统一注册与登录密码长度规则。
- Cookie 已实现 `HttpOnly`、`SameSite=Lax`、登录会话轮换和退出失效；生产 `Secure` 与写操作 CSRF 防护仍需完成。
- 登录、注册和 AI 调用的用户/IP 限流、失败退避与审计；M6 评论频率和分享口令已加入 Redis 基础限流，但公网代理 IP 解析与安全审计仍需加固。
- URL 导入已限制 HTTP(S)、禁止私网解析、禁止重定向，并限制响应大小和超时；公网部署前仍需进行 DNS 重绑定和代理环境专项测试。
- 上传已限制 20 MB，校验扩展名、MIME/魔数和图片解码；后续需补畸形图片与解压炸弹专项测试。
- WebSocket Origin 白名单、Cookie 鉴权、连接限额、消息大小和频率限制。
- 图片已统一写入 Docker Compose 中的私有 MinIO bucket，并通过后端鉴权资源接口访问；运行时不再回退本地文件或 COS。后续仍需补公开派生图、CDN、备份恢复和分享访问控制，但不得引入并行图片存储实现。
- 查询排序字段白名单，避免客户端字段直接进入动态排序。
- 测试文件接口、批量抓图、调试缓存接口和 Knife4j 在生产环境关闭或仅管理员可见。
- 日志脱敏、统一 `requestId`、安全事件审计和依赖漏洞扫描。

本地 Docker 开发不能视为这些风险已经解决。任何公网部署评审都必须把本节转换为可验证的上线清单。

## 9. 测试与构建基线

- 后端以 Java 17 为目标，Maven 编译器现已与该版本对齐，主源码可以完成编译。
- Surefire 已升级到 3.2.5，默认测试可正确执行 JUnit 5。
- `RedisStringTest`、Spring 上下文测试和会写入 5000 条数据的 `PictureMockDataTest` 已标记为 `integration`，默认测试无外部副作用。
- 后续仍需使用 Testcontainers 补 Flyway、注册事务和真实 Mapper 集成测试；手工数据生成工具应继续从测试源码中拆出。

## 10. 推荐实施顺序

1. ~~清理可提交凭据，引入 Flyway，补齐当前 `user/picture/space/space_user` 基线。~~ 已完成代码侧清理与新环境基线；历史凭据轮换仍需仓库所有者确认。
2. 修复团队空间创建事务，并完成存量用户个人空间补偿；注册自动创建个人空间已完成。
3. ~~固定响应、错误、分页、ID、Cookie 和 OpenAPI 契约，建立 `/api/v1`。~~ M1 范围已完成。
4. ~~交付认证、个人空间、图片上传/管理、公开只读和发布审核的 M1 闭环。~~ 已完成真实 E2E 验收。
5. ~~实现 AI 任务与独立配额。~~ M2 已完成。
6. ~~实现单人编辑器与版本持久化闭环。~~ M3-R 已完成全量生产验收；下一阶段进入 M4 团队与邀请。
7. ~~在版本和操作持久化模型稳定后，再重构实时协作协议。~~ M5-R 已完成基础协议和持久化；后续补齐跨实例广播、分布式锁、日志压缩和版本恢复原子切换。
8. ~~实现下载、受控分享、评论、提及和版本批注闭环。~~ M6 已完成代码、契约、MySQL/Flyway 迁移、完整 Playwright 和三视口走查。

每个阶段都必须有数据库迁移、服务层测试、控制器契约测试和鉴权测试；不能只以接口能返回成功作为完成标准。
