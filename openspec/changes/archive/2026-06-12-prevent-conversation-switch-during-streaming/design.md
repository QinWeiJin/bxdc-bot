## 设计

### 数据流

```
用户发送消息
  │
  ├─► sendMessage()
  │     ├─ isProcessing = true           ← useConversations 单例
  │     ├─ fetch(SSE) + reader.read()
  │     │     ... token 写入 messages ...
  │     └─ finally: isProcessing = false
  │
用户点击另一个对话（SSE 进行中）
  │
  ├─► handleSelect(convB)
  │     ├─ if isProcessing → Toast 提示 + return
  │     └─ (阻断了，什么也不做)
```

### 实现细节

**`useConversations.ts`：**

```ts
const isProcessing = ref(false)
// return 中暴露
```

**`useChat.ts` `sendMessage()`：**

```ts
// 函数体顶部已有 isThinking guard
const conversations = useConversations()

// 设置 isProcessing
conversations.isProcessing.value = true

// ... SSE while loop ...

// finally / catch / done 所有出口都设回 false
conversations.isProcessing.value = false
```

关键：`sendMessage` 的 `isThinking` 手动设为 `false` 的**所有路径**（`!response.ok` / `!reader` / `data.error` / stream `done` / catch AbortError / catch 通用错误）都要同步 `isProcessing = false`。

**`ConversationSidebar.vue` `handleSelect()`：**

```ts
import { MessagePlugin } from 'tdesign-vue-next'

async function handleSelect(conv) {
  if (isProcessing.value) {
    MessagePlugin.warning('当前对话正在进行中，请等待完成后切换')
    return
  }
  // 原有逻辑
}
```

### 为什么不用 `isThinking`

`isThinking` 在 `useChat` 的 provide/inject 作用域内，`ConversationSidebar` 在 `Layout` 下、`ChatView` 的兄弟位置，无法通过 inject 拿到。走 `useConversations` 模块级单例是最短路径，不需要动组件树层级。

### 为什么不中断旧对话

这是有意取舍：简单锁比 AbortController 改动更小，且避免了"中断后服务端还在跑但客户端收不到结果"的半状态。后续可以在此基础上加 AbortController 做主动取消。
