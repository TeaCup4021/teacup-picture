# M3 API 契约决策

- 状态：M3-R 已完成开发闭环
- 首次确认：2026-08-16
- 重构确认：2026-08-16
- 机器可读契约：`docs/openapi/m3.yaml`

## 范围

本契约覆盖 M3 单人编辑器与版本闭环：

- 读取和自动保存 `EditorState v2` 结构化编辑草稿。
- 从当前草稿创建不可变正式版本，并保存渲染预览图。
- 查询版本列表、版本详情和版本鉴权内容。
- 将历史版本恢复为新的当前版本。

多人协作、评论批注、分享、团队空间和 AI 版本来源的跨模块调用不在本契约内。

## 数据模型

- 草稿由 `picture_draft` 持久化，每张图片最多一条，`editorState` 为 `EditorState v2` JSON 字符串。
- 草稿带单调递增的 `revision`；接口按 Java `Long` 规则将其序列化为十进制字符串，写入、删除和版本恢复必须提交读取到的 `expectedRevision`。
- 正式版本由 `picture_version` 持久化，`(pictureId, versionNumber)` 唯一，版本一旦创建不可覆盖。
- 版本保存 `editorState` 作为结构化事实来源，同时保存 `assetObjectKey` 和 `thumbnailObjectKey` 作为预览与导出资产。
- 恢复旧版本时复制旧版本状态和资产引用，创建新的当前版本；旧版本历史不被删除。
- 图片版本资产与图片原图都只写入 `PictureStorage` 管理的私有 MinIO；前端不直接接触对象键。

## 通用约定

- 基础路径 `/api/v1`，全部接口要求登录；不存在匿名版本接口。
- Java `Long` ID 在路径和响应中统一为十进制字符串。
- 时间使用 ISO 8601 UTC。
- 响应使用 `BaseResponse<T>`，`code === 0` 表示成功，并返回 `X-Request-Id`。
- 草稿保存、条件删除和版本恢复在图片行锁内串行执行；`expectedRevision` 与当前草稿不一致时返回 HTTP 409，禁止其他窗口的旧状态覆盖新状态。
- `editorState` 必须是 `schemaVersion === 2` 的 JSON 对象；后端拒绝 v1、数组、字符串、空值和超过 4,000,000 字符的请求。
- 画布宽高为 `1..32768`；最多 500 个图层，图层 ID 最长 128 字符，单条绘画路径最多 20,000 个指令；位置、角度、缩放、文字和路径指令按 OpenAPI 的边界校验。
- 版本来源只允许 `user_save`、`restore`、`ai_generate`、`ai_outpaint`、`team_confirm`。

## 权限

- 只有图片所有者或平台管理员可以读取、保存草稿，查询、创建和恢复版本。
- 其他调用者统一返回 404，不泄露私有资源存在性。
- 版本内容读取继续执行图片所有权校验，不绕过图片权限模型。

## 版本预览

- 创建版本时必须上传渲染后的预览图；后端先写入 MinIO，再写版本记录。
- 数据库写入失败时删除已上传的版本原图和缩略图，避免对象残留。
- 版本列表返回缩略图 URL；版本详情和内容接口返回原图/缩略图。
- 版本内容响应设置 `Cache-Control: no-store` 和 `X-Content-Type-Options: nosniff`。

## EditorState v2

前端使用 Fabric.js 作为交互内核，但 Fabric 私有 JSON 不进入接口契约。后端保存的业务状态固定为以下结构：

```json
{
  "schemaVersion": 2,
  "canvas": { "width": 1600, "height": 1200 },
  "transform": { "rotation": 0, "scale": 1 },
  "crop": null,
  "adjustments": {
    "exposure": 0,
    "brightness": 0,
    "contrast": 0,
    "highlights": 0,
    "shadows": 0,
    "saturation": 0,
    "vibrance": 0,
    "temperature": 0,
    "tint": 0,
    "sharpness": 0,
    "fade": 0,
    "vignette": 0,
    "enhance": 0,
    "dehaze": 0
  },
  "layers": []
}
```

`layers` 只允许业务定义的文字图层和绘画图层。裁切坐标相对于原始图片保存，图层 ID、路径、变换和颜色均必须可 JSON 序列化。服务端不会接收或执行 Fabric 运行时对象。

## 与编辑器前端的关系

- 前端只保存结构化 `EditorState v2`（图层/对象、裁切和调节参数），不把最终像素作为版本唯一事实。
- 版本预览图只是渲染快照，用于列表和恢复前核对；恢复编辑器状态以 `editorState` 为准。
- M3-R 编辑器使用 Fabric.js MIT 作为 Canvas 交互内核；Fabric JSON 通过适配器转换为业务状态。
- 自有图片滤镜管线统一服务编辑器预览、PNG 导出和版本预览，避免 CSS 预览与最终资产不一致。
- 草稿继续使用 800ms 防抖自动保存；同一编辑器会话内的自动保存、退出保存、退出回退、正式版本保存和恢复通过单队列串行提交。
- 顶部退出按钮固定返回 `/pictures/{pictureId}`。退出确认提供“保存草稿并退出”“不保存并退出”“继续编辑”：保存会先冲刷当前草稿；不保存只回退本次会话，并恢复到进入编辑器时或最近一次正式版本保存/恢复后的基线；进入时无草稿则通过条件 `DELETE` 删除本次新建草稿。
- 浏览器刷新、关闭标签或地址栏跳转使用原生 `beforeunload` 提示；站内退出使用上述三动作对话框。

## 关闭验收

- 裁切和 14 项图片调节已实现并覆盖高光、阴影、锐度、增强和去雾专项测试。
- 真实 Playwright 流程覆盖注册、登录、MinIO 上传、文字与三类绘画、裁切、旋转、缩放、撤销/重做、PNG 导出、版本保存和恢复。
- 真实 Playwright 退出流程覆盖新草稿回退删除、继续编辑、正式版本作为新基线、恢复版本作为新基线，以及保存草稿退出后的 revision 递增。
- 标准 `pnpm lint`、`pnpm typecheck`、24 项 Vitest、Next.js 生产构建和 66 项 Maven 测试通过。
