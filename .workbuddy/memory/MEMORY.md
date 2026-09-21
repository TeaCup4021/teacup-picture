# 项目长期笔记 — teacup-picture

## 构建与验证（Windows / Git Bash）

- Maven: `D:\Maven\apache-maven-3.8.2`；JDK 17: `D:\jdk17`（`java` 走 Oracle javapath，Maven 自动识别）。
- **Git Bash 下不能直接用 `mvn`**（sh 脚本会被 MSYS 路径转换破坏，报 `找不到主类 org.codehaus.plexus.classworlds.launcher.Launcher`）。正确姿势：

```bash
export PATH="/c/Users/wolves/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:$PATH"
cd /d/teacup-picture/backend
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' MAVEN_OPTS="-Dfile.encoding=UTF-8"
"/d/Maven/apache-maven-3.8.2/bin/mvn.cmd" -B test
```

- 平台编码是 GBK，Maven 输出含中文时会乱码甚至让 `grep` 判定为二进制。先重定向到文件，再用 `grep -a`。
- Bash 工具默认 PATH 缺 coreutils（`ls`/`dirname` 找不到），需把 PortableGit 的 `usr/bin` 加进 PATH。
- PowerShell 工具在当前环境**输出为空**，排查问题时不要依赖它，用 Bash。

## 本地依赖服务（Docker）

| 服务 | 端口 | 状态 |
| --- | --- | --- |
| MySQL 8.0.46 | 13306 | 常驻 |
| Redis 7.4.9 | 16379 | 常驻 |
| MinIO | 19000 | 常驻 |
| RabbitMQ | 15672 | **不常驻**，需要 AI 任务链路时手动起 |

- 容器名：`teacup-picture-mysql` / `-redis` / `-minio`。凭据在 `docker/.env`（不提交）。
- 用 MySQL 验证迁移脚本时，**先 `CREATE TABLE 临时库.ai_task LIKE 开发库.ai_task` 再执行脚本，验证完 DROP**，绝不直接对 `teacup_picture` 跑迁移（会让 Flyway 校验和错位）。
- 注意 `application-local.yml` 走 `MYSQL_HOST_PORT:13306`，而本机 3306 上另有一个非 Docker 的 MySQL，别连错。

## 测试约定

- `@Tag("integration")` 的测试（`TeacupPictureBackendApplicationTests`）被 surefire 默认排除，`mvn test` **不会验证 Spring 上下文装配**。改了 Bean 装配（新增组件、新容器工厂）时要留意这一点，缺 RabbitMQ/Redis 时无法本地跑通。

## 代码约定（补充 AGENTS.md）

- AI 任务链路的额度终态流转**必须走 `AiTaskService.finalizeQuota`**，不要在出口处各写一遍归还逻辑。
- 任务归属判定统一用 `AiTaskService.ownedBy`（状态 + 执行者标识 + 执行令牌三者同时匹配）。
- 任务归属日期的换算只有 `AiTaskService.usageDate` 一个来源，对账/审计都必须复用它，不得另写时区转换。
- `AiRabbitConfig` 的 `prefetchCount=1` 是 **per-consumer** 语义，与 `concurrentConsumers=4` 精确匹配，**不要"优化"它**。

## 工作方式

- 同一文件的多处修改**必须串行** Edit；并行发多个 Edit 会互相覆盖（会静默丢改动，报成功但内容不见）。
