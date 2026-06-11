## Why

当前所有对话共享同一套 Gateway Extension Skill 集合——`loadGatewayExtendedTools()` 调用 `GET /api/skills` 不加任何过滤，全量加载。用户无法为不同对话（项目）配置不同的 Skill 集合。这意味着运维对话和开发对话看到的工具完全一样，无关 Skill 会干扰 Agent 推理，增加不必要的 token 消耗和确认中断。

同时，SkillHub 页面缺乏搜索、筛选和 Tab 分类，随着 Skill 数量增长，查找和启用/禁用 Skill 效率低下。

需要实现对话级别的 Skill 隔离，并优化 SkillHub 全局管理页面的交互体验。

## What Changes

### Skill 隔离

- agent-core `AgentFactory.createAgent()` 新增 `enabledSkillIds` 可选参数，透传至 `loadGatewayExtendedTools()`
- `loadGatewayExtendedTools()` 支持按 `enabledSkillIds` 过滤：有值时仅加载匹配 ID 的 Extension Skill，`undefined`/空时完全兼容旧行为（全量加载）
- Built-in Skill（compute、server_lookup、skill_generator、manage_tasks）**不参与过滤**，全员可用
- agent-core `/agent/run` 接口新增 `enabledSkillIds` 字段
- 前端 `useChat.sendMessage()` 发送消息时携带当前对话的 `enabledSkillIds`
- 前端新增对话 Skill 配置入口：对话列表中每个对话的"设置"按钮 → 弹出 Skill 勾选面板
- 新建对话时默认选中全部 Extension Skill（与当前行为一致）
- 修改 `enabled_skills` 后调 `PUT /api/conversations/:id` 持久化

### SkillHub UI 增强

- SkillHub 一级页面：Built-in Skills 和 Extended Skills 采用 **Tab 切换**，默认展示 Extended Skills Tab
- SkillHub 一级页面：Extended Skills 列表每个 Skill 条目显示 **启用/禁用开关**，用户可直接激活/停用
- SkillHub 一级页面：新增 **搜索框**，按 Skill 名称搜索
- SkillHub 一级页面：新增 **筛选 1**（全量 / 已激活 / 未激活）+ **筛选 2**（私人 / 公共）
- SkillHub 二级页面（管理页）：新增 **搜索框** + **筛选**（全量 / 已激活 / 未激活）

## Capabilities

### New Capabilities

- `agent-skill-filtering`: agent-core 按对话过滤 Extension Skill，`enabledSkillIds` 为空时兼容旧行为
- `conversation-skill-panel`: 前端对话 Skill 配置面板，勾选/取消 Skill 并持久化
- `skillhub-ui-enhancements`: SkillHub 页面 Tab 切换、开关、搜索、筛选优化

### Modified Capabilities

<!-- No existing spec requirements change — this is purely additive -->

## Impact

- **agent-core**：修改 `agent.ts`（`AgentFactory.createAgent`）+ `java-skills.ts`（`loadGatewayExtendedTools`）
- **agent.controller.ts**：请求体新增 `enabledSkillIds` 字段
- **前端 useChat.ts**：`sendMessage()` 携带 `enabledSkillIds`
- **前端 useConversations.ts**：新增 `getEnabledSkillIds()`
- **前端 ConversationSidebar.vue**：每个对话条目新增"设置"入口
- **前端新增 ConversationSkillPanel.vue**：Skill 勾选面板
- **前端 SkillHub.vue**：重构为 Tab 布局 + 搜索框 + 筛选 + 开关
- **前端 SkillManagementModal.vue**：新增搜索框 + 筛选
- **不修改**：ReAct 循环逻辑、tool description draft、Gateway 端 `/api/skills` 接口
