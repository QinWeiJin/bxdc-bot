## Why

当前对话的 history 在送 LLM 之前不做任何长度管理，agent-core 把 gateway
持久化的全量 `conversation_messages` 原样塞进 LangChain 的 `MessagesPlaceholder`。
当用户轮数变多（实测 30+ 轮 user/assistant 反复交换）时，prompt 总量超过
模型 context window 上限（qwen-long 32k / gpt-4o 128k / deepseek 64k 各异），
导致：
- 模型直接报错 "context_length_exceeded"；
- 即便不报错，response 也变慢且单次 cost 飙升；
- 长 prompt 噪声大，模型对"最近问题"和"工具结果"的关注度被稀释，回答质量下降。

需要一个**按 L2 轮数动态压缩**机制，**只压缩 user/assistant 对话内容**，
**tool 和 skills 消息原样保留**（异步任务结果 / 工具调用返回值是当前对话
的真实数据源，丢不得）。

**架构约束（用户指定）**：
- ❌ **agent-core 不能大改**——只能小改（保持 1 行调用级别）
- ✅ 压缩 / 摘要 / 缓存逻辑**全部在 gateway**实现
- ✅ agent-core 在送 LLM 前**只调一次** gateway 的 compact 接口，拿到处理后的
  messages 数组
- ✅ DB 缓存**在 gateway 侧**（gateway 已有自己的 schema migration 机制）

## What Changes

- **gateway 侧新增** `ConversationCompactService`，负责：
  - 接收 agent-core 的 compact 请求（含 messages 列表 + userId + model）
  - 按 L2 轮数 > 48 触发摘要（tool/skills 不计入 L2 轮数）
  - 分层：L0（system，含"梦境"消息）永保留 + L1（tool / skills）永保留 +
    L2（user / assistant）按 recent 16 保留 / pre-recent 16 全部进压缩链
  - 调 LLM（用户配置 → 系统默认 fallback）做摘要，**单次 LLM call**（不链式，
    简化实现）
  - 摘要结果持久化到 `conversation_message_summaries` 表（DB 唯一键
    `(conversation_id, covers_from_msg_id, covers_to_msg_id)`）
  - 同一 range 二次走 compact → 0 次 LLM call（命中缓存）
  - 失败时降级为"暂停压缩"：pre-recent 16 不送 LLM（DB 不动），返回 L0 + L1
    + recent 16
- **gateway 新增端点** `POST /api/conversations/{id}/compact`：
  - input: `{ userId, messages, model }`
  - output: `{ messages: ChatMessage[], summaryApplied: boolean, summarySource?: 'user' | 'system' | 'cache' }`
  - 内部用 INTERNAL_API_TOKEN 鉴权（agent-core 调）
- **agent-core 侧**：在 `agent.ts` 拼装 messages 后、塞进 LangChain 前**新增 1 行**
  HTTP 调用 gateway compact 端点：
  ```typescript
  messages = await gatewayClient.compactMessages(conversationId, userId, messages, model);
  ```
- gateway 持久化层**保持原状**——全量 message 仍写入 `conversation_messages`，
  压缩只发生在"送 LLM"那一环。
- agent-core 改造**严格限制**：
  - 最多 1 个新文件（`src/services/gateway-compact-client.ts`）
  - `agent.ts` 1 行调用
  - **不**新增 `lru-cache` 依赖、**不**新增环境变量
  - 失败 fallback：直接用原始 messages（不压缩，让 LLM 自己处理；不阻塞 chat）

## Capabilities

### New Capabilities

- `gateway-conversation-compaction`: gateway 侧对话上下文压缩（L2 轮数 > 48
  触发 LLM 摘要；user/assistant 摘要，tool/skills/梦境 system 消息原样保留；
  summary DB 持久化复用；agent-core 通过 1 个 HTTP 调用接入）

### Modified Capabilities

<!-- 暂无 spec-level 行为变更。`async-task-chat-reply` 关注"异步任务结果回灌"，
     与本 change 解耦，不列。 -->

## Impact

- **skill-gateway**（**主战场**，新增 5 个文件 / 改 2 个文件）：
  - 新增 `SchemaMigrationRunner` 增量 1 张表 `conversation_message_summaries`
    (id, conversation_id, covers_from_msg_id, covers_to_msg_id, summary_text
    MEDIUMTEXT, model VARCHAR(64), input_token_count INT, output_token_count
    INT, created_at DATETIME(3), updated_at DATETIME(3),
    UNIQUE(conversation_id, covers_from_msg_id, covers_to_msg_id),
    INDEX(conversation_id, created_at),
    FK conversation_id → conversations(conversation_id) ON DELETE CASCADE)
  - 新增 `ConversationCompactService`（核心，注入 LlmHttpClient +
    ConversationSummaryDao）
  - 新增 `ConversationSummaryDao`（JdbcTemplate CRUD）
  - 新增 `ConversationController` 端点 `POST /api/conversations/{id}/compact`
    （userId 归属校验 + INTERNAL_API_TOKEN 鉴权）
  - 复用现有 `LlmHttpClient`（已为 async-task 写过）
- **agent-core**（**严格最小**）：
  - 新增 `src/services/gateway-compact-client.ts`（~50 行：HTTP 调 gateway
    compact 端点）
  - 改 `src/agent/agent.ts` 1 行：在拼装 messages 之后、调 LangChain 之前
    `messages = await gatewayCompactClient.compact(convId, userId, messages, modelName)`
  - **不**新增 `lru-cache` 依赖、**不**新增环境变量
- **前端**：无改动
- **DB**：+1 张表（gateway 侧）
- **配置**（env，仅 gateway）：
  - `CONTEXT_COMPACTION_ENABLED` (default `true`)
  - `CONTEXT_COMPACTION_THRESHOLD` (default `48` —— L2 轮数 > 此值触发)
  - `CONTEXT_COMPACTION_RECENT_K` (default `16`)
  - `CONTEXT_COMPACTION_MAX_OUTPUT_TOKENS` (default `500`)
  - 摘要 LLM 复用现有用户 LLM 配置（`LlmHttpClient.pickLlmConfig` 已实现）
- **回退**：摘要 LLM 失败时，gateway 返回"未压缩"的原始 messages，agent-core
  原样塞给 LLM（可能 overflow，**由 LLM 自己处理**——不抛错）。
- **测试**：
  - gateway：`SchemaMigrationRunner` H2 测试 + `ConversationCompactService` 8 case
    + `ConversationSummaryDao` 4 case + `ConversationController` 端点 2 case
  - agent-core：`GatewayCompactClient` 3 case（成功 / 失败 fallback / 超时 fallback）

## 架构示意

```
┌────────────────┐                          ┌────────────────────────┐
│   agent-core   │  POST /api/conversations │      skill-gateway     │
│                │  /{id}/compact           │                        │
│  agent.ts ─────┼─────────────────────────►│  ConversationController│
│   │            │  { messages,             │   │                    │
│   │ 1 line     │    userId, model }       │   │                    │
│   ▼            │                          │   ▼                    │
│  LangChain ────┼─── { messages,            │  ConversationCompact   │
│  (LLM call)    │     summaryApplied }     │  Service               │
│                │  ◄───────────────────────┤   │                    │
└────────────────┘                          │   ├─► trigger check   │
                                            │   ├─► partition       │
                                            │   ├─► cache check     │
                                            │   │    (DB)           │
                                            │   ├─► LLM call        │
                                            │   │    (LlmHttpClient)│
                                            │   └─► assemble        │
                                            └────────────────────────┘
                                                       │
                                                       ▼
                                            ┌────────────────────────┐
                                            │  MySQL                 │
                                            │  - conversations       │
                                            │  - conversation_msgs   │
                                            │  - conversation_message│
                                            │    _summaries (NEW)    │
                                            └────────────────────────┘
```

## 实施补充（2026-06-14 实施完成后补录）

> spec 跑完 4 轮设计迭代 + 7 个 requirement 落定后实施；本节记录**与原始 spec 的偏差**与**实施后实际写入的内容**。

### 实施偏差

| 项 | spec 写 | 实际写 | 理由 |
|---|---|---|---|
| agent-core 接入点 | `agent.ts` 1 行 | **`agent.controller.ts` 1 行**（sanitize 之后） | `agent.ts` 是 agent 工厂，消息是 LangGraph state；compact 必须在 messages 进 state 之前调 |
| 摘要 LLM 选型 | 复用 `LlmHttpClient.pickLlmConfig(userId)` | 改用 `UserMapper.selectById + UserService.mergeLlmConfigForAgent` | codebase 实际没 `pickLlmConfig`；`AsyncTaskChatReplyService` 用的是这个模式 |
| 鉴权 | env `INTERNAL_API_TOKEN` | env 优先 + **system property 优先**（测试用） | 让 controller 端点 2 case 单测能 stub token |
| Requirement 数 | 6 | **7**（多 1 个 `Agent-Core Thin Client Integration`） | 专门约束 agent-core 不许大改 |
| env var 数 | 4（含 MAX_OUTPUT_TOKENS） | 3（去 MAX_OUTPUT_TOKENS） | LLM 端 max_tokens 由 LLM config 决定，不在 gateway 强约束 |
| 监控实现 | Prometheus / Micrometer | **in-memory 计数器 + 结构化日志** | 留待下个 change 接 Micrometer；ELK/Loki 抓走 structured log 即可生成 dashboard |
| Controller 端点数 | 1（/compact） | 2（+ /compaction-metrics） | 加 metrics 端点方便运维查 |

### 实施后实际文件清单

| 类型 | 数量 | 路径 |
|---|---|---|
| 新增 Java 源 | 4 | `dto/ConversationSummaryRow.java`、`service/ConversationSummaryDao.java`、`service/ConversationCompactService.java`、`metrics/CompactionMetrics.java` |
| 修改 Java 源 | 3 | `config/SchemaMigrationRunner.java`、`controller/ConversationController.java`、`service/ConversationCompactService.java` 改造（用 env var + instance field 替代 @Value） |
| 新增 TS 源 | 1 | `services/gateway-compact-client.ts` |
| 修改 TS 源 | 1 | `controller/agent.controller.ts`（runTask 改 async + 1 行调用 + 3 helper 方法） |
| 新增单测 | 3 | Java: `ConversationCompactServiceTest.java` (8 case)、`ConversationControllerCompactTest.java` (2 case)；TS: `gateway-compact-client.test.cjs` (3 case) |
| 新增 e2e 脚本 | 1 | `backend/agent-core/scripts/e2e-compact.sh`（5 case 验证） |
| 新增文档 | 1 | `docs/llm-context-window-summarization.md`（架构图 + 触发逻辑 + 三档降级 + 部署 + 端到端验证 + 监控） |

### 端到端验证结果

✅ **8/8 全过**（详见 tasks.md §10.8）：

1. gateway 编译 → BUILD SUCCESS
2. Java 单测 10 case → 10/10 pass
3. agent-core TS 编译 → exit 0
4. agent-core 单测 3 case → 3/3 pass
5. e2e 5 case → 5/5 pass
6. happy path (user 151515 + minimaxi.com LLM) → 200, 1.8s, 摘要 241 chars
7. DB schema 自动迁移 → `conversation_message_summaries` 表存在
8. 鉴权 → 错 token 401，合法 token 200

### 后续可做（留给下个 change）

- [ ] SchemaMigrationRunner H2 幂等测试（7.1）
- [ ] ConversationSummaryDao 单测（7.2）
- [ ] MessageMarkers 集中常量（10.4）
- [ ] 链式摘要（pre-recent > 32 时分段 LLM）
- [ ] 摘要 GC（定期软删除 `covers_to_msg_id < 当前 earliest` 的行）
- [ ] Micrometer / Prometheus 集成
- [ ] 实际告警系统接入（10.7 阈值）
