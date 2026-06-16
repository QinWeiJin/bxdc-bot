## Why

内网使用 GET 类 API skill 时 query 参数拼出来的 URL 是**双重 URL 编码**（`SkillExecutionService.buildUrlWithQuery` 在 `URLEncoder.encode(key, "UTF-8")` 之后又误加了一段 `URLEncoder.encode(key)`），导致后端按 `?key1=val1key1=val1&...` 这种格式收参，参数解析直接错位、整个 GET skill 跑不起来。

同时异步轮询类 skill 在前端通知中心**永远卡 99%** —— `evaluateCompletion` 只信用户配置的 `completionJsonPath` / `completionValue`，配置缺失或路径写错就**永远不 complete**；前端 `progressPercent` 又用 `Math.min(99, elapsed/max*100)` 把进度死死卡在 99%，状态不到 COMPLETED 用户看不到任何终态反馈。

## What Changes

- **修复 GET 请求 URL 双重编码**：`SkillExecutionService.buildUrlWithQuery` 移除冗余的第二次 `URLEncoder.encode` 调用，URL 严格只编码一次
- **异步轮询自动状态识别**：`AsyncTaskPollingService.evaluateCompletion` 新增 auto-detect 逻辑，从 pollEndpoint 响应里**自动推断** status 字段（`status` / `state` / `code` / `data.status` / `result.status` 等常见路径），匹配常见的成功/失败终态值（`SUCCESS` / `COMPLETED` / `FINISHED` / `DONE` / `FAILED` / `ERROR` 等），不再依赖用户必须配对 `completionJsonPath` + `completionValue`
- **前端进度不再卡 99%**：`TaskNotificationBell.progressPercent` 当 `elapsedSeconds >= maxWaitSeconds` 但状态仍是 `POLLING/SINGLE_CALLED` 时显示"即将完成"提示而不是继续卡 99%
- **兼容旧配置**：原有的 `completionJsonPath` + `completionValue` 用户配置**仍然有效**，auto-detect 只是 fallback，命中后**优先用用户配置**

## Capabilities

### New Capabilities
- `async-task-polling-completion`: 异步轮询类 skill 的终态判定机制，从 pollEndpoint 真实返回值自动推断任务状态，不依赖用户必须配 completionJsonPath/completionValue

### Modified Capabilities
- `api-skill-invocation`: 新增 requirement：URL 单次 URL 编码，禁止重复编码

## Impact

**修改的代码**：
- `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/SkillExecutionService.java` — `buildUrlWithQuery` 去重
- `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/AsyncTaskPollingService.java` — `evaluateCompletion` / `evaluateFailure` 加 auto-detect 分支
- `frontend/src/components/TaskNotificationBell.vue` — `progressPercent` 加 "elapsed >= max" 兜底分支

**修改的数据库表**：无

**修改的 API**：无（行为兼容）

**回归风险**：
- GET URL 单次编码：对存量 GET 调用是 bug fix，无回归
- Auto-detect status：对已经配好 `completionJsonPath` 的存量用户**无影响**（优先用配置）；对未配置的"幸运者"（API 返回的 status 字段恰好命中内置模式）从"永远 99%"变成"正确终态"
- 前端 99% 卡死：纯前端体验改进，无功能回归
