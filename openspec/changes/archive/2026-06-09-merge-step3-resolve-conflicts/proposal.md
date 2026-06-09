## Why

Step 1+2 已将 lijianlong/low-version 的 210 个文件（171 新增 + 31 修改，7 冲突）搬运到 temp。剩余 7 个双方都改动的冲突文件无法自动合并，需要逐个分析冲突点，设计融合方案。

**核心约束：**
1. 底层逻辑按 temp 架构 — agent 确保功能无业务逻辑，skill-gateway 负责各类 tool 对接
2. lijianlong 新增的功能和特性被完完整整保留 — 如有需要可按照新架构调整代码位置

## What Changes

### 7 冲突文件最终判决

| # | 文件 | 策略 | 操作 |
|---|------|------|------|
| 1 | `agent.ts` | **temp + 3 行** | 补入 AGENT_STREAMING 流式开关 |
| 2 | `java-skills.ts` | **temp + 2 字段** | `AsyncPollConfig` 补 `pollStrategy`、`singleCallReadTimeoutSeconds` |
| 3 | `SkillController.java` | **temp 不动** | 统一入口保留，逻辑在 `SkillExecutionService` 里改 |
| 4 | `AsyncTaskPollingScheduler.java` | **全量 lijianlong** | 双线程池 + 通知推送 + audit log |
| 5 | `SkillManagementModal.vue` | **temp 不动** | schema 驱动已有 SINGLE_CALL 字段 |
| 6 | `useChat.ts` | **全量 lijianlong** | SSE + 文件上传 + 思考模式 |
| 7 | `skillEditor.ts` | **全量 lijianlong** | asyncPoll 类型解析 |

### lijianlong 功能在新架构下的去向

| 功能 | 旧位置 | 新位置 | 方式 |
|------|--------|--------|------|
| SINGLE_CALL + PERIODIC fire-and-forget | 旧 `SkillController` 的 `POST /api/async` + `java-skills.ts` 的 `executeConfiguredApiSkillAsync` | `SkillExecutionService.executeApiSkillAsync()` | **完整移植**，去掉 blocking 等待 |
| RequestSignature 去重 | 旧 `SkillController.callApiAsync()` | `SkillExecutionService.executeApiSkillAsync()` | **完整移植** |
| AsyncPollingAudit | 旧 `SkillController` + `java-skills.ts` `postPollingAudit` | `AsyncTaskPollingScheduler`（已有） | 全量 lijianlong 覆盖 ✅ |
| DeepSeek 兼容（discriminatedUnion→flat + ensureObjectType） | 旧 `java-skills.ts` + `skill-generator.ts` | `skill-generator.ts` + `java-skills.ts` | **完整移植** |
| `AsyncPollConfig.pollStrategy` / `singleCallReadTimeoutSeconds` | 旧 `java-skills.ts` | `java-skills.ts` + `skill-generator.ts` | 各补 2 字段 |

## Capabilities

### Modified Capabilities

- `api-extension-skill-llm-tool-call`: 
  - SINGLE_CALL/PERIODIC fire-and-forget 执行路径从 agent-core 迁移到 SkillExecutionService
  - RequestSignature 去重签名在 SkillExecutionService 中执行
  - DeepSeek 兼容（discriminatedUnion→flat Zod schema + ensureObjectType wrapper）

## Impact

- **agent-core (微小)**：`agent.ts` +3 行流式开关；`java-skills.ts` +2 字段 + DeepSeek wrapper；`skill-generator.ts` +2 Zod 字段 + 扁平 schema
- **skill-gateway (核心)**：`SkillExecutionService.executeApiSkillAsync()` 重写（+SINGLE_CALL 分支 + fire-and-forget + RequestSignature）；`AsyncTaskPollingScheduler` 全量覆盖
- **frontend (无)**：`SkillManagementModal.vue` 不动；`useChat.ts`/`skillEditor.ts` 全量覆盖
