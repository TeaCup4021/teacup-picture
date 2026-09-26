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
- 空间额度相关的单元测试（`M1ServiceTest` / `M3ServiceTest`）需要 stub `tryConsume` 返回 true。注意 Mockito mock 接口时 **default 方法不会委托给真实实现**，所以 `verify(spaceMapper).update(...)` 这类旧断言必然失效，要改为 `verify(spaces).tryConsume(...)`。
- 验证命令（本机可用，`mvn test` 全绿 144 项，约 1.5 分钟）：

```bash
export PATH="/c/Users/wolves/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:$PATH"
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' MAVEN_OPTS="-Dfile.encoding=UTF-8"
"/d/Maven/apache-maven-3.8.2/bin/mvn.cmd" -B -f "D:/teacup-picture/backend/pom.xml" test > "<日志路径>" 2>&1
```

## 代码约定（补充 AGENTS.md）

- AI 任务链路的额度终态流转**必须走 `AiTaskService.finalizeQuota`**，不要在出口处各写一遍归还逻辑。
- 任务归属判定统一用 `AiTaskService.ownedBy`（状态 + 执行者标识 + 执行令牌三者同时匹配）。
- 任务归属日期的换算只有 `AiTaskService.usageDate` 一个来源，对账/审计都必须复用它，不得另写时区转换。
- `AiRabbitConfig` 的 `prefetchCount=1` 是 **per-consumer** 语义，与 `concurrentConsumers=4` 精确匹配，**不要"优化"它**。
- 空间额度的收支**必须走 `SpaceMapper` 上的额度入口**：`tryConsume`（占用已用）/ `reserve`（占预留）/ `settle`（预留结转）/ `releaseReservation`（释放预留）/ `releaseUsage`（扣减已用）。约束统一为「**已用 + 在途预留 + 本次 ≤ 上限**」。**禁止**在调用点自己写「先读 space 判断、再单独 `setSql` 累加」——后者在并发下每个请求都会读到同一个旧值并同时通过校验，结果是空间被超额占用，而**每一行数值仍然正确、不会被数据核对发现**。
- **上传会话的额度必须在建会话时就占住**（`reserve`），不能只在完成阶段校验：会话从创建到完成可能持续数小时，只判断已用的话用户可以并发开出多个会话、各自通过校验。完成走 `settle` 结转（**不能再用 `tryConsume` 占用一次**），取消与过期清理走 `releaseReservation`。
- 预留量与声明大小恒等，前提是**分片大小固定 5 MiB、总片数由声明大小算出**。一旦允许片大小浮动，预留就会泄漏——改分片协议时必须同时改预留结算。
- `SpaceMapper.tryConsume` 是 Mapper 接口的 **default 方法**（MyBatis 3.4.2+ 原生支持，3.5.9 下验证通过）。它内部必须用 `BaseMapper.update()` 取 `int` 影响行数；`IService.update(Wrapper)` 只返回 boolean，**无法区分「扣减成功」与「条件不成立」**。
- 判断与扣减分离是这类额度问题的通用形态：`createUploadSession` 里的额度检查只是**提前失败**（避免用户白传流量），额度保证必须落在完成阶段的条件更新上。
- **配额对账的年龄门槛只有一个配置项**（`teacup.ai.quota.reconcile-zombie-age-minutes`，默认 11 分钟），候选筛选与僵尸判定共用同一个值。**禁止再拆成两个**——历史上拆成 360/120 时，360 > 120 使僵尸判定沦为死条件，用户为悬挂预占白等 6 小时。该值是「4 次执行 × 读超时 120s ＋ 退避 5/30/120s ≈ 635s」的函数，改重试阶梯或读超时**必须同步复核**。
- 对账的年龄判据**按任务创建时间，不按最近状态变化时间**。改用后者会让「抢占-失败-重投」循环持续刷新时间戳，任务永不满足条件、预占永久悬挂。
- 额度悬挂的恢复时间分两个口径，别混：**账本口径**（对账门槛 + 一轮扫描间隔 ≈ 21 分钟）与**用户感知口径**（额度表按日分行、按上海时区日切，跨零点即为新一行）。空间额度不日切，其恢复只靠会话过期清理（24 小时 + 每小时一轮 ≈ 25 小时）。

## 工作方式

- 同一文件的多处修改**必须串行** Edit；并行发多个 Edit 会互相覆盖（会静默丢改动，报成功但内容不见）。
