## Context

`bxdcbot-skill-orchestration`（即原 `openclaw-skill-orchestration`，本次随 bxdcbot 改名）当前是同步 ReAct 子规划循环（最多 6 轮 LLM 串行），`executeOpenClawSkill` 方法实现详见 `backend/agent-core/src/tools/java-skills.ts:551-700`。

子规划循环支持调"扩展 API skill"（`loadGatewayExtendedTools`），但是当扩展 skill 是 `SINGLE_CALL` / `PERIODIC` 异步任务时，子规划**只拿到占位返回**（`{asyncTaskId, status: "POLLING"|"SINGLE_CALLED"}`），拿不到真实结果，进而**没法**驱动"依赖上一任务真实结果"的下游子任务（如：A 调"订单查询" → B 调"物流查询"以 A 的真实订单号作入参）。

更糟的是 6 轮循环结束（`executeOpenClawSkill` 方法返回、对象销毁），**没有挂起-恢复机制**把异步结果注回 Bxdcbot 内部 messages，Bxdcbot 上下文彻底断裂。

异步任务完成链路（已存在）：
- agent-core 终态 → `POST /api/internal/async-task/echo-to-chat` → gateway `AsyncTaskChatReplyService` → 写 `chat_messages` + 推 SSE + 调 LLM 续答（archive 2026-06-12 实施）
- 通知中心 `/api/async-tasks/my` 返回每个 async task 状态

**约束（AGENTS.md 5.5）**：尽量不改 agent-core（NestJS）代码，新增能力优先在 gateway（Java 侧）以 Tool / Service 形式接入。但本次 Bxdcbot 多轮子规划是 **LLM 调度层基础能力变更**——无法靠 gateway 单方完成（gateway 没有 LLM 调度 context），需要 agent-core 必要侵入。理由会在决策 1 详细说明。

## Goals / Non-Goals

**Goals:**
- Bxdcbot 子规划可调 5-10 个 async skill（fanout 模式）
- 调 async 时拿到占位 → 挂起当前 6 轮周期 → 等所有 pending async 完成 → 启动新周期
- 异步真结果作为 `tool` role 消息注入到 Bxdcbot messages，**LLM 看到真结果后再决定下一步**
- 跨周期保留 BxdcbotRun 状态（messages / pendingAsyncTaskIds / currentRound）
- 单 run 最多 60 轮（10 个 6 轮子规划周期）
- 通知中心列表项支持按 `parent_tool_id` 聚合，查看 Bxdcbot run X 的所有子 async 状态
- 对话流回显每个子 async 真结果（沿用 archive 2026-06-12 路径）
- 单 async 超时复用 `async_tasks.singleCallReadTimeoutSeconds` / `maxWaitSeconds`（已有配置，不新增 env，遵循 AGENTS.md 5.2）

**Non-Goals:**
- 不实现"agent-core 重启后恢复 BxdcbotRun"（MVP in-memory，重启即丢）
- 不实现"OPENCLAW 内部 LLM 调 async 时阻塞性 wait 到 30 分钟"（有单 async 超时控制）
- 不实现"跨 run 共享 messages 上下文"（每个 run 独立）
- 不改 Bxdcbot 同步路径（不调 async 的纯 sync 场景）行为（回归保证）
- 不实现"续答 LLM 调新 skill"（archive 2026-06-12 已限制续答不调 tool）
- 不实现"agent-core 多实例协同"（MVP 单实例 in-memory Map）
- 不重命名代码级 `executeOpenClawSkill` 函数名（用户决定暂不动代码级）
- **N 范围：1-10 个 async skill 统一处理**：本次设计 MUST 对 Bxdcbot 内部调的 **任意 N 个**（1 ≤ N ≤ 10）async / 单次长调用 skill 都按同一套逻辑处理——记录 `parentToolId` + 走 `awaiting_async` 状态 + 跨周期等真结果 + 消息中心按 `parentToolId` 聚合 + 对话流按 Bxdcbot run 整体回灌。**N=1 不是特例**，N=5-10 也不是——同代码路径，仅循环次数不同。**重要**：用户在最近评审中明确要求"不管是几个 async / 长调用 skill，都按统一逻辑处理"，所以本次设计 MUST NOT 把 1 个和 5-10 个的逻辑区分开（不能"只 fanout 不 single"）

## Decisions

### 决策 1：为什么必须改 agent-core？

**Bxdcbot 多轮子规划必须改 agent-core**，违反 AGENTS.md 5.5 "尽量不改 agent-core" 规约——属"无法走 Tool 接入"的例外情况。

理由：
- Bxdcbot 子规划循环是 LLM 调度层基础能力，**驱动者是 LLM**（`plannerModel.bindTools`），不是预定义流程
- gateway 端是 Java，没有 LangGraph context，**没法"中途插入"一段 LLM 调用**——只能"完整执行一段 Bxdcbot 跑"
- 多轮子规划需要"等异步 + 重新 invoke LLM"，这是 agent-core 的 LangGraph 范畴
- 类比：当前 archive 2026-06-12 的"异步任务对话回显"——gateway 能做是因为"写消息 + 调 LLM 续答"是**完整 LLM 调用**（用户消息 + 上下文 + 输出），可以独立完成
- 而 Bxdcbot 多轮子规划是"在已有 LLM 调用的中间，**插入**一次等待 + 重新调"，必须 agent-core 持状态

侵入点明确：
- `src/tools/java-skills.ts::executeOpenClawSkill`（1 个函数改多周期）
- 1 个新 store：`BxdcbotRunStore`（in-memory Map）
- 1 个新 scheduler：`BxdcbotRunScheduler`（后台扫 awaiting_async run）
- **不**影响其他 skill 类型（compute / server_lookup / api_caller）的执行路径

**回归影响**：
- Bxdcbot 同步路径（不调 async 的纯 sync 场景）行为**不变**
- 新路径仅在 LLM 调 async 时激活

### 决策 2：BxdcbotRun 状态保留

**in-memory Map 持久化**（`Map<runId, BxdcbotRun>`），MVP 阶段不考虑 DB 持久化。

```ts
interface BxdcbotRun {
  runId: string;            // UUID v4
  conversationId: string;
  userId: string;
  parentToolId: string;     // 外层 tool call_id（标识这是 Bxdcbot run X 调的第 N 个子任务）
  parentSkillId: number;    // Bxdcbot skill_id（async_tasks.parent_skill_id）
  messages: any[];          // LangChain messages 累积（含 tool calls + tool results + async 真结果）
  pendingAsyncTaskIds: Set<number>;  // 当前 6 轮子规划周期待等的 async
  asyncTaskIdToToolCallId: Map<number, string>;  // async ↔ tool_call_id 关联（漏洞 3 修复）
  currentRound: number;     // 跨周期计数（不超过 60）
  totalLlmCalls: number;    // 总 LLM 调用次数（用于审计/限额）
  startedAt: Date;
  finishedAt?: Date;        // completed / failed / 60 轮触顶时填
  status: 'running' | 'awaiting_async' | 'completed' | 'failed' | 'timeout';
  result?: string;          // 最终 LLM 输出（completed 时填）
  failureReason?: string;   // failed 时填（"60 轮触顶" / "全局超时" / "LLM 调用错误"）
  subTaskSummary?: {        // run 终结时统计
    total: number;          // 总发起子 async 数
    succeeded: number;
    failed: number;
    pending: number;        // 0（终结时不该有 pending）
  };
}
```

**关键字段语义**：
- `pendingAsyncTaskIds: Set<number>`：**当前 6 轮子规划周期**待等的 async。N=1 时 `size=1`，N=5-10 时 `size=5..10`，**同一字段、同一逻辑**——这是用户要求"1-10 统一处理"的核心：scheduler 处理时**不关心** size 是 1 还是 10
- `asyncTaskIdToToolCallId: Map<number, string>`：漏洞 3 修复。`asyncTaskId → tool_call_id` 的反向索引。**删除时机**：async 完成 + tool message 注入后立即 `delete()`（避免 map 无限增长）
- `skillRetries: Map<string, number>`：决策 11 失败隔离。`skillName → 当前重试次数`（0 表示还没重试过）。**删除时机**：skill 成功完成 + tool message 注入后立即 `delete()`（不跨 skill 累积）
- `originalSkillArgs: Map<string, any>`：决策 11 失败隔离。`skillName → 原 args`，重试时复用同 args 调 gateway（避免 LLM 知道"在重试"）

备选 A：DB 持久化（agent-core 重启不丢）—— 太重，违反 MVP 原则
备选 B：完全无状态，每次 invoke 重新拉取 —— 跨周期无法保留 messages
**选 in-memory**——MVP 阶段够用，agent-core 重启丢失时由外层 LLM 续答兜底（用户已确认 MVP 阶段 in-memory 方案）。

### 决策 3：异步真结果如何注入 messages？

**作为 `tool` role 消息注入**，用 `tool_call_id` 关联。

```ts
// async task 完成时
run.messages.push({
  role: 'tool',
  tool_call_id: `async_${asyncTaskId}`,  // 与当初 tool_call 的 id 对应
  name: 'async_task_callback',
  content: JSON.stringify({
    asyncTaskId,
    status: taskStatus,  // SUCCESS / FAILED / TIMEOUT
    result: taskResult,
    finishedAt
  })
});
```

**为什么用 `tool` role**：
- LLM 已经在 message 流里看到 `assistant` 发出 `tool_call(id="async_X", ...)`，对应的 tool result 必须是 `tool` role
- LLM 续 invoke 时看到 `tool` role 消息自然理解为"该 tool 的返回"
- LangChain `AIMessage` 解析也原生支持

**为什么用 `async_X` 作为 tool_call_id**：
- 之前 Bxdcbot LLM 调 async skill 时，invokeToolDirect 拿到的占位结果填入 `tool_call_id=<原始_id>`——但 LLM 调 async 时**还没拿到 tool_call_id**（async 是 fire-and-forget，gateway 立即返回）
- 所以 invokeToolDirect 阶段需要**为 async 生成一个 fake tool_call_id**（如 `async_${asyncTaskId}`），把这个 tool_call_id 存到 BxdcbotRun
- 等 async 真的完成，把 tool 消息填这个 tool_call_id
- LLM 下一轮 invoke 时看到 messages 里有 `tool_call_id="async_42"` 的 tool 消息——这是它之前调 async 时预期的 id，能 match

**实现**：
- `invokeToolDirect` 检测到是 async skill（dispatch=async 模式或 gateway 返回 POLLING）→ 改写为"占位 tool result + 记录 asyncTaskId + 标记 run.awaiting_async"
- 下一轮 invoke 时 LLM 看到"async_42 占位 tool result"
- async 42 完成时 watchdog 把真结果以 `tool` role 注入（tool_call_id=async_42）

**asyncTaskId ↔ tool_call_id 关联（漏洞 3 修复）**：

`invokeToolDirect` 收到的"原始 tool_call_id"是 LLM 调 async 时生成的（LangChain 默认 `call_<random>` 格式）。async 完成后要把真结果注入 messages，必须用**同一个** tool_call_id，LLM 才能正确关联。

BxdcbotRun 加字段：
```ts
interface BxdcbotRun {
  // ... 现有字段
  asyncTaskIdToToolCallId: Map<number, string>;  // 漏洞 3 修复
}
```

**写入时机**：`invokeToolDirect` 调 async skill 拿到 `{asyncTaskId: 42}` 时，立即 `run.asyncTaskIdToToolCallId.set(42, currentToolCallId)`。

**读出时机**：BxdcbotRunScheduler 拿到 async 42 真结果时，注入 messages 前 `const toolCallId = run.asyncTaskIdToToolCallId.get(42); run.asyncTaskIdToToolCallId.delete(42);`，然后 push 消息时用这个 toolCallId。

**为什么不能用 `async_<id>` 当 tool_call_id**：
- LangChain 的 tool message 校验严格，tool_call_id 必须**精确匹配** assistant 之前发的 tool_call.id
- 如果 Bxdcbot LLM 调 async 时生成的 tool_call_id 是 `call_abc123`，那 async 完成后注入的 tool 消息 tool_call_id **必须**是 `call_abc123`，不能是 `async_42`
- 用 `async_<id>` 包装会破坏 LangChain 内部状态

**为什么 `Map` 而不是 `Record`**：
- Map 提供 O(1) 增删改查
- TypeScript Map 序列化时是 `{}`——MVP in-memory 不持久化，序列化不重要

### 决策 4：跨周期调度器如何触发？

**后台 scheduler 每 2 秒扫 awaiting_async 的 run，监听 pending 完成**。

```ts
// BxdcbotRunScheduler（agent-core 内）
@Cron('*/2 * * * * *')  // 每 2 秒
async poll() {
  const awaitingRuns = this.runStore.list({ status: 'awaiting_async' });
  for (const run of awaitingRuns) {
    // 全完成？
    const allReady = await this.checkAllAsyncTasksReady(run.pendingAsyncTaskIds);
    if (allReady) {
      // 重新 invoke Bxdcbot 子规划
      await this.resumeBxdcbotRun(run);
    }
    // 检查超时（单 async > singleCallReadTimeoutSeconds / maxWaitSeconds）
    // 检查总轮数（run.currentRound >= 60 → 标记 failed）
  }
}
```

备选 A：SSE 订阅 gateway 异步完成事件（gateway 推，agent-core 被动收）—— 复杂度高，需新增 gateway 内部事件总线
备选 B：agent-core 起独立线程每 X 秒扫 —— 用 `@nestjs/schedule` 的 `@Cron` 注解
**选 B**——简单，符合 NestJS 习惯。

**2 秒间隔的选择**：单 async 完成时间 1~10 分钟，2 秒扫一次延迟可接受（最坏多等 2 秒）

### 决策 5：复用 gateway `/api/async-tasks/{id}/wait` 还是 agent-core 自己轮询？

**复用 gateway `/api/async-tasks/{id}/wait` 端点**（archive 2026-05-14 + archive 2026-06-04 实现）。

理由：
- 端点已存在，已实现长轮询 / SSE / HTTP wait 三种模式
- 单 async 超时配置（`singleCallReadTimeoutSeconds` / `maxWaitSeconds`）已在 gateway 端维护
- 不在 agent-core 复制超时逻辑
- agent-core 调 `GET /api/async-tasks/{id}/wait?timeout=300` 阻塞等结果
- gateway 端 wait 端点超时返回 TIMEOUT 状态，agent-core 把 TIMEOUT 作为 tool result 注入

**`wait` 端点**：
- 输入：`asyncTaskId` + `timeoutSeconds`（可选，默认 60）
- 输出：任务终态时返回完整 result（PENDING 时长轮询等）
- 复用现有，无需新增

### 决策 6：async_tasks 表加列的方式

**Java migration 类**（参考 `SchemaMigrationRunner::migrateConversationMessageSummaries` 模式），加 2 列 + 1 索引。

```sql
-- AsyncTaskSchemaMigration.java
ALTER TABLE async_tasks
  ADD COLUMN parent_tool_id VARCHAR(128) NULL COMMENT '父 Bxdcbot run_id（标识这是 Bxdcbot X 调的第 N 个子任务）',
  ADD COLUMN parent_skill_id BIGINT NULL COMMENT '父 Bxdcbot skill_id（冗余字段，方便按 skill 过滤）',
  ADD INDEX idx_parent_tool_status (parent_tool_id, status);
```

- `parent_tool_id`：标识"这个 async 是哪个 Bxdcbot run 调的"，前端按这个聚合
- `parent_skill_id`：冗余字段（如果 conversation_id + parent_tool_id 已能定位 Bxdcbot run，加上 skill_id 是方便按 skill 维度过滤）

**`chat_messages` 表不加列**——archive 2026-06-12 已加 `async_task_id`，通过 JOIN 即可获取 `parent_tool_id`。

### 决策 7：通知中心聚合视图

**前端按 `parent_tool_id` 聚合**，列表项可展开看子任务。

UI：
- 列表项顶部：父 Bxdcbot skill 名 + run 状态（"3 完成 / 1 失败 / 1 进行中"）
- 展开后：每个子 async 单独一行（skill 名 + 状态徽章 + 终态时间 + 真结果链接）
- 折叠默认关闭（避免列表太长）

**后端 `/api/async-tasks/my`**：
- 返回字段加 `parentToolId` / `parentSkillId`
- 不改 SQL（沿用现有查询，group by 由前端做）

**对话流组件已有 AsyncTaskResultMessage**（archive 2026-06-12 实现）：
- 加 `parent_tool_id` 标识（视觉上显示"这是 Bxdcbot 子任务"徽章）
- 多个 async 完成时 N 条独立 message 渲染（不合并）

### 决策 8：LLM 续答要不要调 skill？

**不调**（沿用 archive 2026-06-12 限制）。

archive 2026-06-12 已规定 LLM 续答**不调 tool**（避免递归等待），本次 Bxdcbot run 的子 async 续答也遵守同一规则。

**额外约束**（prompt 调整）：
- context 里加一行 "如果 async 任务有 parent_tool_id（即来自 Bxdcbot），续答时不要重复调任何 skill（包括 Bxdcbot 自身），避免嵌套等待"
- LLM 续答时只**总结**子 async 真结果，**不**驱动新 skill

**为什么不让续答调 Bxdcbot**：避免"外层 LLM 调 Bxdcbot → Bxdcbot 调 async → async 完成续答 → 续答再调 Bxdcbot"递归。

**替代方案**：让用户**主动**基于真结果追问，外层 LLM 自然接力。

### 决策 9：Bxdcbot system prompt 强约束

**1 行 system prompt 规则**——在 `executeOpenClawSkill` 内的 system message 加：

```
⚠️ 异步 skill 调完返回 {asyncTaskId, status: "POLLING"|"SINGLE_CALLED"} 是占位。
   **不要**基于占位调下游子任务。如需依赖真实结果，可结束当前轮等真结果注入后再规划。
```

放在 system message 顶部，让 LLM 第一眼看到。

**为什么不约束"必须按顺序调"**：
- Bxdcbot 内部 LLM 是智能体，应该让它自主判断依赖关系
- 强约束"必须按顺序"会限制 LLM 智能性
- 1 行"不要基于占位调下游"已足够防呆

### 决策 10：run 跑完时回灌对话流（关键修复）

**新增 gateway 内部 API `POST /api/internal/bxdcbot-run/complete`**，agent-core run 终态时（completed / failed / 60 轮触顶 / 全局超时）调一次。

```
POST /api/internal/bxdcbot-run/complete
Headers: X-Internal-Token: <shared-secret>
Body: {
  runId, conversationId, userId, parentToolId, parentSkillId,
  status: "completed" | "failed" | "timeout",
  finalText: "Bxdcbot 最终输出文本（completed 时填）",
  failureReason: "60 轮触顶 / 全局超时 / LLM 错误（failed 时填）",
  roundsUsed, llmCallsUsed, totalTokensUsed,
  subTaskSummary: { total: N, succeeded: A, failed: B, pending: 0 },
  finishedAt
}
```

gateway 行为：
1. **写对话消息**：`INSERT INTO chat_messages (source='BXDCBOT_RUN_RESULT', async_task_id=NULL, parent_tool_id=runId, parent_skill_id=parentSkillId, content=finalText, llm_summary_pending=true)`
2. **推 SSE**：`POST /api/sse/publish` 推 `message_inserted` 事件
3. **触发外层 LLM 续答**（fire-and-forget）：用 `parentToolId` 反查 chat_messages 找"调 Bxdcbot 的外层 assistant tool_call message" + 最近一条 user message，组装续答 prompt
4. 续答 LLM 看到"Bxdcbot run X 完成，最终输出是 [finalText]，跑了 N 轮"，用自然语言回应用户
5. 续答 LLM **不**调任何 skill（沿用 archive 2026-06-12 限制）

**为什么必须有这个**：
- Bxdcbot run 跑完时**没有**任何 async 完成事件（除非 run 内调过 async）
- 不写对话消息 → 用户看不到 Bxdcbot 整体输出
- 不续答 → 外层 LLM 不知道 Bxdcbot 跑完了，**对话流断了**
- 续答 prompt 必须跨层关联（用 parentToolId 反查），不能直接用 run 内的 messages

**续答 prompt 模板**：
```
system: 你是助手。用户调了一个 Bxdcbot 自主规划 skill，run X 已完成。
       跑了 {roundsUsed} 轮（{llmCallsUsed} 次 LLM 调用）。
       请基于 Bxdcbot 最终输出 + 子 async 任务汇总，用自然语言回应用户。
       不要重复调 tool，不要假装有更多结果。回答格式：纯文本（Markdown 也可）。

user:   [原 user 消息（外层对话流最近 1 条 user 消息）] +
        [原 tool call 摘要（外层 LLM 调 Bxdcbot 的 tool_name + arguments）] +
        [Bxdcbot 最终输出（finalText）] +
        [子 async 任务汇总：{total: N, succeeded: A, failed: B, pending: 0}，
         附每个子 async 的 skill_name + status + 真结果摘要]
```

**备选 A**：run 跑完时**不**回灌，让用户主动问。**否决**——用户不主动问永远看不到结果
**备选 B**：run 跑完时只写 chat_message，不续答。**否决**——外层 LLM 不知道 Bxdcbot 跑完，5-10 个子 async 完成时仍会触发续答，但 Bxdcbot 整体结果未明确呈现
**选 API + 写消息 + 续答一体**——与 archive 2026-06-12 的"async echo-to-chat 流程"对称一致

### 决策 11：Skill 失败隔离（用户硬性要求 — 错误不传染）

**核心原则**：Bxdcbot 内部调任意 skill 失败时，**失败结果 MUST NOT 注入 LLM messages**——避免错误传染到下游 skill。

**为什么必须隔离**：
- 失败结果（status=FAILED + error）注入 messages 后，LLM 可能基于错误信息**做出错误的下游决策**（如："订单查询失败" → LLM 调"取消订单" skill——错误传染）
- 用户明确要求"不能将失败的结果传给下一步执行"
- 即便 LLM 收到失败结果不调下游，也浪费了 LLM 调用 token

**失败处理流程**（BxdcbotRunScheduler 捕获 skill 终态）：

```ts
// BxdcbotRunScheduler 处理 skill 终态时
async handleSkillResult(skillName, asyncTaskId, result) {
  if (result.status === 'FAILED' || result.status === 'TIMEOUT') {
    // 失败路径：MUST NOT 注入 messages
    const retriesSoFar = run.skillRetries.get(skillName) || 0;
    const maxRetries = parseInt(process.env.BXDCBOT_SKILL_MAX_RETRIES) || 3;
    
    if (retriesSoFar < maxRetries) {
      // 自动重试（LLM 透明）
      run.skillRetries.set(skillName, retriesSoFar + 1);
      // 重新发起同 args 同 payload 的新 task
      const newTaskId = await retrySkill(run, skillName, originalArgs);
      // 注意：仍走正常 async 路径，pendingAsyncTaskIds +1（同一 skill 仍 pending）
      // 但 skillRetries 计数增加
      // LLM 不感知重试
      return;
    } else {
      // N 次全失败 → 硬终止 Bxdcbot run
      run.status = 'failed';
      run.failureReason = `${skillName} 失败 ${maxRetries} 次后终止：${result.error}`;
      run.finishedAt = new Date();
      // 调 gateway /api/internal/bxdcbot-run/complete 回灌
      await this.notifyRunComplete(run);
      return;
    }
  }
  
  // 成功路径：注入真结果（沿用决策 3）
  injectToolResult(run, asyncTaskId, result);
}
```

**关键设计点**：
- **重试在 LLM 外**：agent-core scheduler 自己重试，**不**调 LLM
- **重试对 LLM 透明**：LLM 只看到"最终结果"（成功真结果）或"run terminated"——**不**看到中间 N 次失败
- **重试不计入 Bxdcbot 60 轮上限**：重试是同步等待的，**不**消耗 6 轮子规划周期
- **重试用新 asyncTaskId**：原 taskId 失败状态保留在 `async_tasks` 表（用于审计），重试用新 taskId
- **sync skill 失败也走同样重试**：不只是 async 失败
- **重试状态计数**：`BxdcbotRun.skillRetries: Map<skillName, number>` 跟踪每个 skill 的重试次数

**新增 env 配置**（遵循 AGENTS.md 5.2）：
- `BXDCBOT_SKILL_MAX_RETRIES=3`（默认 3 次）
- 0 = 禁用重试（第一次失败就终止 run）

**备选 A**：失败注入 messages + LLM 决定怎么办。**否决**——错误传染风险 + LLM 调用浪费
**备选 B**：失败直接终止 run（不重试）。**否决**——网络抖动等临时性失败会终止 run，UX 差
**备选 C**：失败让用户主动决定。**否决**——Bxdcbot 是 fire-and-forget 异步，用户不在场
**选 重试 N 次 + 仍未成功硬终止**——平衡鲁棒性和简单性

**对 60 轮 / messages / LLM 的影响**：
- 失败结果**不**注入 messages → messages 体积不增
- 重试不消耗 60 轮 → 60 轮真正用于有效子规划
- LLM 调用次数**不**增 → token 成本不增

**对其他 skill 类型的影响**：
- compute / server_lookup / 同步 API 失败也走同样重试
- 重试同步 skill：直接重新调 gateway `/api/skills/execute`（同 args），错误处理复用

**与其他决策的关系**：
- 与决策 3（async 真结果注入 messages）的关系：失败场景**不**走决策 3 路径，走决策 11 路径
- 与决策 10（run 终态回灌）的关系：决策 11 触发 run 失败时，复用决策 10 的 `/api/internal/bxdcbot-run/complete` API

## Risks / Trade-offs

- **agent-core 重启 → BxdcbotRun 丢失**
  - 缓解：MVP 阶段 in-memory 可接受；agent-core 重启后 Bxdcbot 用户主动重问时外层 LLM 自然接力
  - 后续：DB 持久化（用户已说 MVP 阶段 in-memory）
- **BxdcbotRun.messages 累积到很大**
  - 缓解：60 轮上限；每周期结束清理"已用完"的 tool result（如早于 N 轮的）
  - 后续：messages 摘要压缩（复用 `llm-context-window-summarization` 的压缩逻辑）
- **scheduler 2 秒扫一次的延迟**
  - 缓解：async 完成时延最多 2 秒，对 1~10 分钟任务可忽略
  - 后续：gateway 端推 SSE 完成事件，agent-core 订阅（升级到决策 4 备选 A）
- **Bxdcbot LLM 不遵守"不要基于占位调下游"规则**
  - 缓解：invokeToolDirect 检测到"async 占位 + LLM 又调 async" → 拒绝 + 报错回 LLM
  - 后续：把这条约束提到 user 消息侧（双保险）
- **单 async 超时后 LLM 拿到的是"timeout" tool result**
  - 缓解：tool result 内容明确写"async 任务超时（已等 N 秒）"——LLM 决定怎么办（重试 / 跳过 / 报错）
  - 不算 BUG：超时是合理结果
- **60 轮上限不够用**
  - 缓解：上限可配（env `OPENCLAW_MAX_ROUNDS=60`），需要时调整
  - 后续：监控 metrics 触发上限的次数，必要时提升
- **同一 conversationId 并发多个 Bxdcbot run**
  - 风险：run 状态机可能冲突
  - 缓解：run_id 是 UUID（agent-core 每次 invoke 生成），不依赖 conversationId
  - 后续：如果 Bxdcbot 在前端被并发触发（同一对话快速发 2 条用户消息），加 `conversationId + runId` 二级索引
- **AGENTS.md 5.5 规约违反**——必须侵入 agent-core
  - 缓解：在 proposal.md 已明确"为什么不能走 Tool 接入"+ 侵入点文件 + 回归影响
  - 评估：开发人员 review 这次侵入是否可接受
  - 后续：未来 agent-core 抽象出"MultiTurnPlanExecutor"接口，Bxdcbot 作为第一个实现，Bxdcbot 多轮子规划逻辑就放到 gateway 侧（远期）

## Migration Plan

**前置**：
- 现有 `async_tasks` 表 schema 不变（只加列）
- 现有 chat_messages 表 schema 不变
- 现有 `executeOpenClawSkill` 行为对**纯 sync 场景**不变（回归保证）

**部署顺序**（按依赖关系）：
1. **gateway 先行**：
   - 部署 `AsyncTaskSchemaMigration`（加列）+ `AsyncTaskService.submit` 接收 `parentToolId`/`parentSkillId`
   - `/api/async-tasks/my` 返回字段加 `parentToolId`/`parentSkillId`
   - LLM 续答 prompt 调整（加 Bxdcbot 提示）
   - 重启 gateway（schema 自动跑）
2. **agent-core 跟进**：
   - 部署 `BxdcbotRunStore` + `BxdcbotRunScheduler` + 改 `executeOpenClawSkill` 多周期
   - agent-core 调 gateway `/api/async-tasks/{id}/wait`（已有）
3. **frontend 最后**：
   - 通知中心列表项加 `parent_tool_id` 聚合
   - 对话流 AsyncTaskResultMessage 加徽章

**回滚**：
- gateway schema 列加 + 不删（不回滚，列是 NULL safe）
- agent-core `executeOpenClawSkill` 改多周期 → 回退到原 6 轮同步循环（git revert 单 commit）
- frontend 聚合 UI → 回退到原列表（git revert 单 commit）

**监控**：
- gateway 端 metrics：`async_task_submit_total{parent_tool_id_present=true}` 计数（看 Bxdcbot 调用 async 的频率）
- agent-core 端 metrics：`openclaw_run_status_total{status=running|completed|failed|timeout}`、`openclaw_run_rounds_used` 直方图
- 告警阈值：60 轮触顶 > 5 次/小时 → 通知开发

## Open Questions

- **状态机类名 `BxdcbotRun` 后续是否还要再重命名？**——当前已统一为 `BxdcbotRun`（与 `bxdcbot-skill-orchestration` capability 一致）。仅遗留 `executeOpenClawSkill` 函数名（代码级）未改，与状态机类名**不再同源**（一个在源码一个在 spec/agent-core 内部模块名）。后续代码级重命名 `executeOpenClawSkill` → `executeBxdcbotSkill` 时，本 change 不再受影响
- **数据库 enum `executionMode=OPENCLAW` → `BXDCBOT`？**——本 change 不改（避免影响生产数据），等代码级重命名时一起改
- **`async_tasks` 表历史数据是否补 `parent_tool_id`？**——不需要（NULL safe，旧数据 parent_tool_id=NULL 不影响查询）
- **60 轮上限是否需要可配？**——MVP 阶段 hardcode 60，后续如需可配加 env `OPENCLAW_MAX_ROUNDS`
