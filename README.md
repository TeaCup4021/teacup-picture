# Teacup Picture

茶杯云图库是一个前后端分离的 Monorepo。当前已完成 M1 图片闭环：账号注册登录、默认个人空间、图片上传与 URL 导入、公开申请、管理员审核、公开图库和图片详情。

## 当前状态

| 模块 | 状态 | 事实来源 |
| --- | --- | --- |
| M1 前端与后端 | 已实现 | `frontend/`、`backend/`、`docs/openapi/m1.yaml` |
| M1 产品与接口决策 | 已确认 | `docs/product-prd.md`、`docs/m1-api-contract.md` |
| UI v1.1 | 已落地到当前 M1 页面 | `docs/ui-design/README.md` |
| M2～M7 | 规划中，禁止用假数据提前实现 | `docs/product-prd.md` 路线图 |

完整文档导航与优先级见 [`docs/README.md`](docs/README.md)。后端已知缺口见 [`docs/backend-gap-analysis.md`](docs/backend-gap-analysis.md)。图片统一存储规范见 [`docs/picture-storage.md`](docs/picture-storage.md)：业务图片只能存入私有 MinIO，禁止恢复 COS 或新增其他并行存储。

## 目录

```text
backend/   Spring Boot 后端
frontend/  Next.js 前端
docs/      产品、接口、缺口与 UI 设计文档
docker/    本地 MySQL、Redis 与 MinIO 配置
```

## 本地运行

要求 Java 17、Maven 3.8+、Node.js 20+、pnpm，以及 `teacup-picture` Docker Compose 中可用的 MySQL、Redis 和 MinIO。环境变量示例分别见 `backend/src/main/resources/application.yml`、`frontend/.env.example` 和 `docker/.env.example`。

```powershell
# 后端
Set-Location backend
mvn test
mvn spring-boot:run

# 前端（另一个终端）
Set-Location frontend
pnpm install
pnpm dev
```

默认地址：

- 前端：`http://localhost:3000`
- 后端：`http://localhost:8123`
- API 基础路径：`http://localhost:8123/api/v1`

## 质量检查

```powershell
Set-Location backend
mvn test

Set-Location ..\frontend
pnpm lint
pnpm typecheck
pnpm test
pnpm build
```

涉及注册、上传和审核闭环时追加 `pnpm test:e2e`；完整链路需要配置测试管理员账号。
