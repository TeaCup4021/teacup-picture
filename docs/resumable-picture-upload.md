# 图片分片上传与断点续传：当前实现链路

本文解释当前代码中**本地图片**的分片上传与断点续传。它描述已实现的行为，不把待改进点当成已具备的能力。上传接口以 [M1 OpenAPI 契约](openapi/m1.yaml) 和后端实现为准；图片持久化仍遵守 [图片存储架构](picture-storage.md)：浏览器只请求后端，后端将图片写入私有 MinIO。

## 一张图看全程

```text
用户选择 File
  ├─ 文件 <= 8 MiB：POST /api/v1/pictures/uploads（普通上传）
  └─ 文件 > 8 MiB：
       1. 用文件属性查找 sessionStorage 中的会话，或创建新会话
       2. GET 会话，以数据库返回的 uploadedParts 为准
       3. 顺序切出 5 MiB 分片，计算 SHA-256，PUT 给后端
       4. 后端校验编号/大小/摘要，写入 MinIO part-N，记录分片行
       5. POST complete；MinIO compose 合并临时对象
       6. 复用图片校验、原图和缩略图保存，尝试清理临时对象
       7. 图片入库，空间预留额度结转为已用额度，移除浏览器缓存
```

前端入口是 [`m1Api.uploadPicture`](../frontend/src/features/prototype/api/m1-api.ts#L143)，服务端接口在 [`M1Controller`](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/M1Controller.java#L73)。分片并非浏览器直传 MinIO，也不是暴露给浏览器的 MinIO Multipart Upload：每一片都先经过后端，再作为一个临时 MinIO 对象保存。

## 以 13 MiB 图片为例

假设选择 `旅行.png`，大小为 13 MiB。前端仅对**大于** 8 MiB 的本地文件启用分片，服务端固定分片大小为 5 MiB，因此文件会被切成三片：

| 编号 | 文件中的范围 | 本片大小 | 临时对象名 |
| --- | --- | --- | --- |
| 1 | 0 至 5 MiB | 5 MiB | `part-1` |
| 2 | 5 至 10 MiB | 5 MiB | `part-2` |
| 3 | 10 至 13 MiB | 3 MiB | `part-3` |

这里的编号从 **1** 开始，最后一片按剩余字节数计算。前端逐片**串行**上传；分片模式的进度在某片完成后更新，并非该片传输过程中的连续字节进度。8 MiB 及以下的本地文件走普通 `multipart/form-data` 上传；URL 导入是另一条链路。[前端阈值和调度](../frontend/src/features/prototype/api/m1-api.ts#L10)

## 第一步：创建会话与预留额度

前端调用 `POST /api/v1/picture-upload-sessions`，提交文件名、内容类型、总字节数、5 MiB 分片大小、目标空间和名称等元数据。后端要求当前用户已登录，校验文件名、大小（最多 20 MiB）、分片大小、数量和空间上传权限，计算 `totalParts = ceil(totalSize / chunkSize)`。[创建逻辑](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/M1Service.java#L118)

服务端随后在空间的 `reservedSize` 中预留文件声明的字节数，并新增一条 `picture_upload_session` 记录：用户、空间、文件信息、总大小、分片大小、总片数、状态 `active`、24 小时后的过期时间，以及随机生成的 `upload-sessions/{uuid}/` 存储前缀。预留使用数据库条件更新，约束为 `totalSize + reservedSize + 本次声明大小 <= maxSize`，避免多个并发会话各自通过容量检查。[额度实现](../backend/src/main/java/com/teacup/teacuppicturebackend/mapper/SpaceMapper.java#L71) · [会话表迁移](../backend/src/main/resources/db/migration/V19__add_resumable_picture_upload.sql)

返回的会话包含字符串形式的 `id`、`chunkSize`、`totalParts`、`totalSize`、`uploadedParts`、`expiresAt` 和 `status`。浏览器缓存的是这些会话信息，不持有 MinIO 对象键或凭据。[会话响应结构](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/model/M1Dtos.java#L51)

## 第二步：记录并找回会话

浏览器文件选择控件提供一个标准 `File` 对象。项目直接读取它的 `name`、`size`、`lastModified`，拼成缓存键：

```ts
const resumeKey = `teacup-upload:${file.name}:${file.size}:${file.lastModified}`;
```

`lastModified` 表示文件最后修改时间的毫秒时间戳，是标准 `File` 对象的属性。如果来源无法提供可靠的真实修改时间，或者后续选中的文件属性变化，同一个文件就可能拼不出原来的键。当前代码**没有针对缺失或不稳定的值做额外防护**。这三个属性也不是内容哈希：不同内容若恰好同名、同大小、同修改时间，仍可能命中同一键。[恢复键实现](../frontend/src/features/prototype/api/m1-api.ts#L195)

该键在 `sessionStorage` 中对应会话信息，**不是文件字节**。首次没有缓存就创建会话；再次提交时，用户需要重新选择文件，前端用新的 `File` 对象重新拼键，取出旧会话 ID。随后调用 `GET /api/v1/picture-upload-sessions/{sessionId}`，从后端重新读取真正的 `uploadedParts`。所以 `lastModified` 只协助“找会话”，不是用来计算“缺哪片”。[前端恢复查询](../frontend/src/features/prototype/api/m1-api.ts#L198) · [后端进度查询](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/M1Service.java#L177)

这里采用 `sessionStorage` 的实际效果是：同一标签页刷新后通常仍能找回缓存，关闭标签页则不能依赖它继续找回；`localStorage` 会跨标签页关闭而长期保留。代码没有记录选型原因。即使换成 `localStorage`，浏览器也不会替应用保存原文件，继续上传仍需用户重新选择文件。旧会话只能在同一用户、状态 `active` 且未过期时使用；查询失败时，前端会尝试创建新会话。[会话访问检查](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/M1Service.java#L232)

## 第三步：上传、校验与记录每一片

前端从服务端返回的 `uploadedParts` 建立集合，跳过已完成编号。待上传的第 `n` 片用以下范围切出：

```ts
const start = (n - 1) * chunkSize;
const chunk = file.slice(start, Math.min(file.size, start + chunkSize));
```

浏览器用 Web Crypto 对**这一片的字节**计算 SHA-256（256 位摘要，通常表示为 64 位十六进制字符串），随后发送二进制请求：

```http
PUT /api/v1/picture-upload-sessions/{sessionId}/parts/{partNumber}
Content-Type: application/octet-stream
X-Chunk-SHA256: <该片的 SHA-256>

<该片的二进制字节>
```

后端从 URL 识别**哪个会话、哪一片**，从请求 `Content-Length` 获取本片大小；缺少该长度会拒绝请求。后端根据建会话时保存的 `totalSize`、`chunkSize`、`totalParts` 计算预期大小：非末片必须恰好 5 MiB，末片必须恰好等于剩余字节数。编号超界或大小不符都会被拒绝。[控制器](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/M1Controller.java#L98) · [预期大小计算](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/M1Service.java#L154)

通过校验后，后端将分片流写入私有 MinIO 的 `upload-sessions/{uuid}/part-{n}`，读取时再次计算 SHA-256，与请求头比较；若不一致，则尝试删除本片并拒绝请求。SHA-256 是内容摘要，不是加密，也不是会话鉴权。后端再向 `picture_upload_part` 写入 `sessionId`、`partNumber`、`size` 和 `checksum`；数据库对同一会话的同一编号设有唯一约束。当前 `etag` 列也保存计算出的 SHA-256，不能把它理解成实际读取并比对过的 MinIO ETag。[存储实现](../backend/src/main/java/com/teacup/teacuppicturebackend/storage/MinioPictureStorage.java#L230) · [分片表迁移](../backend/src/main/resources/db/migration/V19__add_resumable_picture_upload.sql#L28)

重复发送已记录的分片时，后端先比较大小以及提供的校验值：一致则返回现有进度，不重复写对象；不同则返回冲突。前端始终发送校验值，但后端接口允许不传；不传时，重复分片的检查只比较大小。分片校验能验证**收到的字节与客户端声明的摘要一致**，却不能独立证明客户端确实从原文件的正确偏移切出了这片。[重复分片处理](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/M1Service.java#L163)

## 第四步：中断后的续传

继续使用 `旅行.png`：假设第 1、2 片成功，第 3 片发送途中断网。数据库记录 `uploadedParts = [1, 2]`；未完整验收的第 3 片不计入进度。

同一标签页中再次选择同一个文件并提交时：

1. 前端按文件名、大小、`lastModified` 找到旧会话 ID。
2. `GET` 旧会话，使用后端返回的 `[1, 2]`，而不是盲信浏览器缓存。
3. 跳过前两片，从第 3 片的**起始字节**重新上传整个 3 MiB 分片。
4. 第 3 片成功后才进入合并阶段。

因此“断点”是**已经完成的分片之间**，不是精确到中断时的字节位置。普通网络错误不会自动重试；前端保留缓存，等待用户再次提交。若旧会话过期、已结束或查询失败，当前前端的宽泛 `catch` 会新建会话；查询时临时网络故障也可能造成新的并存会话，旧会话的额度预留需等取消或过期清理释放。[续传循环和异常路径](../frontend/src/features/prototype/api/m1-api.ts#L201)

## 第五步：合并、校验与正式入库

前端调用 `POST /api/v1/picture-upload-sessions/{sessionId}/complete`。后端先要求数据库分片行的数量等于 `totalParts`，且编号完整连续。然后 [`MinioPictureStorage.completeUpload`](../backend/src/main/java/com/teacup/teacuppicturebackend/storage/MinioPictureStorage.java#L256) 将 `part-1`、`part-2`、`part-3` 作为 `ComposeSource`，通过 MinIO 的 `composeObject` 按顺序合成临时对象 `assembled`。**合并发生在后端调用 MinIO 时，不在浏览器。**

后端读取 `assembled`，复用 `PictureStorage.store`：检查大小和文件格式，解码并验证图片，生成缩略图，向 MinIO 写入正式原图和缩略图对象，计算完整文件的 SHA-256。随后写入私有 `picture` 记录，把建会话时的 `reservedSize` 结转为已使用的 `totalSize`、增加图片件数，更新会话为 `completed` 并记录图片 ID。成功响应后，前端移除 `sessionStorage` 键。[图片保存](../backend/src/main/java/com/teacup/teacuppicturebackend/storage/MinioPictureStorage.java#L295) · [入库与结转](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/M1Service.java#L182) · [额度结转](../backend/src/main/java/com/teacup/teacuppicturebackend/mapper/SpaceMapper.java#L87)

存储实现的 `finally` 会尝试删除 `assembled` 和所有 `part-N`。正式图片仍在私有 MinIO，浏览器只消费后端返回的图片资源 URL。[临时对象清理](../backend/src/main/java/com/teacup/teacuppicturebackend/storage/MinioPictureStorage.java#L278)

## 取消、过期和失败

| 情况 | 当前行为 |
| --- | --- |
| 网络中断或普通上传异常 | 不自动重试；未主动取消时，前端保留会话缓存。重新提交同一文件会查询后端进度。 |
| 用户点击“取消上传” | `AbortController` 中断当前请求。前端尝试 `DELETE /api/v1/picture-upload-sessions/{id}`，移除缓存；后端尝试删除临时分片、释放预留、标记 `aborted`。**取消不是暂停**。 |
| 会话超过 24 小时 | 服务端拒绝继续使用。定时任务每小时查询过期的活跃会话，尝试删除分片、释放预留、标记 `expired`。 |
| 图片大小、分片编号/大小或摘要不合法 | 后端拒绝相应请求；未成功记录的分片需要重新上传。 |
| 完成请求发现分片编号不齐 | 返回冲突，尚未开始合并，缺失的分片仍可补传。 |

取消和定时任务见 [`M1Service`](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/M1Service.java#L208)，前端取消路径见 [`resumableUpload`](../frontend/src/features/prototype/api/m1-api.ts#L241)。对象删除是尽力执行：MinIO 的 `delete` 记录删除异常后不会向上抛出，因此不能声称每次都能物理清理干净。[删除实现](../backend/src/main/java/com/teacup/teacuppicturebackend/storage/MinioPictureStorage.java#L202)

### 特别注意：合并失败不保证可以原会话重试

`completeUpload` 的 `finally` **无论合并或后续存储是否成功**，都会尝试删除 `assembled` 和所有分片。如果 MinIO 合并、图片校验或正式存储失败，数据库事务可能回滚，但分片行仍显示“已上传”，对应 MinIO 对象却已被删除。再次查询可能看到全部分片齐全，重试 `/complete` 仍会因物理分片缺失而失败。数据库事务不能回滚 MinIO 的删除。[存储清理](../backend/src/main/java/com/teacup/teacuppicturebackend/storage/MinioPictureStorage.java#L256) · [完成事务](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/M1Service.java#L182)

代码在保存图片记录失败时尝试删除已经写入的正式原图和缩略图；但 `catch` 内将会话写为 `failed` 后又抛异常，在 `@Transactional(rollbackFor = Exception.class)` 下，该状态更新也会随事务回滚，不能把它当作稳定的失败状态。[补偿代码](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/M1Service.java#L463)

## 实现边界与资料入口

- 当前只在 `sessionStorage` 中保存会话线索，不保存文件；关闭标签页、换设备或文件属性变化后，不能保证找到旧会话。
- 前端没有自动重试、并行分片上传或整文件内容指纹；服务端虽在会话表中保留可选的 `fileChecksum` 字段，当前前端没有提交，完成流程也没有用它与最终文件摘要比较。
- 当前分片最多服务于 20 MiB 的单张图片；固定 5 MiB 切片并不是无限大文件上传能力。
- 产品 PRD 和上传页设计文档仍写着“不提供断点续传”，与实际前后端和 [后端进度记录](backend-gap-analysis.md#L23) 不一致。本文只解释当前实现；产品范围和对外承诺需要另行统一。[PRD](product-prd.md#L219) · [上传页设计](ui-design/pages/upload.md)

主要代码入口：[前端上传适配](../frontend/src/features/prototype/api/m1-api.ts#L143) · [上传页面](../frontend/src/widgets/upload-screen/upload-screen.tsx#L62) · [v1 控制器](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/M1Controller.java#L86) · [会话服务](../backend/src/main/java/com/teacup/teacuppicturebackend/api/v1/M1Service.java#L118) · [MinIO 实现](../backend/src/main/java/com/teacup/teacuppicturebackend/storage/MinioPictureStorage.java#L224) · [数据库迁移](../backend/src/main/resources/db/migration/V19__add_resumable_picture_upload.sql) · [OpenAPI](openapi/m1.yaml#L179)。
