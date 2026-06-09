## Context

**temp 分支架构原则（不可违背）：**
- agent 不承接 tool 业务逻辑 — 集成类 Skill（API/SSH/Template）统一走 `POST /api/skills/execute` → `SkillExecutionService`
- 轮询/异步请求属基础功能 — `SkillExecutionService` 和 `AsyncTaskPollingScheduler` 可修改
- 前端 Skill 编辑页用 `ConfigFormRenderer` schema 驱动动态渲染

**lijianlong 功能清单（必须完整保留）：**

| 功能 | 核心代码位置 |
|------|-------------|
| 思考模式 | `thinking-mode.ts`、`ThinkingMode.vue`、`useChat.ts` |
| 审计表 | `ConversationLog.java`、`ToolCallLog.java`、对应 Mapper/Service |
| LLM fallback + DeepSeek 兼容 | discriminatedUnion→flat、`ensureObjectType<T>()` |
| AGENT_STREAMING 流式开关 | `agent.ts` |
| 异步通知中心 | `AsyncTaskNotificationController.java`、`TaskNotificationBell.vue` |
| CORS 修复 | `SecurityConfig.java` |
| 对话下载 MD/PDF | `chatDownload.ts` |
| 异步 dedup + 时区 | `DedupConfig.java`、`RequestSignatureUtil.java`、`AsyncTaskPollingScheduler` |
| PERIODIC/SINGLE_CALL fire-and-forget | `SkillExecutionService.executeApiSkillAsync()`（新位置） |
| 文件上传 + 文档解析 | `useFileUpload.ts`、`*ParserService.java`、parser 工具 |

参考：`docs/merge-plan-low-version-main.md`、`AGENTS.md` 7.4 节

## Goals / Non-Goals

**Goals:**
- 保留 temp 的架构骨架（统一 POST /execute、ConfigFormRenderer、tool 拆分）
- 保留 lijianlong 的全部增量功能和特性（fire-and-forget、DeepSeek 兼容、去重、审计等）
- 融合后 agent-core 和 skill-gateway 均有完整编译

**Non-Goals:**
- 不新增功能（纯合并）
- 不修改 Build/Run 流程
- 不碰 dist/ 目录

## Decisions

### 决策 1: agent.ts — temp + 3 行流式开关

temp 移除了 SSH/API/LinuxScript 工具注册，这是正确方向。

lijianlong 新增 `AGENT_STREAMING` 环境变量，是独立增量，不与 temp 冲突。

```ts
// 在 new ChatOpenAI({...}) 之前加入
const agentStreaming = String(process.env.AGENT_STREAMING ?? "true").toLowerCase() !== "false";
// ChatOpenAI 配置中添加
streaming: agentStreaming,
```

### 决策 2: java-skills.ts — temp 不动，只补 AsyncPollConfig 2 字段

temp 的扩展 Skill 执行已统一走 `POST /api/skills/execute`，agent-core 侧不做 async 分支判断。SINGLE_CALL/PERIODIC 的执行逻辑全部在 Java 侧 `SkillExecutionService`。

**Agent 侧只需做好类型定义：**

```ts
// java-skills.ts AsyncPollConfig 接口（约 L225）
export interface AsyncPollConfig {
  // ... 现有字段不变 ...
  pollStrategy?: "PERIODIC" | "SINGLE_CALL";       // ← 新增
  singleCallReadTimeoutSeconds?: number;            // ← 新增
}
```

同时 `skill-generator.ts` 的 Zod schema 对应补 2 字段。

### 决策 3: DeepSeek 兼容 — skill-generator.ts + java-skills.ts

lijianlong 做的重要兼容：DeepSeek 模型不支持 `z.discriminatedUnion()`（返回 400），必须用扁平 `z.object()`。

**skill-generator.ts:**
```ts
// 旧: z.discriminatedUnion("targetType", [ ... ])
// 新: 扁平 z.object({ targetType: z.enum([...]), ... })
const skillGeneratorToolInputSchema = z.object({
  targetType: z.enum(["api", "ssh", "openclaw", "template"]).describe("..."),
  // ... 所有字段平铺
});
```

**java-skills.ts:** 同样引入 `ensureObjectType<T>()` 包裹扩展 Skill 的 Zod schema：
```ts
const extendedApiSkillLooseSchema = ensureObjectType(
  z.record(z.string(), z.any()),
  "API parameters as top-level fields..."
);
const extendedPassthroughSkillToolSchema = ensureObjectType(
  z.object({}).passthrough(),
  "Any parameters passed through as-is"
);
```

### 决策 4: SkillExecutionService.executeApiSkillAsync() — 完整重写

**当前问题：**
1. 只有 PERIODIC 路径，没有 SINGLE_CALL 判断
2. `future.get()` 同步阻塞等待 — 违反 fire-and-forget

**重写后的流程：**

```
executeApiSkillAsync(config, parameters) {
    asyncPoll = config.asyncPoll
    pollStrategy = asyncPoll.pollStrategy ?? "PERIODIC"

    // ====== SINGLE_CALL 分支 ======
    if (pollStrategy == "SINGLE_CALL") {
        readTimeout = asyncPoll.singleCallReadTimeoutSeconds ?? 600
        创建 AsyncTask(status=PENDING, pollStrategy="SINGLE_CALL", ...)
        asyncTaskPollingScheduler.submitSingleCall(task)  // CachedThreadPool
        → 立即返回 { status: "SINGLE_CALLED", asyncTaskId, note: "后台处理中" }
    }

    // ====== PERIODIC 分支 ======
    // Step 1: 同步调一次第三方拿 externalTaskId
    initialResponse = executeApiSkill(config, parameters)
    externalTaskId = extractTaskId(initialResponse, idJsonPath)

    // Step 2: RequestSignature 去重（session 维度 1h 窗口）
    signature = RequestSignatureUtil.compute(method, url, body, ...)
    asyncTaskPollingService.findDuplicate(signature) → 返回已有 task

    // Step 3: 创建 AsyncTask + 注册到 periodicExecutor → 立即返回
    asyncTaskPollingService.createTask(task)
    asyncTaskPollingScheduler.registerPeriodic(task)
    → 立即返回 { status: "POLLING", asyncTaskId, note: "后台轮询中" }
    // ⚠️ 去掉 future.get() 阻塞等待
}
```

**RequestSignature/dedup 集成要点：**
- `DedupConfig.java` 和 `RequestSignatureUtil.java` 已通过 Step 2 搬运到 temp
- signature 计算：SHA-256(method|url|body|idJsonPath|pollMethod|pollEndpoint|userId)
- per-session 1h 去重窗口（有 sessionId） / no-session 60s 窗口
- 去重命中时返回已有 task 的同一条记录，不发起重复第三方请求

### 决策 5: AsyncTaskPollingScheduler.java — 全量 lijianlong

temp 只加了 `registerFuture`/`completeFuture`（18 行），完全被 lijianlong 的 +162/-19 包含。

lijianlong 版本的要点：
- `periodicExecutor`（FixedThreadPool 20）处理 PERIODIC 轮询
- `singleCallExecutor`（CachedThreadPool）处理 SINGLE_CALL 长调用
- 完整的审计日志写入（GATEWAY_POLL_START → NETWORK_REQUEST → EVALUATION → GATEWAY_POLL_COMPLETE）
- 通知中心推送

### 决策 6: SkillManagementModal.vue — temp 不动

temp 的 `SystemSkillController.buildApiConfigSchema()` 已包含 `asyncPollEnabled`、`asyncPollStrategy`、`asyncPollReadTimeoutSeconds` 字段，`ConfigFormRenderer` 支持 `visibleWhen` 条件显示。验证通过则不动。

### 决策 7: useChat.ts / skillEditor.ts — 全量 lijianlong

temp 改动微小（1 行 / 8 行），直接用 lijianlong 版本保留全部增量。

## Risks / Trade-offs

- [AsyncTaskPollingScheduler 全量覆盖后 registerFuture → submitSingleCall 方法名变化] → `SkillExecutionService` 调用处需同步改
- [DeepSeek discriminatedUnion→flat schema 改动] → `JavaSkillGeneratorTool` 的描述逻辑需要适配（targetType 不再是 discriminator key）
- [SkillExecutionService 重写后 agent-core 透传响应格式变化] → agent-core 的 `java-skills.ts` func() 已直接 `JSON.stringify(response.data)` 透传，兼容 `SINGLE_CALLED`/`POLLING` 状态
