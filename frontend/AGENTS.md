# 茶杯图库前端开发规则

本文件适用于 `frontend/`。产品范围以 `../docs/product-prd.md` 为准，当前后端能力以 `../backend/`、Flyway 和 `../docs/openapi/m1.yaml` 为准，视觉与交互以 `../docs/ui-design/README.md` 为准。

## 当前阶段

当前已经实现 M1：

- `/`：匿名公开图库。
- `/pictures/[pictureId]`：图片详情；公开图片允许匿名访问，私有图片依赖会话和服务端权限。
- `/login`、`/register`：账号与 Cookie 会话。
- `/spaces/personal`：个人图片和公开审核状态。
- `/upload`：本地文件或图片 URL 导入个人空间。
- `/admin/reviews`：平台管理员处理公开申请。

M2～M7 的 AI、编辑器、团队、协作、通知、分享、评论和分析仍是规划能力。对应产品图和页面规范只保存设计方向，不代表路由、接口、依赖或数据模型已就绪。

## 当前技术栈

```text
React 19 + TypeScript + Next.js 16 App Router
Ant Design + @ant-design/icons
TanStack Query + Axios
React Hook Form + Zod
Vitest + React Testing Library + Playwright
ESLint + Prettier
pnpm
```

- 只使用 Next.js App Router，不引入 React Router。
- 只使用 Ant Design 和项目自定义组件，不引入第二套 UI 或图标库。
- Zustand、ECharts、WebSocket、编辑器和协作库只能在对应里程碑需要且架构决策完成后使用；依赖存在不代表必须使用。
- 修改 Next.js 代码前阅读 `node_modules/next/dist/docs/` 中与当前版本对应的文档，不依赖旧版本记忆。

## 目录边界

```text
src/
  app/       路由、布局、Provider 和全局错误状态
  widgets/   页面级组合组件
  features/  用户可执行的业务能力、Query 和业务适配
  shared/    跨业务复用的基础设施、样式和组件
  api/       统一 API Client 与 OpenAPI 生成类型
```

- `app/` 不承载复杂业务逻辑。
- 服务端数据通过 TanStack Query 管理，不复制到全局 Store。
- 页面和组件不得直接使用 `fetch`/Axios 或拼接业务 URL；统一经 `src/api/client.ts` 和业务 API 封装。
- `src/api/generated/` 是生成代码，禁止手工修改。
- 不创建巨型通用 `components/`、`utils.ts` 或单一全局 Store。

## API 与权限

- 所有新接口位于 `/api/v1`；匿名只读接口仅位于 `/api/v1/public/**`。
- `docs/openapi/m1.yaml` 是 M1 编译期契约；代码与契约不一致时先修正契约或后端，不回退到旧 `/api/**`。
- Java `Long` ID 始终按 `string` 处理，禁止转换为 `number`。
- 统一处理 `BaseResponse<T>`、HTTP 状态、字段错误和 `requestId`。
- 身份认证使用 `HttpOnly` Cookie/Session，Axios 开启 `withCredentials`；浏览器不得持久化登录令牌或服务端密钥。
- 前端隐藏按钮不等于授权，敏感操作必须依赖服务端校验。
- 未实现的后端能力必须呈现为空、错误或不可用状态，禁止用本地假数据伪装成功。

## 图片规则

- 列表优先使用 `thumbnailUrl`，详情按契约使用可查看的图片 URL。
- 图片持久化唯一实现为后端管理的私有 MinIO；前端只消费 API 返回的资源 URL，不得拼接 MinIO/COS/其他对象存储地址，不得上传到对象存储或持有对象键、凭据、预签名 URL。
- 当前上传支持 JPEG/JPG、PNG、WebP，单张最大 20 MB；前端校验不能替代后端校验。
- 普通用户当前只上传到默认个人空间；团队目标空间等待 M4 契约。
- 使用 Next.js Image，提供稳定尺寸、懒加载和失败占位；首屏 LCP 图片标记优先加载。
- `public/mock-images/` 只用于登录视觉、失败占位、测试和本地视觉验收，不能作为业务 API 成功数据。

## UI 与响应式

- 修改页面前阅读对应 `../docs/ui-design/pages/*.md`、公共规范和参考产品图。
- 使用 `src/shared/styles/design-tokens.css` 与 `src/shared/styles/antd-theme.ts`，业务组件不另建颜色、圆角和动效体系。
- 公共页面使用顶部导航；登录后的业务和管理页面使用冰蓝灰侧栏工作台。
- 当前 M1 页面需要适配 1440×900、1024×768 和 390×844；移动适配不得改变业务能力。
- 动效支持 `prefers-reduced-motion`，优先使用 `transform` 和 `opacity`。
- 状态不能只依赖颜色；表单需要可见标签，图标按钮需要 `aria-label` 或 tooltip。
- 页面必须处理 loading、empty、error、permission denied 和操作反馈。

## 当前实现边界

- 公开图库当前一次读取 M1 接口返回的批次；游标续页和滚动位置恢复尚未实现。
- 个人空间当前在客户端进行状态筛选；服务端分页和 URL 查询状态尚未接入。
- 登录成功当前进入角色默认页；“返回登录前页面”尚未实现。
- 公开详情当前不展示下载、评论、分享、缩放或编辑入口。
- 主题切换和深色全站主题尚未实现；当前基线为浅色，只有未来编辑器允许独立深色工作区。

不得把上述缺口仅通过修改文档标记为完成；实现时同步更新代码、测试和对应文档。

## 测试与验收

每次前端改动至少执行：

```bash
pnpm lint
pnpm typecheck
pnpm test
pnpm build
```

涉及注册、登录、上传、审核或公开读取时追加：

```bash
pnpm test:e2e
```

完整 E2E 需要测试管理员凭据。未配置导致的跳过必须在交付说明中明确记录。

视觉改动按 `../docs/ui-design/visual-regression.md` 检查桌面、紧凑桌面和手机。只有人工走查通过的页面才能批准或更新视觉基线。

## 完成标准

- 路由、接口和权限符合当前里程碑及 OpenAPI。
- 成功、加载、空、失败和无权限状态完整。
- 没有新增假数据、密钥暴露或未记录的后端假设。
- 设计规范、实现和测试同步更新。
- lint、类型检查、单元测试和生产构建通过。
- 核心流程有 Playwright 覆盖，或明确记录外部环境造成的测试缺口。

<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
