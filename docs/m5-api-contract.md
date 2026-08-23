# M5 协作 API 契约

- 状态：M5 协作闭环已实现（单实例部署基线）
- 机器可读接口：当前协作 HTTP 契约由 Spring Controller 和本文维护；WebSocket 消息使用本文 JSON 信封

## 范围

M5-R 覆盖团队图片的多人结构化编辑基础闭环：

- 通过 Cookie/Session 鉴权加入图片协作房间。
- viewer 可以旁观、接收编辑状态和在线人数，但不能修改或创建 checkpoint。
- editor/admin/owner 可以修改共享 `EditorState v3`，并通过 Yjs update 实时广播。
- 服务端为每个房间分配递增 `serverSeq`，按 `operationId` 幂等写入更新日志。
- WebSocket 加入时返回最新 snapshot 和 snapshot 之后的增量更新。
- 客户端使用 IndexedDB 保存 Yjs 文档和未确认更新，断线后自动重连。
- 正式保存前创建 checkpoint，保存结构化草稿并绑定房间 epoch 和 server sequence。
- 更新日志按页补偿并检查连续 `serverSeq`；房间基线持久化到 `baseEditorState`。
- checkpoint 同时校验 `editorStateHash` 和 `yjsStateHash`，对象编辑使用显式锁 token。
- 文字按 `Y.Text` 差分发送，笔迹按约 33ms 路径块追加到 `Y.Array`。
- 正式替换或历史恢复在事务内轮换 `roomEpoch`，旧客户端更新会被拒绝。
- 当前用户的撤销/重做使用 Yjs `UndoManager`，不撤销其他用户的更新。

## 房间

每张团队图片对应一个活动房间，数据库表为 `collaboration_room`：

```text
roomId       房间 ID，字符串传输
pictureId    图片 ID
baseVersionId 当前协作基线版本，可为空
roomEpoch    版本切换时改变，隔离旧房间更新
lastSeq      房间内最后一个 serverSeq
status       active 等状态
baseEditorState 首次建房间使用的 EditorState v3 基线
```

个人空间图片不会创建协作房间，继续使用 M3 单人草稿协议。

## HTTP 接口

### 获取协作会话

```http
GET /api/v1/pictures/{pictureId}/collaboration/session
```

响应字段：

```json
{
  "roomId": "12",
  "pictureId": "9001",
  "roomEpoch": "uuid",
  "lastServerSeq": "42",
  "role": "editor",
  "enabled": true,
  "canEdit": true,
  "wsPath": "/api/v1/ws/pictures/9001/collaboration",
  "baselineEditorState": { "schemaVersion": 3, "canvas": {}, "layers": [] }
}
```

非团队图片返回 `enabled=false`，前端回退到 M3 单人模式。未登录、图片不存在或空间无权查看时不能获得房间信息。

### 创建 checkpoint

```http
POST /api/v1/pictures/{pictureId}/collaboration/checkpoint
```

请求：

```json
{
  "roomEpoch": "uuid",
  "lastServerSeq": "42",
  "yjsState": "base64",
  "editorStateHash": "sha256-hex",
  "yjsStateHash": "sha256-hex",
  "editorState": { "schemaVersion": 3, "canvas": {}, "transform": {}, "crop": null, "adjustments": {}, "layers": [] },
  "expectedRevision": "7"
}
```

服务端要求提交的 `lastServerSeq` 等于当前房间最新序号，在事务中保存 `picture_draft` 和协作 snapshot；序号过期返回 409，客户端应重新同步后重试。该接口不会直接创建 `picture_version`，PNG 正式保存仍使用 M3 的 `editor-saves` 流程。

## WebSocket

```text
WebSocket /api/v1/ws/pictures/{pictureId}/collaboration
```

握手使用 HttpOnly Cookie Session，并校验图片查看权限。生产环境必须配置明确的 Origin 白名单；当前本地实现只允许 localhost/127.0.0.1 来源。

### hello

```json
{
  "type": "hello",
  "roomEpoch": "uuid",
  "lastServerSeq": "0"
}
```

### welcome

```json
{
  "type": "welcome",
  "roomId": "12",
  "roomEpoch": "uuid",
  "serverSeq": "42",
  "snapshotServerSeq": "40",
  "snapshotYjsState": "base64",
  "baselineEditorState": { "schemaVersion": 3, "canvas": {}, "layers": [] },
  "canEdit": true,
  "presence": ["101", "102"],
  "updates": []
}
```

### update

```json
{
  "type": "update",
  "roomEpoch": "uuid",
  "operationId": "client-uuid-counter",
  "gestureId": "drag-uuid",
  "kind": "layer.patch",
  "targetId": "layer-42",
  "lockToken": "lock-token",
  "changedFields": ["left", "top"],
  "phase": "commit",
  "yjsUpdate": "base64"
}
```

服务端先在 `collaboration_update` 写入 `operationId/serverSeq/yjsUpdate`，再向其他客户端广播，并向发送方返回 `ack`。重复 `operationId` 只返回原 ACK，不重复写入。

### ack、presence 和 awareness

```json
{ "type": "ack", "operationId": "client-uuid-counter", "serverSeq": "43", "duplicate": false }
```

`presence` 和 `awareness` 是临时消息，不写入正式版本。对象锁消息使用 `lock.acquire`、`lock.renew`、`lock.release`，服务端返回 `lock.granted` 或 `lock.denied`；锁冲突时客户端撤销本地乐观修改。

## Yjs 文档映射

Yjs 文档内部不使用 Fabric 私有 JSON：

```text
metadata: schemaVersion
canvas: width/height
transform: rotation/scale/flipX/flipY
crop: 完整 CropRect
adjustments: 每个调整项独立字段
layers: layerId -> Y.Map
layerOrder: Y.Array<string>
```

文字内容使用 `Y.Text`；绘画路径使用 `Y.Array`，完成的绘画路径只按业务图层变换修改。前端将 Yjs 文档读取为 `EditorState v3`，现有 Fabric renderer 继续负责交互和 PNG 导出。

## 当前边界

- 当前更新广播和对象 TTL 锁在单个 Spring 进程内；多实例 Redis Pub/Sub、分布式锁和独立 Hocuspocus 服务尚未接入。
- 当前 snapshot 在 checkpoint 时写入，更新日志压缩和长期保留策略尚未实现；更新补偿按 500 条分页。
- 团队图片的“退出”保留已同步修改，不提供回滚整个协作会话的单人“不保存并退出”。
- 历史恢复或替换图片后会在 M3 事务中创建新的 `roomEpoch`，旧连接必须重新获取会话。
- WebSocket 消息大小、频率和连接数已有基础限制，公网限流、CSRF、Origin 配置和监控仍属于安全上线闸门。
