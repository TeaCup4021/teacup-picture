# M1 API 契约决策

状态：Accepted  
日期：2026-08-11  
机器可读契约：`docs/openapi/m1.yaml`

## 范围

本契约只覆盖 M1 闭环：

- 注册、登录、退出与当前用户。
- 注册时创建的默认个人空间。
- 本地文件上传和图片 URL 导入。
- 登录用户的图片列表与详情。
- 用户提交公开申请，平台管理员审核或撤回。
- 匿名公开图库列表与图片详情。

评论、下载、分享、团队空间、AI、版本和实时协作不在本契约内。

## 通用约定

- 基础路径为 `/api/v1`。
- 公开匿名只读接口只位于 `/api/v1/public/**`。
- 平台管理员接口只位于 `/api/v1/admin/**`。
- 所有 Java `Long` ID 在 JSON、路径和查询参数中均使用十进制字符串。
- 时间使用 ISO 8601 UTC 字符串，例如 `2026-08-11T08:00:00Z`。
- JSON 和 multipart 文本字段统一使用 UTF-8。
- 成功和失败都使用 `BaseResponse<T>`；`code === 0` 表示业务成功。
- 每个响应都返回 `X-Request-Id`，响应体同步包含 `requestId`。

## 会话

- 注册成功返回 `userId` 和 `personalSpaceId`，但不自动登录。
- 登录成功设置 `TEACUP_SESSION` Cookie，并返回当前用户。
- Cookie 为 `HttpOnly`、`Path=/`、`SameSite=Lax`；生产环境必须启用 `Secure`。
- 浏览器请求使用 `withCredentials: true`，前端不保存会话令牌。
- 退出登录幂等；无论会话是否存在都返回成功并清除 Cookie。

## 默认个人空间

- 用户与默认个人空间在同一注册事务中创建。
- 每个用户只能有一个个人空间，且不能通过独立 API 删除。
- `GET /spaces/personal` 是唯一的默认个人空间定位接口。
- 上传请求不传 `spaceId` 时，服务端必须使用当前用户的默认个人空间。

## 图片与分页

- 本地上传支持 JPEG、JPG、PNG、WebP，最大 20 MB。
- URL 导入与本地上传返回相同的 `PictureDetail`。
- 上传后图片保持私有，`publishStatus` 初始为 `not_requested`。
- 私有图片列表使用 `page/pageSize`，默认按 `createdAt desc, id desc` 排序；M1 不开放任意排序字段。
- 公开图库使用不透明 `cursor` 和 `limit`，适配无限滚动；M1 不锁定搜索、筛选和热度算法。

## 公开审核状态机

```text
not_requested -> pending -> approved -> public
                         -> rejected -> private
approved/public -> withdrawn -> private
rejected/withdrawn -> 新建 publish request -> pending
```

- 同一图片同一时间只能有一个 `pending` 申请。
- 只有图片所有者或具备图片管理权限的成员可以提交申请。
- 只有平台 `admin` 可以查看审核队列、通过、拒绝和撤回。
- 拒绝与撤回必须提供原因；每次决定保留独立审核记录。
- 公开 DTO 不返回权限列表、内部审核备注、空间信息或原始审核记录。

## HTTP 与业务错误

| HTTP | code | 含义 |
| ---: | ---: | --- |
| 400 | 40000 | 参数或格式错误 |
| 401 | 40100 | 未登录或会话失效 |
| 403 | 40101 | 已登录但无权限 |
| 404 | 40400 | 资源不存在或不可见 |
| 409 | 40901 | 唯一约束或状态冲突 |
| 413 | 41300 | 上传文件超过限制 |
| 415 | 41500 | 不支持的图片类型 |
| 429 | 42900 | 请求过于频繁 |
| 500 | 50000 | 未预期服务端错误 |

参数错误通过 `errors` 返回字段级原因。404 不区分“确实不存在”和“当前调用者不可见”，避免泄露私有资源状态。

## 与当前后端的关系

这是目标契约，不代表当前 Spring 控制器已经实现。现有 `/api/**`、数字审核状态和 DTO 需要通过 v1 适配层迁移；前端只能以 OpenAPI 生成类型为编译期契约，不得回退到旧接口或用假数据冒充接通。
