## Context

当前异步任务系统（5 原子 + 2 模式：Submit / Wait / Audit / Dedup / Notify + SINGLE_CALL / PERIODIC）实现详见 `openspec/specs/api-extension-skill-llm-tool-call/spec.md`。任务完成后只走 2 条路径：
1. **审计**：`async_polling_audit_log` 表记终态 phase
2. **通知**：推 SSE 到前端 `/api/async-tasks/my` 通知中心

**问题**：用户跟 LLM 的对话上下文（`chat_messages` 表）和异步任务结果**完全隔离**。用户在对话里说"帮我查一下 XX 任务"，LLM 看不到任务结果（任务结果在 `async_polling_audit_log` 表里）；用户想"基于这个结果再追问"必须手动复制粘贴。

**约束（AGENTS.md 5.5）**：尽量不改 agent-core（NestJS）代码，新增能力优先在 gateway（Java 侧）以 Tool / Service 形式接入。

需要打通：异步任务完成 → 写一条对话消息 + 触发 LLM 续答，且主体逻辑全部在 gateway，agent-core 仅做 1 行转发。

## Goals / Non-Goals

**Goals:**
- 异步任务终态（SUCCESS / FAILED / TIMEOUT）→ 在对话流追加一条 assistant 消息，含原 tool call 引用
- 终态 → 触发一次 LLM 续答（fire-and-forget），让用户得到自然语言解读（**字数根据问题+结果动态决定，无硬上限**）
- LLM 续答**用用户自己的 LLM 配置**（`User.llmApiBase` / `User.llmModelName`，无配置时降级系统默认）
- 通知中心保留现状（双通道并存）
- LLM 续答时复用现有 LLM HTTP audit 管道（不绕过）
- 主体在 gateway，agent-core 仅在 `AsyncTaskPollingScheduler` 终态加**一行** gateway 内部 API 调用
- 不改 SSE 协议，不改 `async_polling_audit_log` 表结构

**Non-Goals:**
- 不实现"对话流对异步任务状态的实时订阅"（用户刷新对话流看到新消息即可）
- 不改 SINGLE_CALL / PERIODIC 现有超时逻辑
- 不实现"异步任务取消时撤回对话消息"（cancel 是另一个流程）
- 不改通知中心 UI
- 不在 agent-core 加新 Service / 新模块（除了那一行调用）

## Decisions

### 决策 1：主体逻辑放 gateway 还是 agent-core？

**主体放 gateway（Java 侧）**，agent-core 仅 1 行调用。

理由（对齐 AGENTS.md 5.5）：
- gateway 已有 MyBatis-Plus / Service / Controller 模板，加 entity + service + controller 是常规 Java 工作
- 调 LLM 走现有 gateway HTTP 客户端（`LlmHttpClient` 类似物，已有 LLM HTTP audit 管道）
- 写 `chat_messages` 表用 MyBatis-Plus 即可
- agent-core 不再加 Service / 模块，仅在 `AsyncTaskPollingScheduler` 终态时多调一次 gateway 内部 API
- 链路：agent-core 终态 → `POST /api/internal/async-task/echo-to-chat` → gateway 做写消息 + 调 LLM + UPDATE 总结

### 决策 2：对话消息的"来源"枚举怎么加？

**新加一个枚举值 `ASYNC_TASK_RESULT`** 到 `chat_messages.source` 字段。

备选 A：复用 `ASSISTANT` 枚举 + 新加 `tool_call_id` 字段
备选 B：新增 `ASYNC_TASK_RESULT` 枚举 + 新加 `async_task_id` 字段
**选 B** —— 语义清晰，前端按 source 走不同渲染分支。

### 决策 3：LLM 续答的 LLM 配置

**用用户自己的 LLM 配置**（`User.llmApiBase` / `User.llmModelName` / `User.llmApiKey`）。

理由：保持一致性 —— 用户在其他对话回合用的是哪个 LLM，续答也用哪个。避免"对话用 Claude、续答用 GPT"的撕裂感。

降级路径：用户未配置 LLM 时用系统默认 LLM（在 gateway 配置里），不报错。

### 决策 4：LLM 续答字数怎么定？

**根据用户问题 + 异步结果动态决定**，**无硬上限**（让 LLM 自己看情况给回答）。

- system prompt 明确："基于任务结果回答用户问题。回答长度根据问题复杂度和结果内容量自行决定，不需要 1-2 句硬性限制，也不要无限冗长。"
- 不强制 1-2 句（之前是过度保守）
- 短问题 + 短结果 → 短答（1-2 句）
- 长问题 + 大结果 → 长答（可以是多段分析、引述、对比）
- 截断：LLM 输出**不**做硬截断。如果 LLM 输出过长，前端 UI 自动折叠（> 500 字折叠，点击展开）

理由：用户提的问题复杂度差异巨大（如"这任务结果是什么" vs "基于这个结果帮我写一份分析报告"），固定 1-2 句回答不了后者。

### 决策 5：LLM 续答的 prompt 组装

```
system: 你是助手。用户的某次提问触发了异步任务，任务已经完成。
       请基于任务结果回答用户问题。回答长度根据问题复杂度和结果内容量自行决定，
       不要无限冗长。不要重复调 tool，不要假装有更多结果。
       回答格式：纯文本（Markdown 也可）。

user:   [原 user 消息全文] + [tool call 摘要：tool_name + arguments] +
        [异步任务结果：externalTaskId + status + result（最大 20000 token）+ finished_at]
```

**不**调 tool，**不**支持多轮。

**result 输入限制 20000 token** —— 这是**用户的任务结果返回的最大字符内容**（不是 LLM 生成的输出限制）。如果 `taskResult` 超过 20000 token，gateway 截断到 20000 token（按 token 计数，保留头部 + 末尾，中间留 "..." 标记截断点）。这样 LLM 不会被超长 result 撑爆 context。

### 决策 6：fire-and-forget 的实现

gateway 内部 API 走 `@Async` 注解（Spring Async）+ try/catch 包住：异常 catch 后记 error log，写消息失败/调 LLM 失败都不抛回主流程。

agent-core 调 gateway 内部 API 用 HTTP 异步（不 await Response），失败也只记 log。

### 决策 7：对话流刷新策略

**不**做实时推送。用户刷新对话流（前端 polling 或路由切换）时自然拉到新消息。

### 决策 8：async_task_id 与 chat_message 的关联

`chat_messages` 表加 `async_task_id` 列（可为 NULL）。
前端拿到这条消息时，根据 `async_task_id` 调 `/api/async-tasks/{id}` 拿完整结果（按需懒加载）。

### 决策 9：前端展示优化

异步任务结果消息在 UI 上和普通消息视觉区分，提供更好可读性：
- **专用图标** 🔔 + "异步任务" 标签（区别普通对话气泡）
- **状态徽章**：SUCCESS 绿 / FAILED 红 / TIMEOUT 黄
- **折叠原始结果**：默认折叠（避免消息太长），点击展开
- **LLM 总结区**：
  - `summary_pending = true` → 显示骨架屏 / "系统正在生成总结..." placeholder
  - 总结文本用 Markdown 渲染（LLM 输出可以是 Markdown）
  - 总结太长时折叠（> 500 字自动折叠，点击展开）
- **复制按钮**：一键复制 LLM 总结文本
- **"查看完整任务"** 链接：调 `/api/async-tasks/{id}` 跳通知中心
- **响应式**：移动端折叠逻辑自动适配

## Risks / Trade-offs

- **LLM 续答失败** → 用户看到"异步任务完成消息"但看不到 LLM 总结
  - 缓解：消息体 LLM 总结字段为 NULL 时显示"任务完成（系统未生成总结，可点击下方'查看完整任务'了解结果）"

- **LLM 续答耗时**（通常 1-10s，长答案更久）→ 用户等不到
  - 缓解：先写"任务完成"消息（`summary_pending=true`），LLM 续答回来后 UPDATE 总结字段。前端 skeleton 占位。

- **LLM 长答案（无硬上限）撑爆对话流** → 用户翻不到下面的对话
  - 缓解：UI 上长答案自动折叠（> 500 字），点击展开

- **用户没配 LLM** → 续答调用失败
  - 缓解：降级用系统默认 LLM（gateway 配置项 `app.llm.fallback-api-base` 等）

- **result 太大（> 50KB）撑爆 prompt** → 续答 LLM context 超限
  - 缓解：result 截到 50KB（保留头部），超过记 log 提示

- **写对话消息时数据库 schema 未更新** → source 枚举值不存在导致 insert 失败
  - 缓解：Java migration 类（`ChatMessageSchemaMigration`），启动时 idempotent 应用

- **agent-core 那一行调用 gateway 失败**（如 gateway 重启）→ 任务主流程卡住
  - 缓解：agent-core 用 HTTP 异步调用 + 超时 2s + 失败 catch，失败只记 log 不影响任务状态

- **gateway 内部 API 暴露给外部** → 安全风险
  - 缓解：`/api/internal/*` 路径加 IP 白名单 / Spring Security 内部网络限定（与现有 `app.registration.admin-password` 一样的内部保护模式）

## Migration Plan

数据库 schema 演进**只用 Java migration**（参考 `StartupRecoveryRunner` 模式），**不**改 `schema-mysql.sql` 也不让用户手动 `mysql -e` 跑（AGENTS.md 5.3 规约）。

### 1. Java migration 类（主路径）

新建 `ChatMessageSchemaMigration`（gateway 启动时跑一次，idempotent）：

- 步骤 1 — `ALTER TABLE chat_messages MODIFY COLUMN source ENUM('USER','ASSISTANT','TOOL_RESULT','ASYNC_TASK_RESULT')`
  - 幂等：查 `information_schema.COLUMNS` 现有枚举值
- 步骤 2 — `ALTER TABLE chat_messages ADD COLUMN async_task_id VARCHAR(64) NULL`
  - 幂等：查 `information_schema.COLUMNS` 列名
- 步骤 3 — `ALTER TABLE chat_messages ADD COLUMN summary_pending TINYINT(1) DEFAULT 1`
  - 幂等：查列名
- 步骤 4 — `ALTER TABLE chat_messages ADD COLUMN summary_text MEDIUMTEXT NULL`
  - 幂等：查列名
- 步骤 5 — `ALTER TABLE chat_messages ADD COLUMN summary_generated_at DATETIME NULL`
  - 幂等：查列名
- 步骤 6 — `CREATE INDEX idx_chat_messages_async_task_id ON chat_messages(async_task_id)`
  - 幂等：查 `information_schema.STATISTICS`
- 步骤 7 — 记录到 `schema_migrations` 表（migration name + applied_at），下次启动跳过

### 2. 为什么不用 `schema-mysql.sql`

- `schema-mysql.sql` 是 Spring `spring.sql.init.mode=always` 自动跑的，**仅适用于全新 DB 初始化**
- 现有 DB 已跑过 schema-mysql.sql，再跑一次会 `CREATE TABLE` 报错
- 增量变更（加列 / 加枚举值 / 加索引）**必须**走 Java migration

### 3. gateway 新增文件

- `entity/ChatMessage.java`
- `mapper/ChatMessageMapper.java`
- `service/ChatMessageService.java`
- `service/AsyncTaskChatReplyService.java`（核心业务逻辑）
- `controller/AsyncTaskEchoController.java`（内部 API）
- `migration/ChatMessageSchemaMigration.java`
- `dto/EchoToChatRequest.java` / `EchoToChatResponse.java`

### 4. agent-core 改动（最小）

- `AsyncTaskPollingScheduler.terminalState()` 末尾加 1 行：`await this.gatewayClient.echoToChat(task)`（HTTP 异步，try/catch）
- 新增 `GatewayClient` 服务（如果还没有）：调 `POST http://gateway:18080/api/internal/async-task/echo-to-chat`
- 现有 SSE / LLM 调度 / 异步任务 polling 逻辑**全部不动**

### 5. frontend 改动

- `MessageItem.vue` 加 `source` prop 分支
- 新建 `AsyncTaskResultMessage.vue`：专用 UI（图标 / 状态徽章 / 折叠 / skeleton / Markdown / 复制 / 链接）
- 复用现有 `useMarkdown.ts` 渲染 LLM 总结

### 6. 回归测试

- gateway 单元测试覆盖 `AsyncTaskChatReplyService` 各路径（写消息 / 调 LLM / UPDATE / 失败降级）
- agent-core 仅需 1 个端到端测试覆盖"终态 → gateway 内部 API 调用"
- frontend snapshot 测试
- migration 跑 2 次：第一次应用 + 第二次幂等跳过

### 7. 灰度

单 skill 上线观察 1-2 天，全 skill 开放。

**回滚**：
- Java migration 加 DROP COLUMN / DROP INDEX 改回（不破坏老数据）
- 代码回滚 = revert commit（`AsyncTaskChatReplyService` / `ChatMessageSchemaMigration` / `AsyncTaskEchoController` 是新文件，老逻辑不依赖）

- **回灌对话的 LLM 续答被 dedup 误杀**（per-session 1h 去重窗口） → 风险：用户连续触发多个异步任务，LLM 续答被认为"重复"
  - 缓解：续答调用走独立 audit 通道（不复用 dedup 签名），不参与去重

### 1. Java migration 类（主路径）

新建 `ChatMessageSchemaMigration`（agent-core 启动时跑一次，idempotent）：

- 步骤 1 — `ALTER TABLE chat_messages MODIFY COLUMN source ENUM('USER','ASSISTANT','TOOL_RESULT','ASYNC_TASK_RESULT')`
  - 幂等：用 `information_schema.COLUMNS` 查现有枚举值，已含 `ASYNC_TASK_RESULT` 则跳过
- 步骤 2 — `ALTER TABLE chat_messages ADD COLUMN async_task_id VARCHAR(64) NULL`
  - 幂等：`information_schema.COLUMNS` 查列名已存在则跳过
- 步骤 3 — `CREATE INDEX idx_chat_messages_async_task_id ON chat_messages(async_task_id)`
  - 幂等：`information_schema.STATISTICS` 查索引已存在则跳过
- 步骤 4 — 记录到 `schema_migrations` 表（migration name + applied_at），下次启动跳过已跑的

### 2. 为什么不用 `schema-mysql.sql`

- `schema-mysql.sql` 是 Spring `spring.sql.init.mode=always` 自动跑的，**仅适用于全新 DB 初始化**
- 现有 DB 已跑过 schema-mysql.sql，再跑一次会 `CREATE TABLE` 报错（表已存在）
- 增量变更（加列 / 加枚举值 / 加索引）**必须**走 Java migration，才能 idempotent 应用到现有 DB

### 3. 其他端

- **agent-core**：
  - 加 `AsyncTaskChatReplyService`（新文件）
  - 加 `ChatMessageSchemaMigration`（新文件）
  - `AsyncTaskPollingScheduler` 终态路径加 hook
  - 不影响现有 SINGLE_CALL / PERIODIC 主流程
- **frontend**：
  - 消息渲染组件加 `source === ASYNC_TASK_RESULT` 分支（折叠 + icon）

### 4. 回归测试

- agent-core 跑现有 SSE / async 任务测试套件
- frontend 跑对话流 snapshot（如有）
- migration 跑 2 次：第一次应用 + 第二次幂等跳过（不报错）

### 5. 灰度

单 skill 上线观察 LLM 续答效果，全 skill 开放。

**回滚**：
- Java migration 改回 idempotent 检测 + DROP COLUMN / DROP INDEX（不破坏老数据）
- 代码回滚 = revert commit（`AsyncTaskChatReplyService` + `ChatMessageSchemaMigration` 是新文件，老逻辑不依赖它）

## Resolved Decisions（原 Open Questions 已并入上方 Decisions）

| 原 Open Question | 决议 | 位置 |
|---|---|---|
| LLM 续答用谁的配置 | **用户自己的 LLM 配置**（无则降级系统默认）| 决策 3 |
| 续答字数怎么定 | **根据问题+结果动态调整，无硬上限** | 决策 4 |
| 大 result 怎么截断 | **截到 20000 token**（保留头尾 + "..."，这是用户的任务结果最大字符）| 决策 5 |
| 前端要不要优化 | **要**（专用 UI + 折叠 + 骨架屏 + Markdown + 复制 + 链接）| 决策 9 |
