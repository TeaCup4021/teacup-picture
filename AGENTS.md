# Teacup Picture Monorepo Rules

本文件适用于整个仓库。子目录中的 `AGENTS.md` 可以补充更具体的规则；冲突时以更靠近目标文件的规则为准。

## 产品与事实来源

- 产品范围以 `docs/product-prd.md` 为准。
- 后端实现事实以 `backend/`、数据库迁移和 OpenAPI 契约为准。
- 后端已知缺口及实施顺序见 `docs/backend-gap-analysis.md`。
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

## 开发约束

- 先阅读目标目录及其最近的 `AGENTS.md`，再修改代码。
- 保持改动聚焦，不顺带重构无关模块。
- Java `Long` ID 对外按字符串传输，避免浏览器精度丢失。
- 新接口统一放在 `/api/v1`；公开匿名接口仅放在 `/api/v1/public/**`。
- 身份认证使用服务端 Cookie/Session，不在浏览器持久化服务端密钥或登录令牌。
- 数据库结构变更必须使用版本化迁移；不得只修改本地数据库或 `create_table.sql`。
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
