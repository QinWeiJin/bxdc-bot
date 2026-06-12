## Why

异步任务（`SINGLE_CALL` / `PERIODIC`）完成后结果只进入通知中心 `async_polling_audit_log`，用户必须切到通知中心才能看结果，没法和当前对话上下文串联。一旦用户想"基于这个长调用结果再追问一句"，上下文已经断了——LLM 看不到这条结果，只能由用户自己复制粘贴到对话里。

需要让异步任务在终态时（SUCCESS / FAILED / TIMEOUT）**自动回显到对话流**并触发一次 LLM 续答，把"长调用的产物"和"对话上下文"绑成一条连续链路。

## What Changes

- 异步任务终态时向**对话流**追加一条 assistant 消息（含原 tool call 引用 + 任务结果摘要）
- 终态时**触发一次 LLM 续答**（fire-and-forget，不阻塞异步任务主流程），让用户得到一句自然语言的"读后感 / 下一步建议"
- LLM 续答时把"原 user 消息 + tool call 摘要 + 任务结果"作为上下文喂进去，输出纯文本回复（不重复调 tool）
- 通知中心**保留现有行为不变**（不丢，双通道并存）
- 对话流消息来源加一个新枚举 `ASYNC_TASK_RESULT`（区别于普通 `ASSISTANT` / `TOOL_RESULT` / `USER`）

## Capabilities

### New Capabilities

- `async-task-chat-reply`: 异步任务完成 → 对话回显 + LLM 续答（独立 spec，定义消息模型 + 触发时机 + LLM 协议）

### Modified Capabilities

- `api-extension-skill-llm-tool-call`: 现有"异步任务通知中心" requirement 扩展 —— 终态时不光写 audit_log + 推 SSE 通知，还要写对话消息 + 触发 LLM 续答

## Impact

按 **AGENTS.md 5.5 "尽量不改 agent-core 代码" 规约**，主体逻辑下沉到 gateway（Java 侧）：

- **skill-gateway（Java，主战场）**：
  - 新增 `ChatMessage` entity（读写 `chat_messages` 表）
  - 新增 `AsyncTaskChatReplyService`（封装"组装上下文 → 调 LLM → 写消息 → 更新总结"）
  - 新增内部 API `POST /api/internal/async-task/echo-to-chat`（agent-core 终态时调一次）
  - 新增 `ChatMessageSchemaMigration`（Java migration 类，schema 演进）
  - 现有 `async_polling_audit_log` schema 不动
- **agent-core（NestJS，最小侵入）**：
  - `AsyncTaskPollingScheduler` 终态处理路径加**一行**调用：`this.gatewayClient.echoToChat(task)`（调 gateway 内部 API）
  - 现有 SSE 推送逻辑不动
  - 现有 LLM 调度不动
  - 不在 agent-core 写 chat_messages，不在 agent-core 调 LLM
- **frontend**：
  - 对话流组件新增"异步任务完成"消息渲染（专用子组件 + 折叠 + 状态徽章 + LLM 总结区）
  - 通知中心 UI 不动
- **数据库**：
  - `chat_messages` 表加 `source` 枚举值 `ASYNC_TASK_RESULT`（Java migration）
  - `chat_messages` 表加可空列 `async_task_id` + 索引（Java migration）
  - 不新增表
- **新接口**：
  - gateway 新增 `POST /api/internal/async-task/echo-to-chat`（内部调用，agent-core 走）
  - gateway 调 LLM 走现有 LLM HTTP audit 管道
- **回归测试**：
  - gateway 单元测试覆盖 `AsyncTaskChatReplyService` 各路径
  - agent-core 仅需 1 个端到端测试覆盖"终态 → gateway 内部 API 调用"
  - frontend 渲染组件的 snapshot 测试（如果存在）
