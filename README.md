# Teacup Picture

茶杯云图库采用前后端分离的 Monorepo，包含 Spring Boot 后端、Next.js 前端规划、跨端产品文档和本地容器化配置。

## 目录

```text
teacup-picture/
  backend/   Spring Boot 后端
  frontend/  React + Next.js 前端
  docs/      PRD、架构和接口文档
  docker/    本地 Docker 配置
  AGENTS.md
  README.md
```

当前阶段以仓库与接口契约基线为主。产品范围见 `docs/product-prd.md`，M1 契约见 `docs/m1-api-contract.md` 和 `docs/openapi/m1.yaml`，后端现状与实施缺口见 `docs/backend-gap-analysis.md`。

## 后端

要求 Java 17 和 Maven 3.8+。本地数据库、Redis、COS 与 AI Provider 参数应通过未提交的本地配置或 `DB_*`、`REDIS_*`、`COS_*`、`ALIYUN_AI_API_KEY` 环境变量提供。

```bash
cd backend
mvn test
mvn spring-boot:run
```

## 前端

`frontend/` 当前保留前端工程规则，初始化和开发约定见 `frontend/AGENTS.md`。目标技术栈为 Node.js 20 LTS、pnpm、Next.js App Router、TypeScript 和 Ant Design。

## Git

本目录是新的 Monorepo 根目录。旧后端仓库的 `.git`、IDE 缓存和构建产物未迁入，不继承旧提交历史。
