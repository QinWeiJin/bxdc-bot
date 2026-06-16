## Context

当前 `SkillExecutionService.buildUrlWithQuery` 的 query 拼接循环里有**两次** URL 编码块：
1. 第一个 try-catch 块用 `URLEncoder.encode(key, "UTF-8")` —— 这是 JDK 1.8 兼容写法，正确
2. 紧跟其后有一段**没有 try-catch 包裹**的 `URLEncoder.encode(key)` 调用，注释写的是 "JDK 1.8: URLEncoder.encode(String, Charset) 是 JDK 10+；使用单参数版本（deprecated but 1.8 compatible）"

注释是错的：`URLEncoder.encode(String, String)`（双参数）从 JDK 1.4 就存在，不存在 1.8 不兼容问题。第一段本来就是双参数 UTF-8 编码，完全正确。第二段是冗余/有害的——重复编码导致 query string 变成 `key1=val1key1=val1&key2=val2key2=val2`，后端收不到正确参数。

异步轮询的 `evaluateCompletion` 强依赖 `task.getCompletionJsonPath()` + `task.getCompletionValue()` 两个字段：
- 如果用户没配：`evaluateCompletion` 返回 `false`，永远不 complete
- 如果用户配错路径：返回 `false`，永远不 complete
- 如果 API 实际响应格式与用户配置不匹配：返回 `false`，永远不 complete

前端 `TaskNotificationBell.progressPercent` 对 POLLING/SINGLE_CALLED 状态计算公式是 `Math.min(99, elapsed/max*100)` —— 当 status 永远是 POLLING 时，进度条永远 ≤ 99%，用户看不到任何"已卡死"或"即将完成"的提示。

## Goals / Non-Goals

**Goals：**
- GET 类 API skill 的 query 参数严格只 URL 编码一次，行为符合 RFC 3986
- 异步轮询 skill 在 `completionJsonPath` 未配置或配错时，仍能从 pollEndpoint 响应中自动识别状态
- 前端轮询进度在 elapsed ≥ maxWaitSeconds 但状态仍未变时，给用户"即将完成/可能已卡住"的视觉提示，而不是继续显示 99%
- 完全向后兼容：已配 `completionJsonPath` 的存量 skill 行为不变

**Non-Goals：**
- 不重写整个 URL 构造逻辑（保留 `buildUrlWithQuery` 函数签名）
- 不动 `evaluateFailure` 之外的评估链路
- 不改 progress 显示的 99% 上限设计本身（这与"任务进行中"语义相符，不在本 change 范围）
- 不引入新的第三方包（沿用现有 jayway JsonPath）

## Decisions

### Decision 1：保留第一段双参数 URLEncoder.encode，删除第二段单参数冗余调用

**为什么**：第一段本来就是正确的 JDK 1.8 兼容写法（`URLEncoder.encode(String, String)` 自 JDK 1.4 就存在）。第二段注释作者对 JDK 1.8 API 理解有误，写了"单参数版本 deprecated but 1.8 compatible"——其实单参数版本自 JDK 1.4 就被 deprecated，但项目用 JDK 1.8 没理由用 deprecated API。

**替代方案考虑**：
- 改用 Spring 的 `UriComponentsBuilder` —— 引入新依赖，违反 AGENTS.md 5.1
- 改用 `URIBuilder`（Apache HttpClient）—— 同上，需要新依赖
- **采用**：删第二段，保留第一段

### Decision 2：auto-detect 状态字段的优先级 = 用户配置 > 内置模式

**逻辑流程**：
```
evaluateCompletion(pollResponse, completionJsonPath, completionValue):
  if completionJsonPath != null && completionValue != null:
    return evaluateByConfiguredPath()  // 存量行为不变
  // 用户没配 → 走 auto-detect
  for statusField in STATUS_FIELD_CANDIDATES:
    value = extractByPath(pollResponse, statusField)
    if value in TERMINAL_SUCCESS_VALUES:
      return true
    if value in TERMINAL_FAILURE_VALUES:
      return false  // 不标 completed
  return false  // 兜底：未识别按未完成
```

**`STATUS_FIELD_CANDIDATES` 候选路径**（按优先级）：
1. `$.status`
2. `$.state`
3. `$.code`
4. `$.data.status`
5. `$.data.state`
6. `$.result.status`
7. `$.data.code`
8. `$.result.code`

**`TERMINAL_SUCCESS_VALUES`**：`SUCCESS` / `COMPLETED` / `FINISHED` / `DONE` / `SUCCEED` / `OK` / `2`（部分金融类 API 用 `2` 表示成功）

**`TERMINAL_FAILURE_VALUES`**：`FAILED` / `FAILURE` / `ERROR` / `CANCELLED` / `CANCELED` / `TIMEOUT` / `EXPIRED`

匹配大小写不敏感。

### Decision 3：auto-detect 命中后复用现有失败判定流程

auto-detect 命中 SUCCESS 值就标 COMPLETED；命中 FAILURE 值就调现有的 `evaluateFailure` 同样的失败标记逻辑。这样：
- 失败状态由 `updatePollResult(id, "FAILED", null, errMsg)` 标记
- 成功状态由 `updatePollResult(id, "COMPLETED", result, null)` 标记
- 写对话消息、触发续答的逻辑完全复用

### Decision 4：前端"即将完成"提示加在 progress 计算函数里

**逻辑**：
```ts
function progressPercent(t):
  if t.status in ['COMPLETED', 'FAILED', 'TIMEOUT']: return 100
  if t.status in ['POLLING', 'SINGLE_CALLED']:
    const max = t.maxWaitSeconds > 0 ? t.maxWaitSeconds : 1800
    const elapsed = t.elapsedSeconds || 0
    if elapsed >= max * 0.95:
      return 99  // 上限不变，但加 tooltip 提示 "即将完成"
    return Math.min(99, Math.floor(elapsed / max * 100))
```

同时给 `t-progress` 加 `:title` prop 显示"已运行 X 秒，等待任务完成"——告诉用户**进度条卡 99% 不是 bug，是设计上不到 100%**。

## Risks / Trade-offs

**[Risk] auto-detect 误判普通 status 字段** → 比如某个 API 的 `status=active` 表示"运行中"，但被错误归类为 SUCCESS
- **Mitigation**：候选值白名单而非黑名单；未命中候选值一律视为"未完成"；用户仍可配 `completionJsonPath` 覆盖

**[Risk] 单参数 `URLEncoder.encode` 删除后有未发现的间接调用方依赖** → 比如有其他 service 自己调了单参数版本
- **Mitigation**：先用 grep 全仓搜 `URLEncoder.encode` 单参数版本，确认只有 `buildUrlWithQuery` 一处使用；如果有就一起改

**[Risk] auto-detect 路径优先级排错** → 比如 `$.data.status` 命中但其实是个嵌套的错误对象
- **Mitigation**：第一次失败可观测，写 audit log 记录 auto-detect 命中了哪个路径、值是什么，方便后续调整

**[Risk] 前端"即将完成"提示让用户以为任务已卡死** → 实际上还在跑
- **Mitigation**：tooltip 文案明确写"任务仍在运行中"而不是"已卡死"；进度条颜色保持 active 状态不变

## Migration Plan

- 本 change 是**纯 bug fix + 增量能力**，不需要数据迁移
- 部署顺序：
  1. 部署 gateway（含 `buildUrlWithQuery` 修复 + `evaluateCompletion` auto-detect）
  2. 部署 frontend（含 `progressPercent` 改进）
- 灰度策略：不需要，可全量上线
- 回滚策略：gateway / frontend 各自回滚到上一版本即可，无需 schema 变更
