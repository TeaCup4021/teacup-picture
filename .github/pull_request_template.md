## 变更说明

<!-- 说明目标页面、功能、所属里程碑和用户可见变化。 -->

## 接口与权限

- 使用的接口：
- 登录与空间权限：
- 新增或变更的后端假设：无 / 请说明

## UI 验收

- [ ] 已阅读 `docs/ui-design/README.md`、对应页面规范和参考产品图
- [ ] 使用统一 Design Token 和 Ant Design 主题，没有在业务组件中另建颜色体系
- [ ] 没有用假数据伪装当前后端尚未实现的能力
- [ ] 已覆盖 loading、empty、error、permission denied 和成功反馈
- [ ] 已检查 1440×900、1024×768 和 390×844 布局
- [ ] 动效支持 `prefers-reduced-motion`
- [ ] 已附实现截图；已启用基线的页面同时检查 Playwright 截图差异
- [ ] 设计变化已更新页面规范、产品图或 `docs/ui-design/CHANGELOG.md`

## 质量检查

- [ ] `pnpm lint`
- [ ] `pnpm typecheck`
- [ ] `pnpm test`
- [ ] `pnpm build`
- [ ] 核心流程已运行 `pnpm test:e2e` 或记录未运行原因

## 截图与风险

<!-- 附桌面/移动截图，说明剩余风险、测试缺口和暂缓事项。 -->
