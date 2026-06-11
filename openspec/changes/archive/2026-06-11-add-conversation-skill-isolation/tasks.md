## 1. agent-core — AgentFactory 参数扩展

- [x] 1.1 `agent.ts`：`createAgent()` 新增可选参数 `enabledSkillIds?: number[]`，透传至 `loadGatewayExtendedTools()`
- [x] 1.2 `agent.ts`：调用 `loadGatewayExtendedTools()` 时在 `options` 中传入 `enabledSkillIds`

## 2. agent-core — loadGatewayExtendedTools 过滤逻辑

- [x] 2.1 `java-skills.ts`：`loadGatewayExtendedTools()` 的 `options` 新增 `enabledSkillIds?: number[]`
- [x] 2.2 `java-skills.ts`：在 `extensionSkills` 获取后、循环注册前，按 `enabledSkillIds` 过滤（`undefined` 或 `[]` 时不生效，全量加载）
- [x] 2.3 确保 Built-in Tool（compute、server_lookup、skill_generator、manage_tasks）不经过此过滤，始终注册

## 3. agent-core — /agent/run 接口适配

- [x] 3.1 `agent.controller.ts`：请求体新增 `enabledSkillIds?: number[]` 字段
- [x] 3.2 `agent.controller.ts`：将 `enabledSkillIds` 传递给 `AgentFactory.createAgent()`

## 4. 前端 — useChat 携带 enabledSkillIds

- [x] 4.1 `useConversations.ts`：新增 `getEnabledSkillIds(conversationId): number[]` 方法，从本地 conversations 列表中解析 `enabled_skills` JSON
- [x] 4.2 `useChat.ts`：`sendMessage()` 请求体中新增 `enabledSkillIds` 字段，从 conversations 获取当前对话的 Skill ID 列表

## 5. 前端 — ConversationSkillPanel 组件

- [x] 5.1 创建 `ConversationSkillPanel.vue`：基于 `t-dialog` 的 Skill 勾选面板
- [x] 5.2 面板打开时调 `GET /api/skills` 获取全量 Skill 列表，过滤 `enabled && type === "EXTENSION"`
- [x] 5.3 根据当前对话 `enabled_skills` 回显选中状态（空数组 `[]` = 全选）
- [x] 5.4 实现"全选/取消全选"快捷操作
- [x] 5.5 保存时调 `PUT /api/conversations/:id` 更新 `enabled_skills`
- [x] 5.6 取消或关闭面板时恢复原值，不发送请求

## 6. 前端 — ConversationSidebar 集成设置入口

- [x] 6.1 `ConversationSidebar.vue`：每个对话条目添加齿轮图标按钮（设置入口）
- [x] 6.2 点击设置按钮打开 `ConversationSkillPanel` 弹窗

## 7. 前端 — SkillHub Tab 重构

- [x] 7.1 `SkillHub.vue`：将两个 `<div class="section">` 直排改为 `t-tabs` 组件
- [x] 7.2 Tab 1：「Built-in Skills」— 展示 `BUILT_IN_SKILLS` 常量列表（展示型，无开关）
- [x] 7.3 Tab 2：「Extended Skills」— **默认激活**，展示扩展 Skill 列表
- [x] 7.4 搜索框和筛选控件仅放在 Extended Skills Tab 内

## 8. 前端 — SkillHub Extended Skills 开关

- [x] 8.1 `SkillHub.vue`：Extended Skills 列表每个条目添加 `t-switch` 启用/禁用开关
- [x] 8.2 点击开关调 `POST /api/skills/:id/toggle`（或等价 API），乐观更新本地 `skills` 数组
- [x] 8.3 开关失败时回滚状态并提示错误

## 9. 前端 — SkillHub 搜索与筛选

- [x] 9.1 `SkillHub.vue`：Extended Skills Tab 顶部添加 `t-input` 搜索框
- [x] 9.2 `SkillHub.vue`：添加筛选 1（全量 / 已激活 / 未激活）下拉框
- [x] 9.3 `SkillHub.vue`：添加筛选 2（全量 / 私人 / 公共）下拉框
- [x] 9.4 实现 `computed` 派生 `filteredSkills`：搜索 + 激活状态筛选 + 可见性筛选联动
- [x] 9.5 搜索和筛选缓存于组件内，关闭 SkillHub 后重置

## 10. 前端 — 二级管理页搜索与筛选

- [x] 10.1 `SkillManagementModal.vue`：顶部添加 `t-input` 搜索框，按 Skill 名称过滤
- [x] 10.2 `SkillManagementModal.vue`：添加筛选下拉框（全量 / 已激活 / 未激活）
- [x] 10.3 实现 `computed` 派生 `filteredManagementSkills`：搜索 + 激活状态联动

## 11. 验证

- [x] 11.1 场景 1（默认对话 `enabled_skills = []`）：Agent 可调用所有 Extension Skill
- [x] 11.2 场景 2（仅勾选 Skill A 和 B）：Agent 只能调用 A 和 B，不能调用 C
- [x] 11.3 场景 3（不传 `enabledSkillIds` 或旧客户端）：行为与改造前完全一致（全量加载）
- [x] 11.4 删除某个对话后，新建对话的 Skill 配置不受影响
- [x] 11.5 全链路：新建对话 → 配置 Skill → 发送消息 → Agent 仅使用选中 Skill → 响应正确
- [x] 11.6 SkillHub Tab 默认展示 Extended Skills，切换正常  
- [x] 11.7 Extended Skills 开关可正常启用/禁用 Skill
- [x] 11.8 搜索框按名称过滤、筛选下拉联动正常工作
- [x] 11.9 二级管理页搜索和筛选正常
