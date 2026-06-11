## Why

Phase 1 已完成对话持久化的后端基础设施（`conversations` 表 + CRUD API），但前端仍处于"单对话"模式——用户消息保存在内存中、关闭页面即丢失、无对话列表、无切换能力。用户需要类似 DeepSeek 的左侧对话列表来管理多个对话上下文，支持新建、切换、重命名，并能向上滚动查看完整的历史记录（含工具调用日志）。

## What Changes

- **新增 `useConversations` composable**：前端对话管理核心逻辑——对话列表加载、创建、切换、重命名、删除、历史消息分页加载、消息落库。
- **新增 `ConversationSidebar.vue`**：左侧对话列表组件，含新建按钮、对话名称显示/编辑、激活态高亮。
- **Layout 改造**：左侧嵌入 `ConversationSidebar`，支持折叠/展开。
- **ChatView 初始化适配**：`onMounted` 时加载对话列表、若用户无对话则自动创建默认对话并选中。
- **消息发送后自动落库**：`useChat.ts` 在 SSE 流结束后异步调用 Phase 1 的消息保存 API。
- **对话切换 + 历史加载**：切换对话时清空消息 → 加载历史 → 渲染；向上滚动触发 cursor 分页。
- **对话命名**：新建对话名称为空，首次发送消息后自动取前 18 字；点击名称可编辑，失焦/回车保存。

## Capabilities

### New Capabilities
- `conversation-sidebar`: 左侧对话列表 UI，支持新建、切换、重命名、排序（最近更新优先）、激活态高亮。
- `conversation-switching`: 对话切换能力——清空当前消息、中止进行中的 SSE 流、加载目标对话的历史消息、渲染。
- `conversation-naming`: 对话命名能力——默认取首条用户消息前 18 字（清除特殊字符/换行）；inline 编辑名称（失焦/回车保存）。
- `conversation-message-history`: 历史消息加载能力——切换对话加载最近 50 条；向上滚动触发 cursor 分页；区分"加载中"和"没有更多"状态。

### Modified Capabilities
- `chat-ui`: ChatView 初始化流程变更——不再从内存加载历史，改为通过 `useConversations` 初始化对话列表、获取默认对话、加载历史消息。
- `agent-client`: `useChat.ts` 消息发送流程变更——SSE 流结束后异步落库到 `conversation_messages`；发送消息携带 `conversationId` 上下文。

## Impact

- **前端新增文件**：`useConversations.ts`、`ConversationSidebar.vue`、`conversation.ts`（类型）共 3 个文件。
- **前端修改文件**：`Layout.vue`（嵌入侧边栏）、`ChatView.vue`（初始化适配）、`useChat.ts`（落库钩子 + `conversationId` 上下文）、`api.ts`（新增 conversation API 函数）。
- **后端**：无改动，复用 Phase 1 的 `/api/conversations` 接口。
- **agent-core**：无改动。
- **依赖**：Phase 1 的 Conversation CRUD API 必须已部署。
