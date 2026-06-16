# bxdcbot-multi-turn-async-fanout Specification

## Purpose
TBD - created by archiving change bxdcbot-multi-turn-async. Update Purpose after archive.
## Requirements
### Requirement: Bxdcbot 多轮子规划可调 5-10 个异步 skill

Bxdcbot 自主规划 skill 内部 LLM 在单次 run 内 MUST 能发起 5-10 个 `SINGLE_CALL` 或 `PERIODIC` 异步 skill（fanout 模式），并行提交到 gateway。所有异步任务 MUST 走 fire-and-forget 立即返回路径（`POST /api/skills/execute`），子规划 LLM MUST NOT 阻塞等待单个异步任务完成。

#### Scenario: Bxdcbot LLM 一次性发起 1 个 async（N=1 边界）
- **WHEN** Bxdcbot 内部 LLM 在某轮决定调 **1 个** async skill
- **THEN** agent-core MUST 走**同一套**逻辑（不分支"single" vs "fanout"）
- **AND** agent-core MUST 调 gateway `/api/skills/execute`
- **AND** 拿到 `{asyncTaskId, status: "POLLING"|"SINGLE_CALLED"}` 占位
- **AND** agent-core MUST 把 asyncTaskId 记录到当前 BxdcbotRun.pendingAsyncTaskIds（此时 size=1）
- **AND** BxdcbotRun.asyncTaskIdToToolCallId MUST 记录 `asyncTaskId ↔ tool_call_id` 关联（漏洞 3）
- **AND** pendingAsyncTaskIds.size > 0 时 MUST 退出当前 6 轮循环
- **AND** BxdcbotRun.status 置 `awaiting_async`
- **AND** BxdcbotRunScheduler 监听到这 1 个 async 完成时 MUST 注入真结果 + 重新 invoke
- **AND** N=1 走同 scheduler 路径（scheduler 不感知 N 大小）

#### Scenario: Bxdcbot LLM 一次性发起 5-10 个 async
- **WHEN** Bxdcbot 内部 LLM 在某轮决定调 5-10 个 async skill
- **THEN** agent-core 必须在该轮内**并行**调 gateway `/api/skills/execute`
- **AND** 每个 async 调完立即返回 `{asyncTaskId, status: "POLLING"|"SINGLE_CALLED"}` 占位
- **AND** agent-core MUST 把每个 asyncTaskId 记录到当前 BxdcbotRun.pendingAsyncTaskIds
- **AND** BxdcbotRun.asyncTaskIdToToolCallId MUST 记录每个 `asyncTaskId ↔ tool_call_id` 关联

#### Scenario: Bxdcbot 单次 run 调 async 数量上限
- **WHEN** Bxdcbot 内部 LLM 一次性发起 N 个 async skill
- **THEN** agent-core MUST 允许 **1 ≤ N ≤ 10**
- **AND** N=1 和 N=5-10 走**同一套代码路径**——不在 BxdcbotRunScheduler / asyncTaskIdToToolCallId / pendingAsyncTaskIds / message 注入逻辑上区分
- **AND** N > 10 时 SHOULD 在 system prompt 提示 LLM"超过 10 个任务会让状态机复杂化"

#### Scenario: Bxdcbot 调 sync skill 与 async 混合
- **WHEN** Bxdcbot LLM 在某轮同时调 sync skill 和 async skill
- **THEN** sync skill 立即执行并把结果注入 messages
- **AND** async skill 走占位返回路径
- **AND** 下一轮 LLM 看到 messages 里有 sync 真结果 + async 占位

### Requirement: Bxdcbot 遇 async 占位时挂起当前子规划周期

Bxdcbot 6 轮子规划循环检测到当前轮 LLM 调了 async skill 时 MUST 挂起当前周期，不再让 LLM 调新 skill；待所有 pending async 完成后 MUST 重新启动新周期，让 LLM 看到累积的 async 真结果再规划。

#### Scenario: 当前周期检测到 async 调用
- **WHEN** 6 轮子规划循环检测到 `pendingAsyncTaskIds.size > 0`
- **THEN** agent-core MUST 立即退出当前 6 轮循环
- **AND** BxdcbotRun.status MUST 置为 `awaiting_async`
- **AND** BxdcbotRun MUST 保留所有累积的 messages（含 tool calls + tool results + 占位结果）

#### Scenario: 重新启动子规划周期
- **WHEN** BxdcbotRunScheduler 检测到 BxdcbotRun.pendingAsyncTaskIds 全部完成
- **THEN** scheduler MUST 把 BxdcbotRun.status 置为 `running`
- **AND** scheduler MUST 重新 invoke `executeOpenClawSkill` 进入新一个 6 轮周期
- **AND** 新周期 LLM 看到 BxdcbotRun.messages（含已完成 async 的真结果）作为初始 context

#### Scenario: 跨周期 LLM 看到 async 真结果
- **WHEN** 新周期 LLM 收到 messages
- **THEN** LLM MUST 看到所有已完成 async 任务的真实结果（作为 `tool` role 消息注入）
- **AND** LLM 基于真结果 MUST 能规划"依赖真实结果"的下游子任务

#### Scenario: Bxdcbot 单 run 总轮数上限
- **WHEN** BxdcbotRun.currentRound 累计达到 60
- **THEN** agent-core MUST 终止 run，置 status=`failed`，返回当前累积结果
- **AND** MUST NOT 继续 invoke 新周期
- **AND** 60 轮对应 10 个 6 轮子规划周期

#### Scenario: Bxdcbot 总轮数触发上限时外层 LLM 兜底
- **WHEN** Bxdcbot run 触达 60 轮上限
- **THEN** 外层 LLM 续答时 MUST 能看到"该 Bxdcbot run 已超 60 轮"的提示
- **AND** 外层 LLM 决定是否继续追问用户 / 重启新 run

### Requirement: 异步真结果以 tool 消息形式注入 messages

async task 到达终态时 agent-core MUST 把真实结果作为 `tool` role 消息注入到 BxdcbotRun.messages，用 `tool_call_id` 关联到 LLM 之前发起 async 时的 tool_call_id。

#### Scenario: async 完成后真结果注入
- **WHEN** 一个 BxdcbotRun.pendingAsyncTaskIds 中的 async task 到达终态
- **THEN** agent-core MUST 用 `BxdcbotRun.asyncTaskIdToToolCallId.get(asyncTaskId)` 查 tool_call_id（漏洞 3 修复）
- **AND** agent-core MUST 在 BxdcbotRun.messages 追加一条消息：
  ```
  { role: 'tool', tool_call_id: <查到的 tool_call_id>, 
    content: JSON.stringify({ asyncTaskId, status, result, finishedAt }) }
  ```
- **AND** BxdcbotRun.pendingAsyncTaskIds MUST 移除该 asyncTaskId
- **AND** BxdcbotRun.asyncTaskIdToToolCallId MUST 立即 `delete(asyncTaskId)`（避免 map 无限增长）

#### Scenario: async 超时时 tool 消息含 timeout 提示
- **WHEN** async task 因 `singleCallReadTimeoutSeconds` / `maxWaitSeconds` 触发超时
- **THEN** tool 消息 content MUST 包含 `status: "TIMEOUT"` + 已等秒数
- **AND** LLM 看到后 MUST 能决定"重试 / 跳过 / 报错"等后续动作

#### Scenario: async 失败时 tool 消息含 error 信息
- **WHEN** async task 到达 FAILED 终态（HTTP 4xx/5xx / 业务失败）
- **THEN** **决策 11 失败隔离生效**：tool 消息 MUST **不**注入 BxdcbotRun.messages（避免错误传染）
- **AND** agent-core BxdcbotRunScheduler MUST 走"自动重试 N 次 + 仍未成功则硬终止 Bxdcbot run"路径
- **AND** 用户在 Bxdcbot run 失败时看到 BXDCBOT_RUN_RESULT chat_message，content 含 `<skill_name> 失败 N 次后终止：<error>`

### Requirement: Skill 失败隔离（错误不传染到下游 skill）

Bxdcbot 内部调任意 skill（sync / async / 单次长调用）失败时 MUST 走"自动重试 N 次 + 仍未成功则硬终止 Bxdcbot run"路径，**失败结果 MUST NOT 注入 LLM messages**。这是用户硬性要求。

#### Scenario: async skill 失败 N 次后终止 Bxdcbot run
- **WHEN** 一个 async skill 到达 FAILED 终态
- **AND** `run.skillRetries.get(skillName) >= BXDCBOT_SKILL_MAX_RETRIES`（已重试 N 次仍失败）
- **THEN** agent-core MUST 立即终止 Bxdcbot run
- **AND** BxdcbotRun.status 置 `failed`
- **AND** BxdcbotRun.failureReason MUST 包含 `${skillName} 失败 N 次后终止：${error}`
- **AND** 失败结果 MUST **不**注入 BxdcbotRun.messages
- **AND** 调 `POST /api/internal/bxdcbot-run/complete` 回灌对话流，body 包含 `status=failed` + `failureReason`

#### Scenario: async skill 失败后自动重试
- **WHEN** 一个 async skill 到达 FAILED 终态
- **AND** `run.skillRetries.get(skillName) < BXDCBOT_SKILL_MAX_RETRIES`（还有重试机会）
- **THEN** agent-core MUST 自动重试该 skill（调 gateway 重新发起同 args 同 payload 的新 task）
- **AND** 重试用新 asyncTaskId（原失败 taskId 状态保留在 `async_tasks` 表用于审计）
- **AND** 新 task 加入 BxdcbotRun.pendingAsyncTaskIds
- **AND** `run.skillRetries.set(skillName, retriesSoFar + 1)`
- **AND** 失败结果 MUST **不**注入 messages
- **AND** Bxdcbot 内部 LLM MUST **不**感知"在重试"（LLM 不调、不等、不 token 消耗）

#### Scenario: sync skill 失败也走自动重试
- **WHEN** Bxdcbot 调 sync skill（compute / server_lookup / 同步 API）失败
- **THEN** agent-core MUST 走**同一套**重试逻辑（不区分 sync / async）
- **AND** `run.skillRetries` 用于 sync skill 也算重试
- **AND** sync skill 失败结果 MUST **不**注入 messages

#### Scenario: 重试成功后 LLM 看到的是真结果
- **WHEN** 一个 skill 重试 N 次后**第 N+1 次成功**
- **THEN** BxdcbotRun.messages MUST 注入**仅一次**成功真结果（tool message）
- **AND** BxdcbotRun.messages MUST **不**包含任何失败记录（中间 N 次失败不污染 context）
- **AND** `run.skillRetries.delete(skillName)`（成功完成后清理计数器，避免跨 skill 累积）

#### Scenario: 重试不消耗 Bxdcbot 60 轮上限
- **WHEN** skill 失败触发自动重试
- **THEN** BxdcbotRun.currentRound MUST **不**增加
- **AND** BxdcbotRun.totalLlmCalls MUST **不**增加
- **AND** 重试是同步等待的（agent-core scheduler 内部循环），**不**走 LLM 调用

#### Scenario: BXDCBOT_SKILL_MAX_RETRIES=0 禁用重试
- **WHEN** env `BXDCBOT_SKILL_MAX_RETRIES=0` 显式配置
- **THEN** 第一次 skill 失败 MUST 立即终止 Bxdcbot run（不重试）
- **AND** 适用场景：用户希望"严格模式"，任何失败立即终止

#### Scenario: BXDCBOT_SKILL_MAX_RETRIES 默认值
- **WHEN** env `BXDCBOT_SKILL_MAX_RETRIES` 未设置
- **THEN** MUST 默认走 3 次重试（沿用项目"配置缺省值"约定，AGENTS.md 5.2）

#### Scenario: 失败原因 audit
- **WHEN** 一个 skill 失败并触发重试
- **THEN** agent-core MUST 记 log：`level=WARN, msg="bxdcbot skill failed, retrying", skillName, asyncTaskId, retryCount, error`
- **AND** 重试成功记 log：`level=INFO, msg="bxdcbot skill retry success", skillName, newAsyncTaskId, retryCount`
- **AND** 重试用尽记 log：`level=ERROR, msg="bxdcbot skill failed N times, terminating run", skillName, runId`

#### Scenario: 重试过程对前端 / 通知中心透明
- **WHEN** skill 失败触发自动重试
- **THEN** 通知中心 `/api/async-tasks/my` 列表显示**新 taskId**（重试的）+ 老 taskId 状态为 FAILED
- **AND** 用户能看到"重试"行为（通过 taskId 列表 + status 变化）
- **AND** 对话流 MUST **不**显示中间失败消息（避免消息爆炸）

### Requirement: Bxdcbot system prompt 必须约束不要基于占位调下游

Bxdcbot 内部 LLM system prompt MUST 包含 1 行强约束规则，防止 LLM 基于 async 占位结果规划下游子任务。

#### Scenario: system prompt 包含占位警告
- **WHEN** agent-core 拼装 Bxdcbot 子规划 system message
- **THEN** system message MUST 包含：
  ```
  ⚠️ 异步 skill 调完返回 {asyncTaskId, status: "POLLING"|"SINGLE_CALLED"} 是占位。
     不要基于占位调下游子任务。如需依赖真实结果，可结束当前轮等真结果注入后再规划。
  ```
- **AND** 该规则 MUST 放在 system message 顶部（LIM 第一眼看到）

#### Scenario: invokeToolDirect 检测 LLM 不遵守规则
- **WHEN** Bxdcbot 内部 LLM 调 async 占位 skill 后**又**调 sync skill
- **THEN** agent-core SHOULD 仍执行 sync skill（不阻拦）
- **AND** 但 tool result SHOULD 加 warning "⚠️ 上一步 async 任务还在跑，本工具结果可能不准确"
- **AND** 这是软约束（不阻塞），依赖 LLM 自我遵守 system prompt

### Requirement: Bxdcbot Run 状态机持久化

agent-core MUST 维护一个 in-memory Map 持久化所有正在跑的 Bxdcbot run，支持跨子规划周期访问。

#### Scenario: BxdcbotRunStore 维护 run 状态
- **WHEN** agent-core 创建或更新一个 BxdcbotRun
- **THEN** BxdcbotRunStore MUST 保存到 `Map<runId, BxdcbotRun>` in-memory
- **AND** BxdcbotRun MUST 包含字段：
  - `runId` (UUID v4)
  - `conversationId`
  - `userId`
  - `parentToolId` (BxdcbotRun.runId 的副本，对应 `async_tasks.parent_tool_id`)
  - `parentSkillId` (Bxdcbot skill_id，对应 `async_tasks.parent_skill_id`)
  - `messages` (LangChain messages 累积)
  - `pendingAsyncTaskIds: Set<number>` (当前 6 轮周期待等的 async)
  - **`asyncTaskIdToToolCallId: Map<number, string>`**（漏洞 3 修复，async ↔ tool_call_id 关联）
  - **`skillRetries: Map<string, number>`**（决策 11 失败隔离，每 skill 重试次数，0 = 还没重试）
  - **`originalSkillArgs: Map<string, any>`**（决策 11 失败隔离，skillName → 原 args，重试时复用）
  - `currentRound` (跨周期计数，不超过 60)
  - `totalLlmCalls` (总 LLM 调用次数)
  - `startedAt` (Date)
  - `finishedAt?` (Date, completed / failed / 60 轮触顶时填)
  - `status` (`running` / `awaiting_async` / `completed` / `failed` / `timeout`)
  - `result?` (最终 LLM 输出，completed 时填)
  - `failureReason?` (failed 时填)
  - `subTaskSummary?` (run 终结时统计) / asyncTaskIdToToolCallId
- **AND** `asyncTaskIdToToolCallId: Map<number, string>` MUST 维护 asyncTaskId 到 LLM 调 async 时生成的 tool_call_id 的映射（漏洞 3 修复）
- **AND** `invokeToolDirect` 调 async skill 拿到 asyncTaskId 时 MUST 写入该 map
- **AND** BxdcbotRunScheduler 拿到 async 真结果时 MUST 用该 map 查 tool_call_id 注入 tool 消息
- **AND** tool 消息注入完成后 MUST 从 map 删除该 asyncTaskId 条目（避免内存泄漏）

#### Scenario: BxdcbotRun 在 agent-core 重启后丢失
- **WHEN** agent-core 进程重启
- **THEN** in-memory Map 全部丢失
- **AND** 正在跑的 Bxdcbot run 状态不可恢复（MVP 阶段 in-memory 不持久化）
- **AND** 丢失的 run 对应的外层对话流由外层 LLM 续答兜底（用户看到"任务已启动，但 agent 重启，等结果回来"）

#### Scenario: BxdcbotRun status 状态机
- **WHEN** Bxdcbot run 生命周期变化
- **THEN** status 流转 MUST 是：`running` → `awaiting_async` → `running`（重入） → `completed` / `failed` / `timeout`
- **AND** `completed` 终态：run 跑完，result 已填
- **AND** `failed` 终态：run 异常终止（如 LLM 调用失败 / 60 轮触顶）
- **AND** `timeout` 终态：run 总时长超阈值（MVP 阶段不设全局超时，仅按 60 轮控制）

### Requirement: BxdcbotRunScheduler 后台扫描

agent-core MUST 有后台 scheduler 每 2 秒扫一次 `awaiting_async` 状态的 BxdcbotRun，监听所有 pending async 任务的完成事件。

#### Scenario: scheduler 监听 pending 完成
- **WHEN** scheduler 每 2 秒扫描 awaiting_async run
- **THEN** 对每个 run，scheduler MUST 调 gateway `/api/async-tasks/{id}/wait?timeout=2` 阻塞监听
- **AND** 任一 async 任务完成时 MUST 把真结果注入 BxdcbotRun.messages
- **AND** 所有 pending 任务完成时 MUST 重新 invoke `executeOpenClawSkill` 进入新周期

#### Scenario: scheduler 单 run 超时检测
- **WHEN** 单 async 任务等待时间 > `singleCallReadTimeoutSeconds` / `maxWaitSeconds`
- **THEN** gateway wait 端点返回 TIMEOUT 状态
- **AND** agent-core MUST 把 TIMEOUT 作为 tool result 注入 BxdcbotRun.messages
- **AND** agent-core MUST 移除该 taskId from pendingAsyncTaskIds

#### Scenario: scheduler 跨周期计数
- **WHEN** scheduler 重新 invoke `executeOpenClawSkill` 进入新周期
- **THEN** BxdcbotRun.currentRound MUST +1
- **AND** BxdcbotRun.totalLlmCalls MUST 累加新周期的 LLM 调用次数
- **AND** currentRound >= 60 时 MUST 终止 run，status=`failed`

### Requirement: Bxdcbot 同步路径行为不变

Bxdcbot 子规划不调 async skill（纯 sync 场景）的执行行为 MUST 不变，本次 Bxdcbot 多轮子规划改造 MUST NOT 引入任何回归。

#### Scenario: 纯 sync 场景 Bxdcbot 行为不变
- **WHEN** Bxdcbot 内部 LLM 在 6 轮内**只**调 sync skill（compute / server_lookup / 同步 API）
- **THEN** Bxdcbot 6 轮跑完立即返回 result
- **AND** BxdcbotRun.currentRound = 1（一个 6 轮周期）
- **AND** BxdcbotRun.pendingAsyncTaskIds 始终为空
- **AND** BxdcbotRunScheduler 不介入（run status 始终 `running` → `completed`）

#### Scenario: 其他 skill 类型（compute / server_lookup / api_caller）的执行路径不变
- **WHEN** agent-core 执行非 Bxdcbot 类型的 skill
- **THEN** executeOpenClawSkill 改造 MUST NOT 影响其他 skill 执行
- **AND** compute / server_lookup / 同步 API 调用的代码路径 MUST 100% 保持

### Requirement: Bxdcbot 续答时不要重复调 skill

Bxdcbot run 完结后，外层 LLM 续答（archive 2026-06-12 路径）调 LLM 解释 async 结果时 MUST NOT 触发 tool call，包括 Bxdcbot 自身。

#### Scenario: LLM 续答不调 Bxdcbot
- **WHEN** gateway 收到 Bxdcbot run 子 async 任务的 echo-to-chat 请求
- **AND** async task 来自 Bxdcbot（`parent_tool_id IS NOT NULL`）
- **THEN** LLM 续答 prompt MUST 加一行 "如果 async 任务有 parent_tool_id（即来自 Bxdcbot），续答时不要重复调任何 skill（包括 Bxdcbot 自身），避免嵌套等待"
- **AND** LLM MUST NOT 触发 tool call

#### Scenario: 用户主动接力
- **WHEN** Bxdcbot 续答完成（LLM 总结完所有 async 真结果）
- **AND** 用户看到对话流中"Bxdcbot 已完成"消息
- **THEN** 用户 MUST 可主动追问
- **AND** 外层 LLM 自然接力调 Bxdcbot（如果用户想基于真结果再规划）
- **AND** 这一次是"完整 Bxdcbot 跑"，不是续答中的递归

