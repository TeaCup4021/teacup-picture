# M6 分享、评论与批注 API 契约

- 状态：M6 开发闭环已实现
- 机器可读接口：`openapi/m6.yaml`
- 数据库迁移：`V17__establish_current_picture_version.sql`、`V18__add_m6_shares_and_comments.sql`

## 范围

M6 覆盖图片下载、公开撤回、受控链接分享、评论、回复、提及和位置批注：

- 图片所有者或团队 owner/admin 管理分享；同一图片最多一个未撤销链接。
- 重新生成会立即撤销旧链接；已到期链接在下次创建时先撤销再替换。
- 分享地址为 `/shares/{publicId}#{secret}`。浏览器片段不会进入服务器日志，前端仅在访问校验请求体中提交密钥。
- 数据库只保存密钥 SHA-256 和可选密码 BCrypt，不保存完整分享 URL、原始密钥或明文密码。
- 校验成功后在 `HttpSession` 中授予该分享访问权；链接撤销或到期后已有 Session 也立即失效。
- 分享始终跟随图片当前版本，但访问者不能读取历史版本资源。
- 登录用户可以下载、评论、回复、提及和添加位置批注；匿名分享访问者只能查看图片和已有讨论。
- 评论与批注使用同一讨论串。批注坐标为 `[0,1]` 归一化坐标并绑定创建时的不可变 `pictureVersionId`。
- 历史版本批注保留在讨论列表中，但不覆盖到当前图片；陈旧版本创建批注返回 409。
- viewer 可以评论和解决自己的讨论，不能解决他人的讨论；editor、团队 admin/owner 和个人图片所有者按服务端权限管理。

## 分享访问

创建接口只在成功响应中返回一次 `sharePath`。后续读取活动分享只返回元数据，不再返回密钥：

```text
POST   /api/v1/pictures/{pictureId}/shares
GET    /api/v1/pictures/{pictureId}/shares
POST   /api/v1/pictures/{pictureId}/shares/regenerate
DELETE /api/v1/pictures/{pictureId}/shares/{shareId}
```

访问者先提交片段密钥和可选密码：

```http
POST /api/v1/public/shares/{publicId}/access
Content-Type: application/json

{ "secret": "fragment-secret", "password": "optional-password" }
```

无密码分享校验密钥后直接授权；有密码分享还受 Redis 的 IP/链接维度失败次数限制。密码缺失或错误返回 HTTP 401、业务码 `40102`；超过限制返回 `42900`；Redis 不可用时失败关闭并返回 `50300`。不存在、撤销、到期、错误密钥和未获得授权统一按 404 隐藏资源状态。

分享元数据和图片资源接口均使用 `Cache-Control: no-store` 和 `Referrer-Policy: no-referrer`。页面设置 `noindex`、`nofollow`、`no-referrer`：

```text
GET /api/v1/public/shares/{publicId}
GET /api/v1/public/shares/{publicId}/content
GET /api/v1/public/shares/{publicId}/download
```

内容允许获得分享授权的匿名 Session 查看；下载额外要求登录。登录续办只把不含 fragment 的站内分享路径放入 `returnTo`，片段密钥暂存在当前标签页 `sessionStorage`，登录成功后在浏览器内恢复并立即清理续办记录。原始密钥只进入专用 access 校验请求体，不进入登录页请求、`returnTo`、访问日志或数据库。

## 评论与批注

```text
GET    /api/v1/pictures/{pictureId}/comments
GET    /api/v1/public/pictures/{pictureId}/comments
GET    /api/v1/public/shares/{publicId}/comments
POST   /api/v1/pictures/{pictureId}/comments
POST   /api/v1/comments/{rootId}/replies
PATCH  /api/v1/comments/{rootId}
DELETE /api/v1/comments/{commentId}
GET    /api/v1/pictures/{pictureId}/comment-mention-candidates
```

根讨论按倒序 ID 游标分页，每页 20 条；回复按时间升序返回，单次最多 100 条。回复接口路径中的 `rootId` 标识讨论串，请求体中的 `replyToId` 可指向根评论或该讨论串内的任意回复。删除采用软删除并清空正文，同时删除提及关系。评论频率由 Redis 按用户和 IP 限制为每分钟 20 次。

普通评论不携带版本和坐标。位置批注请求示例：

```json
{
  "kind": "annotation",
  "body": "杯把高光过强，请降低亮度",
  "pictureVersionId": "300",
  "x": 0.75,
  "y": 0.4,
  "mentionedUserIds": ["12"]
}
```

提及候选只包含图片所有者、团队成员和已有讨论参与者，并排除当前用户。前端提交结构化用户 ID，不从正文昵称推断身份。回复、提及、解决和重新打开复用 M4 `notification` 表写入站内通知。

## 图片下载与公开撤回

```text
GET    /api/v1/pictures/{pictureId}/download
GET    /api/v1/public/pictures/{pictureId}/download
DELETE /api/v1/pictures/{pictureId}/publication
```

下载统一从后端 `PictureStorage` 读取私有 MinIO 对象，并以附件响应；浏览器不接触对象键、MinIO 地址或凭据。公开撤回由图片所有者或具备分享管理权的团队 owner/admin 执行，图片立即离开公开图库，最新已批准申请标记为 `withdrawn`。

## 数据模型

- `picture.currentVersionId`：当前可见资产对应的不可变版本 ID；上传、AI 结果、另存、替换和恢复均维护该字段。
- `picture_share`：分享公开号、密钥/密码哈希、到期和撤销状态；生成列唯一约束保证每图一个未撤销链接。
- `picture_comment`：根讨论、回复、版本、坐标、删除和解决状态。
- `comment_mention`：评论与被提及用户的复合主键关系。
- `notification`：继续复用既有通知表，不为 M6 创建并行通知模型。

## 验证边界

- 服务层和控制器测试覆盖成功、参数/版本冲突、未登录、无权限、不存在、到期/撤销、密码、限流依赖失败和资源响应。
- 前端测试覆盖片段密钥只进入请求体、结构化提及载荷和批注坐标归一化。
- Docker Desktop 的 MySQL 8、Redis 和 MinIO 健康运行；后端真实启动已由 Flyway 成功执行 V17/V18，`picture_share`、`picture_comment`、`comment_mention` 均已创建，存量有效图片的 `currentVersionId` 回填无缺失。
- 后端 `mvn test` 共 101 项通过；前端 lint、类型检查、46 项 Vitest 和生产构建通过；M6 OpenAPI 可由 `openapi-typescript` 成功解析。
- 完整 Playwright 共 5 项业务流程通过，3 项仅用于更新截图基线的 `@visual` 用例按开关跳过；临时管理员已实际执行 M1 审核发布和 M6 审核后撤回场景，结束后恢复为普通用户。
- M6 主流程覆盖受密码保护分享、匿名访问、片段密钥登录续办、下载、评论、回复、结构化提及、位置批注、解决/重新打开、软删除、重新生成和撤销；公开图片审核后由所有者撤回也已通过。
- 已人工检查图片详情和已授权分享页在 1440x900、1024x768、390x844 三视口的 6 张全页截图，无横向溢出、控件越界或同父级控件重叠；Next.js 开发态 issues 徽标不属于产品 UI。
