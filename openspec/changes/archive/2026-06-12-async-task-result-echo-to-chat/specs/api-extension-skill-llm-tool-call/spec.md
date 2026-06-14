## ADDED Requirements

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
