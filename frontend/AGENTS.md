# 茶杯云图库前端开发规则

本文件适用于 `frontend/` 目录及其所有子目录。它是前端工程和 AI 编码任务的执行约束；产品范围以 `../docs/product-prd.md` 为准，后端事实以 `../backend/` 和 OpenAPI 文档为准。

## 1. 项目目标

前端是茶杯云图库的 React/Next.js 应用，面向桌面浏览器提供：

- 公开图库和图片详情。
- 用户登录、个人空间和图片管理。
- AI 绘图、AI 扩图和任务历史。
- 单人图片编辑和可恢复版本。
- 团队空间、成员邀请和实时协作。
- 图片公开审核、链接分享、评论和位置批注。

当前开发目标是先完成 M0/M1，再按 PRD 的 M2～M7 推进。不要为了演示提前伪造完整 AI、协作或分享能力。

## 2. 固定技术栈

```text
React + TypeScript + Next.js App Router
Ant Design
TanStack Query
Zustand
Axios
React Hook Form + Zod
ECharts
WebSocket
Vitest + React Testing Library
Playwright
ESLint + Prettier
pnpm + Node.js 20 LTS
```

规则：

- 使用 Next.js App Router，不使用 React Router。
- Next.js 负责应用构建和 SSR/ISR；Vitest 可使用 Vite 作为测试底层，但不把 Vite 当作应用框架。
- 不同时引入多个 UI 组件库。
- 具体图片编辑器、协同库、AI 模型和部署工具尚未确定，未经决策不得擅自引入。

## 3. 前端与后端边界

```text
Next.js Frontend
  -> API Client / TanStack Query
    -> Spring Boot /api/v1
      -> MySQL / Redis / COS / AI Provider / WebSocket
```

- 前端只调用 Platform API，不直接访问数据库、Redis、COS 管理接口或 AI Provider。
- 服务端密钥、COS 密钥、模型密钥和 Sa-Token 配置不能出现在浏览器代码中。
- 前端权限控制只影响页面、按钮和交互；最终权限以服务端响应为准。
- 发现后端接口不满足产品规则时，先记录缺口并提出后端变更，不在前端用绕过权限或假数据补齐。

## 4. 目录边界

推荐结构：

```text
src/
  app/          App Router 路由、布局、Provider、错误边界
  widgets/      页面级组合模块
  features/     登录、上传、AI、评论、分享、邀请、协作等功能
  entities/     User、Picture、Space、Comment、AiTask 等领域模型
  shared/       通用组件、工具、样式、请求基础设施
  api/
    generated/  OpenAPI 自动生成类型和底层客户端
    *.ts        面向业务的 API 封装
```

- `app/` 负责路由和页面组合，不承载复杂业务逻辑。
- `features/` 负责用户可执行的业务能力。
- `entities/` 负责领域类型、展示模型和轻量转换。
- `shared/` 只能放真正跨业务复用的代码。
- 业务模块只能通过公开入口导入，不能跨模块直接引用内部文件。
- 不创建巨型 `components/`、`utils.ts` 或 `store.ts` 文件。
- 生成代码放在 `api/generated/`，禁止手工修改；业务适配放在 `api/` 或对应 feature 中。

## 5. 路由与渲染

公开路由：

- `/`：公开图库。
- `/pictures/[pictureId]`：公开图片详情。

业务路由：

- `/login`、`/register`。
- `/spaces/personal`。
- `/spaces/team`、`/spaces/[spaceId]`。
- `/upload`。
- `/ai/create`、`/ai/tasks`。
- `/editor/[pictureId]`。
- `/notifications`。

管理员路由：

- `/admin/reviews`。
- `/admin/users`。
- `/admin/spaces`。

渲染规则：

- 公开图库和公开图片详情使用 SSR/ISR，并调用 `/api/v1/public/**`。
- 个人空间、团队空间、AI 任务、通知、编辑器和管理后台使用客户端请求，禁止公共缓存。
- 只有确实需要浏览器 API、状态或事件处理的组件才使用 `use client`。
- 公开页面允许搜索引擎收录；私有页面、管理页面和仅链接分享页面设置 `noindex`。
- 从图片详情返回公开图库时恢复滚动位置和查询状态。

## 6. API 与数据请求

所有接口遵循 `/api/v1`。接口契约以 OpenAPI 为准：

```text
OpenAPI / Java DTO
  -> 生成 TypeScript 类型
  -> 统一 API Client
  -> TanStack Query Hook
  -> 页面或组件
```

规则：

- 页面和组件不得拼接 URL 或直接使用 `fetch`/Axios 请求业务接口。
- 所有请求经过统一 API Client，统一处理 `BaseResponse<T>`、HTTP 状态和 `requestId`。
- `code === 0` 表示业务成功；业务错误必须转成可展示的错误对象。
- Java `Long` ID 在前端按 `string` 处理，禁止转换成 `number`。
- 分页、列表、详情、错误和异步任务类型集中定义。
- 服务端数据使用 TanStack Query；通过 query invalidation 更新缓存。
- 不把服务端列表、详情或任务数据复制到 Zustand。
- API 失败、空数据和加载中状态必须由页面显式处理。

公开接口与登录接口分开：

```text
/api/v1/public/**  匿名公开只读数据
/api/v1/**         登录用户业务接口
/api/v1/admin/**   平台管理员接口
```

## 7. 登录、权限与会话

- 使用 HttpSession/Sa-Token Cookie 会话。
- Axios 开启 `withCredentials`。
- 不在 `localStorage` 保存登录 Token、Cookie 或服务端密钥。
- 收到未登录错误时保存当前 URL，跳转登录页，登录成功后返回原页面。
- 收到无权限错误时展示明确提示或无权限页面，不静默失败。
- `viewer` 只能查看；`editor` 可以查看、上传、编辑和删除；`admin` 还可以管理成员。
- 团队所有者是独立概念，不能用前端角色字符串替代所有权判断。
- 公开图片访客可以查看；下载、评论和分享需要登录；继续编辑需要受邀且具备编辑权限。
- 前端隐藏按钮不等于授权，所有敏感操作必须依赖后端校验。

## 8. 状态管理

```text
TanStack Query：用户、图片、空间、评论、通知、AI 任务、成员
Zustand：当前空间、主题、编辑器 UI、临时全局交互状态
React state：表单输入、弹窗、局部选中和 hover 状态
```

- 每个 Query 使用稳定、可读的 query key。
- 上传、删除、审核、评论、邀请、AI 任务完成后主动失效相关缓存。
- 当前空间变化时清理不属于新空间的列表和详情缓存。
- 退出登录时清理用户相关 Query 和 Zustand 状态。
- 不使用单个全局 Store 承载整个应用。

## 9. 图片、AI 与编辑器规则

图片：

- 列表优先使用 `thumbnailUrl`，详情和编辑器再请求原图。
- 支持 JPEG、JPG、PNG、WebP，单张最大 20 MB；前端校验不能替代后端校验。
- 图片展示使用 Next.js Image；编辑器原图必须满足 COS CORS 要求。
- 第一版普通上传只开放本地文件和图片 URL，不调用 Bing 关键词抓图接口。
- 第一版批量操作只实现分类和标签修改。

AI：

- 模型、比例、清晰度和能力由后端返回，不在前端硬编码模型列表。
- AI 绘图和 AI 扩图每日各 100 次，两个配额独立。
- 任务必须有 queued/running/succeeded/failed/cancelled 状态。
- 生成结果自动保存到个人空间，同时提供下载。
- 任务失败原因和重试入口不能被吞掉。

编辑器：

- 需要支持旋转、裁切、缩放、涂鸦、擦除、文字和图片调节。
- 编辑数据应结构化保存，不能只依赖导出的最终位图。
- 自动保存草稿；关键节点创建正式版本。
- 恢复旧版本时创建新的当前版本，不删除后续历史。
- 编辑器底层库尚未确定，禁止先绑定某个库的不可逆数据格式。

## 10. 实时协作规则

- 每张图片的当前版本对应一个协作房间。
- 进入房间获取快照、版本号、在线成员和当前操作序号。
- 编辑操作携带唯一操作 ID、对象 ID、基础版本号和操作 payload。
- 服务端负责权限校验、操作排序和广播。
- 客户端按服务端顺序应用操作，不能自行决定最终顺序。
- 不同对象允许并行编辑；同一对象使用对象级软锁和冲突提示。
- 光标、在线状态、加入和离开属于临时消息，不写入正式版本。
- 连接关闭、重连和消息异常必须有可观察状态。
- 断线重连后获取遗漏操作或最新快照。
- CRDT、OT 和具体协作库待实现阶段比较。

## 11. UI、主题与可访问性

视觉方向为“飞书式协作效率 + X 式内容发现”，不复制品牌界面。

设计事实来源：

- 修改页面前必须阅读 `../docs/ui-design/README.md`、对应的 `pages/*.md` 和参考产品图。
- 产品能力和权限仍以 PRD、后端实现和 OpenAPI 为高优先级事实来源；产品图不单独定义业务能力。
- `../docs/ui-redesign-proposal.md` 是已被 UI v1.1 取代的历史探索，不得根据其中建议擅自引入 Tailwind、Lucide、Framer Motion 或第二套 UI 体系。
- 页面必须使用 `src/shared/styles/design-tokens.css` 和 `src/shared/styles/antd-theme.ts`；业务组件不得自行建立主色、背景、圆角和动效体系。
- 产品图中的 M2～M7 能力只能在对应后端能力和里程碑就绪后实现，不得使用假数据提前伪装。
- 修改公共视觉组件时至少检查公开图库、图片详情、个人空间、上传和管理后台的影响。
- 已完成 UI v1.1 走查的页面按 `../docs/ui-design/visual-regression.md` 建立和维护 Playwright 截图。

- 公开图库使用瀑布流和无限滚动。
- 个人空间、团队空间使用规则网格和分页。
- 编辑器使用深色工作区，减少画布周围干扰。
- 只使用一个 UI 组件体系（Ant Design + 项目自定义组件）。
- 使用已有图标库中的图标，不手写重复 SVG。
- 桌面优先，Chrome/Edge 最新两个版本为主要验收范围。
- 第一版只提供简体中文。
- 首次访问跟随系统主题；用户手动切换后保存在浏览器本地。
- 支持键盘操作、清晰焦点、表单标签和足够颜色对比度。
- 状态不能只依赖颜色表达。
- 每个页面必须考虑 loading、empty、error、permission denied 和成功反馈。
- 不把功能说明、快捷键说明或教程段落堆在页面中；通过合理的界面状态和必要的 tooltip 提供信息。

## 12. 测试、质量与性能

新增功能至少提供与风险匹配的测试：

Vitest：

- API 类型适配。
- 权限判断。
- 状态逻辑。
- 编辑器核心工具函数。

React Testing Library：

- 登录表单。
- 上传组件。
- 评论组件。
- 权限按钮和空错误状态。

Playwright：

- 注册和登录。
- 上传图片。
- AI 任务提交与结果保存。
- 团队邀请与接受。
- 图片评论和分享。
- 管理员审核。

每次改动至少执行：

```bash
pnpm lint
pnpm typecheck
pnpm test
pnpm build
```

涉及核心用户流程时追加：

```bash
pnpm test:e2e
```

性能规则：

- 图片懒加载和失败占位。
- 公开图库分批或虚拟化渲染。
- 搜索输入防抖。
- 路由和大型编辑模块懒加载。
- WebSocket 光标和高频操作节流。
- 不在管理列表中预加载所有原图。

## 13. 错误处理与可观测性

- Axios 统一处理 HTTP 状态、业务错误码和 `requestId`。
- 页面使用 Error Boundary，避免单个组件错误击穿整个应用。
- WebSocket 记录连接、断开、重连和消息错误。
- AI 任务展示失败原因，不用“生成失败”覆盖全部上下文。
- 生产环境接入前端错误监控，工具在实现阶段确定。
- 日志不得记录密码、Cookie、分享密码、服务端 Token 或完整敏感 URL。
- 用户可见错误使用简体中文，技术细节进入日志和监控。

## 14. AI 开发工作流

每个 vibe coding 任务必须遵循：

1. 先阅读本文件、`../docs/product-prd.md` 和相关后端代码。
2. 明确目标页面、API、权限、状态和验收条件。
3. 先检查现有模块，优先复用已有 API、组件和 Query。
4. 只修改完成任务所需的文件，不做无关重构。
5. 先实现最小可用路径，再补 loading、empty、error、权限和响应式状态。
6. 为跨模块或共享行为补测试。
7. 执行 lint、typecheck、test 和 build。
8. 汇报修改文件、接口假设、测试结果和未解决风险。

任务描述应包含：

```text
目标页面或功能
使用的后端接口
请求和返回类型
登录与空间权限
加载、空数据、失败和无权限状态
验收标准
明确禁止修改的模块
```

## 15. 禁止事项

- 不得把 PRD 中的暂缓决策擅自变成依赖或数据协议。
- 不得直接修改 `api/generated/` 生成代码。
- 不得在组件内拼接 URL 或重复实现响应错误处理。
- 不得用本地假数据伪装真实接口成功，除非任务明确是原型阶段。
- 不得把所有服务端数据放入 Zustand。
- 不得在前端保存 COS、AI、数据库或会话密钥。
- 不得绕过后端权限来实现下载、编辑、审核或空间管理。
- 不得在未评估大图性能时加载原图列表。
- 不得删除或覆盖用户版本、评论和空间数据而不经过产品规则确认。
- 不得为了完成一个页面顺手重写无关模块。
- 不得引入第二套 UI 组件库或未经讨论的编辑器/协作库。

## 16. 功能完成标准

一个前端功能只有同时满足以下条件才算完成：

- 页面路由和权限符合 PRD。
- API 类型、请求封装和 Query key 已归位。
- 成功、加载、空数据、失败和无权限状态完整。
- 关键操作有明确反馈，危险操作有确认。
- 组件在 Chrome/Edge 主要支持范围内可用。
- TypeScript、Lint、单元测试和生产构建通过。
- 核心流程有 Playwright 覆盖或明确记录测试缺口。
- 没有新增未记录的后端假设、密钥暴露或数据丢失风险。

## 17. 暂缓事项

以下事项必须在决定前保持可替换：

- 公开图库的搜索、筛选和热度算法。
- 图片编辑器底层组件。
- CRDT、OT 或其他实时协作方案。
- AI 模型供应商和具体模型。
- Docker、Nginx、CI/CD 和生产部署方案。
- 后端密码哈希、CSRF、限流、SSRF 和 WebSocket Origin 等安全改造。

如果任务依赖上述事项，先提出决策记录，不要直接把临时实现扩散到业务模块。

<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
