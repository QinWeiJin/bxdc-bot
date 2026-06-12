## Why

当前对话 A 的 SSE 流还在运行时（Agent 正在生成回复），用户点击侧边栏切换到对话 B，`switchConversation` 会拉取 B 的历史消息并替换 `messages.value`。但对话 A 的 `reader.read()` 循环尚未退出，继续往 `messages.value` 上追加内容，导致对话 A 的 SSE 事件污染了对话 B 的消息列表——表现为界面显示混乱、消息混搭。

根本原因是 `useChat` 的 `messages` 和 `isThinking` 是模块级全局共享的 ref，而 `useConversations` 的 `switchConversation` 对正在运行的流毫不知情，直接覆盖了底层状态。

## What Changes

- `useConversations` 单例新增 `isProcessing: Ref<boolean>`，标记当前是否有 SSE 流正在运行
- `useChat.sendMessage()` 开始时 `isProcessing = true`，结束时（正常完成 / AbortError / 异常）`isProcessing = false`
- `ConversationSidebar.handleSelect()` 在切换前检查 `isProcessing`，若为 `true` 则阻断并弹出 Toast 提示

不修改后端的任何代码。这是一个纯前端防御性约束。

## Capabilities

### New Capabilities

- `conversation-switch-guard`: 对话切换守卫——SSE 流运行中禁止切换到其他对话

## Impact

- **useConversations.ts**：新增 `isProcessing` ref 并暴露
- **useChat.ts**：`sendMessage()` 开始/结束时设置 `isProcessing`
- **ConversationSidebar.vue**：`handleSelect()` 首行检查 `isProcessing`，阻断时显示 TDesign Message 提示
- **不修改**：后端接口、Gateway、agent-core、消息持久化逻辑、`useConversations.switchConversation` 内部逻辑
