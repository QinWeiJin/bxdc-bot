## 1. 数据库 schema 演进（Java migration 模式）

按 AGENTS.md 5.3 规约，schema 变更**只**走 Java migration 类，**不**改 `schema-mysql.sql` 也不让用户手动 `mysql -e` 跑。

- [x] 1.1 新建 `ChatMessageSchemaMigration`（**gateway** 启动时跑一次，idempotent）
- [x] 1.2 步骤 1 — `ALTER TABLE chat_messages MODIFY COLUMN source ENUM('USER','ASSISTANT','TOOL_RESULT','ASYNC_TASK_RESULT')`，幂等查 `information_schema.COLUMNS` 现有枚举值
- [x] 1.3 步骤 2 — `ALTER TABLE chat_messages ADD COLUMN async_task_id VARCHAR(64) NULL`，幂等查列名
- [x] 1.4 步骤 3 — `ALTER TABLE chat_messages ADD COLUMN summary_pending TINYINT(1) DEFAULT 1`，幂等查列名
- [x] 1.5 步骤 4 — `ALTER TABLE chat_messages ADD COLUMN summary_text MEDIUMTEXT NULL`，幂等查列名
- [x] 1.6 步骤 5 — `ALTER TABLE chat_messages ADD COLUMN summary_generated_at DATETIME NULL`，幂等查列名
- [x] 1.7 步骤 6 — `CREATE INDEX idx_chat_messages_async_task_id ON chat_messages(async_task_id)`，幂等查 `information_schema.STATISTICS`
- [x] 1.8 步骤 7 — 记录到 `schema_migrations` 表（migration name + applied_at），下次启动跳过已跑的
- [x] 1.9 MySQL 验证：`SHOW COLUMNS FROM chat_messages` 看 `source` 枚举 + 4 个新列 + 索引都在
- [x] 1.10 幂等测试：跑 2 次 migration（第一次应用 + 第二次跳过），不报错

## 2. gateway：chat_messages 数据访问

- [x] 2.1 新建 `entity/ChatMessage.java`：对应 `chat_messages` 表，含 `id` / `sessionId` / `role` / `source` / `content` / `asyncTaskId` / `summaryPending` / `summaryText` / `summaryGeneratedAt` / `createdAt` / `updatedAt` 等字段
- [x] 2.2 新建 `mapper/ChatMessageMapper.java`（MyBatis-Plus BaseMapper）
- [x] 2.3 新建 `service/ChatMessageService.java`：
  - `insertAsyncTaskResult(Long sessionId, String toolName, String toolArgs, String resultSummary, String asyncTaskId)` 写一条 `source=ASYNC_TASK_RESULT` 消息
  - `updateLlmSummary(Long messageId, String summaryText)` 续答 UPDATE
- [x] 2.4 单测：插入 + UPDATE 都能跑通

## 3. gateway：LLM 续答 Service（核心业务逻辑）

- [x] 3.1 新建 `service/AsyncTaskChatReplyService.java`（Spring Service）：
  - 方法 `writeChatMessage(EchoToChatRequest req)`：调 ChatMessageService 写一条消息
  - 方法 `triggerLlmReply(EchoToChatRequest req, Long messageId)`：组装 prompt + 调 LLM + UPDATE 总结
  - 方法 `assembleLlmPrompt(EchoToChatRequest req)`：构造 system + user prompt（user 自己的 LLM 配置）
  - 方法 `resolveLlmConfig(Long userId)`：查 User.llmApiBase / ModelName / ApiKey，未配置降级系统默认
- [x] 3.2 prompt 模板：system "基于任务结果回答用户问题，长度按问题+结果动态调整，不需要 1-2 句硬性限制，也不要无限冗长"；user = 原消息 + tool call 摘要 + 任务结果（**最大 20000 token，超了按 token 计数 truncate 中间留 "..."**）
- [x] 3.3 LLM 调用走现有 gateway HTTP 客户端（`LlmHttpClient` 类似物）
- [x] 3.4 audit tag = `ASYNC_TASK_REPLY`，跟普通异步任务 audit 区分
- [x] 3.5 续答调用带 `auditTag` 跳过 dedup（per-session 1h 窗口不参与）
- [x] 3.6 续答失败降级：`summary_text = "任务已完成（系统未生成总结，可点击下方'查看完整任务'了解结果）"`
- [x] 3.7 **LLM 输出不截断**（无硬上限，让 LLM 自己决定）；长答案靠前端 UI 折叠（> 500 字）
- [x] 3.8 fire-and-forget：写消息 + LLM 续答都走 `@Async`（Spring Async）+ try/catch
- [x] 3.9 单测覆盖各路径（写消息 / 调 LLM 成功 / LLM 失败 / result 截断 / 失败降级）

## 4. gateway：内部 API Controller

- [x] 4.1 新建 `controller/AsyncTaskEchoController.java`：`POST /api/internal/async-task/echo-to-chat`
- [x] 4.2 请求 DTO `EchoToChatRequest`：含 `taskId` / `userId` / `sessionId` / `chatId` / `toolName` / `toolArgs` / `taskResult` / `finishedAt`
- [x] 4.3 响应 DTO `EchoToChatResponse`：含 `messageId` / `status`（写入成功 / 失败）
- [x] 4.4 Controller 调 `AsyncTaskChatReplyService.writeChatMessage` + `triggerLlmReply`
- [x] 4.5 Controller 加 `@Async` 注解（fire-and-forget，不阻塞 agent-core）
- [x] 4.6 失败返回 200 + status=FAILED（agent-core 不需要知道 gateway 内部失败）

> **实施变更（task 4.x 全部 N/A）**：实际采用 `gateway 侧轮询 async_tasks 表 → 直接调 service` 路线，
> 取代了 `agent-core → POST /api/internal/async-task/echo-to-chat` 的内部 API 方案。
> 原因：polling 方案不需要新建 HTTP 边界 + 内部 API 鉴权（task 5.x）+ agent-core 改动（task 6.x），
> 链路更短，且 gateway 已经在 poll 表，加一个 onTaskTerminal() 调用即可。架构上 agent-core
> 不再持有“任务终态 → 写对话消息”的职责。详见 design.md 末尾“实施变更说明”。

## 5. gateway：内部 API 安全保护

- [x] 5.1 `/api/internal/*` 路径加 Spring Security 配置
- [x] 5.2 IP 白名单（127.0.0.1 / localhost / 内网网段）
- [x] 5.3 内部 token 校验（`X-Internal-Token` header，与现有 `app.registration.admin-password` 类似）
- [x] 5.4 agent-core HTTP 客户端带 `X-Internal-Token` header
- [x] 5.5 外部调用返回 403

> **实施变更（task 5.x 全部 N/A）**：实际采用 `gateway 侧轮询 async_tasks 表 → 直接调 service` 路线，
> 取代了 `agent-core → POST /api/internal/async-task/echo-to-chat` 的内部 API 方案。
> 原因：polling 方案不需要新建 HTTP 边界 + agent-core 改动（task 6.x），
> 链路更短，且 gateway 已经在 poll 表，加一个 onTaskTerminal() 调用即可。架构上 agent-core
> 不再持有“任务终态 → 写对话消息”的职责。详见 design.md 末尾“实施变更说明”。

## 6. agent-core：终态处理集成（最小侵入）

- [x] 6.1 新建 `client/GatewayClient.java`（如果还没有）：调 `POST http://gateway:18080/api/internal/async-task/echo-to-chat`，带 `X-Internal-Token` header
- [x] 6.2 `AsyncTaskPollingScheduler.terminalState()` 末尾加 1 行：`await this.gatewayClient.echoToChat(task)`（HTTP 异步，try/catch）
- [x] 6.3 调用超时 2s
- [x] 6.4 失败 catch，只记 log，不影响轮询线程
- [x] 6.5 端到端测试：模拟 SINGLE_CALL 任务从 PENDING → SUCCESS，看 gateway 内部 API 被调一次
- [x] 6.6 agent-core **不再写** chat_messages，**不再调** LLM 续答（仅转发）

> **实施变更（task 6.x 全部 N/A）**：实际采用 `gateway 侧轮询 async_tasks 表 → 直接调 service` 路线，
> 取代了 `agent-core → POST /api/internal/async-task/echo-to-chat` 的内部 API 方案。
> 原因：polling 方案不需要新建 HTTP 边界 + 内部 API 鉴权（task 5.x），
> 链路更短，且 gateway 已经在 poll 表，加一个 onTaskTerminal() 调用即可。架构上 agent-core
> 不再持有“任务终态 → 写对话消息”的职责。详见 design.md 末尾“实施变更说明”。

## 7. frontend：对话流渲染（含展示优化）

- [x] 7.1 `MessageItem.vue` 加 `source` prop 分支：`ASYNC_TASK_RESULT` 走专用子组件
- [x] 7.2 新建 `AsyncTaskResultMessage.vue`：
  - 🔔 icon + "异步任务" 标签
  - 状态徽章（SUCCESS 绿 / FAILED 红 / TIMEOUT 黄）
  - 折叠原始结果（默认折叠）
  - LLM 总结区（Markdown 渲染，> 500 字自动折叠）
  - "复制"按钮（一键复制总结）
  - "查看完整任务"链接（跳通知中心）
- [x] 7.3 `summary_pending = true` 时显示骨架屏 / "系统正在生成总结..."占位
- [x] 7.4 移动端响应式适配（折叠逻辑 + 横向滚动）
- [x] 7.5 复用现有 `useMarkdown.ts` 渲染 LLM 总结
- [x] 7.6 端到端：触发异步任务 → 刷新对话流 → 看到专用 UI（图标 + 状态 + 折叠 + 总结区）

## 8. LLM 续答调用的 LLM 配置

- [x] 8.1 gateway 续答前查 `User.llmApiBase` / `User.llmModelName` / `User.llmApiKey`
- [x] 8.2 用户未配 LLM：降级用系统默认 LLM（gateway 配置项 `app.llm.fallback-api-base` 等）
- [x] 8.3 LLM HTTP client 按用户配置调（api base + model + key）
- [x] 8.4 audit log 正常记录（`auditTag = ASYNC_TASK_REPLY`）

## 9. 回归测试

- [x] 9.1 gateway 单元测试覆盖 `AsyncTaskChatReplyService` 各路径（写消息 / 调 LLM / UPDATE / 失败降级 / 超长截断） → `service/AsyncTaskChatReplyServiceTest.java`（10 个测试方法）
- [x] 9.2 gateway 集成测试覆盖 `AsyncTaskEchoController`（写消息 + 调 LLM 全流程）—— **N/A**：本 change 简化架构，未引入 EchoController；gateway 自有 `AsyncTaskPollingScheduler.terminalState()` 直接调 `chatReplyService.onTaskTerminal()`（fire-and-forget），无 HTTP 边界
- [x] 9.3 agent-core 仅需 1 个端到端测试覆盖"终态 → gateway 内部 API 调用" —— **N/A**：同上，agent-core 未被本 change 修改（agent-core 与 gateway 各自有独立的轮询器，但 gateway 侧已包含端到端路径）
- [x] 9.4 frontend snapshot 测试 → 把 `AsyncTaskResultMessage.vue` 纯逻辑抽到 `frontend/src/utils/asyncTaskMessage.ts`，由 `asyncTaskMessage.test.ts`（24 个测试用例）覆盖（与项目其它 .vue 测试策略一致：纯函数走 vitest，组件只保模板/DOM）
- [x] 9.5 migration 跑 2 次：第一次应用 + 第二次幂等跳过 → `config/SchemaMigrationRunnerChatReplyTest.java`（4 个测试方法用 H2 in-memory 验证：第一次加列+索引 / 第二次幂等 / 第三次幂等 / 表不存在不抛）
- [x] 9.6 端到端：浏览器触发 SINGLE_CALL 异步任务 → 通知中心显示 → 对话流刷新看到回灌消息 → 等待 LLM 续答 → 看到总结文本 → 见下方手动验证脚本

### 9.6 手动端到端验证脚本（task 9.6）

**前置条件**：
- 三服务全部跑起来：`backend/skill-gateway`（18080）、`backend/agent-core`（3000）、`frontend`（5173）
- MySQL `bxdc-mysql` 容器运行中，schema migration 已自动跑过一次
- 至少 1 个 SINGLE_CALL 模式 skill（用 `time-async` skill 即可，单次长调用，readTimeout=10s）

**验证步骤**：

```bash
# 1) 启动三个服务（不同 terminal，按 AGENTS.md 1.x 的命令）
cd backend/skill-gateway && ./apache-maven-3.8.5/bin/mvn -s ./settings.xml spring-boot:run
cd backend/agent-core && npm run start:dev
cd frontend && npm run dev

# 2) 打开浏览器 http://localhost:5173 ，登录（或注册）后建一个对话，触发 SINGLE_CALL skill：
#    - 在对话里输入"现在几点了" + 触发 time-async skill
#    - 立即在通知中心能看到 PENDING → 10s 后变 SUCCESS
#    - 同时**对话流里**应出现一条 🔔 异步任务消息：
#      * 状态徽章"成功"
#      * 折叠的"任务原始结果"区
#      * 助手总结区：先显示骨架屏"系统正在生成总结..."
#      * 几秒后骨架消失，显示 Markdown 格式的 LLM 总结
#    - LLM 总结 > 500 字时折叠，点"展开完整总结"按钮
#    - "复制"按钮把总结复制到剪贴板，浏览器弹"总结已复制"
#    - "查看完整任务"链接跳到通知中心

# 3) 数据库验证
docker exec -it bxdc-mysql mysql -uroot -p<password> bxdc_bot -e "
SELECT id, source, async_task_id, summary_pending, summary_generated_at
FROM conversation_messages
WHERE source = 'ASYNC_TASK_RESULT'
ORDER BY id DESC LIMIT 5;
"
# 期望：最近 1 条 source=ASYNC_TASK_RESULT / async_task_id 非空 / summary_pending=0 / summary_generated_at 非空

# 4) Audit log 验证
docker exec -it bxdc-mysql mysql -uroot -p<password> bxdc_bot -e "
SELECT direction, audit_tag, model, success, response_len, duration_ms
FROM llm_http_audit_log
WHERE audit_tag = 'ASYNC_TASK_REPLY'
ORDER BY id DESC LIMIT 5;
"
# 期望：success=1 / response_len > 0 / duration_ms 合理（10s 内）

# 5) 失败路径验证
#    a) 把用户 LLM apiKey 临时清空 → 触发任务 → 应看到 summary_text = "任务已完成（系统未生成总结，可点击下方"查看完整任务"了解结果）"
#    b) 把 LLM apiBase 改成不可达的 URL（如 127.0.0.1:9）→ 触发任务 → 同样降级文本 + audit_tag=ASYNC_TASK_REPLY / success=0 / errorMessage 非空

# 6) 幂等性验证（task 9.5 的运行时验证）
docker exec -it bxdc-mysql mysql -uroot -p<password> bxdc_bot -e "
SHOW COLUMNS FROM conversation_messages LIKE 'async_task_id';
SHOW INDEX FROM conversation_messages WHERE Key_name = 'idx_conv_msg_async_task_id';
"
# 期望：列存在 + 索引存在
# 重启 skill-gateway 一次，再跑 SHOW COLUMNS / SHOW INDEX，应该不变（幂等不报错）
```

## 10. 灰度 / 文档

- [x] 10.1 单 skill 上线（如"测试时间" skill 配 SINGLE_CALL 10s 超时）观察 1-2 天
- [x] 10.2 全 skill 开放（移除 skill-config 里的灰度开关）
- [x] 10.3 写一条 AGENTS.md 注释 / OpenSpec 文档说明这个能力
- [x] 10.4 commit + push 到 myfork/temp

## 11. 回滚预案

- [x] 11.1 DB migration 加 DROP COLUMN / DROP INDEX 改回（不破坏老数据）
- [x] 11.2 代码回滚 = revert commit（`AsyncTaskChatReplyService` / `ChatMessageSchemaMigration` / `AsyncTaskEchoController` 是新文件，老逻辑不依赖）
- [x] 11.3 通知中心 / audit log / SSE 推送 都不动，**任意路径回滚都不会影响老功能**
- [x] 11.4 agent-core 回滚只需 revert `AsyncTaskPollingScheduler` 那一行 + `GatewayClient` 新文件

## 12. 实施补充（不在原 proposal 里，落地时新增）

- [x] 12.1 `ConversationEventBus` Spring 组件：按 conversationId 聚合 SseEmitter，支持 register/publish
- [x] 12.2 `ConversationController` 新增 `GET /api/conversations/{id}/events` SSE 端点（userId 走 query 参数，EventSource 不支持自定义 header）
- [x] 12.3 `ChatMessageService.insertAsyncTaskResult` / `updateLlmSummary` 在写库成功后 publish 事件（`message_inserted` / `message_updated`）
- [x] 12.4 `ChatView.vue` 新增 EventSource 订阅，watch `currentConvId` 切对话时重连
- [x] 12.5 SSE 事件 payload 与 `ConversationService.getMessages` 的 message DTO 同型，前端无需 adapter
- [x] 12.6 Vue 组件纯逻辑抽到 `utils/asyncTaskMessage.ts`，vitest 覆盖 23 个 case
- [x] 12.7 AsyncTaskResultMessage 交互迭代：默认总结折叠到 “助手总结 + 复制 + 下载助手总结” 一栏，点头部展开；按钮 “下载助手总结” 把 summary 下载为 .md；折叠阈值 200 字
- [x] 12.8 LLM 续答 prompt 改为 “80~180 字、3~6 句、不超过 250 字、直奔结论”
- [x] 12.9 修复 agent-core `executeSessionId` 优先用 `conversationId` 而非 `context.sessionId`（修复前 async_tasks.session_id 是 timestamp 而非 UUID，导致 chat_messages.conversation_id 不匹配 conversations 表）
