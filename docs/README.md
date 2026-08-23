# 项目文档导航

本文是仓库文档入口，用于避免产品目标、当前实现、接口契约和视觉稿互相覆盖。

## 事实来源

发生冲突时按以下顺序判断：

1. 当前代码、Flyway 迁移和已发布 OpenAPI：已经实现的行为。
2. `product-prd.md`：产品范围、业务规则和里程碑。
3. `backend-gap-analysis.md`：后端已知缺口和实施顺序。
4. `ui-design/README.md`：视觉、布局和交互；不能单独增加业务能力。

## 当前必读

| 文档 | 用途 | 状态 |
| --- | --- | --- |
| `product-prd.md` | 产品目标、业务规则、M0～M7 路线图 | 持续维护 |
| `openapi/m1.yaml` | M1 机器可读 API 契约 | 已实现 |
| `openapi/m2.yaml` | M2 AI 模型、任务与配额契约 | 已实现 |
| `openapi/m3.yaml` | M3 EditorState v3、草稿、图片保存与版本契约 | 已实现 |
| `openapi/m5.yaml` | M5 协作会话与 checkpoint 契约 | M5-R 基础闭环 |
| `m1-api-contract.md` | M1 契约的关键决策与边界 | 已实现 |
| `m3-api-contract.md` | M3 契约的关键决策与边界 | 已实现 |
| `m5-api-contract.md` | M5 Yjs 协作房间、更新日志和 checkpoint 契约 | M5-R 基础闭环 |
| `backend-gap-analysis.md` | 代码事实、阻断项和后续后端工作 | 持续维护 |
| `picture-storage.md` | 单一 MinIO 图片存储架构、前后端边界和迁移规则 | 已实现 |
| `ui-design/README.md` | UI v1.1 入口、页面状态与产品图索引 | 持续维护 |

## 规划文档的使用方式

`ui-design/pages/` 中 M4～M7 页面用于保存已经讨论过的设计方向，不表示路由、接口或数据模型已经存在。只有同时满足以下条件才可以进入实现：

- PRD 对应里程碑已启动。
- 后端能力和 OpenAPI 契约已经确认。
- 页面规范中的状态从“规划”调整为“可开发”。

不再维护独立的旧接口手册、扩容畅想或被新版 UI 取代的历史提案。Git 历史可以用于追溯，但这些材料不能继续作为开发输入。

`backend/docs/缓存设计与问题处理.md` 是唯一保留的遗留实现说明，只适用于旧 `/api/**` 缓存代码，不属于 v1 接口或前端事实来源。

## 文档维护规则

- 接口变更同时更新 OpenAPI、契约决策和缺口分析。
- 产品范围或里程碑变化更新 PRD。
- UI 变化更新对应页面规范和 `ui-design/CHANGELOG.md`。
- 当前实现与目标状态必须分段书写，禁止用“第一版”“后续”等模糊词掩盖实际里程碑。
- 文档中的示例不得包含真实密码、Cookie、密钥或敏感 URL。
