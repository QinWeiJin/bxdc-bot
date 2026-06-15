## ADDED Requirements

### Requirement: 异步任务终态时 gateway 内部 API 必须回灌对话消息

异步任务（`pollStrategy: SINGLE_CALL` 或 `PERIODIC`）到达终态（SUCCESS / FAILED / TIMEOUT）时，agent-core MUST 调 gateway 内部 API `POST /api/internal/async-task/echo-to-chat`，gateway 收到后 MUST 立即向 `chat_messages` 表追加一条消息。整个过程 **不阻塞** LLM 调度主流程（fire-and-forget）。

#### Scenario: SINGLE_CALL 任务 SUCCESS 终态
- **WHEN** 一个 SINGLE_CALL 异步任务到达 SUCCESS 终态
- **THEN** agent-core MUST 调 gateway 内部 API
- **AND** gateway MUST 向 `chat_messages` 写入一条 `source = ASYNC_TASK_RESULT` 的消息
- **AND** 消息 MUST 包含 `async_task_id` 字段（关联到原任务）
- **AND** 消息 MUST 包含对原 tool call 的引用（`tool_name` + `tool_args` 摘要）
- **AND** 消息 MUST 包含任务结果摘要（`status` + `result_text`）
- **AND** 消息初始 `summary_pending = true`（LLM 续答还没回来）
- **AND** 写消息操作 MUST NOT 阻塞任务主流程（最多 1s 内完成）

#### Scenario: FAILED / TIMEOUT 终态也要回灌
- **WHEN** 一个异步任务到达 FAILED 或 TIMEOUT 终态
- **THEN** agent-core 同样 MUST 调 gateway 内部 API
- **AND** gateway 写入消息 SHOULD 包含失败原因 / 超时信息
- **AND** 通知中心依然显示状态（不丢）

#### Scenario: gateway 写消息失败不影响主流程
- **WHEN** gateway 写对话消息失败（如 DB 临时不可用）
- **THEN** gateway MUST 返回 500 给 agent-core
- **AND** agent-core MUST catch 错误，只记 log，不抛回轮询线程
- **AND** 异步任务本身依然 SUCCESS（审计 / 通知中心照常）

### Requirement: gateway 异步任务内部 API 必须触发 LLM 续答

gateway 收到 `POST /api/internal/async-task/echo-to-chat` 后，写完对话消息 MUST 触发一次 LLM 续答调用，让用户得到自然语言解读。LLM 续答 fire-and-forget，**不**阻塞 agent-core 等待响应。

#### Scenario: LLM 续答用用户自己的 LLM 配置
- **WHEN** gateway 触发 LLM 续答
- **THEN** gateway MUST 查 `User.llmApiBase` / `User.llmModelName` / `User.llmApiKey`
- **AND** 用用户配置的 LLM 调 API
- **WHEN** 用户未配置 LLM（字段为 NULL）
- **THEN** gateway MUST 降级用系统默认 LLM（gateway 配置项 `app.llm.fallback-api-base` 等）
- **AND** MUST NOT 报错

#### Scenario: LLM 续答 prompt 组装
- **WHEN** gateway 组装续答 prompt
- **THEN** system prompt MUST 包含："基于任务结果回答用户问题。回答长度根据问题复杂度和结果内容量自行决定，不需要 1-2 句硬性限制，也不要无限冗长。不要重复调 tool，不要假装有更多结果。回答格式：纯文本（Markdown 也可）。"
- **AND** user prompt MUST 包含：原 user 消息全文 + tool call 摘要（`tool_name` + arguments）+ 异步任务结果（`externalTaskId` + `status` + `result` 最大 20000 token + `finished_at`）
- **AND** LLM MUST NOT 触发 tool call（prompt 已说明）
- **AND** **LLM 输出无硬上限**（让 LLM 自己决定）；如果输出过长，前端 UI 自动折叠

#### Scenario: LLM 续答只调一次，不调 tool
- **WHEN** LLM 续答请求发送给上游
- **THEN** LLM MUST NOT 触发 tool call
- **AND** LLM 输出**无硬上限**（不截断）

#### Scenario: 任务结果超长截断（输入侧，不是输出侧）
- **WHEN** `taskResult` 字段超过 20000 token
- **THEN** gateway MUST 按 token 计数 truncate
- **AND** 保留头部 + 末尾，中间留 "..." 标记截断点
- **AND** 这是**用户的任务结果最大字符内容**，不是 LLM 生成的输出限制

#### Scenario: LLM 续答成功后 UPDATE 对话消息
- **WHEN** LLM 续答调用成功返回
- **THEN** gateway MUST UPDATE 之前写入的 `chat_messages` 行
- **AND** 设置 `summary_text` = LLM 输出
- **AND** 设置 `summary_pending = false`
- **AND** 设置 `summary_generated_at` = 当前时间

#### Scenario: LLM 续答失败时消息降级
- **WHEN** LLM 续答调用失败（超时 / 5xx / 内容违规 / token 超限）
- **THEN** gateway MUST 记录 error log
- **AND** 对话消息保持 `summary_pending = false` 且 `summary_text` = "任务已完成（系统未生成总结，可点击下方'查看完整任务'了解结果）"
- **AND** 消息行依然存在（保证审计）

#### Scenario: 续答调用不参与 dedup
- **WHEN** LLM 续答被触发
- **THEN** 该调用 MUST NOT 走异步任务 dedup 签名（per-session 1h 窗口）
- **AND** 续答调用 MUST NOT 被 dedup 误杀
- **AND** LLM HTTP audit 正常记录（用独立的 `auditTag = "ASYNC_TASK_REPLY"` 标识）

#### Scenario: 续答超长不截断（输出侧）
- **WHEN** LLM 输出超过任意长度
- **THEN** gateway MUST NOT 截断 LLM 输出
- **AND** 长输出由前端 UI 折叠（> 500 字自动折叠）
- **AND** 与"任务结果超长截断"不同：那是**输入侧**截断（用户的 task result），这里是**输出侧不截断**（LLM 的 summary）

### Requirement: agent-core 终态处理只调一行 gateway 内部 API

agent-core 在 `AsyncTaskPollingScheduler` 检测到异步任务终态时 MUST **只**调一次 gateway 内部 API，不在本端写对话消息、不在本端调 LLM。

#### Scenario: agent-core 终态调用
- **WHEN** `AsyncTaskPollingScheduler.terminalState()` 处理异步任务
- **THEN** 末尾 MUST 调 `this.gatewayClient.echoToChat(task)`（HTTP 异步）
- **AND** 调用 MUST 在 try/catch 块内
- **AND** 调用 MUST 设置 2s 超时（gateway 写消息 + 调 LLM 应该更快）
- **AND** 失败 MUST catch 并记 log
- **AND** MUST NOT 阻塞轮询调度线程

#### Scenario: agent-core 现有逻辑不动
- **WHEN** 异步任务终态处理
- **THEN** 现有 SSE 推送 MUST 不变
- **AND** 现有 audit log 写 `async_polling_audit_log` MUST 不变
- **AND** 现有 LLM 调度（如果有）MUST 不变
- **AND** agent-core MUST NOT 新增 Service / 新模块（除了一行调用 + GatewayClient 客户端）

### Requirement: 对话流消息按 source 区分渲染

前端对话流组件 MUST 根据 `chat_messages.source` 字段走不同渲染分支。

#### Scenario: 普通 assistant 消息
- **WHEN** `chat_messages.source = "ASSISTANT"`
- **THEN** 前端按现有对话气泡渲染

#### Scenario: 异步任务结果消息
- **WHEN** `chat_messages.source = "ASYNC_TASK_RESULT"`
- **THEN** 前端 MUST 用专用 UI 组件 `AsyncTaskResultMessage.vue` 渲染
- **AND** MUST 显示 🔔 icon + "异步任务" 标签
- **AND** MUST 显示任务状态徽章（SUCCESS 绿 / FAILED 红 / TIMEOUT 黄）
- **AND** MUST 折叠原始结果（默认折叠，点击展开）
- **AND** MUST 显示 LLM 总结区：`summary_text` 用 Markdown 渲染
- **AND** `summary_pending = true` 时 MUST 显示骨架屏 / "系统正在生成总结..."占位
- **AND** LLM 总结 > 500 字时 MUST 自动折叠（点击展开）
- **AND** MUST 显示"复制"按钮（一键复制 LLM 总结文本）
- **AND** MUST 显示"查看完整任务"链接（调 `/api/async-tasks/{id}` 跳通知中心）

#### Scenario: 移动端响应式
- **WHEN** 用户在移动端查看
- **THEN** 折叠 / 展开逻辑 MUST 自动适配（小屏默认折叠更多内容）
- **AND** LLM 总结区 MUST 可横向滚动（避免长代码块撑爆布局）

### Requirement: chat_messages 表 schema 演进

数据库 schema 演进 MUST 走 Java migration 类（参考 `StartupRecoveryRunner` 模式），**不**靠手动 `mysql -e`，**不**改 `schema-mysql.sql`。

#### Scenario: source 枚举扩展
- **WHEN** schema migration 跑完
- **THEN** `chat_messages.source` 字段 MUST 支持新值 `ASYNC_TASK_RESULT`
- **AND** 旧值 `USER` / `ASSISTANT` / `TOOL_RESULT` 依然存在（不丢）

#### Scenario: async_task_id 可空列
- **WHEN** schema migration 跑完
- **THEN** `chat_messages.async_task_id` 列 MUST 存在（VARCHAR(64) 可空）
- **AND** 普通对话消息 `async_task_id = NULL`
- **AND** 异步任务结果消息 `async_task_id` 必填

#### Scenario: summary 字段加列
- **WHEN** schema migration 跑完
- **THEN** `chat_messages.summary_pending` TINYINT(1) DEFAULT 1 列 MUST 存在
- **AND** `chat_messages.summary_text` MEDIUMTEXT NULL 列 MUST 存在
- **AND** `chat_messages.summary_generated_at` DATETIME NULL 列 MUST 存在

#### Scenario: async_task_id 索引
- **WHEN** schema migration 跑完
- **THEN** `chat_messages.async_task_id` 列 MUST 有索引
- **AND** 查询"该任务产生了哪些对话消息"是 O(log n)

### Requirement: 通知中心行为不变

异步任务终态时通知中心推送逻辑 MUST 保持现状（不丢）。

#### Scenario: 通知中心继续工作
- **WHEN** 异步任务到达终态
- **THEN** SSE 推送到 `/api/async-tasks/my` 通知中心 MUST 继续工作
- **AND** 通知中心 UI 不动
- **AND** 通知中心与对话回灌是**并行**两条路径，互不影响

#### Scenario: 双通道并存
- **WHEN** 用户既没刷新对话流也没打开通知中心
- **THEN** 两条路径的写操作 MUST 都成功
- **AND** 任意一条失败 MUST NOT 影响另一条

### Requirement: gateway 内部 API 安全保护

`/api/internal/*` 路径 MUST 加内部网络保护，禁止外部网络直接访问。

#### Scenario: 内部 API 路径前缀
- **WHEN** gateway 启动
- **THEN** `/api/internal/async-task/echo-to-chat` MUST 仅允许内网调用
- **AND** MUST 配 IP 白名单或 Spring Security 内部网络限定
- **AND** 外部调用 MUST 返回 403

#### Scenario: agent-core 内部调用鉴权
- **WHEN** agent-core 调 `/api/internal/async-task/echo-to-chat`
- **THEN** gateway MUST 校验调用方 IP / 内部 token
- **AND** 校验失败 MUST 返回 401
- **AND** agent-core MUST 不在请求里带外部用户的 token / cookie
