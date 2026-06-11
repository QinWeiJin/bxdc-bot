## ADDED Requirements

### Requirement: SkillHub Tab 切换

SkillHub 一级页面 SHALL 使用 Tab 组件切换展示 Built-in Skills 和 Extended Skills。

#### Scenario: Tab 默认激活 Extended

- **WHEN** 用户打开 SkillHub
- **THEN** "Extended Skills" Tab SHALL 处于激活状态
- **AND** Extended Skills 列表 SHALL 直接可见

#### Scenario: Tab 切换

- **WHEN** 用户点击"Built-in Skills" Tab
- **THEN** 页面 SHALL 展示 Built-in Skills 列表
- **WHEN** 用户点击"Extended Skills" Tab
- **THEN** 页面 SHALL 展示 Extended Skills 列表（含搜索、筛选、开关）

---

### Requirement: Extended Skills 启用/禁用开关

SkillHub 一级页面 Extended Skills Tab 中，每个 Skill 条目 SHALL 显示启用/禁用开关。

#### Scenario: 点击开关切换启用状态

- **WHEN** 用户在 Extended Skills 列表点击某个 Skill 的开关
- **THEN** 系统 SHALL 调用 `POST /api/skills/:id/toggle` 切换该 Skill 的 `enabled` 状态
- **AND** 开关视觉状态 SHALL 立即响应（乐观更新）

#### Scenario: Built-in Skills 无开关

- **WHEN** 用户在"Built-in Skills" Tab 中
- **THEN** Built-in Skill 条目 SHALL 不显示启用/禁用开关
- **AND** 仅显示"Built-in"标签

---

### Requirement: 搜索框

SkillHub 一级页面 Extended Skills Tab SHALL 提供搜索框，按 Skill 名称过滤。

#### Scenario: 输入搜索关键词

- **WHEN** 用户在搜索框中输入"天气"
- **THEN** Extended Skills 列表 SHALL 仅显示名称包含"天气"的 Skill
- **AND** 不区分大小写

#### Scenario: 清空搜索关键词

- **WHEN** 用户清空搜索框
- **THEN** Extended Skills 列表 SHALL 恢复显示全部 Skill（受当前筛选条件约束）

---

### Requirement: 一级页面筛选

SkillHub 一级页面 Extended Skills Tab SHALL 提供两类筛选下拉框。

#### Scenario: 激活状态筛选

- **WHEN** 筛选 1 选择"已激活"
- **THEN** 列表 SHALL 仅显示 `enabled = true` 的 Extended Skill
- **WHEN** 筛选 1 选择"未激活"
- **THEN** 列表 SHALL 仅显示 `enabled = false` 的 Extended Skill
- **WHEN** 筛选 1 选择"全量"
- **THEN** 列表 SHALL 显示所有 Extended Skill

#### Scenario: 可见性筛选

- **WHEN** 筛选 2 选择"私人"
- **THEN** 列表 SHALL 仅显示 `visibility = "PRIVATE"` 的 Skill
- **WHEN** 筛选 2 选择"公共"
- **THEN** 列表 SHALL 仅显示 `visibility = "PUBLIC"` 的 Skill
- **WHEN** 筛选 2 选择"全量"
- **THEN** 列表 SHALL 显示所有可见性的 Skill

#### Scenario: 搜索与筛选联动

- **WHEN** 用户同时设置搜索关键词和筛选条件
- **THEN** 列表 SHALL 同时应用搜索和筛选，展示交集结果

---

### Requirement: 二级管理页面搜索与筛选

SkillHub 二级管理页面（SkillManagementModal）SHALL 提供搜索框和激活状态筛选。

#### Scenario: 管理页搜索

- **WHEN** 用户在管理页搜索框输入 Skill 名称
- **THEN** 管理列表 SHALL 仅显示名称匹配的 Skill

#### Scenario: 管理页筛选

- **WHEN** 用户在管理页筛选选择"已激活"
- **THEN** 管理列表 SHALL 仅显示 `enabled = true` 的 Skill（含 Built-in）
- **WHEN** 筛选选择"全量"
- **THEN** 管理列表 SHALL 显示所有 Skill
