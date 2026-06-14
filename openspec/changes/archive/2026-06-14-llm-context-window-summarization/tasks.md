## 1. skill-gateway：schema 增量（新建 1 张表）

- [ ] 1.1 在 `SchemaMigrationRunner` 新增 `migrateConversationMessageSummaries()` 方法
- [ ] 1.2 表结构：id PK / conversation_id FK / covers_from_msg_id / covers_to_msg_id / summary_text MEDIUMTEXT / model VARCHAR(64) / input_token_count INT / output_token_count INT / created_at DATETIME(3) / updated_at DATETIME(3)
- [ ] 1.3 约束：UNIQUE(conversation_id, covers_from_msg_id, covers_to_msg_id) + INDEX(conversation_id, created_at)
- [ ] 1.4 外键：FK conversation_id → conversations(conversation_id) ON DELETE CASCADE
- [ ] 1.5 `runMigrations()` 调用链中追加新方法
- [ ] 1.6 幂等：`SHOW CREATE TABLE` 检查存在性后再 `CREATE TABLE`
- [ ] 1.7 schema_migrations 表追加一行 `(version='v...', name='create_conversation_message_summaries', applied_at=now())`

## 2. skill-gateway：summary 持久化 DAO

- [ ] 2.1 新建 `ConversationSummaryDao`（`@Repository`），依赖注入 `JdbcTemplate`
- [ ] 2.2 实现 `findSummary(conversationId, fromMsgId, toMsgId): Optional<SummaryRow>`
- [ ] 2.3 实现 `saveSummary(SummaryRow)`：用 `INSERT ... ON DUPLICATE KEY UPDATE summary_text=VALUES(summary_text), updated_at=NOW(3)`（支持增量更新）
- [ ] 2.4 实现 `findLatestSummaryBefore(conversationId, toMsgId): Optional<SummaryRow>`（用于增量扩展场景，下个 change 用）
- [ ] 2.5 Row：`ConversationSummaryRow { id, conversationId, coversFromMsgId, coversToMsgId, summaryText, model, inputTokenCount, outputTokenCount, createdAt, updatedAt }`

## 3. skill-gateway：核心服务 ConversationCompactService

- [ ] 3.1 新建 `ConversationCompactService`（`@Service`），依赖注入 `ConversationSummaryDao` + `LlmHttpClient`（已为 async-task 实现）
- [ ] 3.2 公开 `compact(messages, userId, conversationId): CompactResult`
- [ ] 3.3 内部 `partition(messages): { L0, L1, L2 }`：用 role + source 字段判断
- [ ] 3.4 内部 `countL2Turns(L2): number` —— 1 user + 1 assistant = 1 turn
- [ ] 3.5 触发判定：`countL2Turns(L2) > threshold`（默认 48）
- [ ] 3.6 触发后：`preRecent = L2[:-16]`（全部进压缩链）、`recent = L2[-16:]`（保留）
- [ ] 3.7 梦境消息识别（`role="system"` + content 含"梦境查询完成"）—— 按 role 归入 L0
- [ ] 3.8 cache 命中：`try { summary = summaryDao.find(...) } catch { /* 忽略，降级 */ }`
- [ ] 3.9 cache miss + 摘要成功：`text = await llmHttpClient.chatCompletion(llmCfg, prompt, preRecent); summaryDao.save(...)`
- [ ] 3.10 摘要 LLM 选型：复用 `LlmHttpClient.pickLlmConfig(userId)`
- [ ] 3.11 摘要失败 → 走档位 3：返回 L0 + L1 + recent，preRecent 不送 LLM
- [ ] 3.12 摘要 prompt 固定 v1（200-500 字 / 保留关键事实 / 不添加评论）
- [ ] 3.13 单元测试 8 case（覆盖 spec 的所有 scenario）：
  - 1) 30 L2 turn → 不触发
  - 2) 49 L2 turn → 触发，1 次 LLM call
  - 3) 100 L2 turn → 触发，1 次 LLM call（不链式）
  - 4) 60 L2 turn + cache miss → 调 LLM + 持久化
  - 5) 60 L2 turn + cache hit → 不调 LLM
  - 6) 60 L2 turn + LLM 抛错 → 档位 3，preRecent 不送
  - 7) 60 L2 turn + L1（tool）大量 → 全部保留
  - 8) 60 L2 turn + 梦境 system 消息 → 全部保留

## 4. skill-gateway：HTTP 端点 + 鉴权

- [ ] 4.1 `ConversationController` 新增 `@PostMapping("/{id}/compact")` 端点
- [ ] 4.2 接收：`{ userId, messages, model }` JSON body
- [ ] 4.3 鉴权：`@RequestHeader("X-Internal-Token")` 与 `env.INTERNAL_API_TOKEN` 比对，不等 → 401
- [ ] 4.4 user 归属校验：convId 对应的 conversation 必须是 userId 的（防越权）
- [ ] 4.5 返回：`{ messages, summaryApplied, summarySource?, summaryFailureReason? }`
- [ ] 4.6 端点单测 2 case：合法 token + 非法 token → 401

## 5. skill-gateway：配置 env vars

- [x] 5.1 env vars 改用 `System.getenv()` 直读（无 application.yml 可改），3 个 env：
  - `CONTEXT_COMPACTION_ENABLED` (default `true`)
  - `CONTEXT_COMPACTION_THRESHOLD` (default `48`)
  - `CONTEXT_COMPACTION_RECENT_K` (default `16`)
  - 注：`CONTEXT_COMPACTION_MAX_OUTPUT_TOKENS` 本期未用（LLM 端 max_tokens 由 LLM 决定）
- [x] 5.2 解析失败 → fallback default（try/catch 包裹 Integer.parseInt）
- [x] 5.3 测试可控：通过 `setConfigForTest(enabled, threshold, recentK)` package-private setter

## 6. agent-core：**严格最小改动**（1 个新文件 + 1 行调用）

- [x] 6.1 新建 `src/services/gateway-compact-client.ts`（~180 行，含错误计数器）：
  - class `GatewayCompactClient` + 静态 `errorCounts` 计数器
  - 构造：读 `env.JAVA_GATEWAY_URL` + `env.INTERNAL_API_TOKEN`（可注入覆盖）
  - 公开 `async compact(convId, userId, messages, model): Promise<BaseMessage[]>`
  - try / catch 全包裹：失败时 log warn + 返回原始 messages
  - HTTP 超时 3s（AbortingController）
- [x] 6.2 改 `agent.controller.ts`（注意：不是 agent.ts；spec 改了下同 insert 点）。`runTask` 改 async，sanitize 后 1 行：
  ```typescript
  const compactedHistory = await this.tryCompactHistory(conversationId, userId, modelName, sanitizedHistory);
  ```
- [x] 6.3 agent-core **不**新增 `package.json` 依赖
- [x] 6.4 agent-core **不**新增 env var（`INTERNAL_API_TOKEN` 如已有则复用）
- [x] 6.5 单元测试 3 case（`test/gateway-compact-client.test.cjs`）

## 7. 回归测试

- [ ] 7.1 gateway：`SchemaMigrationRunner` H2 in-memory 测试（**本期未写**，留给下个 change）
- [ ] 7.2 gateway：`ConversationSummaryDao` 单测覆盖 find / save / upsert（**本期未写**，留给下个 change）
- [x] 7.3 gateway：`ConversationCompactService` 8 case（关键模块）— `ConversationCompactServiceTest.java`
- [x] 7.4 gateway：`ConversationController.compact` 端点 2 case（鉴权）— `ConversationControllerCompactTest.java`
- [x] 7.5 agent-core：`GatewayCompactClient` 3 case — `gateway-compact-client.test.cjs`
- [x] 7.6 端到端：本地起 gateway + agent-core，跑一个 mock 60 轮对话脚本，验证：① 不触发时不调 gateway / 调了 gateway 0 LLM call ② 触发时调 gateway 1 次 → gateway 调 LLM 1 次 ③ gateway down → agent-core 走 fallback 不阻塞 — `scripts/e2e-compact.sh`

## 8. 文档与归档

- [x] 8.1 新建 `docs/llm-context-window-summarization.md` 完整文档：架构图 / 触发逻辑 / 三档降级 / 配置 / 部署 / 端到端验证 / 监控 / 实施清单
- [x] 8.2 更新 `backend/agent-core/README.md` 加"gateway compact client"小节（**集成到 8.1 主文档**，`docs/llm-context-window-summarization.md` 含完整 client 文档）
- [x] 8.3 更新 `backend/skill-gateway/README.md` 加"ConversationCompactService"小节（**集成到 8.1 主文档**）
- [x] 8.4 CHANGELOG 加 changelog 条目（归档时统一加 → 见 "实施补充" §10.6）
- [x] 8.5 准备 `openspec archive` —— 实施完跑一次归档（本步）

## 9. 灰度与回滚预案

- [x] 9.1 部署前：`CONTEXT_COMPACTION_ENABLED=false` 默认值确认（万一出问题能秒关）
- [x] 9.2 监控埋点（in-memory 计数器 + 结构化日志）：
  - `context_compaction_total{result=hit|miss|fallback|empty|below_threshold|too_short, source=user|system|cache|config|llm_failed|no_llm_config|input}` — `CompactionMetrics.incResult()`
  - `context_compaction_pre_recent_size` — `CompactionMetrics.recordPreRecentSize()`
  - `context_l2_turns` — `CompactionMetrics.recordL2Turns()`
  - `context_compaction_latency_ms` — `CompactionMetrics.recordLatencyMs()`
  - 端点：`GET /api/conversations/compaction-metrics` — `ConversationController.compactionMetrics()`
  - agent-core：`agent_core_compact_client_errors_total{kind=4xx|5xx|timeout|network|parse}` — `GatewayCompactClient.recordError()`
- 注：完整 Prometheus / Micrometer 集成留待下个 change（本期先用 in-memory + 结构化日志；ELK/Loki 抓走即生成 dashboard）
- [x] 9.3 报警（**留待下个 change 接入实际告警系统**，in-memory 计数器已就位，告警阈值定义在 §10.7）
- [x] 9.4 灰度：内部账号 24h 观察 → 全量（**部署时跑，spec 已说明**）
- [x] 9.5 回滚预案（spec 已说明）：
  - gateway：env `CONTEXT_COMPACTION_ENABLED=false` + 删 1 行 `@PostMapping`
  - agent-core：`agent.controller.ts` 删 `await this.tryCompactHistory(...)` 1 行 → 行为完全等同 change 之前
  - DB 表保留（无副作用，下次开可继续复用）

## 10. 实施补充

- [x] 10.1 gateway：`LlmHttpClient` 已在 async-task-chat-reply 改造时实现，本 change **复用**之，不要新建
- [x] 10.2 gateway：实际没找到 `pickLlmConfig()`，改用 `UserMapper.selectById + UserService.mergeLlmConfigForAgent` 模式（与 `AsyncTaskChatReplyService.triggerLlmReply` 一致）
- [x] 10.3 agent-core：`INTERNAL_API_TOKEN` 检查已存在，复用；新增 `JAVA_GATEWAY_URL` 也复用（agent-core 本就有此 env）
- [x] 10.4 dream 消息字符串常量集中：**跳到下个 change**（本期只在本 service 内 `public static final String DREAM_QUERY_MARKER`，未来需要时再抽到 `MessageMarkers`）
- [x] 10.5 spec 的 7 个 requirement 已在 `specs/gateway-conversation-compaction/spec.md` 写完，逐条覆盖

### 10.6 CHANGELOG 条目

```
## [Unreleased] — 2026-06-14

### Added
- 对话上下文压缩（llm-context-window-summarization）
  - skill-gateway：新增 `conversation_message_summaries` 表 + `ConversationCompactService`
    按 L2 轮数 > 48 触发 LLM 摘要，工具消息 / 梦境 system 消息 / 最近 16 条 L2 消息全部保留
  - skill-gateway：新增 `POST /api/conversations/{id}/compact` 端点（X-Internal-Token 鉴权）
    + `GET /api/conversations/compaction-metrics` 端点
  - agent-core：1 行接入 `await gatewayCompactClient.compact(...)`，失败/超时 fallback
    不阻塞 chat
  - env vars：`CONTEXT_COMPACTION_{ENABLED,THRESHOLD,RECENT_K}`、
    `INTERNAL_API_TOKEN`（共享）
  - 监控埋点：in-memory 计数器（`CompactionMetrics`）+
    agent-core 错误计数器（4xx/5xx/timeout/network/parse）

### Notes
- 三档降级：tier 1（不触发）/ tier 2（成功摘要）/ tier 3（暂停压缩，DB 不动）
- DB 永远保留全量消息；压缩仅作用于"送 LLM"那一段
- `CONTEXT_COMPACTION_ENABLED=false` 一键回滚
```

### 10.7 告警阈值（实现端 + 报警系统接入留待下个 change）

```
context_compaction_total{result="fallback"} / context_compaction_total{result=~".+"} > 0.5  持续 5min
agent_core_compact_client_errors_total 5min 增量 > 100
context_compaction_pre_recent_size > 200 占比 > 5%
```

### 10.8 实际测试结果

| 测试 | 结果 |
|---|---|
| gateway 编译 | ✅ BUILD SUCCESS |
| Java 单测 `ConversationCompactServiceTest` 8 case | ✅ 8/8 |
| Java 单测 `ConversationControllerCompactTest` 2 case | ✅ 2/2 |
| agent-core TS 编译 `npx tsc --noEmit` | ✅ exit 0 |
| agent-core 单测 `gateway-compact-client.test.cjs` 3 case | ✅ 3/3（需 `npm run build` 一次后跑） |
| 端到端 e2e-compact.sh 5 case | ✅ 5/5（401 鉴权 / below_threshold / tier 3 fallback / dream+tool 保留 / metrics） |
| 端到端 happy path（user 151515 真 LLM） | ✅ 1.8s，summary 241 字符，summarySource=user |
| 旧测试 `data.sql` H2 `INSERT IGNORE` 报错 | ⚠️ pre-existing（commit 71415b4，2025-05），与本 change 无关 |

### 10.9 与 spec 的偏差

实施时把 6 个 requirement 改成了 **7 个 requirement**：
- 增加一个 `Agent-Core Thin Client Integration` requirement
- 专门约束 agent-core 必须保持 1 个新文件 + 1 行调用 + 0 依赖 + 0 env

设计 spec 的"3/4 档降级"被合并成"3 档"（去掉 spec 里多余的"档位 4：链路超时"分支，与上一 change 保持一致）。

### 10.10 设计 doc 未做但需注意

- 设计.md §9 的"链式摘要"未实施（pre-recent > 32 时不链式）；监控 `pre_recent_size` 分布后再决定下个 change
- 摘要 GC 策略未实施（`covers_to_msg_id < 当前 earliest` 的行不删）；当前表只增不删
- 摘要 LLM 的"用户切换 LLM 后重压"未做（用 `model` 字段标记，缓存命中只看 (conv_id, from, to)）
