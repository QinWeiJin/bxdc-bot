## 1. useConversations — 新增 isProcessing ref

- [x] 1.1 `useConversations.ts`：在 `createConversationsState()` 内新增 `const isProcessing = ref(false)`
- [x] 1.2 `useConversations.ts`：在 `ConversationsState` 接口和 return 对象中暴露 `isProcessing`

## 2. useChat — sendMessage 生命周期标记

- [x] 2.1 `useChat.ts`：`sendMessage()` 内通过 `isThinking` guard 后，设置 `useConversations().isProcessing.value = true`
- [x] 2.2 `useChat.ts`：SSE stream done 分支（`done = true`）重置 `isProcessing = false`
- [x] 2.3 `useChat.ts`：`!response.ok` 分支重置 `isProcessing = false`
- [x] 2.4 `useChat.ts`：`!reader` 分支重置 `isProcessing = false`
- [x] 2.5 `useChat.ts`：`data.error` 分支重置 `isProcessing = false`
- [x] 2.6 `useChat.ts`：外层 catch 分支（包括 AbortError 和通用异常）重置 `isProcessing = false`

## 3. ConversationSidebar — handleSelect 阻断

- [x] 3.1 `ConversationSidebar.vue`：从 `useConversations()` 解构 `isProcessing`
- [x] 3.2 `ConversationSidebar.vue`：`handleSelect()` 首行检查 `isProcessing.value`，若为 `true` 则 `MessagePlugin.warning('当前对话正在进行中，请等待完成后切换')` 并 `return`
- [x] 3.3 `ConversationSidebar.vue`：引入 `import { MessagePlugin } from 'tdesign-vue-next'`

## 4. 验证

- [ ] 4.1 对话 A 正在生成回复 → 点击对话 B：Toast 提示，不切换，对话 A 继续生成
- [ ] 4.2 对话 A 回复完成 → 点击对话 B：正常切换，加载 B 的消息
- [ ] 4.3 对话 A SSE 报错（断网 / 超时）→ 点击对话 B：正常切换（因为 isProcessing 已重置）
- [ ] 4.4 没有进行中的对话 → 新建对话：正常创建并切换，不误拦
- [ ] 4.5 对话 A 回复完成后 → 点击"新建对话"：正常创建
