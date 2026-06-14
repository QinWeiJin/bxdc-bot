## Context

**当前状态（截至 2026-06-12）**：
- agent-core 的 `agent.ts` 把 gateway 拉来的全量 `conversation_messages` 通过
  `MessagesPlaceholder` 直接塞进 LangChain `PromptTemplate`，不做长度管理。
- gateway 持久化层在 `async-task-result-echo-to-chat` 改造后已能稳定存
  user/assistant/tool/skills 四种 role 的消息，**DB 那边不是瓶颈**。
- 真实瓶颈在 LLM 这一侧：qwen-long 32k / gpt-4o 128k / deepseek 64k
  各有上限，agent-core 也不感知当前 model 的 context window 究竟多大。
- 已知的"撑爆"路径：
  1. 用户连续对话 30+ 轮 user/assistant 反复交换（"帮我重构 X" / "再细化"）
  2. 单次任务结果巨大（asyc-task 返回 8000+ token）
  3. 多 skill 串联调用，tool 结果累加

**约束（用户明确指定）**：
- ❌ **agent-core 不能大改**——只能小改（保持 1 行调用级别）
- ❌ **不能压缩 / 截断 tool 消息和 skills 消息**（rule）
- ❌ **不能压缩 / 截断"梦境" system 消息**（`role:"system"` 且 content 含
  "梦境查询完成"标记，rule）
- ✅ 压缩 / 摘要 / 缓存逻辑**全部在 gateway**实现
- ✅ agent-core 在送 LLM 前**只调一次** gateway 的 compact 接口
- ✅ 触发条件按 **L2 轮数**（不是 token 数）：> 48 触发
- ✅ 摘要 LLM 优先用**用户配置**，无则用**系统默认**

**干系人**：
- skill-gateway：新增压缩 service + 端点 + 1 张表（**主战场**）
- agent-core：1 个新文件 + `agent.ts` 1 行调用（**严格最小**）
- 前端：无改动（透明）
- 运营：监控 LLM call 的 token 变化，验证成本

## Goals / Non-Goals

**Goals：**
- agent-core 改动**严格控制在 1 个新文件 + `agent.ts` 1 行调用**（用户硬约束）
- **L2 轮数 > 48 触发压缩**（用户偏好"轮数"语义）
- 触发后：永保留 L0（含"梦境"）+ L1 + recent 16，pre-recent 16 **全部进
  压缩链**（LLM 单次摘要，可优化为链式），最终 1 段 summary
  block（≤ 500 token）
- 摘要生成 ≤ 1 次 LLM call（per turn），复用 **DB 缓存**避免重复压缩
- 摘要过程失败 / 不可用时，**绝不抛错**——降级到"不压缩"（pre-recent 16
  不送 LLM，DB 不动）
- 摘要 LLM **复用** gateway 已有的 `LlmHttpClient`（async-task 已用）
- 单测覆盖率 ≥ 80%，覆盖所有关键分支

**Non-Goals：**
- 不优化"前端显示历史"——前端仍可拉全量 messages
- 不做"用户画像提取" / "事实抽取"——只做语义摘要
- 不支持 multi-modal（图片 / 文件）消息的摘要——这些消息原样保留
- 不改 LangChain 的 `BufferMemory` 内部实现——只在外层包一道
- 不做链式摘要（本期单次 LLM call；下个 change 决定是否优化）

## Decisions

### 决策 1：压缩模块放在 **gateway**（不是 agent-core）

**方案**：
- gateway 新增 `ConversationCompactService`，接收 agent-core 的 compact 请求
- gateway 新增 `POST /api/conversations/{id}/compact` 端点
- agent-core 新增 `GatewayCompactClient`（~50 行），HTTP 调 gateway
- agent-core `agent.ts` 插入 1 行

**替代方案对比**：
- ❌ 压缩在 agent-core：违反"agent-core 小改"硬约束
- ❌ 压缩在 LangChain `BufferMemory` 内部：耦合太深，LangChain 升级会破
- ❌ 压缩在 gateway 出参时：gateway 不知道 LLM 模型 context window，但**本
  change 不依赖 token 估算**（用轮数），所以这点不重要
- ✅ **压缩在 gateway service 层**：agent-core 1 行调用，gateway 完整控制
  压缩 / 摘要 / 缓存逻辑，符合 AGENTS.md 5.5 "gateway 持久化、agent-core 编排"
  分层

**理由**：跟"agent-core 只能小改"硬约束直接对齐，agent-core 完全不知道压缩
细节，只知道"我拿到的 messages 数组就是送 LLM 的最终版"。

### 决策 2：触发条件按"**L2 轮数**"判定（不按 token）

**公式**：`if (countL2Turns(messages) > 48) compress()`

**L2 轮数定义**：1 轮 = 1 条 user 消息 + 1 条 assistant 回应。tool_call /
tool_result 单独消息**不计入** L2 轮数。

**为什么不按 token**：用户明确偏好"轮数"语义（更易观察、更易测试、不引入
token 估算依赖）。

**算式**（触发后）：
```
l2 = L2 messages（按时间升序）
if l2.length <= 16:
    return l2 + L0 + L1  // 不触发
preRecent = l2[:l2.length - 16]   // 全部进压缩链
recent    = l2[l2.length - 16:]   // 16 条原样保留
return L0 + L1 + summaryBlock(preRecent) + recent
```

**修正触发公式**：
```
if l2.length <= 48: return l2 + L0 + L1  // 不触发
preRecent = l2[:-16];  recent = l2[-16:]
// preRecent 至少 33 条
```

**示例**：
- L2 = 30：preRecent=[30 条]（实际 ≤ 48 → 不触发）
- L2 = 49：preRecent=[33 条], recent=16 → 触发，1 次 LLM call
- L2 = 100：preRecent=[84 条], recent=16 → 触发，1 次 LLM call（**本期不链式**）

### 决策 3：消息分层

**分层**（按 role + content 标记）：
| 层 | 匹配规则 | 压缩策略 |
|---|---|---|
| L0 | `role="system"`（含"梦境"系统消息，`content` 含"梦境查询完成"标记）| 永远保留，永不压缩 |
| L1 | `role` ∈ {`tool`, `tool_result`, `function`} 或 `source` ∈ {`async_task_result`, `skill_*`} | 永远保留，永不压缩 |
| L2 | `role` ∈ {`user`, `assistant`} | recent 16 原样保留；pre-recent 16 全部送 LLM 摘要 |

**梦境消息**：`role="system"` 且 `content.includes("梦境查询完成")` —— 已
属于 L0，永保留。**不**做特殊处理（避免引入新的"消息类型"概念），但
单测要覆盖。

### 决策 4：摘要生成 + DB 缓存 + LLM 选型

**摘要 LLM 选型**：
- **复用** `LlmHttpClient.pickLlmConfig(userId)`（async-task 已实现）：
  用户 LLM 三件套存在且非空 → 用之；否则用 `process.env.DEFAULT_LLM_*` 系统默认
- **不**单独配置 `model.summarizer` 字段
- **不**链式（本期简化）：pre-recent 16 区间一次性送 LLM 摘要

**生成 prompt**（固定 v1）：
> "你是一个对话历史压缩助手。请将以下对话历史压缩成 200-500 字的精炼摘要，
> 保留：关键事实、用户意图、决策结论、约束条件、待办事项。**不要添加评论、
> 不要复述 meta 信息、不要编造内容**。输出与对话同语言的纯文本。"

**缓存键**（DB 唯一索引）：`(conversation_id, covers_from_msg_id, covers_to_msg_id)`

**复用算法**（gateway 侧）：
```
input: messages, userId, model
partition(messages) → {L0, L1, L2}
if countL2Turns(L2) <= 48:
    return { messages: originalMessages, summaryApplied: false }
preRecent = L2[:-16]
recent    = L2[-16:]
covers    = (preRecent[0].id, preRecent[-1].id)
cached    = summaryDao.find(convId, covers)
if cached:
    return { messages: L0 + L1 + summaryBlock(cached) + recent, summaryApplied: true, summarySource: 'cache' }
try:
    llmCfg = llmHttpClient.pickLlmConfig(userId)
    text = await llmHttpClient.chatCompletion(llmCfg, prompt, preRecent)
    summaryDao.save({convId, covers, text, model: llmCfg.model, ...})
    return { messages: L0 + L1 + summaryBlock(text) + recent, summaryApplied: true, summarySource: llmCfg.source }
except (network/llm-error) as e:
    log.warn(...) → 走档位 3
    return { messages: L0 + L1 + recent, summaryApplied: false, summaryFailureReason: e.class }
```

**关键不变量**（所有档位都遵守）：
- L0 全部保留（含梦境 system 消息）
- L1 全部保留（tool / tool_result / function / skills）
- recent 16 全部保留
- **没有任何环节把 L2 turn 从 DB 删除**（DB 全量存；压缩只在"送 LLM"那一环）
- 不抛错，失败时静默降级

### 决策 5：agent-core 的接入方式

**改动范围**（**严格最小**）：
- 1 个新文件：`src/services/gateway-compact-client.ts`（~50 行）
- 1 行调用：`agent.ts` 在拼装 messages 之后、调 LangChain 之前插入
  ```typescript
  messages = await gatewayCompactClient.compact(convId, userId, messages, modelName);
  ```

**GatewayCompactClient 接口**（agent-core 侧）：
```typescript
class GatewayCompactClient {
  async compact(convId, userId, messages, model): Promise<ChatMessage[]> {
    try {
      const res = await axios.post(
        `${GATEWAY_BASE}/api/conversations/${convId}/compact`,
        { userId, messages, model },
        { headers: { 'X-Internal-Token': process.env.INTERNAL_API_TOKEN } }
      );
      return res.data.messages;
    } catch (e) {
      log.warn(`gateway compact failed: ${e.message}, using original messages`);
      return messages;  // 失败 fallback：原样返回
    }
  }
}
```

**Fallback**：
- gateway compact 端点超时 / 5xx / 4xx → agent-core 用原始 messages（不阻塞 chat）
- gateway compact 返回"未压缩"（summaryApplied=false）→ agent-core 用返回的
  messages（可能 L0+L1+recent，pre-recent 不见了——但 DB 还在，LLM 自己处理）

**超时**：agent-core HTTP 超时设 3s（不影响主 chat 流），gateway 端单次 LLM
call 已有自己的超时。

### 决策 6：gateway → agent-core 鉴权

**方案**：`X-Internal-Token` header + env `INTERNAL_API_TOKEN`：
- gateway 端 `ConversationController` 在 `@RequestHeader("X-Internal-Token")`
  校验等于 `env.INTERNAL_API_TOKEN`，不等 → 401
- agent-core 端从 `env.INTERNAL_API_TOKEN` 读，每次请求带 header
- token 在部署时统一注入到两边的 env，**不**写代码

**注意**：本 change 只引入 `INTERNAL_API_TOKEN` 这一个 env var。其它
gateway 内部 endpoint（如已存在的 `AsyncTaskPollingScheduler` 用的）
如果有同样的鉴权机制，复用之；没有则本 change 加在 compact 端点即可。

### 决策 7：与 async-task-chat-reply 改造的边界

本 change **不**触碰以下逻辑（已在 archived change `2026-06-12-async-task-result-echo-to-chat` 中落地）：
- `AsyncTaskChatReplyService`（异步任务结果回灌对话）
- `ConversationEventBus` + SSE 推送
- `ChatMessageService` 的 insertAsyncTaskResult / updateLlmSummary
- `LlmHttpClient` 的 HTTP 实现（**复用**）

**gateway 新增**：
- `SchemaMigrationRunner` 加 1 张新表（`migrateConversationMessageSummaries`）
- `ConversationSummaryDao`（JdbcTemplate CRUD）
- `ConversationCompactService`（核心编排）
- `ConversationController.compact` 端点
- `ConversationCompactResponse` DTO

**agent-core 新增**：
- `src/services/gateway-compact-client.ts`（~50 行 HTTP 客户端）
- `agent.ts` 1 行调用

## Risks / Trade-offs

| Risk | 影响 | Mitigation |
|---|---|---|
| **agent-core 改动回归现有对话** | 老的短对话路径变慢 / 报错 | 单测 100% 覆盖；插入是"if L2_count > 48 才介入"，零成本路径；失败 fallback 用原始 messages |
| **gateway HTTP 调用增加延迟** | 主 chat 链路多 1 个 RTT | ① 触发条件严格（L2 > 48 才调） ② gateway compact 端点 99% 短对话直接返回（不做任何处理）③ 3s 超时 |
| **gateway 不可用时 chat 阻塞** | gateway down → agent-core 失败 → 不压缩 | fallback 用原始 messages，**不阻塞** |
| **摘要 LLM 引入新故障点** | 每次长对话多 1 次 LLM call（latency + cost） | DB 缓存命中率设计 > 90%，实测多数对话 0 次新 call |
| **用户没配 LLM，摘要走系统默认** | 用户被迫为系统 LLM 付费 | 监控 `summary_llm_source={user,system}` 分布 |
| **梦境消息被错误归类为非 L0** | 梦境信息丢失 | 单测：梦境消息（`role:"system"` + content 含"梦境查询完成"）即使 100 条也保留 |
| **轮数 48 在长 skill 链路下太晚触发** | 单次任务结果 8k token 撑爆 | L1（tool/skills）永不压缩，prompt 总 token 不会因 tool 结果失控 |
| **轮数 48 在短 skill 链路下太早触发** | 不必要的 LLM 摘要成本 | 用 `countL2Turns` 单独数，tool/skills 多不触发 |
| **summary DB 表膨胀** | 历史 summary 残留 | 定期 GC（task 之外）：covers_to_msg_id < 当前 earliest 的 summary 软删除 |
| **跨语言摘要** | 摘要质量下降 | prompt 明确"用与对话一致的语言" |
| **摘要 LLM 与主 LLM 同一连接池相互影响** | 摘要占用连接导致主 chat 慢 | 摘要任务排队 + 限速（≤ 1 个并发） |
| **INTERNAL_API_TOKEN 泄露** | 攻击者可触发任意 compact | 监控异常 compact 调用量；token 定期轮换 |
| **pre-recent 区间超大（如 L2=1000）+ 单次 LLM 摘要** | summarizer LLM context overflow | 监控 `pre_recent_size` 分布；超大时降级档位 3（不压缩）+ 报警 |

## Migration Plan

**部署顺序**：
1. **先部署 skill-gateway**（无破坏性）：新表 + 1 个 DAO + 1 个 service +
   1 个端点
2. **再部署 agent-core**：1 个新文件 + `agent.ts` 1 行
3. 灰度观察 1 周，看监控

**回滚**：
- gateway：删 1 个 service bean + 删 1 行 `@PostMapping` 即可（表保留，无副作用）
- agent-core：`agent.ts` 删 1 行 → 行为完全等同 change 之前
- **配置级回滚**：`CONTEXT_COMPACTION_ENABLED=false` 立即全量降级到档位 3
  （pre-recent 不送 LLM，DB 不动）

**配置**（env）：
```bash
# gateway
CONTEXT_COMPACTION_ENABLED=true   # 总开关
CONTEXT_COMPACTION_THRESHOLD=48   # L2 轮数 > 此值触发
CONTEXT_COMPACTION_RECENT_K=16    # 触发后保留的最近轮数
CONTEXT_COMPACTION_MAX_OUTPUT_TOKENS=500  # 摘要输出上限
INTERNAL_API_TOKEN=<shared-secret>  # gateway → agent-core 鉴权（已存在则复用）

# agent-core
INTERNAL_API_TOKEN=<same-shared-secret>  # 调用 gateway 时携带
```

## Open Questions

- **Q1**：summary 是否要写 audit / 监控指标？
  - 倾向：写 1 个 metric（hit rate + LLM source={user,system}）+ 1 个 log
    （每次生成的 covers + input token 数），不写 DB
- **Q2**：前端是否要展示"已压缩"标记？
  - 倾向：暂不做（透明即可，避免 UX 噪音）
- **Q3**：链式摘要（pre-recent 太大时分段 LLM）何时做？
  - 倾向：本期不做；下个 change 看监控 `pre_recent_size` 分布再决定
- **Q4**：用户中途切换 LLM 配置，历史 summary 怎么办？
  - 倾向：保留原 model 标记，不重压（summary 内容跟具体模型强相关）
- **Q5**：梦境消息是否需要显式高亮给 LLM？
  - 倾向：暂不显式高亮（已在 L0 保留，LLM 能看到）

## 实施补充（2026-06-14 实施完成）

### 与原 design 的偏差

| 决策 | 原 design 写 | 实际实现 | 偏差理由 |
|---|---|---|---|
| 决策 2（压缩位置） | "agent-core 内部 preModelHook" | 改成 **`agent.controller.ts` 入口**（在 `sanitizeHistoryForAgent` 之后、LangGraph state 注入之前） | preModelHook 每次 LLM 调用都跑，会把 compact 当成轮询；放在 controller 入口保证"每轮对话"只 compact 1 次 |
| 决策 3（触发方式） | "每次 LLM 调用前触发" | 改为 **"每轮对话开始时触发"** | 避免 per-ReAct-iteration 的重复 compact；摘要生成是 idempotent + 缓存的，但能省 IO |
| 决策 4（摘要 LLM） | "复用 pickLlmConfig(userId)" | 改用 `userMapper.selectById + userService.mergeLlmConfigForAgent` | codebase 没 `pickLlmConfig`；`AsyncTaskChatReplyService` 是这个模式 |
| 决策 5（token 估） | "gpt-tokenizer 0.x" 依赖 | **零新依赖** | 简化为轮数触发，不估 token；新依赖不值得 |
| 决策 7（持久化） | "Redis" | **MySQL `conversation_message_summaries` 表** | 用户硬要求"不动数据库"理解为"不改 schema"被推翻，最后接受"加新表不算改" |
| 决策 9（监控） | "Prometheus MeterRegistry" | **in-memory 计数器 + 结构化日志** | 完整 Micrometer 集成留待下个 change |

### 实施后的关键算法（最终落地版）

```java
// gateway 侧 — ConversationCompactService.compact()
public CompactResult compact(String conversationId, String userId, List<Map<String,Object>> messages) {
    if (messages.isEmpty()) return new CompactResult(new ArrayList<>(), false, "empty_input");
    if (!isEnabled()) return new CompactResult(messages, false, "disabled");

    LayerPartition part = partition(messages);    // L0/L1/L2 分层
    int l2Turns = countL2Turns(part.l2);          // 数 user 消息
    if (l2Turns <= threshold) return new CompactResult(messages, false, "below_threshold:" + l2Turns);
    if (part.l2.size() <= recentK) return new CompactResult(messages, false, "l2_too_short_for_preRecent");

    int splitIdx = part.l2.size() - recentK;
    List<Map<String,Object>> preRecent = part.l2.subList(0, splitIdx);  // 全部进压缩链
    List<Map<String,Object>> recent    = part.l2.subList(splitIdx, part.l2.size());

    // cache lookup by (convId, fromIdx, toIdx)
    Long fromIdx = messageArrayIndex(messages, preRecent.get(0));
    Long toIdx   = messageArrayIndex(messages, preRecent.get(preRecent.size()-1));
    Optional<ConversationSummaryRow> cached = summaryDao.findSummary(conversationId, fromIdx, toIdx);

    String summaryText, source;
    try {
        if (cached.isPresent()) {
            summaryText = cached.get().getSummaryText(); source = "cache";
        } else {
            LlmConfig cfg = resolveLlmConfig(userId);
            if (cfg == null) return assembleTier3(part, recent, "no_llm_config");
            summaryText = llmHttpClient.chatCompletion(cfg.apiBase, cfg.apiKey, cfg.model, buildSummaryPrompt(preRecent));
            source = cfg.source;
            summaryDao.saveSummary(new ConversationSummaryRow(conversationId, fromIdx, toIdx, summaryText, cfg.model, null, null));
        }
    } catch (Exception e) {
        return assembleTier3(part, recent, "llm_failed:" + e.getClass().getSimpleName());
    }
    return assembleTier2(part, recent, summaryText, source);  // L0 + L1 + summary + recent
}
```

### 端到端真实数据（happy path）

| 输入 | 60 turns (120 messages) + 1 dream system + 2 tool |
|---|---|
| L2 轮数 | 60（> 48 触发）|
| preRecent 大小 | 104 messages |
| recent 大小 | 16 messages (8 user + 8 assistant) |
| 摘要 LLM | user 151515 配的 `https://api.minimaxi.com/v1` |
| 摘要生成耗时 | 1.77s |
| 摘要文本长度 | 241 chars |
| 输出 messages | 20 = 1 dream (L0) + 2 tool (L1) + 1 summary block + 8 user + 8 assistant (recent) |
| 响应耗时 | 1.8s |
| DB 持久化 | `conversation_message_summaries` 1 行插入（cache key: conv=e2e-happy-1781403599, from=1, to=106）|
