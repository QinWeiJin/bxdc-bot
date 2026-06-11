## Context

Phase 1 已完成 `conversations` 和 `conversation_messages` 表 + REST API（`GET/POST/PUT/DELETE /api/conversations`），skill-gateway 已部署并验证通过。当前前端状态：
- 全局 `messages: Ref<Message[]>` 在 `useChat.ts` 中维护，关闭页面丢失
- `Layout.vue` 为单列布局（顶部导航 + 聊天区域 + 输入框）
- `ChatView.vue` 的 `onMounted` 仅调用 `fetchGreeting()`
- 无对话列表、无切换、无持久化

### 约束
- **技术栈**：Vue 3 + TypeScript + Vite + TDesign Vue Next
- **UI 参考**：DeepSeek 左侧对话列表 + 右侧聊天区域
- **编码规范**：尽量不新增第三方包；新增逻辑通过新 composable 封装，不侵入 `useChat.ts` 核心 SSE 解析
- **API 依赖**：Phase 1 `/api/conversations` 已就绪，通过 `X-User-Id` header 鉴权

## Goals / Non-Goals

**Goals:**
- 左侧对话列表（新建、切换、重命名、删除）
- 对话切换时加载历史消息 + 向上滚动分页
- 首次进入自动创建默认对话
- 消息发送后自动落库
- 对话命名：默认首条消息前 18 字 + 可编辑

**Non-Goals:**
- 不涉及对话 Skill 配置 UI（Phase 3）
- 不修改 agent-core 任何代码
- 不修改 skill-gateway 任何代码（纯前端变更）
- 不改变现有 SSE 流解析逻辑
- 不改变现有确认弹窗、思考模式、LLM 日志查看等功能

## Decisions

### Decision 1：新增 `useConversations` composable 而非修改 `useChat`

**选择**：创建独立的 `useConversations.ts` composable，通过 `provide/inject` 注入到 `ChatView` 和 `ConversationSidebar`。`useChat.ts` 仅新增 `onMessageSaved` 回调钩子。

**理由**：
- `useChat.ts` 有 ~500 行核心 SSE 解析逻辑，直接改造风险高
- 关注点分离：`useConversations` 管理对话生命周期和持久化，`useChat` 管理当前对话的消息流
- 便于单独测试和后续维护

**替代方案**：合并到 `useChat.ts` → 文件膨胀至 700+ 行，增加回归风险。

### Decision 2：消息落库在 SSE 流结束后异步执行

**选择**：`useChat.sendMessage()` 在 `reader.read()` 循环结束后（`done === true`），调用 `POST /api/conversations/:id/messages` 批量保存本轮消息。落库使用 `try/catch` + `console.error`，不阻塞用户。

**理由**：
- 落库失败不应影响当前对话体验（用户已看到消息内容）
- 异步批量提交减少网络开销
- 前端天然拥有完整消息数组（user + assistant + tool），无需聚合

**替代方案**：在 service 层边流式输出边落库 → 需要额外的事件监听和去重逻辑，复杂度过高。

### Decision 3：对话切换时 abort 当前 fetch

**选择**：`useConversations.switchConversation()` 中维护 `AbortController` 引用，切换时调用 `controller.abort()`。

**理由**：
- SSE 流通过 `fetch` + `reader` 实现，`AbortController` 是标准的中止方式
- 防止旧对话的流式数据继续写入新对话的 `messages` 数组

**替代方案**：通过 sessionId 标记忽略旧事件 → 仍需处理网络资源浪费，不如直接 abort。

### Decision 4：Layout 左侧嵌入 ConversationSidebar，通过 t-aside 实现

**选择**：在 `Layout.vue` 的 `<t-layout>` 内增加 `<t-aside width="260px">` 嵌入 `<ConversationSidebar>`，通过 `ref` 控制展开/折叠。

**理由**：
- TDesign `t-layout` 原生支持 `t-aside`，布局自适应
- 侧边栏宽度 260px 与 DeepSeek 一致，视觉体验接近
- 折叠时 `width="0"` + CSS transition 实现动画

**替代方案**：浮动面板 / 抽屉（Drawer）→ 交互不够直观，不如侧边栏自然。

### Decision 5：默认对话在 ChatView onMounted 时自动创建

**选择**：`ChatView.vue` 的 `onMounted` 调用 `useConversations.init(userId)`，该方法先查对话列表，若为空则调 `POST /api/conversations` 创建默认对话（name="默认对话"）。

**理由**：
- 用户首次进入无感知创建默认对话，零操作
- Phase 1 的 `DataMigrationService` 已为存量用户创建默认对话，此处主要覆盖新注册用户

**替代方案**：在 `useConversations` 构造函数中创建 → 需要在 `userId` 就绪后触发，时机不可控。

## Risks / Trade-offs

| 风险 | 缓解 |
|------|------|
| `useChat.ts` 新增 `saveMessage` 钩子改动量失控 → | 仅加 ~15 行：在 SSE 流结束后 `await saveMessages()` + try/catch |
| 对话切换时 messages 数组竞态条件 → | 切换时先清空 `messages`，再加载历史，使用 loading 状态阻挡用户输入 |
| 历史消息加载性能 → | 默认加载 50 条，cursor 分页利用 `created_at` 索引；前端仅渲染可见区域（TDesign t-chat 自带虚拟滚动） |
| 侧边栏折叠/展开与聊天区域布局冲突 → | 使用 CSS `transition` + `max-width`；折叠时 `width: 0; overflow: hidden` |

## Open Questions

- 无。所有关键决策已明确。
