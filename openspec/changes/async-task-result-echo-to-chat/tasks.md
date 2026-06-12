## 1. 数据库 schema 演进（Java migration 模式）

按 AGENTS.md 5.3 规约，schema 变更**只**走 Java migration 类，**不**改 `schema-mysql.sql` 也不让用户手动 `mysql -e` 跑。

- [ ] 1.1 新建 `ChatMessageSchemaMigration`（**gateway** 启动时跑一次，idempotent）
- [ ] 1.2 步骤 1 — `ALTER TABLE chat_messages MODIFY COLUMN source ENUM('USER','ASSISTANT','TOOL_RESULT','ASYNC_TASK_RESULT')`，幂等查 `information_schema.COLUMNS` 现有枚举值
- [ ] 1.3 步骤 2 — `ALTER TABLE chat_messages ADD COLUMN async_task_id VARCHAR(64) NULL`，幂等查列名
- [ ] 1.4 步骤 3 — `ALTER TABLE chat_messages ADD COLUMN summary_pending TINYINT(1) DEFAULT 1`，幂等查列名
- [ ] 1.5 步骤 4 — `ALTER TABLE chat_messages ADD COLUMN summary_text MEDIUMTEXT NULL`，幂等查列名
- [ ] 1.6 步骤 5 — `ALTER TABLE chat_messages ADD COLUMN summary_generated_at DATETIME NULL`，幂等查列名
- [ ] 1.7 步骤 6 — `CREATE INDEX idx_chat_messages_async_task_id ON chat_messages(async_task_id)`，幂等查 `information_schema.STATISTICS`
- [ ] 1.8 步骤 7 — 记录到 `schema_migrations` 表（migration name + applied_at），下次启动跳过已跑的
- [ ] 1.9 MySQL 验证：`SHOW COLUMNS FROM chat_messages` 看 `source` 枚举 + 4 个新列 + 索引都在
- [ ] 1.10 幂等测试：跑 2 次 migration（第一次应用 + 第二次跳过），不报错

## 2. gateway：chat_messages 数据访问

- [ ] 2.1 新建 `entity/ChatMessage.java`：对应 `chat_messages` 表，含 `id` / `sessionId` / `role` / `source` / `content` / `asyncTaskId` / `summaryPending` / `summaryText` / `summaryGeneratedAt` / `createdAt` / `updatedAt` 等字段
- [ ] 2.2 新建 `mapper/ChatMessageMapper.java`（MyBatis-Plus BaseMapper）
- [ ] 2.3 新建 `service/ChatMessageService.java`：
  - `insertAsyncTaskResult(Long sessionId, String toolName, String toolArgs, String resultSummary, String asyncTaskId)` 写一条 `source=ASYNC_TASK_RESULT` 消息
  - `updateLlmSummary(Long messageId, String summaryText)` 续答 UPDATE
- [ ] 2.4 单测：插入 + UPDATE 都能跑通

## 3. gateway：LLM 续答 Service（核心业务逻辑）

- [ ] 3.1 新建 `service/AsyncTaskChatReplyService.java`（Spring Service）：
  - 方法 `writeChatMessage(EchoToChatRequest req)`：调 ChatMessageService 写一条消息
  - 方法 `triggerLlmReply(EchoToChatRequest req, Long messageId)`：组装 prompt + 调 LLM + UPDATE 总结
  - 方法 `assembleLlmPrompt(EchoToChatRequest req)`：构造 system + user prompt（user 自己的 LLM 配置）
  - 方法 `resolveLlmConfig(Long userId)`：查 User.llmApiBase / ModelName / ApiKey，未配置降级系统默认
- [ ] 3.2 prompt 模板：system "基于任务结果回答用户问题，长度按问题+结果动态调整，不需要 1-2 句硬性限制，也不要无限冗长"；user = 原消息 + tool call 摘要 + 任务结果（**最大 20000 token，超了按 token 计数 truncate 中间留 "..."**）
- [ ] 3.3 LLM 调用走现有 gateway HTTP 客户端（`LlmHttpClient` 类似物）
- [ ] 3.4 audit tag = `ASYNC_TASK_REPLY`，跟普通异步任务 audit 区分
- [ ] 3.5 续答调用带 `auditTag` 跳过 dedup（per-session 1h 窗口不参与）
- [ ] 3.6 续答失败降级：`summary_text = "任务已完成（系统未生成总结，可点击下方'查看完整任务'了解结果）"`
- [ ] 3.7 **LLM 输出不截断**（无硬上限，让 LLM 自己决定）；长答案靠前端 UI 折叠（> 500 字）
- [ ] 3.8 fire-and-forget：写消息 + LLM 续答都走 `@Async`（Spring Async）+ try/catch
- [ ] 3.9 单测覆盖各路径（写消息 / 调 LLM 成功 / LLM 失败 / result 截断 / 失败降级）

## 4. gateway：内部 API Controller

- [ ] 4.1 新建 `controller/AsyncTaskEchoController.java`：`POST /api/internal/async-task/echo-to-chat`
- [ ] 4.2 请求 DTO `EchoToChatRequest`：含 `taskId` / `userId` / `sessionId` / `chatId` / `toolName` / `toolArgs` / `taskResult` / `finishedAt`
- [ ] 4.3 响应 DTO `EchoToChatResponse`：含 `messageId` / `status`（写入成功 / 失败）
- [ ] 4.4 Controller 调 `AsyncTaskChatReplyService.writeChatMessage` + `triggerLlmReply`
- [ ] 4.5 Controller 加 `@Async` 注解（fire-and-forget，不阻塞 agent-core）
- [ ] 4.6 失败返回 200 + status=FAILED（agent-core 不需要知道 gateway 内部失败）

## 5. gateway：内部 API 安全保护

- [ ] 5.1 `/api/internal/*` 路径加 Spring Security 配置
- [ ] 5.2 IP 白名单（127.0.0.1 / localhost / 内网网段）
- [ ] 5.3 内部 token 校验（`X-Internal-Token` header，与现有 `app.registration.admin-password` 类似）
- [ ] 5.4 agent-core HTTP 客户端带 `X-Internal-Token` header
- [ ] 5.5 外部调用返回 403

## 6. agent-core：终态处理集成（最小侵入）

- [ ] 6.1 新建 `client/GatewayClient.java`（如果还没有）：调 `POST http://gateway:18080/api/internal/async-task/echo-to-chat`，带 `X-Internal-Token` header
- [ ] 6.2 `AsyncTaskPollingScheduler.terminalState()` 末尾加 1 行：`await this.gatewayClient.echoToChat(task)`（HTTP 异步，try/catch）
- [ ] 6.3 调用超时 2s
- [ ] 6.4 失败 catch，只记 log，不影响轮询线程
- [ ] 6.5 端到端测试：模拟 SINGLE_CALL 任务从 PENDING → SUCCESS，看 gateway 内部 API 被调一次
- [ ] 6.6 agent-core **不再写** chat_messages，**不再调** LLM 续答（仅转发）

## 7. frontend：对话流渲染（含展示优化）

- [ ] 7.1 `MessageItem.vue` 加 `source` prop 分支：`ASYNC_TASK_RESULT` 走专用子组件
- [ ] 7.2 新建 `AsyncTaskResultMessage.vue`：
  - 🔔 icon + "异步任务" 标签
  - 状态徽章（SUCCESS 绿 / FAILED 红 / TIMEOUT 黄）
  - 折叠原始结果（默认折叠）
  - LLM 总结区（Markdown 渲染，> 500 字自动折叠）
  - "复制"按钮（一键复制总结）
  - "查看完整任务"链接（跳通知中心）
- [ ] 7.3 `summary_pending = true` 时显示骨架屏 / "系统正在生成总结..."占位
- [ ] 7.4 移动端响应式适配（折叠逻辑 + 横向滚动）
- [ ] 7.5 复用现有 `useMarkdown.ts` 渲染 LLM 总结
- [ ] 7.6 端到端：触发异步任务 → 刷新对话流 → 看到专用 UI（图标 + 状态 + 折叠 + 总结区）

## 8. LLM 续答调用的 LLM 配置

- [ ] 8.1 gateway 续答前查 `User.llmApiBase` / `User.llmModelName` / `User.llmApiKey`
- [ ] 8.2 用户未配 LLM：降级用系统默认 LLM（gateway 配置项 `app.llm.fallback-api-base` 等）
- [ ] 8.3 LLM HTTP client 按用户配置调（api base + model + key）
- [ ] 8.4 audit log 正常记录（`auditTag = ASYNC_TASK_REPLY`）

## 9. 回归测试

- [ ] 9.1 gateway 单元测试覆盖 `AsyncTaskChatReplyService` 各路径（写消息 / 调 LLM / UPDATE / 失败降级 / 超长截断）
- [ ] 9.2 gateway 集成测试覆盖 `AsyncTaskEchoController`（写消息 + 调 LLM 全流程）
- [ ] 9.3 agent-core 仅需 1 个端到端测试覆盖"终态 → gateway 内部 API 调用"
- [ ] 9.4 frontend snapshot 测试
- [ ] 9.5 migration 跑 2 次：第一次应用 + 第二次幂等跳过
- [ ] 9.6 端到端：浏览器触发 SINGLE_CALL 异步任务 → 通知中心显示 → 对话流刷新看到回灌消息 → 等待 LLM 续答 → 看到总结文本

## 10. 灰度 / 文档

- [ ] 10.1 单 skill 上线（如"测试时间" skill 配 SINGLE_CALL 10s 超时）观察 1-2 天
- [ ] 10.2 全 skill 开放（移除 skill-config 里的灰度开关）
- [ ] 10.3 写一条 AGENTS.md 注释 / OpenSpec 文档说明这个能力
- [ ] 10.4 commit + push 到 myfork/temp

## 11. 回滚预案

- [ ] 11.1 DB migration 加 DROP COLUMN / DROP INDEX 改回（不破坏老数据）
- [ ] 11.2 代码回滚 = revert commit（`AsyncTaskChatReplyService` / `ChatMessageSchemaMigration` / `AsyncTaskEchoController` 是新文件，老逻辑不依赖）
- [ ] 11.3 通知中心 / audit log / SSE 推送 都不动，**任意路径回滚都不会影响老功能**
- [ ] 11.4 agent-core 回滚只需 revert `AsyncTaskPollingScheduler` 那一行 + `GatewayClient` 新文件
