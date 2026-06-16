## ADDED Requirements

### Requirement: async_tasks 表加 parent_tool_id 和 parent_skill_id 列

`async_tasks` 表 MUST 新增 2 个可空列 + 1 个复合索引，用于标识"这个 async 任务是哪个 Bxdcbot run 调起的"。

#### Scenario: schema 演进（Java migration）
- **WHEN** `AsyncTaskSchemaMigration` 启动时跑
- **THEN** gateway MUST 执行：
  ```sql
  ALTER TABLE async_tasks
    ADD COLUMN parent_tool_id VARCHAR(128) NULL COMMENT '父 Bxdcbot run_id（标识这是 Bxdcbot X 调的第 N 个子任务）',
    ADD COLUMN parent_skill_id BIGINT NULL COMMENT '父 Bxdcbot skill_id（冗余字段，方便按 skill 过滤）',
    ADD INDEX idx_parent_tool_status (parent_tool_id, status);
  ```
- **AND** 列 MUST 可空（NULL safe，历史数据不需补）
- **AND** migration MUST 幂等（重复跑不报错，参考 `SchemaMigrationRunner` 模式）

#### Scenario: AsyncTaskService.submit 接收 parentToolId / parentSkillId
- **WHEN** 客户端调 `POST /api/skills/execute` 提交 async 任务
- **AND** 请求 body 包含 `parentToolId` / `parentSkillId` 字段
- **THEN** `AsyncTaskService.submit` MUST 把这俩字段持久化到 `async_tasks` 表
- **AND** 不带这俩字段时 MUST 存 NULL（向后兼容）

#### Scenario: /api/async-tasks/my 返回 parentToolId / parentSkillId
- **WHEN** 客户端调 `GET /api/async-tasks/my` 列表
- **THEN** 响应 JSON 每条 MUST 包含 `parentToolId` / `parentSkillId` 字段（可空）
- **AND** 前端可按 `parentToolId` 过滤出 Bxdcbot run 的子任务

#### Scenario: 通知中心列表项支持按 parent_tool_id 聚合
- **WHEN** 前端调 `GET /api/async-tasks/my?parentToolId=<runId>`（可选 query param）
- **THEN** 响应 MUST 只返回 `parentToolId = <runId>` 的任务
- **AND** 不带 query param 时 MUST 返回所有任务（向后兼容）
- **AND** 列表项 UI SHOULD 展开显示"Bxdcbot X 调用了 N 个子任务：a 完成 / b 失败 / c 进行中"

### Requirement: async 任务终态按 parent_tool_id 分流处理

gateway 异步任务到达终态时 MUST 根据 `async_tasks.parent_tool_id` 是否为空**分流**到两条不同的处理路径：
- 路径 A（普通 async）：调 `POST /api/internal/async-task/echo-to-chat`（archive 2026-06-12 现有路径）→ 写 chat_message + 推 SSE + 触发 LLM 续答
- 路径 B（Bxdcbot 子任务）：**不**调 echo-to-chat，由 BxdcbotRun scheduler 在所有子 async 都完成 / run 整体终结时统一处理（写 BXDCBOT_RUN_RESULT chat_message + 推 SSE + 触发外层 LLM 续答）

不调 echo-to-chat 的原因：Bxdcbot 子 async 各自完成时**不**对外可见，等 Bxdcbot 整体跑完时一次性回灌对外 + 子 async 汇总，避免对话流被 N 条单独消息淹没。

#### Scenario: 普通 async 终态走 echo-to-chat 路径
- **WHEN** 一个 async task 到达终态（SUCCESS / FAILED / TIMEOUT）
- **AND** 对应 `async_tasks.parent_tool_id IS NULL`（非 Bxdcbot 调起）
- **THEN** gateway MUST 走 archive 2026-06-12 现有路径
- **AND** gateway MUST 调 `POST /api/internal/async-task/echo-to-chat`
- **AND** 现有行为 MUST 不变

#### Scenario: Bxdcbot 子 async 终态不走 echo-to-chat
- **WHEN** 一个 async task 到达终态
- **AND** 对应 `async_tasks.parent_tool_id IS NOT NULL`（来自 Bxdcbot run）
- **THEN** gateway MUST **不**调 echo-to-chat
- **AND** 异步任务终结处理 MUST 只记 audit log（MUST 继续记 `async_polling_audit_log`）
- **AND** 异步任务状态更新 MUST 继续（`async_tasks.status` 字段）
- **AND** 通知中心 SSE 推送 MUST 继续（`/api/async-tasks/my` 列表里的状态更新）
- **AND** agent-core BxdcbotRunScheduler MUST 通过 `GET /api/async-tasks/{id}/wait` 阻塞监听（agent-core 侧自己拿真结果注入到 BxdcbotRun.messages）
- **AND** 对话流回灌延后到 Bxdcbot run 整体终结时（调 `POST /api/internal/bxdcbot-run/complete`）

#### Scenario: 父任务标识修改后老行为兼容
- **WHEN** 异步任务 `parent_tool_id` 字段加上后，老的 async task 记录 `parent_tool_id IS NULL`
- **THEN** 老 async 任务的终态处理 MUST 走路径 A（echo-to-chat）
- **AND** 历史数据 MUST NOT 被新逻辑误处理
- **AND** gateway 启动时 MUST 自动跑 migration 给老记录填 NULL（默认行为，列可空）

### Requirement: Bxdcbot run 终态 callback 协议

agent-core MUST 在 Bxdcbot run 终态（completed / failed / 60 轮触顶）时调 gateway 内部 API `POST /api/internal/bxdcbot-run/complete` 回灌对话流。

#### Scenario: Bxdcbot run 终态 callback 请求格式
- **WHEN** agent-core Bxdcbot run 到达 `status=completed`
- **THEN** agent-core MUST 调 `POST /api/internal/bxdcbot-run/complete`
- **AND** 请求 MUST 带 `X-Internal-Token` header（与 `/api/internal/async-task/echo-to-chat` 用同一 token）
- **AND** 请求 body MUST 包含：
  ```json
  {
    "runId": "<BxdcbotRun.runId>",
    "conversationId": "<conversationId>",
    "userId": "<userId>",
    "parentToolId": "<BxdcbotRun.runId>",
    "parentSkillId": <Bxdcbot skill_id>,
    "status": "completed",
    "finalText": "<LLM 最终输出>",
    "roundsUsed": <int>,
    "llmCallsUsed": <int>,
    "totalTokensUsed": <int>,
    "subTaskSummary": {
      "total": <N>,
      "succeeded": <A>,
      "failed": <B>,
      "pending": 0
    },
    "finishedAt": "<ISO8601>"
  }
  ```
- **AND** 调用 MUST 在 try/catch 内
- **AND** 调用 MUST 设置 5s 超时
- **AND** 失败 MUST catch 并只记 log，MUST NOT 抛回

#### Scenario: Bxdcbot run 跑到 60 轮触顶 callback
- **WHEN** Bxdcbot run 触达 60 轮上限（`currentRound >= 60`）
- **THEN** agent-core MUST 调 `POST /api/internal/bxdcbot-run/complete`
- **AND** body MUST 包含 `status=failed` + `failureReason="60 轮触顶"` + `roundsUsed=60` + `finalText=null`
- **AND** gateway 收到后 MUST 写一条 chat_message 描述失败原因
- **AND** 续答 LLM 看到后 MUST 用自然语言告知用户"该 Bxdcbot run 已超 60 轮（跑了 N 轮），请基于已获取的结果继续"

#### Scenario: gateway 内部 API 调用安全
- **WHEN** agent-core 调 `POST /api/internal/bxdcbot-run/complete`
- **THEN** 请求 MUST 带 `X-Internal-Token` header
- **AND** token 配置在 agent-core 配置文件里（与 gateway `app.internal-api.token` 对应）
- **AND** gateway 校验失败 MUST 返回 401

#### Scenario: 多次调同一 run 的 complete 幂等
- **WHEN** agent-core 多次调 `/api/internal/bxdcbot-run/complete`（如 scheduler 重复触发 / 网络重试）
- **THEN** gateway MUST 幂等处理（按 `parent_tool_id=runId` 去重）
- **AND** 第一次写 chat_message，后续 MUST NOT 重复写
- **AND** 多次调 MUST NOT 触发多次 LLM 续答
- **AND** 第二次起的响应 MUST 返回 200 + "已存在消息 ID"
