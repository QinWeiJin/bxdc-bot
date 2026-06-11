## ADDED Requirements

### Requirement: 对话 Skill 配置入口

每个对话条目 SHALL 提供一个设置入口，点击后弹出 Skill 配置面板。

#### Scenario: 对话条目显示设置按钮

- **WHEN** 用户在对话列表中悬停或聚焦某个对话条目
- **THEN** 该条目上 SHALL 显示一个齿轮图标按钮（设置入口）
- **AND** 点击设置按钮后弹出 Skill 配置面板

### Requirement: Skill 勾选面板

Skill 配置面板 SHALL 以复选框列表形式展示所有可选 Extension Skill。

#### Scenario: 面板加载 Skill 列表

- **WHEN** 面板打开
- **THEN** 系统 SHALL 从 `GET /api/skills` 加载所有 Skill
- **AND** 仅展示 `enabled = true` 且 `type = "EXTENSION"` 的 Skill
- **AND** 每个 Skill 条目显示名称和类型标签

#### Scenario: 新建对话默认全选

- **WHEN** 对话的 `enabled_skills` 为空数组 `[]`
- **THEN** 面板中所有 Skill 复选框 SHALL 为选中状态
- **AND** 保存时 SHALL 将所有选中的 Skill ID 保存为 `enabled_skills`

#### Scenario: 已有配置的对话回显

- **WHEN** 对话的 `enabled_skills` 为 `[1, 3]`
- **THEN** 面板中 Skill ID 为 1 和 3 的复选框 SHALL 为选中状态
- **AND** 其他 Skill 复选框为未选中状态

#### Scenario: 全选/取消全选

- **WHEN** 用户点击"全选"按钮
- **THEN** 所有 Skill 复选框 SHALL 变为选中状态
- **WHEN** 用户点击"取消全选"按钮
- **THEN** 所有 Skill 复选框 SHALL 变为未选中状态

### Requirement: 保存 Skill 配置

用户勾选 Skill 后点击保存，系统 SHALL 调 `PUT /api/conversations/:id` 持久化。

#### Scenario: 保存生效

- **WHEN** 用户在面板中修改 Skill 勾选并点击"保存"
- **THEN** 系统 SHALL 调用 `PUT /api/conversations/:id` 更新 `enabled_skills`
- **AND** 面板关闭
- **AND** 下次该对话发送消息时，Agent 仅加载选中 Skill

#### Scenario: 取消不保存

- **WHEN** 用户在面板中修改 Skill 勾选后点击"取消"或关闭面板
- **THEN** 系统 SHALL 不调用 `PUT /api/conversations/:id`
- **AND** 对话的 `enabled_skills` 保持原值

### Requirement: 发送消息携带 enabledSkillIds

`useChat.sendMessage()` SHALL 将当前对话的 `enabled_skills` 解析为 `number[]` 放入请求体。

#### Scenario: 请求体包含 enabledSkillIds

- **WHEN** 用户在当前对话发送消息
- **THEN** 请求体 SHALL 包含 `enabledSkillIds` 字段，值为当前对话 `enabled_skills` 解析后的 `number[]`
- **AND** 若 `enabled_skills` 为空数组，`enabledSkillIds` SHALL 为 `[]`（agent-core 侧视为全量）
