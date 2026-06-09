## MODIFIED Requirements

### Requirement: 异步 API 任务执行路径从 agent-core 迁移到 Skill Execution Service

当 agent 调用扩展 API Skill 并配置了 `asyncPoll` 时，SINGLE_CALL 和 PERIODIC 两种策略的**核心执行逻辑 SHALL 在 SkillExecutionService 中实现**，而非在 agent-core 的 `java-skills.ts` 中。

agent-core 侧的扩展 Skill 工具 `func()` SHALL 统一通过 `POST /api/skills/execute` 调用 gateway，不在 agent-core 内部区分异步/同步路径。

#### Scenario: SINGLE_CALL 模式 — 创建 AsyncTask → submitSingleCall → 立即返回
- **WHEN** LLM 调用带 `asyncPoll.pollStrategy = "SINGLE_CALL"` 的 API skill
- **THEN** agent-core 通过 `POST /api/skills/execute` 提交到 SkillController
- **AND** SkillController 委托给 `SkillExecutionService.execute()`
- **AND** `SkillExecutionService.executeApiSkillAsync()` SHALL 读取 `singleCallReadTimeoutSeconds`（默认 600）
- **AND** SHALL 创建 `AsyncTask`（status=PENDING, pollStrategy="SINGLE_CALL", requestBody 为原 API 请求参数）
- **AND** SHALL 调用 `asyncTaskPollingScheduler.submitSingleCall(task)` 提交到 CachedThreadPool
- **AND** SHALL 立即返回 `{ status: "SINGLE_CALLED", asyncTaskId, note: "后台处理中" }`
- **AND** SHALL NOT 同步等待 `future.get()` 阻塞 agent

#### Scenario: PERIODIC 模式 — 调第三方 → extractTaskId → registerPeriodic → 立即返回
- **WHEN** LLM 调用带 `asyncPoll.pollEndpoint` 的 API skill（PERIODIC 模式）
- **THEN** agent-core 通过 `POST /api/skills/execute` 提交
- **AND** `SkillExecutionService.executeApiSkillAsync()` SHALL 先同步调第三方获取 `externalTaskId`
- **AND** SHALL 创建 `AsyncTask`（pollStrategy="PERIODIC" 或 null, pollEndpoint, externalTaskId 等）
- **AND** SHALL 调用 `asyncTaskPollingScheduler.registerPeriodic(task)` 注册到 FixedThreadPool
- **AND** SHALL 立即返回 `{ status: "POLLING", asyncTaskId, note: "后台轮询中" }`
- **AND** SHALL NOT 同步等待 `future.get()` 阻塞 agent

#### Scenario: 同步 API skill 直通
- **WHEN** LLM 调用不带 `asyncPoll` 的 API skill
- **THEN** agent-core 通过 `POST /api/skills/execute` 提交
- **AND** SkillExecutionService 走 `execute()` → `kind="api"` → `executeApiSkill()` 同步返回结果
- **AND** agent-core 的 java-skills.ts func() 直接透传响应给 LLM

### Requirement: SINGLE_CALL 模式下的 AsyncPollConfig 类型定义

`AsyncPollConfig` 接口 SHALL 包含 `pollStrategy` 和 `singleCallReadTimeoutSeconds` 字段，Zod schema SHALL 同步包含，以支持 agent-core 侧的类型安全。

#### Scenario: AsyncPollConfig 接口包含完整字段
- **WHEN** TypeScript 代码引用 `AsyncPollConfig`
- **THEN** `java-skills.ts` 中的接口 SHALL 包含 `pollStrategy?: "PERIODIC" | "SINGLE_CALL"`
- **AND** SHALL 包含 `singleCallReadTimeoutSeconds?: number`
- **AND** 已有字段（`pollEndpoint`、`idJsonPath`、`maxWaitSeconds` 等）保持不变

#### Scenario: skill-generator Zod schema 包含完整字段
- **WHEN** skill-generator.ts 的 `skillGeneratorAsyncPollSchema` 被验证
- **THEN** schema SHALL 包含 `pollStrategy: z.enum(["PERIODIC", "SINGLE_CALL"]).optional()`
- **AND** SHALL 包含 `singleCallReadTimeoutSeconds: z.number().int().min(1).optional()`

### Requirement: AGENT_STREAMING 流式开关

系统 SHALL 支持通过 `AGENT_STREAMING` 环境变量控制 LLM 流式输出开关，默认开启。

#### Scenario: 默认流式输出
- **WHEN** 环境变量 `AGENT_STREAMING` 未设置或设为 `"true"`
- **THEN** `new ChatOpenAI({ streaming: true, ... })` 启用流式

#### Scenario: 关闭流式输出
- **WHEN** 环境变量 `AGENT_STREAMING` 设为 `"false"`
- **THEN** `new ChatOpenAI({ streaming: false, ... })` 关闭流式

## ADDED Requirements

### Requirement: DeepSeek 模型兼容 — discriminatedUnion 改为扁平 Zod schema

系统 SHALL 使用扁平 `z.object()` 替代 `z.discriminatedUnion()` 定义 `skillGeneratorToolInputSchema`，以确保 DeepSeek 等不支持 JSON Schema `oneOf`/`discriminatedUnion` 的模型可以正常调用 skill generator tool。

#### Scenario: skill generator 使用扁平 schema
- **WHEN** `JavaSkillGeneratorTool` 注册为 LangChain tool
- **THEN** 其 Zod schema SHALL 为 `z.object({ targetType: z.enum([...]), ... })` 扁平结构
- **AND** SHALL NOT 使用 `z.discriminatedUnion()`

#### Scenario: 扩展 Skill tool 的 Zod schema 包含 type: object
- **WHEN** 扩展 Skill 的 Zod schema 被序列化为 JSON Schema
- **THEN** 顶层 SHALL 包含 `type: "object"`
- **AND** 实现方式为 `ensureObjectType<T>()` 包裹原始 Zod schema

### Requirement: RequestSignature 去重签名在 SkillExecutionService 中执行

当 `SkillExecutionService.executeApiSkillAsync()` 处理 PERIODIC 异步任务时，系统 SHALL 在调用第三方 API 之前计算请求签名并进行 session 维度去重，以防止重复提交。

#### Scenario: 计算请求签名
- **WHEN** 异步任务进入 PERIODIC 分支
- **THEN** 系统 SHALL 调用 `RequestSignatureUtil.compute(method, url, body, idJsonPath, pollMethod, pollEndpoint, userId)`
- **AND** 签名 SHALL 基于 SHA-256

#### Scenario: per-session 去重
- **WHEN** 请求包含 `sessionId` 且 1 小时内存在相同签名的 PENDING/POLLING 任务
- **THEN** 系统 SHALL 返回已有任务的 `asyncTaskId` 和 `externalTaskId`
- **AND** SHALL NOT 发起新的第三方请求

#### Scenario: no-session 去重
- **WHEN** 请求不包含 `sessionId` 且 60 秒内存在相同签名的任务
- **THEN** 系统 SHALL 返回已有任务
- **AND** SHALL NOT 发起新的第三方请求
