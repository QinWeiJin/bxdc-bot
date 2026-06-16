## Why

Bxdcbot 自主规划 skill（参见 `bxdcbot-skill-orchestration`）的子规划循环是**同步 ReAct**（最多 6 轮 LLM 串行），它可以调"扩展 API skill"，但是当扩展 skill 是 `SINGLE_CALL`（单次长调用 1~10 min）或 `PERIODIC`（异步轮询 1~30 min）异步任务时，Bxdcbot 子规划**只拿到占位返回**（`{asyncTaskId, status: "POLLING"|"SINGLE_CALLED"}`），拿不到真实结果，进而**没法**驱动"依赖上一任务真实结果"的下游子任务（如：A 调"订单查询"→ B 调"物流查询"以 A 的真实订单号作入参）。

更糟的是 Bxdcbot 内部 LLM 想基于异步真实结果规划下游时，Bxdcbot 6 轮循环结束（`executeOpenClawSkill` 方法返回、对象销毁），**没有挂起-恢复机制**把异步结果注回 Bxdcbot 内部 messages，Bxdcbot 上下文彻底断裂。

## What Changes

- **Bxdcbot 多轮子规划**：把"6 轮 LLM + 立即返回"改为"跨 6 轮子规划周期，每周期可调多个异步 skill；遇异步占位时挂起当前周期、等所有 pending async 完成后再启动新周期"。Bxdcbot 单次 run 最多 60 轮（10 个 6 轮子规划周期），单 async 超时复用 `async_tasks.singleCallReadTimeoutSeconds` / `maxWaitSeconds`（已有配置，不新增 env）
- **BxdcbotRun 状态机**：agent-core 内存里持久化 BxdcbotRun（含 messages / pendingAsyncTaskIds / currentRound），跨子规划周期保留上下文。MVP 阶段 in-memory（agent-core 重启即丢，丢失时由外层 LLM 续答兜底）。**注**：状态机类名 `BxdcbotRun` 暂保留（与代码函数名 `executeOpenClawSkill` 同源），待后续代码级重命名时一起改
- **异步真结果注入 messages**：async task 终态时，把真实结果作为 `tool` role 消息注入到 BxdcbotRun.messages 数组（用 `tool_call_id` 关联），Bxdcbot 下一周期 LLM 看到累积 messages 自然能基于真结果规划
- **async_tasks 表加 `parent_tool_id` / `parent_skill_id` 列**（Java migration 类，遵循 AGENTS.md 5.3），标识"这个 async 是 Bxdcbot run X 调的第 N 个子任务"
- **消息中心聚合视图**：通知中心列表项支持 `parent_tool_id` 过滤，Bxdcbot 那个外层 skill 的所有子 async 状态（PENDING / POLLING / SINGLE_CALLED / SUCCESS / FAILED / TIMEOUT）可展开看
- **对话流回显真结果**：每个子 async 终态时走  archive 2026-06-12 的 `async-task-chat-reply` 路径（写 chat_message + 推 SSE + LLM 续答），chat_message 带 `async_task_id` 让前端能识别"这条结果属于 Bxdcbot run X 的第 N 个子任务"
- **Bxdcbot system prompt 强约束**：1 行规则——"async skill 调完拿到 POLLING/SINGLE_CALLED 是占位，**不要**基于占位调下游；如需依赖真实结果，可结束当前轮等真结果"
- **Bxdcbot run 终态回灌对话流**：run 跑完（completed / failed / 60 轮触顶 / 单 run 全局超时）时 agent-core 调 gateway 内部 API `POST /api/internal/bxdcbot-run/complete`，gateway 写 `chat_messages` 一条 `source=BXDCBOT_RUN_RESULT` 的消息 + 推 SSE `message_inserted` 事件 + 触发外层 LLM 续答（用 BxdcbotRun.parentToolId 反查外层对话流拿"原 user 消息 + 原 tool call 摘要"作为续答 context）
- **Skill 失败隔离（用户硬性要求）**：Bxdcbot 内部调任意 skill（sync / async / 单次长调用）失败时，**失败结果 MUST NOT 注入 LLM messages**（避免错误传染到下游 skill）。BxdcbotRunScheduler 捕获失败后 MUST 走"自动重试 N 次 + 仍未成功则硬终止 Bxdcbot run"路径：
  - 重试在 LLM 外自动执行（agent-core scheduler 调 gateway 重新发起同 args 同 payload 的新 task，调 N 次）
  - 默认重试次数 `BXDCBOT_SKILL_MAX_RETRIES=3`（沿用项目 env 配置规范，AGENTS.md 5.2）
  - N 次全失败 → Bxdcbot run status=failed + failureReason="<skill_name> 失败 N 次后终止"+ 调 `/api/internal/bxdcbot-run/complete` 回灌
  - sync skill 失败也走同样重试（不只是 async）
  - 重试过程对 Bxdcbot 内部 LLM **透明**（LLM 不感知"在重试"，只看到"最终结果"或"run terminated"）

## Capabilities

### New Capabilities

- `bxdcbot-multi-turn-async-fanout`: Bxdcbot 多轮子规划 + 异步 fanout（同一周期可调 5-10 个 async skill）+ 消息中心聚合 + 对话流回显真结果

### Modified Capabilities

- `bxdcbot-skill-orchestration`: 现有 Bxdcbot 执行模型扩展 —— 6 轮同步循环 → 跨多周期子规划 + 等真结果 + 状态机管理
- `async-task-chat-reply`: 现有"异步任务对话回显"扩展 —— chat_message 加 `async_task_id` 已支持，加"Bxdcbot 子任务"标识（继承自 `async_tasks.parent_tool_id`）
- `api-extension-skill-llm-tool-call`: 现有异步任务数据模型扩展 —— `async_tasks` 表加 `parent_tool_id` + `parent_skill_id` 列

## Impact

按 **AGENTS.md 5.5 "尽量不改 agent-core（NestJS）代码" 规约**——本次 Bxdcbot 多轮子规划是 **LLM 调度层基础能力变更**，属于"无法走 Tool 接入"的范畴，需要明确评估侵入点：

- **agent-core（NestJS，必须侵入）**：
  - `executeOpenClawSkill` 改多周期（核心循环 6 轮内不变，外层加 `while (currentRound < maxRounds && pendingAsyncTaskIds.size > 0)`）
  - 新增 `BxdcbotRunStore`（in-memory Map，含 messages / pendingAsyncTaskIds / currentRound）
  - 新增 `BxdcbotRunScheduler`（后台每 2 秒扫 awaiting_async 的 run，监听 pending 完成）
  - 异步 skill 调完后，POST gateway `/api/async-tasks/{id}/wait`（已有端点）阻塞等待
  - async task 完成时把真结果塞回 BxdcbotRun.messages
  - **侵入点明确**：`src/tools/java-skills.ts::executeOpenClawSkill` + 1 个新 store + 1 个新 scheduler，**不**影响其他 skill 类型（compute / server_lookup / api_caller）的执行路径
  - **回归影响**：Bxdcbot 同步路径（不调 async 的纯 sync 场景）行为**不变**；新路径仅在 LLM 调 async 时激活
- **skill-gateway（Java，主战场）**：
  - 新增 `parent_tool_id` + `parent_skill_id` 列 migration（`AsyncTaskSchemaMigration` 类，参考 `SchemaMigrationRunner` 模式）
  - `AsyncTaskService.submit()` 入参新增可空 `parentToolId` / `parentSkillId` 字段
  - 现有 `/api/async-tasks/my` 接口返回 JSON 字段加 `parentToolId`（前端聚合用）
  - 现有 `/api/internal/async-task/echo-to-chat`（archive 2026-06-12 加的）已支持 `asyncTaskId` 入参 → 自动继承 `parent_tool_id`（join `async_tasks` 表）
  - 现有 LLM 续答 prompt 调整：context 里加一行 "如果 async 任务有 parent_tool_id（即来自 Bxdcbot），续答时不要重复调任何 skill（避免递归等待）"
  - 新增 `POST /api/internal/bxdcbot-run/complete` 内部 API：agent-core run 终态（completed / failed / 60 轮触顶）时调一次，gateway 写对话消息 + 推 SSE + 触发外层 LLM 续答
  - 续答 LLM prompt 模板：context 用 BxdcbotRun.parentToolId 反查 chat_messages 找外层"调 Bxdcbot 的 user 消息 + assistant tool_call"，拼装"原 user 消息 + Bxdcbot 整体结果 + 子 async 终态汇总"作为续答输入
- **frontend**：
  - 通知中心列表项加 `parent_tool_id` 聚合展开（"Bxdcbot X 调用了 5 个子任务：3 完成 / 1 失败 / 1 进行中"）
  - 对话流组件已有 AsyncTaskResultMessage 渲染（archive 2026-06-12 实现），加 `parent_tool_id` 标识（视觉上显示"这是 Bxdcbot 子任务"徽章）
- **数据库**：
  - `async_tasks` 表加 `parent_tool_id` (VARCHAR(128) NULL) + `parent_skill_id` (BIGINT NULL) + 复合索引（`parent_tool_id, status`）
  - `chat_messages` 表**不**加新列（archive 2026-06-12 已加 `async_task_id`，join 即可）
- **新接口**：
  - 新增 `POST /api/internal/bxdcbot-run/complete`（agent-core run 终态时调）
  - 复用现有 `/api/skills/execute` 异步 fire-and-forget + `/api/async-tasks/{id}/wait` + `/api/internal/async-task/echo-to-chat`
  - 新增 env `BXDCBOT_SKILL_MAX_RETRIES=3`（Bxdcbot skill 失败自动重试次数，AGENTS.md 5.2 项目级 env 增量）
- **回归测试**：
  - **agent-core** 端到端测试 3 个 case：① Bxdcbot 调 1 个 async + 1 个 sync + 1 个最终输出；② Bxdcbot 调 5-10 个 async + 跨周期等真结果；③ Bxdcbot 单 async 超时注入
  - **agent-core** 端到端测试 ④（新增）：Bxdcbot run 跑完时调 `/api/internal/bxdcbot-run/complete`，验证 chat_message 写入 + SSE 推送 + 外层 LLM 续答触发
  - **agent-core** 端到端测试 ⑤（新增）：Bxdcbot 调 skill 失败 → 验证重试 3 次 + 仍失败 → Bxdcbot run status=failed + 调 complete 失败路径
  - **agent-core** 端到端测试 ⑥（新增）：Bxdcbot 调 skill 失败 2 次 + 第 3 次成功 → 验证 LLM 看到的是**第 3 次成功真结果**（不是失败 1/失败 2 注入 messages）
  - **gateway** 单元测试覆盖 `parent_tool_id` 入参持久化 + `/api/async-tasks/my` 返回字段 + `/api/internal/bxdcbot-run/complete` 各路径
  - **frontend** 通知中心聚合 UI 渲染测试（snapshot）
