## ADDED Requirements

### Requirement: 对话切换守卫 — SSE 运行中阻断切换

系统 SHALL 在 SSE 流（Agent 回复生成）进行中时，阻止用户通过侧边栏切换到其他对话。

#### Scenario: SSE 运行中点击其他对话

- **WHEN** 用户正在对话 A 中等待 Agent 回复（SSE 流进行中，`isProcessing = true`）
- **AND** 用户点击侧边栏的对话 B
- **THEN** 系统 SHALL 不执行切换
- **AND** 系统 SHALL 显示 Toast 提示"当前对话正在进行中，请等待完成后切换"

#### Scenario: SSE 结束后可正常切换

- **WHEN** Agent 回复完成（`isProcessing = false`）
- **AND** 用户点击侧边栏的对话 B
- **THEN** 系统 SHALL 正常切换到对话 B，加载 B 的历史消息

#### Scenario: SSE 异常结束后恢复切换

- **WHEN** SSE 流因网络错误 / 服务端错误 / 用户取消而结束
- **AND** `isProcessing` 被重置为 `false`
- **AND** 用户点击侧边栏的对话 B
- **THEN** 系统 SHALL 正常切换到对话 B

#### Scenario: SSE 运行中点击新建对话

- **WHEN** 用户正在对话 A 中等待 Agent 回复（SSE 流进行中，`isProcessing = true`）
- **AND** 用户点击"新建对话"按钮
- **THEN** 系统 SHALL 不执行创建
- **AND** 系统 SHALL 显示 Toast 提示"当前对话正在进行中，请等待完成后再新建"

---

### Requirement: isProcessing 状态标记

`useConversations` 模块级单例 SHALL 暴露 `isProcessing: Ref<boolean>`，标记当前是否有 SSE 流正在运行。

#### Scenario: sendMessage 开始时设 true

- **WHEN** 用户发送消息（`sendMessage` 被调用，通过 `isThinking` guard）
- **THEN** `useConversations().isProcessing.value` SHALL 被设为 `true`

#### Scenario: SSE 正常完成时设 false

- **WHEN** SSE 流读到 `done = true`（stream complete）
- **THEN** `useConversations().isProcessing.value` SHALL 被重置为 `false`

#### Scenario: SSE 请求失败时设 false

- **WHEN** `fetch()` 返回 `!response.ok`（HTTP 错误），`isThinking` 被设为 `false`
- **THEN** `useConversations().isProcessing.value` SHALL 被重置为 `false`

#### Scenario: SSE reader 为空时设 false

- **WHEN** `response.body?.getReader()` 返回 `null`
- **THEN** `useConversations().isProcessing.value` SHALL 被重置为 `false`

#### Scenario: catch 通用异常时设 false

- **WHEN** `sendMessage` 的 try/catch 捕获到非 AbortError 异常
- **THEN** `useConversations().isProcessing.value` SHALL 被重置为 `false`

#### Scenario: 空闲时可新建对话

- **WHEN** 没有进行中的 SSE 流（`isProcessing = false`）
- **AND** 用户点击"新建对话"按钮
- **THEN** 系统 SHALL 正常创建新对话并切换
