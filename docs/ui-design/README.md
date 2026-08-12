# 茶杯图库 UI 设计基线

> 当前版本：UI v1.1  
> 更新日期：2026-08-12  
> 状态：已确认的开发目标  
> 视觉方向：飞书式协作效率 + 图片内容发现，不复制任何品牌界面

本目录是前端视觉、布局和交互的设计事实来源。所有新页面、页面重构和公共组件修改都必须先阅读本文件、对应页面规范和参考产品图。

## 事实来源优先级

发生冲突时按以下顺序处理：

1. `../product-prd.md`：产品能力、角色、业务规则和里程碑。
2. 后端实现、数据库迁移和 OpenAPI：当前真实可用能力。
3. `pages/*.md`：页面结构、交互、状态和响应式规则。
4. `foundations.md`、`components.md`、`motion.md`：跨页面设计规范。
5. `references/*.png`：目标视觉效果，不单独定义业务能力。

产品图出现但当前里程碑或后端尚未支持的能力，只能作为后续阶段设计，禁止使用假数据伪装为已实现功能。

## 当前视觉结论

- 页面画布使用冰蓝灰，主要内容面使用白色。
- 主交互色为克制的高识别蓝，紫色不作为全站主色。
- 选中态使用浅蓝背景和深蓝文字，不依赖高亮光晕。
- 侧边栏优先使用浅蓝灰；图片编辑器保留深色工作区。
- 渐变仅用于少量品牌横幅或 AI 场景，不用于普通按钮、表格和大多数卡片。
- 图片是公开图库、详情页和资产列表的视觉主角。
- 圆角、阴影和动效保持克制，满足长时间工作场景。

## 页面索引

| 路由/模块                           | 规范                                               | 里程碑 | 产品图                                                                             |
| ----------------------------------- | -------------------------------------------------- | ------ | ---------------------------------------------------------------------------------- |
| `/`                                 | [公开图库](pages/public-gallery.md)                | M1     | [public-gallery.png](references/public-gallery.png)                                |
| `/pictures/[pictureId]`             | [图片详情](pages/picture-detail.md)                | M1/M6  | [picture-detail.png](references/picture-detail.png)                                |
| `/spaces/personal`                  | [个人空间](pages/personal-space.md)                | M1/M7  | [personal-space.png](references/personal-space.png)                                |
| `/upload`                           | [上传图片](pages/upload.md)                        | M1     | [upload.png](references/upload.png)                                                |
| `/ai/create`、`/ai/tasks`           | [AI 创作与任务](pages/ai.md)                       | M2     | [ai-create.png](references/ai-create.png)、[ai-tasks.png](references/ai-tasks.png) |
| `/editor/[pictureId]`               | [图片编辑器](pages/editor.md)                      | M3/M5  | [editor.png](references/editor.png)                                                |
| `/spaces/team`、`/spaces/[spaceId]` | [团队空间](pages/team-space.md)                    | M4/M5  | [team-space.png](references/team-space.png)                                        |
| `/admin/reviews`                    | [管理员审核](pages/admin-reviews.md)               | M1     | [admin-reviews.png](references/admin-reviews.png)                                  |
| `/login`、`/register`               | [账号与会话](pages/account.md)                     | M1     | 产品图待补                                                                         |
| `/notifications`、分享与评论        | [通知、分享与评论](pages/notifications-sharing.md) | M4/M6  | 产品图待补                                                                         |
| `/spaces/[spaceId]/analytics`       | [空间分析](pages/analytics.md)                     | M7     | 产品图待补                                                                         |

## 开发前检查

1. 确认目标页面所属里程碑和后端能力。
2. 阅读对应页面规范、产品图和公共组件规范。
3. 使用 `frontend/src/shared/styles/design-tokens.css` 和 Ant Design 主题，不在业务组件中自行创造颜色体系。
4. 列出 normal、loading、empty、error、permission denied 和操作反馈状态。
5. 明确桌面、平板和手机布局，不按产品图像素硬编码页面。
6. 实现后按 [视觉回归规范](visual-regression.md) 截图验收。

## 设计变更流程

视觉或交互调整必须同时更新：

1. 对应页面规范或公共设计规范。
2. 受影响的产品图或明确记录产品图暂未更新。
3. `CHANGELOG.md`。
4. 已建立的视觉回归截图。

不得直接覆盖旧产品图且不记录变化。重大方向变更创建新的 UI 版本。

## 历史材料

`../ui-redesign-proposal.md` 是 UI v1.0 探索提案，其中紫蓝渐变、Tailwind、Lucide 和 Framer Motion 等建议未被当前基线采纳。除非另有架构决策，不得根据该历史提案引入新 UI 体系或依赖。
