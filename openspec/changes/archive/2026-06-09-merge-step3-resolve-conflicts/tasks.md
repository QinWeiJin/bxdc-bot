## 1. 全量 lijianlong 覆盖（3 文件） ✅

- [x] 1.1 `AsyncTaskPollingScheduler.java` — 用 lijianlong 版本覆盖（双线程池 + 通知推送 + audit log + dedup）
- [x] 1.2 `useChat.ts` — 用 lijianlong 版本覆盖（SSE 流式 + 文件上传 + 思考模式）
- [x] 1.3 `skillEditor.ts` — 用 lijianlong 版本覆盖（asyncPoll 策略/readTimeout 解析 + 类型）

## 2. agent-core 微调（3 文件） ✅

### 2.1 agent.ts — 补流式开关

- [x] 2.1.1 在 `createAgent()` 中 `new ChatOpenAI({...})` 之前添加：`const agentStreaming = String(process.env.AGENT_STREAMING ?? "true").toLowerCase() !== "false"`
- [x] 2.1.2 在 ChatOpenAI 配置中添加 `streaming: agentStreaming`

### 2.2 java-skills.ts — 补 AsyncPollConfig 2 字段 + DeepSeek ensureObjectType

- [x] 2.2.1 `AsyncPollConfig` 接口添加 `pollStrategy?: "PERIODIC" | "SINGLE_CALL"`
- [x] 2.2.2 `AsyncPollConfig` 接口添加 `singleCallReadTimeoutSeconds?: number`
- [x] 2.2.3 添加 `ensureObjectType<T>()` helper（从 lijianlong 移植）
- [x] 2.2.4 用 `ensureObjectType` 包裹 `extendedPassthroughSkillToolSchema`（`extendedApiSkillLooseSchema` 在 temp 中由 `buildSkillZodSchema` 动态生成，不需要额外包裹）

### 2.3 skill-generator.ts — 补 Zod 2 字段 + DeepSeek 扁平 schema

- [x] 2.3.1 `skillGeneratorAsyncPollSchema` 添加 `pollStrategy: z.enum(["PERIODIC", "SINGLE_CALL"]).optional()`
- [x] 2.3.2 `skillGeneratorAsyncPollSchema` 添加 `singleCallReadTimeoutSeconds: z.number().int().min(1).optional()`
- [x] 2.3.3 将 `z.discriminatedUnion("targetType", [...])` 改为扁平 `z.object({ targetType: z.enum([...]), ... })`

## 3. skill-gateway 核心重写（1 文件） ✅

### 3.1 SkillExecutionService.executeApiSkillAsync() — 完整重写

- [x] 3.1.1 注入 `DedupConfig` 和 `RequestSignatureUtil`（已通过 import 引入，均为静态方法无需注入）
- [x] 3.1.2 读取 `asyncPoll.pollStrategy`，默认 `"PERIODIC"`
- [x] 3.1.3 **SINGLE_CALL 分支**：读取 readTimeout → 创建 AsyncTask → Scheduler 自动 pick up → 立即返回
- [x] 3.1.4 **PERIODIC 分支**：调第三方 → extractTaskId → RequestSignature 去重 → 立即返回
- [x] 3.1.5 **去掉** `future.get()` 阻塞等待
- [x] 3.1.6 SINGLE_CALL 模式下不校验 pollEndpoint

## 4. SystemSkillController schema ✅

- [x] 4.1.1 确认 schema 缺少 `asyncPollEnabled`/`asyncPollStrategy`/`asyncPollReadTimeoutSeconds`
- [x] 4.1.2 已补充：`asyncPollEnabled` (checkbox)、`asyncPollStrategy` (radio, PERIODIC/SINGLE_CALL)、`asyncPollReadTimeoutSeconds` (number, default 600)

## 5. 构建验证 ✅ 全部通过

- [x] 5.1 agent-core TypeScript 编译：PASSED
- [x] 5.2 skill-gateway Java 编译：BUILD SUCCESS
- [x] 5.3 frontend TypeScript 编译：PASSED
