## Why

当前对话历史在切换会话时一次性加载 50 条消息，长对话场景下加载耗时长且内存占用大。需要改为翻页加载：首次 20 条，向上滚动到底部时追加 20 条，提升加载性能和用户体验。

## What Changes

- 首次加载从 50 条降到 20 条
- 消息列表顶部实现上拉加载更多（向上滚动到顶部时自动触发）
- `loadMoreMessages` 函数利用后端已有的 cursor 分页接口加载更早的消息
- 历史消息追加而非替换，保留当前已展示的消息

## Capabilities

### New Capabilities
- `conversation-history-pagination`: 对话历史消息的滚动翻页加载能力

### Modified Capabilities
- `conversation-crud`: `Get Conversation Detail with Messages` 的 limit 默认值行为不改变，前端的调用参数从 50 改为 20

## Impact

- `frontend/src/composables/useConversations.ts` - 实现 `loadMoreMessages`，添加 cursor 追踪
- `frontend/src/components/MessageList.vue` - 添加向上滚动到顶部时的加载触发逻辑
- `frontend/src/views/ChatView.vue` - 监听 `historyMessages` 的变化方式适配追加模式
- 后端无需改动（cursor 分页接口已就绪）
