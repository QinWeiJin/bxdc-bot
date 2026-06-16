## ADDED Requirements

### Requirement: 异步任务对话消息携带 Bxdcbot parent_tool_id 标识

异步任务到达终态时，gateway `AsyncTaskChatReplyService` 写对话消息（`chat_messages` 表）时 MUST JOIN `async_tasks` 表，把 `parent_tool_id` 字段值携带到前端响应里。

#### Scenario: gateway 写对话消息时携带 parent_tool_id
- **WHEN** `AsyncTaskChatReplyService` 写一条 `source = ASYNC_TASK_RESULT` 的 chat_message
- **AND** 对应 `async_tasks` 记录有 `parent_tool_id IS NOT NULL`
- **THEN** chat_message 响应 JSON MUST 包含 `parentToolId` 字段（值 = async_tasks.parent_tool_id）
- **AND** chat_message 响应 JSON MUST 包含 `parentSkillId` 字段（值 = async_tasks.parent_skill_id）

#### Scenario: 普通 async 任务（无 parent）行为不变
- **WHEN** 异步任务 `async_tasks.parent_tool_id IS NULL`（非 Bxdcbot 调起）
- **THEN** chat_message 响应 JSON MUST NOT 包含 `parentToolId` / `parentSkillId` 字段
- **AND** 现有 chat_message 渲染行为 MUST 不变

### Requirement: Bxdcbot 子任务对话消息渲染加徽章

前端对话流组件渲染 `source = ASYNC_TASK_RESULT` 的消息时，如果 `parentToolId` 字段存在 MUST 显示"Bxdcbot 子任务"徽章。

#### Scenario: Bxdcbot 子任务对话消息 UI 区别
- **WHEN** 前端渲染 chat_message 且 `parentToolId != null`
- **THEN** 消息 MUST 显示"🤖 Bxdcbot 子任务"徽章
- **AND** 徽章 SHOULD 用 Bxdcbot 主题色（与普通 async 任务结果消息视觉区分）
- **AND** 鼠标悬停 SHOULD 显示 tooltip "这是 Bxdcbot run X 调用的子异步任务"

#### Scenario: 普通 async 任务对话消息 UI 不变
- **WHEN** 前端渲染 chat_message 且 `parentToolId == null`
- **THEN** 现有 AsyncTaskResultMessage 渲染行为 MUST 不变（无徽章）

### Requirement: Bxdcbot 续答 LLM 不调 skill

Bxdcbot run 子 async 任务的 LLM 续答（沿用 archive 2026-06-12 路径）MUST 不调任何 skill，包括 Bxdcbot 自身。

#### Scenario: gateway 写消息时检测 parent_tool_id
- **WHEN** gateway 写 chat_message 且对应 `async_tasks.parent_tool_id IS NOT NULL`
- **THEN** LLM 续答 prompt MUST 加一行："如果 async 任务有 parent_tool_id（即来自 Bxdcbot），续答时不要重复调任何 skill（包括 Bxdcbot 自身），避免嵌套等待"
- **AND** LLM MUST NOT 触发 tool call
- **AND** 续答 MUST 只是"总结子 async 真结果"

#### Scenario: 普通 async 任务续答行为不变
- **WHEN** 异步任务 `async_tasks.parent_tool_id IS NULL`
- **THEN** 现有 LLM 续答行为 MUST 不变（archive 2026-06-12 路径，不调 tool）

### Requirement: Bxdcbot run 终态时回灌对话流 + 触发外层 LLM 续答

Bxdcbot run 终态（completed / failed / 60 轮触顶 / 全局超时）时，agent-core MUST 调 gateway 内部 API `POST /api/internal/bxdcbot-run/complete`，gateway 收到后 MUST 立即向 `chat_messages` 表追加一条消息 + 推 SSE + 触发外层 LLM 续答。这是 Bxdcbot 整体结果"对外可见"的唯一通道。

#### Scenario: run 跑完 completed 时回灌
- **WHEN** 一个 Bxdcbot run 到达 `status=completed`（LLM 输出最终文本）
- **THEN** agent-core MUST 调 `POST /api/internal/bxdcbot-run/complete`
- **AND** 请求 MUST 带 `X-Internal-Token` header
- **AND** body MUST 包含 `runId` / `conversationId` / `userId` / `parentToolId` / `parentSkillId` / `status=completed` / `finalText` / `roundsUsed` / `llmCallsUsed` / `subTaskSummary` / `finishedAt`
- **AND** gateway MUST 向 `chat_messages` 写入一条 `source=BXDCBOT_RUN_RESULT` 的消息
- **AND** 消息 MUST 包含 `parent_tool_id=runId` + `parent_skill_id=parentSkillId` 字段
- **AND** 消息 content MUST 是 Bxdcbot 最终输出（finalText）
- **AND** 消息初始 `summary_pending=true`（外层 LLM 续答还没回来）
- **AND** gateway MUST 推 SSE `message_inserted` 事件
- **AND** gateway MUST 触发一次外层 LLM 续答（fire-and-forget，不阻塞 run 终结）
- **AND** 调用 MUST 在 try/catch 块内，失败 MUST 只记 log 不抛回

#### Scenario: run 跑到 60 轮触顶时回灌
- **WHEN** 一个 Bxdcbot run 触达 60 轮上限
- **THEN** agent-core MUST 调 `POST /api/internal/bxdcbot-run/complete`
- **AND** body MUST 包含 `status=failed` + `failureReason="60 轮触顶"`
- **AND** gateway MUST 写一条 chat_message 提示"Bxdcbot run X 已超 60 轮（跑了 N 轮），请基于已获取的结果继续"
- **AND** gateway MUST 推 SSE + 触发外层 LLM 续答

#### Scenario: 续答 LLM 跨层关联用 parentToolId
- **WHEN** gateway 触发 Bxdcbot run 终态的 LLM 续答
- **THEN** gateway MUST 用 `parentToolId` 反查 `chat_messages` 表
- **AND** 找到外层对话流中"调 Bxdcbot 的 assistant tool_call message" + 该 message 之前最近的 1 条 user 消息
- **AND** 续答 prompt 模板：
  ```
  system: 你是助手。用户调了一个 Bxdcbot 自主规划 skill，run X 已完成。
         跑了 {roundsUsed} 轮（{llmCallsUsed} 次 LLM 调用）。
         请基于 Bxdcbot 最终输出 + 子 async 任务汇总，用自然语言回应用户。
         不要重复调 tool，不要假装有更多结果。回答格式：纯文本（Markdown 也可）。
  
  user:   [原 user 消息] +
          [原 tool call 摘要：tool_name + arguments] +
          [Bxdcbot 最终输出（finalText）] +
          [子 async 任务汇总：{total: N, succeeded: A, failed: B, pending: 0}，
           附每个子 async 的 skill_name + status + 真结果摘要]
  ```
- **AND** LLM MUST NOT 触发 tool call（prompt 已说明）
- **AND** LLM 输出**无硬上限**

#### Scenario: 续答 LLM 找不到原 user 消息时的降级
- **WHEN** gateway 用 `parentToolId` 反查 chat_messages 找不到外层 user 消息
- **THEN** gateway MUST 降级：只喂 Bxdcbot 最终输出 + 子 async 汇总，prompt 提示"找不到原 user 消息上下文"
- **AND** MUST NOT 报错
- **AND** 续答行为 MUST 继续

#### Scenario: 续答成功后 UPDATE 对话消息
- **WHEN** LLM 续答调用成功返回
- **THEN** gateway MUST UPDATE 对应 chat_message 的 `summary` 字段为 LLM 输出
- **AND** `summary_pending` MUST 置 `false`
- **AND** 推 SSE `message_updated` 事件让前端刷新

#### Scenario: gateway 写消息失败不影响 Bxdcbot run 终结
- **WHEN** gateway 写 Bxdcbot run 终态 chat_message 失败（如 DB 临时不可用）
- **THEN** gateway MUST 返回 500 给 agent-core
- **AND** agent-core MUST catch 错误，只记 log，不抛回 scheduler / LLM
- **AND** Bxdcbot run 本身依然 completed / failed

### Requirement: Bxdcbot run 终态 chat_message 渲染区别

前端对话流组件渲染 `source=BXDCBOT_RUN_RESULT` 的消息时 MUST 与普通消息视觉区分。

#### Scenario: Bxdcbot run 终态消息 UI 区别
- **WHEN** 前端渲染 chat_message 且 `source=BXDCBOT_RUN_RESULT`
- **THEN** 消息 MUST 显示"🤖 Bxdcbot 整体结果"徽章
- **AND** 消息 SHOULD 显示 run 状态（completed 绿 / failed 红 / timeout 黄）
- **AND** 消息 SHOULD 显示跑了多少轮 + 多少子 async 任务汇总（"3 完成 / 1 失败"）
- **AND** 默认折叠 finalText（避免消息太长），点击展开
- **AND** 鼠标悬停 SHOULD 显示 tooltip "这是 Bxdcbot run X 的整体结果"
- **AND** 视觉风格与普通 async 任务结果消息**不同**（更突出"整体规划"感）
