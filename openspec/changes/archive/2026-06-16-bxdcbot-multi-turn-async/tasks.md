## 1. 数据库迁移 (skill-gateway, Java)

- [ ] 1.1 新建 `AsyncTaskSchemaMigration` Java 类（参考 `SchemaMigrationRunner::migrateConversationMessageSummaries` 模式）
- [ ] 1.2 在 `SchemaMigrationRunner` 注册新方法 `migrateAsyncTaskParentToolId()`，启动时自动跑
- [ ] 1.3 migration 跑：`ALTER TABLE async_tasks ADD COLUMN parent_tool_id VARCHAR(128) NULL, ADD COLUMN parent_skill_id BIGINT NULL, ADD INDEX idx_parent_tool_status (parent_tool_id, status)`
- [ ] 1.4 migration 幂等（重复跑不报错）

## 2. AsyncTaskService 扩展 (skill-gateway, Java)

- [ ] 2.1 `AsyncTask` entity 加 `parentToolId` + `parentSkillId` 字段（可空）
- [ ] 2.2 `AsyncTaskService.submit()` 入参 DTO 加 `parentToolId` / `parentSkillId` 字段
- [ ] 2.3 持久化时把 `parentToolId` / `parentSkillId` 写入 `async_tasks` 表对应列
- [ ] 2.4 `AsyncTaskController.execute()` 接收前端传的 `parentToolId` / `parentSkillId` 并转发

## 3. /api/async-tasks/my 扩展 (skill-gateway, Java)

- [ ] 3.1 响应 DTO 加 `parentToolId` / `parentSkillId` 字段（可空）
- [ ] 3.2 支持 `?parentToolId=<runId>` query param 过滤（不带时返回所有）
- [ ] 3.3 JOIN `async_tasks` 表把 `parent_tool_id` / `parent_skill_id` 暴露到响应

## 4. LLM 续答 prompt 调整 (skill-gateway, Java)

- [ ] 4.1 `AsyncTaskChatReplyService` 检测到 `parentToolId IS NOT NULL` 时加 1 行 prompt 提示
- [ ] 4.2 提示文案："如果 async 任务有 parent_tool_id（即来自 Bxdcbot），续答时不要重复调任何 skill（包括 Bxdcbot 自身），避免嵌套等待"
- [ ] 4.3 普通 async 任务（`parentToolId IS NULL`）续答行为不变（archive 2026-06-12 路径）

## 5. BxdcbotRunStore (agent-core, NestJS)

- [ ] 5.1 新建 `src/services/openclaw-run-store.ts`
- [ ] 5.2 `BxdcbotRun` interface 定义（runId / conversationId / userId / parentToolId / parentSkillId / messages / pendingAsyncTaskIds / currentRound / totalLlmCalls / startedAt / status / result）
- [ ] 5.3 in-memory Map 持久化（`Map<runId, BxdcbotRun>`）
- [ ] 5.4 CRUD 方法：create / get / update / delete / listByStatus

## 6. BxdcbotRunScheduler (agent-core, NestJS)

- [ ] 6.1 新建 `src/services/openclaw-run-scheduler.ts`
- [ ] 6.2 用 `@nestjs/schedule` 的 `@Cron('*/2 * * * * *')` 每 2 秒扫一次 `awaiting_async` run
- [ ] 6.3 调 gateway `GET /api/async-tasks/{id}/wait?timeout=2` 阻塞监听每个 pending async
- [ ] 6.4 async 终态时把真结果作为 `tool` role 消息注入 BxdcbotRun.messages
- [ ] 6.5 所有 pending 完成时重新 invoke `executeOpenClawSkill` 进入新周期
- [ ] 6.6 检测 `currentRound >= 60` 时终止 run（status=failed）

## 7. executeOpenClawSkill 多周期改造 (agent-core, NestJS)

- [ ] 7.1 改造 `src/tools/java-skills.ts::executeOpenClawSkill` 多周期
- [ ] 7.2 核心 6 轮 LLM 循环不变（回归保证）
- [ ] 7.3 外层加 `while (currentRound < 60 && !completed)` 循环
- [ ] 7.4 检测 `pendingAsyncTaskIds.size > 0` 时退出当前 6 轮循环 + 置 `awaiting_async`
- [ ] 7.5 调 async skill 时（POST `/api/skills/execute` 返回 POLLING/SINGLE_CALLED）：
  - [ ] 7.5.1 把 `parentToolId = BxdcbotRun.runId` + `parentSkillId = Bxdcbot skill_id` 写入 payload
  - [ ] 7.5.2 把 `asyncTaskId` 记录到 `BxdcbotRun.pendingAsyncTaskIds`
  - [ ] 7.5.3 把占位结果作为 `tool` role 消息注入（tool_call_id=`async_<id>`）
- [ ] 7.6 纯 sync 场景 Bxdcbot 行为不变（验证回归）
- [ ] 7.7 compute / server_lookup / api_caller 等其他 skill 类型执行路径不变（验证回归）

## 8. Bxdcbot system prompt 加 1 行约束 (agent-core, NestJS)

- [ ] 8.1 在 `executeOpenClawSkill` 拼装 system message 时加：
  ```
  ⚠️ 异步 skill 调完返回 {asyncTaskId, status: "POLLING"|"SINGLE_CALLED"} 是占位。
     不要基于占位调下游子任务。如需依赖真实结果，可结束当前轮等真结果注入后再规划。
  ```
- [ ] 8.2 该规则 MUST 放在 system message 顶部（LLM 第一眼看到）

## 9. frontend 通知中心聚合 UI (frontend, Vue 3)

- [ ] 9.1 通知中心列表组件 (`NotificationCenter.vue` 或类似) 加 `parentToolId` 字段过滤
- [ ] 9.2 列表项顶部显示父 Bxdcbot skill 名 + run 状态（"3 完成 / 1 失败 / 1 进行中"）
- [ ] 9.3 列表项展开后显示每个子 async 单独一行（skill 名 + 状态徽章 + 终态时间 + 真结果链接）
- [ ] 9.4 折叠默认关闭（避免列表太长）

## 10. frontend 对话流徽章 (frontend, Vue 3)

- [ ] 10.1 `AsyncTaskResultMessage` 组件加 `parentToolId` prop（可空）
- [ ] 10.2 `parentToolId` 不为空时显示"🤖 Bxdcbot 子任务"徽章
- [ ] 10.3 徽章用 Bxdcbot 主题色（与普通 async 任务结果消息视觉区分）
- [ ] 10.4 鼠标悬停显示 tooltip "这是 Bxdcbot run X 调用的子异步任务"
- [ ] 10.5 普通 async 任务渲染行为不变（无徽章）

## 11. agent-core 端到端测试

- [ ] 11.1 测 case ①：Bxdcbot 调 1 个 async + 1 个 sync + 1 个最终输出
- [ ] 11.2 测 case ②：Bxdcbot 调 5-10 个 async + 跨周期等真结果
- [ ] 11.3 测 case ③：Bxdcbot 单 async 超时注入（`singleCallReadTimeoutSeconds` 触发）
- [ ] 11.4 测 case ④（回归）：Bxdcbot 纯 sync 场景行为不变

## 12. gateway 单元测试 (skill-gateway, Java)

- [ ] 12.1 `AsyncTaskServiceTest` 测 `submit()` 持久化 `parentToolId` / `parentSkillId`
- [ ] 12.2 `AsyncTaskServiceTest` 测不带 parent 字段时存 NULL（向后兼容）
- [ ] 12.3 `AsyncTaskControllerTest` 测 `/api/async-tasks/my` 返回 `parentToolId` / `parentSkillId` 字段
- [ ] 12.4 `AsyncTaskControllerTest` 测 `?parentToolId=<runId>` query param 过滤
- [ ] 12.5 `AsyncTaskChatReplyServiceTest` 测 Bxdcbot 续答 prompt 加那 1 行提示
- [ ] 12.6 `AsyncTaskSchemaMigrationTest` 测 schema migration 幂等

## 13. frontend snapshot 测试

- [ ] 13.1 通知中心列表项带 `parentToolId` 渲染 snapshot
- [ ] 13.2 对话流 AsyncTaskResultMessage 带徽章渲染 snapshot
- [ ] 13.3 普通 async 任务渲染行为不变（回归 snapshot）

## 14. 文档

- [ ] 14.1 写 `docs/bxdcbot-multi-turn-async.md`（架构图 + 触发逻辑 + 状态机 + 续答策略 + 部署 + 监控 + 实施清单）
- [ ] 14.2 更新 `backend/agent-core/README.md` 加 Bxdcbot 多周期说明
- [ ] 14.3 更新 `backend/skill-gateway/README.md` 加 async_tasks.parent_tool_id 列说明
- [ ] 14.4 更新 `frontend/README.md` 加通知中心聚合 UI 说明
- [ ] 14.5 在 `CHANGELOG.md` 加本次 change 条目（归档时统一加）

## 15. 监控 / 告警

- [ ] 15.1 gateway 端 metrics：`async_task_submit_total{parent_tool_id_present=true}` 计数
- [ ] 15.2 agent-core 端 metrics：`openclaw_run_status_total{status=running|completed|failed|timeout}` 计数
- [ ] 15.3 agent-core 端 metrics：`openclaw_run_rounds_used` 直方图
- [ ] 15.4 告警阈值：60 轮触顶 > 5 次/小时 → 通知开发

## 16. 验证 / 归档

- [ ] 16.1 跑 `mvn -DskipTests compile` 验证 gateway 编译
- [ ] 16.2 跑 `mvn -Dtest=AsyncTaskServiceTest,AsyncTaskControllerTest,AsyncTaskChatReplyServiceTest test` 验证 gateway 单测
- [ ] 16.3 跑 `mvn -DskipTests package` 验证 gateway jar 打包
- [ ] 16.4 跑 `npx tsc --noEmit` 验证 agent-core TypeScript 编译
- [ ] 16.5 跑 `npm test` 验证 agent-core 单测（含 11.1-11.4）
- [ ] 16.6 重启 3 个服务（gateway / agent-core / frontend），跑端到端验证
- [ ] 16.7 启新 gateway → 验证 `async_tasks.parent_tool_id` 列已加
- [ ] 16.8 跑一次 Bxdcbot + async 端到端：对话流能看到 N 条子 async 任务回显 + 通知中心能看到 Bxdcbot X 的子任务聚合
- [ ] 16.9 跑 `npx openspec validate bxdcbot-multi-turn-async --strict` 验证所有 artifacts 合规
- [ ] 16.10 跑 `npx openspec archive bxdcbot-multi-turn-async` 归档（把 spec 升到 `openspec/specs/`）

## 17. Bxdcbot run 终态回灌 API（漏洞 1 修复）

- [ ] 17.1 gateway 新建 `BxdcbotRunCompletionController`（`/api/internal/bxdcbot-run/complete` 端点）
- [ ] 17.2 gateway 新建 `BxdcbotRunCompletionService`（封装"写 BXDCBOT_RUN_RESULT 消息 + 推 SSE + 触发外层 LLM 续答"）
- [ ] 17.3 续答 LLM 跨层关联：用 `parentToolId` 反查 chat_messages 找"调 Bxdcbot 的外层 assistant tool_call message" + 最近 1 条 user 消息
- [ ] 17.4 续答 prompt 模板：按 design 决策 10 拼装（system 提示 + user = 原 user 消息 + 原 tool call 摘要 + finalText + 子 async 汇总）
- [ ] 17.5 chat_messages 表加 `parent_tool_id` + `parent_skill_id` 列 + 复合索引（与 async_tasks 同结构）
- [ ] 17.6 gateway 给 chat_message 加新枚举值 `BXDCBOT_RUN_RESULT`（与 `ASYNC_TASK_RESULT` 区分）
- [ ] 17.7 chat_message 写消息幂等：按 `parent_tool_id=runId` 去重（多次调同 run 不重复写）
- [ ] 17.8 续答 LLM 找不到原 user 消息时降级：只喂 finalText + 子 async 汇总
- [ ] 17.9 续答成功后 UPDATE chat_message 的 `summary` 字段 + `summary_pending=false` + 推 SSE `message_updated`
- [ ] 17.10 agent-core 端：run 终态时（completed / failed / 60 轮触顶）调 `/api/internal/bxdcbot-run/complete`
- [ ] 17.11 agent-core 端：60 轮触顶时传 `status=failed` + `failureReason="60 轮触顶"`
- [ ] 17.12 agent-core 端：调 HTTP 用 5s 超时 + try/catch + 只记 log 不抛

## 18. async 终态按 parent_tool_id 分流（漏洞 2 修复）

- [ ] 18.1 gateway 端 `AsyncTaskPollingScheduler` 终态处理路径加 `if (parent_tool_id == null) { echoToChat() } else { /* skip */ }` 分流
- [ ] 18.2 gateway 端：分流后 audit log 必走（两个路径都记）
- [ ] 18.3 gateway 端：分流后 SSE 通知中心必走（两个路径都推）
- [ ] 18.4 agent-core 端：Bxdcbot 子 async 终态时由 BxdcbotRunScheduler 通过 `GET /api/async-tasks/{id}/wait` 阻塞拿真结果
- [ ] 18.5 agent-core 端：Bxdcbot 子 async 终态时**不**调 echo-to-chat（避免对话流被 N 条淹没）
- [ ] 18.6 回归测试：普通 async 任务（`parent_tool_id IS NULL`）续答行为**不变**（archive 2026-06-12 路径）
- [ ] 18.7 回归测试：历史 async 任务数据（`parent_tool_id IS NULL`）处理**不变**

## 19. asyncTaskIdToToolCallId 关联（漏洞 3 修复）

- [ ] 19.1 agent-core `BxdcbotRun` interface 加 `asyncTaskIdToToolCallId: Map<number, string>` 字段
- [ ] 19.2 `invokeToolDirect` 调 async skill 时，把 `asyncTaskId ↔ tool_call_id` 写进 map
- [ ] 19.3 BxdcbotRunScheduler 拿到 async 真结果时，从 map 找对应的 tool_call_id
- [ ] 19.4 注入 tool message 时 `tool_call_id` 必须用 map 里查到的值（不是 asyncTaskId）
- [ ] 19.5 单元测试：map put / get / delete 流程

## 20. Skill 失败隔离（用户硬性要求 — 错误不传染）

- [ ] 20.1 agent-core BxdcbotRun interface 加 `skillRetries: Map<string, number>` + `originalSkillArgs: Map<string, any>` 字段
- [ ] 20.2 `invokeToolDirect` 调任意 skill（sync / async）时立即 `run.originalSkillArgs.set(skillName, args)`
- [ ] 20.3 BxdcbotRunScheduler 捕获 skill FAILED / TIMEOUT 时走决策 11 流程
- [ ] 20.4 重试逻辑：`run.skillRetries.get(skillName) < BXDCBOT_SKILL_MAX_RETRIES` → 自动重试
- [ ] 20.5 重试调 gateway `/api/skills/execute`（同 args 同 payload），拿到新 asyncTaskId
- [ ] 20.6 新 taskId 加进 `run.pendingAsyncTaskIds` + `run.asyncTaskIdToToolCallId`
- [ ] 20.7 `run.skillRetries.set(skillName, retriesSoFar + 1)`
- [ ] 20.8 N 次全失败 → BxdcbotRun.status=failed + failureReason + 调 `/api/internal/bxdcbot-run/complete` 失败路径
- [ ] 20.9 失败结果 MUST **不**注入 BxdcbotRun.messages
- [ ] 20.10 重试成功 → 注入**仅一次**成功 tool message + `run.skillRetries.delete(skillName)`
- [ ] 20.11 失败原因 audit log：WARN 重试中 / INFO 重试成功 / ERROR 用尽
- [ ] 20.12 通知中心 `/api/async-tasks/my` 列表显示重试新 taskId（gateway 端 auto-generated）
- [ ] 20.13 单元测试：决策 11 失败流程（5 个 case：第 1 次失败重试 / 第 N 次失败终止 / 重试成功仅一次注入 / sync skill 失败重试 / BXDCBOT_SKILL_MAX_RETRIES=0 禁用重试）
- [ ] 20.14 端到端测试 ⑤：Bxdcbot 调 skill 失败 3 次仍失败 → 验证 run status=failed + 调 complete 失败路径
- [ ] 20.15 端到端测试 ⑥：Bxdcbot 调 skill 失败 2 次 + 第 3 次成功 → 验证 LLM 看到的是**第 3 次成功真结果**（messages 不含失败 1/失败 2）
- [ ] 20.16 文档：更新 `docs/bxdcbot-multi-turn-async.md` 加"失败隔离"段（决策依据 + env 配置 + 行为示例）
- [ ] 20.17 文档：更新 `backend/agent-core/.env.example` 加 `BXDCBOT_SKILL_MAX_RETRIES=3` 默认值
