# api-extension-skill-llm-tool-call

## Purpose

Define how extension **API** skills are presented to the LLM as tools and how the agent MUST avoid tool-call retry loops when the model mis-encodes parameters.
## Requirements
### Requirement: Tool description matches single input parameter

The system MUST present extension API skills to the LLM with documentation that is consistent with the tool schema: all parameters described in the `parameterContract` MUST appear as **top-level** fields in the tool’s structured `parameters` (from `DynamicStructuredTool` / Zod), **not** nested inside a single `input` JSON string.

#### Scenario: Description instructs structured fields

- **WHEN** an extension API skill is registered as a LangChain tool with structured parameters
- **THEN** the tool description visible to the LLM MUST list each contract field (or reference the contract) in a way consistent with the OpenAI/LangChain `parameters` schema
- **AND** the description MUST NOT state that contract fields MUST be provided only via a single stringified JSON assigned to `input`

### Requirement: Recovery from flat tool arguments

When structured tool arguments are **empty** or **missing required contract fields**, the system MUST recover parameter data if the runtime tool call arguments object contains flat keys matching the contract **or** legacy nested `input` string, by merging those values into the same normalization path used for successful API invocation.

#### Scenario: Flat args present when structured payload is empty

- **WHEN** the tool implementation receives an empty object or missing required keys after Zod parsing
- **AND** the tool runtime exposes original call arguments with contract keys at the top level, or a legacy string `input` in `args`
- **THEN** the system MUST merge those arguments into the payload used for validation and HTTP execution
- **AND** the system MUST NOT discard non-empty structured fields in favor of empty legacy data without defined precedence

### Requirement: Full disclosure without REQUIRE_PARAMETERS round-trip

The system MUST NOT rely on a `REQUIRE_PARAMETERS` (or equivalent) **second-call** progressive disclosure flow for API extension skills. The tool’s **description** and structured `parameters` MUST expose **full** contract fields and skill intent in one pass; missing or invalid parameters MUST surface as **Zod** and/or **Ajv** validation errors on the tool result, not as a dedicated “please call again with parameters” empty-handshake response.

#### Scenario: Single-call success path

- **WHEN** the LLM issues a tool call with all required structured fields valid per contract
- **THEN** the system proceeds without requiring a prior `REQUIRE_PARAMETERS` response

#### Scenario: Missing parameters

- **WHEN** required fields are missing or invalid
- **THEN** the system returns validation errors to the LLM
- **AND** MUST NOT require a separate progressive-disclosure round that only returns documentation without executing validation logic

### Requirement: 异步 API 任务必须 fire-and-forget 立即返回

When the agent invokes an extension API skill with `asyncPoll` configured (either `pollStrategy: SINGLE_CALL` or `pollStrategy: PERIODIC` / unset), the system MUST submit the task to the gateway and return control to the LLM **immediately** without waiting for the upstream task to reach a terminal state (COMPLETED / FAILED / TIMEOUT).

#### Scenario: SINGLE_CALL 模式立即返回
- **WHEN** LLM 调用带 `asyncPoll.pollStrategy = "SINGLE_CALL"` 的 API skill
- **THEN** agent-core 提交到 gateway 后**立即**返回
- **AND** 返回值 SHALL 包含 `status: "SINGLE_CALLED"` 和 note 告知"任务在后台跑、结果进通知中心"
- **AND** agent-core MUST NOT 同步调用 `/api/skills/async-tasks/{id}/wait` 阻塞 LLM

#### Scenario: PERIODIC 模式立即返回
- **WHEN** LLM 调用带 `asyncPoll.pollEndpoint` 的 API skill（即 PERIODIC 模式）
- **THEN** agent-core 提交到 gateway 后**立即**返回
- **AND** 返回值 SHALL 包含 `status: "POLLING"` 和 note 告知"任务在后台轮询、结果进通知中心"
- **AND** agent-core MUST NOT 同步调用 `/api/skills/async-tasks/{id}/wait` 阻塞 LLM

#### Scenario: 用户体验一致性
- **WHEN** 用户在前端对话窗口触发任意异步 API skill（SINGLE_CALL 或 PERIODIC）
- **THEN** LLM 响应延迟 SHALL 仅取决于 `submit → gateway` 的网络时间（亚秒级）
- **AND** 前端 SHALL NOT 出现"转圈圈等待任务完成"的卡顿
- **AND** 异步任务 SHALL 立即出现在通知中心（`/api/async-tasks/my`）

#### Scenario: 任务进度由通知中心接管
- **WHEN** 异步任务在 gateway 后台运行（PENDING → POLLING → COMPLETED/FAILED/TIMEOUT）
- **THEN** 通知中心 SHALL 实时反映状态变更（前端轮询 5s 一次）
- **AND** LLM 不再需要返回"任务结果"——结果由通知中心推送给用户

#### Scenario: 审计日志标记 fire-and-forget
- **WHEN** agent-core 提交异步任务后立即返回
- **THEN** audit log SHALL 在 `AGENT_REQUEST` 阶段记录 `fireAndForget: true`
- **AND** audit log SHALL 在 `extraJson` 字段记录 `pollStrategy`（SINGLE_CALL 或 PERIODIC）

### Requirement: 异步任务通知中心时间字段按 Asia/Shanghai 序列化为 ISO 8601 字符串

异步任务通知中心（`/api/async-tasks/my`）返回的 `startedAt` / `completedAt` / `createdAt` / `notifiedAt` 字段，MUST 按 Asia/Shanghai 时区序列化为 ISO 8601 字符串（pattern `yyyy-MM-dd'T'HH:mm:ssXXX`，例：`2026-06-10T10:54:58+08:00`），由前端 `utils/datetime.ts` 的 `parseBackendTimeAsUtc` 反序列化为本地时间显示。

#### Scenario: 字段类型必须是 String 而不是 LocalDateTime

- **WHEN** `AsyncTaskNotificationDto.from(AsyncTask, ...)` 被调用
- **THEN** 时间字段（MUST）赋值 String 类型（不是 `java.time.LocalDateTime`）
- **AND**（MUST）通过 `formatShanghai(LocalDateTime)` 静态方法格式化输出
- **AND** null 时间字段（MUST）序列化为 JSON `null`（不是空字符串、不是 timestamp 数组）

#### Scenario: DTO 不依赖 Spring Boot auto-config 的 JavaTimeModule

- **WHEN** 项目以 Spring Boot 2.7+ 启动
- **THEN** `JacksonConfig`（MUST）提供 `@Primary @Bean ObjectMapper` 显式注册 `JavaTimeModule`
- **AND** 即使 `JavaTimeModule` 未生效（auto-config 失败场景），DTO 时间字段（MUST）依然能正常序列化（因为已经是 String）

### Requirement: Skill entity 时间字段按 Asia/Shanghai 格式化

`Skill` entity 的 `createdAt` / `updatedAt` 字段（MUST）加 `@JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss", timezone="Asia/Shanghai")` 注解，让 Jackson 走 JSR-310 serializer 输出 `"2026-06-01T10:33:40"` 格式字符串。pattern **不能**带 `XXX`（因为 `LocalDateTime` 本身不带 timezone offset，带 `XXX` 会触发 Jackson 的 `Unsupported field: OffsetSeconds` bug）。

#### Scenario: /api/skills 返回的 Skill 列表

- **WHEN** 调用 `GET /api/skills` 返回技能列表
- **THEN** 每个 Skill 的 `createdAt` / `updatedAt` 字段 MUST 输出 `"2026-06-01T10:33:40"` 格式（不含 `+08:00` offset）
- **AND**（MUST）HTTP 200，不是 500

### Requirement: 异步任务终态时 agent-core 转发到 gateway 内部 API

异步 API 任务（SINGLE_CALL / PERIODIC）到达终态时，agent-core MUST 在现有"通知中心推送"基础上，**新增**一条最小化行为：

调 gateway 内部 API `POST /api/internal/async-task/echo-to-chat`（gateway 负责写对话消息 + 调 LLM 续答）。

按 **AGENTS.md 5.5 "尽量不改 agent-core 代码" 规约**，agent-core **不**写对话消息、**不**调 LLM，只做 1 行转发。

详细行为定义见 `specs/async-task-chat-reply/spec.md`（新增 capability）。本 requirement 只声明"老异步任务系统现在要承担额外的 gateway 内部 API 调用"。

#### Scenario: 终态处理从"只推通知"变成"推通知 + 转发 gateway"
- **WHEN** 一个异步任务到达 SUCCESS / FAILED / TIMEOUT 终态
- **THEN** `AsyncTaskPollingScheduler.terminalState()` 末尾 MUST 调 `this.gatewayClient.echoToChat(task)`（HTTP 异步）
- **AND** 调用 MUST 在 try/catch 块内
- **AND** 调用 MUST 设置 2s 超时
- **AND** 失败 MUST catch 并只记 log，MUST NOT 抛回轮询线程
- **AND** 现有 `auditLog` + `notifySse` 逻辑 MUST 继续执行（不丢）

#### Scenario: 老 requirement 不变
- **WHEN** gateway 内部 API 调用失败
- **THEN** 现有"fire-and-forget 立即返回"行为 MUST 不变
- **AND** 现有"通知中心推 SSE"行为 MUST 不变
- **AND** 现有"audit log"行为 MUST 不变

#### Scenario: agent-core 不在本端做对话相关工作
- **WHEN** 异步任务终态处理
- **THEN** agent-core MUST NOT 写 `chat_messages` 表
- **AND** agent-core MUST NOT 调 LLM
- **AND** agent-core MUST NOT 新增 Service / 新模块（除了 GatewayClient + 1 行调用）

#### Scenario: gateway 异步任务 callback 协议扩展
- **WHEN** agent-core 调 gateway 创建异步任务
- **THEN** callback 载荷 SHOULD 包含 `sessionId` + `chatId`（agent-core 用来关联对话）
- **AND** 老 callback（不带 sessionId / chatId）MUST 兼容（gateway 跳过对话回灌逻辑，通知中心 / audit log 照常）
- **AND** gateway MUST NOT 强制要求这两个字段（MUST 后向兼容）

#### Scenario: gateway 内部 API 调用安全
- **WHEN** agent-core 调 `POST /api/internal/async-task/echo-to-chat`
- **THEN** 请求 MUST 带 `X-Internal-Token` header
- **AND** token 配置在 agent-core 配置文件里（与 gateway `app.internal-api.token` 对应）
- **AND** gateway 校验失败 MUST 返回 401

