## Context

当前 `useConversations.switchConversation` 调用 `apiFetchConversation(userId, id, undefined, 50)` 一次性拉取 50 条历史消息。对于长对话（超过 50 条消息），用户看不到更早的记录。

后端 `GET /api/conversations/:id` 已支持 cursor-based 分页（参数 `?cursor=&limit=`），返回 `hasMore` 标志。前端只需适配消费端逻辑即可。

当前消息流转路径：
```
switchConversation() → apiFetchConversation(50条) → historyMessages.value = msgs
                                                              ↓
ChatView.vue watch historyMessages → convertHistoryMessages() → messages.value = 全量替换
```

## Goals / Non-Goals

**Goals:**
- 首次加载 20 条历史消息
- 用户向上滚动到消息列表顶部时自动加载更早的 20 条
- 新加载的历史消息追加到当前消息列表前方（不丢失已有的当前回合消息）
- `isProcessing` 时禁止加载更多

**Non-Goals:**
- 不修改后端 API（cursor 分页已就绪）
- 不改变 `historyMessages` 的 watch 全量替换模式（追加后的 `historyMessages` 仍然是完整数组，由 `ChatView` 统一转换）
- 不改变消息持久化逻辑

## Decisions

### 1. 翻页粒度：20 条

`useConversations.PAGE_SIZE = 20`，与后端 `MAX_LIMIT = 100` 和 `DEFAULT_LIMIT = 50` 形成合理分层。20 条消息足够填充视口（约 2-3 屏），且单次请求/渲染成本低。

### 2. 滚动触发方式：监听 `.t-chat__list` 的 scroll 事件

`TChat` 组件内部的 `.t-chat__list` 元素已有 `overflow-y: auto`。在 `MessageList.vue` 中通过 `ref` 获取该 DOM 元素，监听 `scroll` 事件：
- `scrollTop === 0` 时触发加载
- 设一个阈值（如 `scrollTop < 50`）避免 iOS rubber-banding 误触发

不自己实现 `IntersectionObserver` 或额外哨兵元素，因为 scroll 事件方案更直接。

### 3. cursor 取 historyMessages 中最旧消息的 `created_at`

`historyMessages` 按时间升序排列（`switchConversation` 中做了 `.reverse()`），最旧的即 `historyMessages[0].created_at`。`loadMoreMessages` 将此作为 cursor 传给 `apiFetchConversation`。

### 4. 加载更多时拼接而非替换

`loadMoreMessages` 完成后将新消息（API 返回 DESC 排列，需 reverse 后）追加到 `historyMessages` 前面：
```
historyMessages.value = [...olderMsgs, ...historyMessages.value]
```
`ChatView.vue` 的 watch 会将新的完整 `historyMessages` 重新转换为 `messages`。

### 5. 去重：防止滚动事件重复触发

使用 `isLoadingHistory.value` 作为加载锁，加载期间忽略新的滚动触发。

## Risks / Trade-offs

- **[滚动位置跳变]** 新消息 prepend 到 DOM 后，`TChat` 可能滚动位置偏移。→ 加载前记录 `scrollHeight`，加载后在 `nextTick` 中恢复 `scrollTop = newScrollHeight - oldScrollHeight`。
- **[hasMore 过期]** 用户在加载更多的同时有新消息写入（SSE 流结束），`hasMore` 的准确性不受影响（cursor 基于 `created_at`，新消息时间戳更新，不会出现在 cursor 之前的查询中）。
- **[`historyMessages` 持久增长]** 每次加载更多都会让 `historyMessages` 变大。→ 当前阶段不做裁剪（内存可承受几百条消息），后续有需要可加"仅保留最近 N 条"的裁剪逻辑。
