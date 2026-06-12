## 1. 常量与状态调整

- [x] 1.1 在 `useConversations.ts` 中定义 `PAGE_SIZE = 20`，修改 `switchConversation` 首次加载从 50 改为 `PAGE_SIZE`
- [x] 1.2 添加 `loadMoreMessages` 实现：取 `historyMessages` 最旧消息的 `created_at` 作为 cursor，调用 `apiFetchConversation`，将结果 reverse 后 prepend 到 `historyMessages` 前面，更新 `hasMoreHistory`

## 2. 滚动触发加载更多

- [x] 2.1 在 `MessageList.vue` 中获取 `.t-chat__list` DOM 引用，监听 `scroll` 事件
- [x] 2.2 当 `scrollTop < 50`、`hasMoreHistory === true`、`isLoadingHistory === false`、`isProcessing === false` 时，调用 `conversations.loadMoreMessages(userId)`
- [x] 2.3 加载完成后在 `nextTick` 中恢复滚动位置：记录加载前 `scrollHeight`，加载后将 `scrollTop` 设为 `newScrollHeight - oldScrollHeight`

## 3. ChatView 适配

- [x] 3.1 确认 `ChatView.vue` 中 `watch(historyMessages)` 的全量替换模式与 prepend 追加兼容（追加后的 `historyMessages` 仍是完整数组，无需改动）

## 4. 验证

- [ ] 4.1 创建一个超过 20 条消息的对话，切换进入后验证只显示 20 条、且向上滚动可触发加载更多
- [ ] 4.2 验证滚动位置在加载更多后不跳变
- [ ] 4.3 验证无更多消息时不触发额外请求
- [ ] 4.4 验证 SSE 流活跃时滚动到顶不触发加载
