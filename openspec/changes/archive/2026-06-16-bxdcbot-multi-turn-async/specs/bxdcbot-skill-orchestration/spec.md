## ADDED Requirements

### Requirement: Bxdcbot 跨多周期子规划支持

Bxdcbot 自主规划 skill 内部 LLM 调 async skill（`SINGLE_CALL` / `PERIODIC`）时 MUST 走多周期子规划机制：
1. 第 1 个 6 轮子规划周期内 LLM 调 async → agent-core 把 asyncTaskId 记录到 BxdcbotRun.pendingAsyncTaskIds
2. 当前 6 轮周期立即退出（不再让 LLM 调新 skill），BxdcbotRun.status 置 `awaiting_async`
3. BxdcbotRunScheduler 后台监听所有 pending async 任务完成
4. 全部完成时 scheduler 重新 invoke `executeOpenClawSkill` 进入新一个 6 轮周期
5. 新周期 LLM 看到 BxdcbotRun.messages（含已完成 async 真结果 + 历史 tool calls + 历史 tool results）作为初始 context

详见 `bxdcbot-multi-turn-async-fanout/spec.md`。本 requirement 只声明"Bxdcbot 现有执行模型必须支持多周期子规划"。

#### Scenario: 现有 6 轮同步循环扩展为多周期
- **WHEN** Bxdcbot 内部 LLM 在某轮调了 async skill
- **THEN** agent-core MUST 把当前 6 轮循环的退出条件从"6 轮跑完"扩展为"6 轮跑完 OR pendingAsyncTaskIds.size > 0"
- **AND** 检测到 async 调用时 MUST 立即退出当前 6 轮循环
- **AND** BxdcbotRun.status MUST 置 `awaiting_async`

#### Scenario: 重新 invoke 进入新周期
- **WHEN** BxdcbotRunScheduler 检测到 BxdcbotRun.pendingAsyncTaskIds 全部完成
- **THEN** scheduler MUST 重新调用 `executeOpenClawSkill` 进入新一个 6 轮周期
- **AND** BxdcbotRun.currentRound MUST +1
- **AND** BxdcbotRun.status MUST 置回 `running`
- **AND** BxdcbotRun.messages MUST 保留所有历史（含 tool calls + tool results + async 真结果）

#### Scenario: 60 轮总上限
- **WHEN** BxdcbotRun.currentRound 累计到 60
- **THEN** agent-core MUST 终止 run，status=`failed`
- **AND** 60 轮对应 10 个 6 轮子规划周期
- **AND** 不继续 invoke 新周期

#### Scenario: 纯 sync 场景 Bxdcbot 行为不变
- **WHEN** Bxdcbot 内部 LLM 6 轮内**只**调 sync skill
- **THEN** 行为 MUST 100% 与本次改造前一致
- **AND** BxdcbotRun.currentRound = 1（一个 6 轮周期）
- **AND** BxdcbotRun.pendingAsyncTaskIds 始终为空
- **AND** 现有 6 轮跑完立即返回 result 的行为 MUST 不变

### Requirement: Bxdcbot Run 状态机持久化（in-memory）

agent-core MUST 维护一个 in-memory Map 持久化所有正在跑的 Bxdcbot run，支持跨子规划周期访问。

#### Scenario: BxdcbotRunStore 维护 run 状态
- **WHEN** agent-core 创建或更新一个 BxdcbotRun
- **THEN** BxdcbotRunStore MUST 保存到 `Map<runId, BxdcbotRun>` in-memory
- **AND** BxdcbotRun MUST 包含字段：
  - `runId` (UUID v4)
  - `conversationId`
  - `userId`
  - `parentToolId` (外层 tool call_id)
  - `parentSkillId` (Bxdcbot skill_id)
  - `messages` (LangChain messages 累积)
  - `pendingAsyncTaskIds: Set<number>`
  - **`asyncTaskIdToToolCallId: Map<number, string>`**（漏洞 3 修复）
  - **`skillRetries: Map<string, number>`**（决策 11 失败隔离）
  - **`originalSkillArgs: Map<string, any>`**（决策 11 失败隔离）
  - `currentRound` (跨周期计数，不超过 60)
  - `totalLlmCalls`
  - `startedAt` / `finishedAt?`
  - `status` (`running` / `awaiting_async` / `completed` / `failed` / `timeout`)
  - `result?` / `failureReason?` / `subTaskSummary?` / asyncTaskIdToToolCallId
- **AND** `asyncTaskIdToToolCallId: Map<number, string>` MUST 维护 asyncTaskId 到 tool_call_id 的映射（漏洞 3 修复）
- **AND** 调 async skill 拿到 asyncTaskId 时 MUST 写入该 map
- **AND** async 真结果注入 messages 时 MUST 用该 map 查 tool_call_id

#### Scenario: MVP 阶段 in-memory 不持久化
- **WHEN** agent-core 进程重启
- **THEN** in-memory Map 全部丢失
- **AND** 正在跑的 Bxdcbot run 状态不可恢复
- **AND** 丢失的 run 对应的外层对话流由外层 LLM 续答兜底

#### Scenario: BxdcbotRun status 状态机
- **WHEN** Bxdcbot run 生命周期变化
- **THEN** status 流转 MUST 是：`running` → `awaiting_async` → `running`（重入）→ `completed` / `failed` / `timeout`

### Requirement: Bxdcbot 调 async 时记录 parent_tool_id

Bxdcbot 调 async skill 时 MUST 把 `parentToolId`（BxdcbotRun.runId）和 `parentSkillId`（Bxdcbot skill_id）传给 gateway，让 gateway 在 `async_tasks` 表记录这俩字段。

#### Scenario: executeOpenClawSkill 调 async 时传 parentToolId
- **WHEN** Bxdcbot 内部 LLM 决定调 async skill（经 `invokeToolDirect`）
- **THEN** `invokeToolDirect` MUST 检测到是 async skill（POST `/api/skills/execute` 返回 POLLING/SINGLE_CALLED）
- **AND** MUST 把 `parentToolId = BxdcbotRun.runId` 和 `parentSkillId = Bxdcbot skill_id` 作为 payload 字段发给 gateway
- **AND** gateway MUST 把这俩字段持久化到 `async_tasks.parent_tool_id` / `async_tasks.parent_skill_id`

#### Scenario: async 任务在通知中心可被 parent_tool_id 过滤
- **WHEN** 前端调 `/api/async-tasks/my` 列表接口
- **THEN** 响应 JSON MUST 包含 `parentToolId` / `parentSkillId` 字段（可空）
- **AND** 前端可按 `parentToolId` 过滤出"Bxdcbot X run 的所有子 async"
