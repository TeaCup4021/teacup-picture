# AI 创作与任务

- 路由：`/ai/create`、`/ai/tasks`
- 里程碑：M2
- 参考图：[ai-create.png](../references/ai-create.png)、[ai-tasks.png](../references/ai-tasks.png)

## 创建任务

- AI 绘图与 AI 扩图使用同一页面的模式切换。
- 输入模型、比例、清晰度、参考图和提示词。
- 模型与参数由后端返回，禁止硬编码供应商或模型列表。
- 显示绘图和扩图两个独立日配额。

## 任务中心

- 展示提示词、参考图、参数、类型、状态、结果、创建时间和失败原因。
- 状态统一为 queued、running、succeeded、failed、cancelled。
- 失败任务展示是否返还额度和可用重试入口。

## 视觉

- 工作区沿用浅色侧边栏。
- AI 画布使用冷灰网格和白色结果框，不使用大面积紫色发光。
- 生成中的动效必须克制并支持减少动态效果。
