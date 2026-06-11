## ADDED Requirements

### Requirement: 按对话过滤 Extension Skill

agent-core `loadGatewayExtendedTools()` SHALL 支持按 `enabledSkillIds` 参数过滤 Extension Skill。**Built-in Skill（compute、server_lookup、skill_generator、manage_tasks）不参与过滤，始终全员可用。**

#### Scenario: enabledSkillIds 有值时仅加载匹配 Skill

- **WHEN** `enabledSkillIds = [1, 3]` 传入 `loadGatewayExtendedTools()`
- **THEN** agent-core SHALL 只注册 Skill ID 为 1 和 3 的 Extension Skill 为 LangChain Tool
- **AND** Skill ID 为 2、4 的其他启用 Extension Skill 不被注册

#### Scenario: enabledSkillIds 为空数组时全量加载

- **WHEN** `enabledSkillIds = []` 传入 `loadGatewayExtendedTools()`
- **THEN** agent-core SHALL 注册所有 Gateway 返回的启用 Extension Skill（与未传 `enabledSkillIds` 行为一致）

#### Scenario: enabledSkillIds 未传时全量加载

- **WHEN** `loadGatewayExtendedTools()` 调用未传入 `enabledSkillIds` 参数
- **THEN** agent-core SHALL 注册所有 Gateway 返回的启用 Extension Skill（完全兼容旧行为）

#### Scenario: Built-in Skill 不受过滤影响

- **WHEN** `enabledSkillIds = [1]` 传入 `AgentFactory.createAgent()`
- **THEN** compute、server_lookup、skill_generator、manage_tasks 等 Built-in Tool SHALL 仍然注册到 Agent

### Requirement: /agent/run 接口接收 enabledSkillIds

agent-core `POST /agent/run` 请求体 SHALL 接受可选字段 `enabledSkillIds: number[]`。

#### Scenario: 前端传入 enabledSkillIds

- **WHEN** 前端请求体包含 `{ "enabledSkillIds": [1, 3], ... }`
- **THEN** agent.controller SHALL 将 `enabledSkillIds` 传递给 `AgentFactory.createAgent()`

#### Scenario: enabledSkillIds 缺失时兼容

- **WHEN** 前端请求体不包含 `enabledSkillIds` 字段
- **THEN** agent.controller SHALL 以 `undefined` 调用 `AgentFactory.createAgent()`，行为与改造前一致

### Requirement: enabledSkillIds 响应式透传

`AgentFactory.createAgent()` SHALL 将 `enabledSkillIds` 参数完整透传至 `loadGatewayExtendedTools()`。

#### Scenario: 参数逐层透传不丢失

- **WHEN** `AgentFactory.createAgent(..., [1, 3])` 被调用
- **THEN** `loadGatewayExtendedTools()` 的 `options.enabledSkillIds` SHALL 为 `[1, 3]`
