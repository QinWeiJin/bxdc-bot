## 1. 类型定义与 API 层

- [x] 1.1 创建 `frontend/src/types/conversation.ts`，定义 `Conversation`、`ConversationMessage`、`ConversationListResponse` 等 TypeScript 接口
- [x] 1.2 在 `api.ts` 新增 conversation API 函数：`fetchConversations`、`createConversation`、`fetchConversation`、`updateConversation`、`deleteConversation`、`saveMessages`

## 2. useConversations Composable

- [x] 2.1 创建 `frontend/src/composables/useConversations.ts`，管理对话状态（`conversations: Ref<Conversation[]>`、`currentConversationId: Ref<string | null>`）
- [x] 2.2 实现 `init(userId)`：加载对话列表，若无对话则调 `POST /api/conversations` 创建默认对话并选中
- [x] 2.3 实现 `switchConversation(conversationId)`：清空消息、abort 当前 SSE 流、设置 loading、加载目标对话消息（带 cursor 分页）、渲染
- [x] 2.4 实现 `newConversation()`：调 `POST /api/conversations` 创建 name 为空的新对话，插入列表顶部并自动选中切换
- [x] 2.5 实现 `renameConversation(conversationId, name)`：调 `PUT /api/conversations/:id` 更新名称，更新本地列表
- [x] 2.6 实现 `deleteConversation(conversationId)`：调 `DELETE /api/conversations/:id` 删除对话，从列表移除，切换回默认对话
- [x] 2.7 实现 `loadMoreMessages()`：传入 cursor 调分页接口，追加到消息列表头部

## 3. ConversationSidebar 组件

- [x] 3.1 创建 `frontend/src/components/ConversationSidebar.vue`：左侧对话列表，含"新建对话"按钮、对话条目列表、空状态提示
- [x] 3.2 对话条目显示名称（空则显示"新对话"）+ 创建时间（如"3 分钟前"），激活态高亮
- [x] 3.3 实现对话名称 inline 编辑：点击名称 → `<input>` 全选 → 回车/失焦保存（空名称回退原值）
- [x] 3.4 实现侧边栏折叠/展开：折叠时宽度 48px（仅显示展开图标），展开时 260px

## 4. Layout 改造

- [x] 4.1 在 `Layout.vue` 的布局内增加侧边栏区域嵌入 `<ConversationSidebar />`，使用 `layout-body` flex 行布局
- [x] 4.2 确保侧边栏折叠/展开不影响聊天区域布局

## 5. ChatView 适配

- [x] 5.1 `ChatView.vue` 的 `onMounted` 钩子改为调用 `useConversations.init(userId)` 初始化对话列表并加载默认对话的历史消息
- [x] 5.2 `ChatView.vue` 通过 `watch(conversations.historyMessages)` 同步历史消息到 `useChat.messages`

## 6. useChat 落库适配

- [x] 6.1 `useChat.sendMessage()` 在 SSE 流结束后（`done === true`），通过 `saveMessageCallback` 异步调用 `persistMessages()`
- [x] 6.2 新建对话首条消息发送后自动命名：`persistMessages()` 检测未命名的对话，提取首条 user 消息前 18 字自动命名
- [x] 6.3 落库方法用 try/catch 包裹，失败仅 `console.error`，不阻塞用户交互

## 7. 消息历史渲染适配

- [x] 7.1 `ChatView.vue` 通过 `convertHistoryMessages()` 将后端消息转换为前端 Message 格式，支持 user/assistant/tool 角色渲染
- [x] 7.2 历史消息中 `skill_calls` JSON 解析为 `ToolInvocation[]`，tool 消息结果附加到对应 assistant 消息的 tool 调用上

## 8. 验证

- [x] 8.1 首次进入页面自动创建默认对话并选中 — `init()` 检测空列表时调用 `apiCreateConversation` 并自动选中
- [x] 8.2 新建对话后侧边栏列表立即更新，输入区可用 — `newConversation()` 插入列表顶部并调 `switchConversation`
- [x] 8.3 点击对话切换，历史消息正确加载，消息正序渲染 — `switchConversation` 通过 `historyMessages` ref 触发 ChatView 重渲染
- [x] 8.4 向上滚动触发分页加载更早消息 — `loadMoreMessages` 使用 cursor 分页，`hasMoreHistory` 控制
- [x] 8.5 发送首条消息后对话名称自动取前 18 字 — `persistMessages` 检测 `convNamedMap` 未标记时调用 `autoNameFromMessage`
- [x] 8.6 点击对话名称可编辑，失焦/回车保存 — `ConversationSidebar.vue` 的 `startEdit/commitEdit/cancelEdit` 
- [x] 8.7 发送消息后 SSE 结束，消息自动落库 — `saveMessageCallback` 在 `useChat.ts` stream done 块中调用
- [x] 8.8 对话 A 正在流式响应时切换到对话 B，B 不受旧流干扰 — `switchConversation` 中 `activeAbortController.abort()`

---

## 9. Bug 修复记录（实现过程中补充）

### 9.1 provide/inject 报错 → 模块级单例

**现象**：`Error: useConversations must be used within a provider that calls provideConversations()`

**原因**：Vue `provide/inject` 在 `ChatView.vue` 的 `setup()` 中 `provideConversations()` → 子组件 `Layout.vue` → `ConversationSidebar.vue` 的 `useConversations()` 调用时 inject 失败。Vite HMR 模块缓存也可能导致依赖顺序错乱。

**修复**：`useConversations.ts` 改为模块级单例模式（`let _instance`），不依赖 Vue 注入树。

### 9.2 空对话切换页面不刷新

**现象**：切换到空对话时，聊天区域残留上一个对话的内容。

**原因**：`ChatView.vue` 的 `watch(historyMessages)` 遇到空数组直接 `return`，未清空 `messages.value`。

**修复**：空对话时先 `messages.value = []` 再调 `fetchGreeting()`。

### 9.3 消息重复落库（1,2,3,4 → 1,2,3,4,1,2,3,4,5）

**现象**：有历史消息的对话再次发送消息后，历史消息被全部重新保存，导致刷新后重复。

**原因**：`useChat.sendMessage()` SSE 完成后把整个 `messages.value` 传给 `persistMessages`。

**修复**：在 `sendMessage()` 开始记 `messageCountBeforeSend`，SSE 结束时只保存 `.slice(messageCountBeforeSend)` 的新消息。

### 9.4 删除对话报错 `event.stopPropagation is not a function`

**现象**：点击删除按钮报 `TypeError: event.stopPropagation is not a function`。

**原因**：`t-popconfirm` 的 `@confirm` 传给 handler 的是 `{ e: MouseEvent }` 包装对象，不是原生 Event。

**修复**：移除 `event` 参数和 `stopPropagation()` 调用。

### 9.5 新增对话后不自动切换页面

**现象**：点击新建对话后页面没有切换到新对话。

**原因**：`newConversation()` 只设置了 `currentConversationId`，没有调用 `switchConversation` → `historyMessages` 不变 → ChatView watch 不触发。

**修复**：`newConversation()` 内部调用 `switchConversation(conv.conversation_id, userId)`。

### 9.6 历史消息排序错乱（DB 层）

**现象**：同毫秒插入的多条消息，查询返回顺序随机，导致 LLM 上下文乱序。

**原因**：`ConversationMessageMapper` 只按 `created_at DESC` 排序，同毫秒数据 MySQL 返回任意顺序。

**修复**：`ConversationMessageMapper.java` 加 `.orderByDesc(ConversationMessage::getId)` 二级排序，AUTO_INCREMENT `id` 保证写入先后。
