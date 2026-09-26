# Teacup Picture Monorepo Rules

本文件适用于整个仓库。子目录中的 `AGENTS.md` 可以补充更具体的规则；冲突时以更靠近目标文件的规则为准。

## 产品与事实来源

- 产品范围以 `docs/product-prd.md` 为准。
- 后端实现事实以 `backend/`、数据库迁移和 OpenAPI 契约为准。
- 后端已知缺口及实施顺序见 `docs/backend-gap-analysis.md`。
- 前端视觉和交互基线见 `docs/ui-design/README.md`；产品图不得覆盖 PRD、权限或真实后端能力。
- 不得使用前端假数据掩盖后端尚未实现的能力。

## 目录职责

```text
backend/   Spring Boot 后端
frontend/  Next.js 前端
docs/      跨端产品、架构和接口文档
docker/    本地容器化配置
```

- 后端使用 Maven，前端使用 pnpm；第一阶段不引入 Nx 或 Turborepo。
- 后端构建产物、IDE 配置、本地密钥和前端依赖不得提交。
- 跨端契约变更必须同步更新 OpenAPI/接口文档与缺口分析。
- 业务图片唯一存储实现为 `teacup-picture` Docker Compose 中的私有 MinIO；完整约束见 `docs/picture-storage.md`。

## 开发约束

- 先阅读目标目录及其最近的 `AGENTS.md`，再修改代码。
- 保持改动聚焦，不顺带重构无关模块。
- Java `Long` ID 对外按字符串传输，避免浏览器精度丢失。
- 新接口统一放在 `/api/v1`；公开匿名接口仅放在 `/api/v1/public/**`。
- 身份认证使用服务端 Cookie/Session，不在浏览器持久化服务端密钥或登录令牌。
- 数据库结构变更必须使用版本化迁移；不得只修改本地数据库或 `create_table.sql`。
- 不得新增或恢复 COS、OSS、S3、云厂商 SDK、本地文件持久化或浏览器直连对象存储。所有图片来源必须经后端 `PictureStorage` 落入 MinIO，前端只使用后端图片资源 URL。
- 新功能需要覆盖成功、参数错误、未登录、无权限和资源不存在等关键路径。

## 验证

后端改动至少执行：

```bash
cd backend
mvn test
```

前端改动按 `frontend/AGENTS.md` 执行 lint、类型检查、测试和构建。

## 安全边界

- 不提交数据库密码、云存储密钥、AI Provider 密钥、Cookie 或分享密码。
- 日志不得记录密码、Cookie、完整敏感 URL 或访问令牌。
- `docs/backend-gap-analysis.md` 中标为“阻断”的安全问题必须在首次共享仓库或公网部署前解决。

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **teacup-picture** (4631 symbols, 15697 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/teacup-picture/context` | Codebase overview, check index freshness |
| `gitnexus://repo/teacup-picture/clusters` | All functional areas |
| `gitnexus://repo/teacup-picture/processes` | All execution flows |
| `gitnexus://repo/teacup-picture/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
