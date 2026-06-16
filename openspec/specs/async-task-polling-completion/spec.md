# async-task-polling-completion Specification

## Purpose
TBD - created by archiving change fix-skill-get-url-and-polling-status. Update Purpose after archive.
## Requirements
### Requirement: 用户配置 completionJsonPath + completionValue 时严格按配置判定（存量行为）

当 async_task 表的 `completion_json_path` 与 `completion_value` 字段均非空时，系统 MUST 严格按配置的 JSONPath 提取响应字段值，并与 `completionValue` 做大小写不敏感匹配：
- 命中 → 标记 COMPLETED，调 `extractResult` 抽 `result_json_path` 字段作为 result
- 不命中 → 视为未完成，继续轮询

用户配置 MUST 优先于自动检测（本要求覆盖 auto-detect 行为）。

#### Scenario: 用户配置正确
- **WHEN** skill 配置 `completionJsonPath=$.data.status` `completionValue=SUCCESS`
- **AND** pollEndpoint 返回 `{"data":{"status":"SUCCESS","result":{...}}}`
- **THEN** 系统 MUST 标 COMPLETED 并把 `result` 字段作为最终 result

#### Scenario: 用户配置路径错误
- **WHEN** skill 配置 `completionJsonPath=$.data.status` 但响应实际是 `{"status":"SUCCESS"}`（字段在根级）
- **THEN** 按配置路径提取 MUST 返回 null
- **AND** 系统 MUST 触发 auto-detect 兜底（见下一条 requirement），不应直接判失败

#### Scenario: 用户只配路径没配 value
- **WHEN** skill 仅配 `completionJsonPath=$.data.status`，`completionValue` 为空
- **THEN** 系统 MUST 跳过用户配置判定，走 auto-detect 兜底

### Requirement: 用户未配置或配置失败时 MUST 走 auto-detect 兜底

当用户配置缺失 / 路径解析失败 / 值不匹配时，系统 MUST 自动从 pollEndpoint 响应中按候选字段路径列表依次尝试，找到第一个存在的 status-like 字段并按候选终态值白名单判定。

候选字段路径（按优先级）：
1. `$.status`
2. `$.state`
3. `$.code`
4. `$.data.status`
5. `$.data.state`
6. `$.data.code`
7. `$.result.status`
8. `$.result.code`

候选 SUCCESS 终态值（大小写不敏感）：`SUCCESS` / `COMPLETED` / `FINISHED` / `DONE` / `SUCCEED` / `OK` / `2`
候选 FAILURE 终态值（大小写不敏感）：`FAILED` / `FAILURE` / `ERROR` / `CANCELLED` / `CANCELED` / `TIMEOUT` / `EXPIRED`

#### Scenario: 标准 {status: "SUCCESS"} 响应
- **WHEN** pollEndpoint 返回 `{"status":"SUCCESS","data":{...}}`
- **AND** 用户未配置 completionJsonPath
- **THEN** auto-detect 走 `$.status` 命中
- **AND** 命中值 `SUCCESS` ∈ SUCCESS 终态白名单
- **AND** 系统 MUST 标 COMPLETED

#### Scenario: 嵌套 {data: {status: "FINISHED"}}
- **WHEN** pollEndpoint 返回 `{"data":{"status":"FINISHED","payload":{...}}}`
- **AND** 用户未配置
- **THEN** auto-detect 走 `$.data.status` 命中
- **AND** `FINISHED` ∈ SUCCESS 白名单
- **AND** 系统 MUST 标 COMPLETED

#### Scenario: 失败 {status: "FAILED"}
- **WHEN** pollEndpoint 返回 `{"status":"FAILED","error":"..."}`
- **AND** 用户未配置
- **THEN** auto-detect 走 `$.status` 命中 `FAILED`
- **AND** `FAILED` ∈ FAILURE 白名单
- **AND** 系统 MUST 标 FAILED，errorMessage MUST 为响应中的 `error` 字段或 `"Task failed: status=FAILED"`

#### Scenario: 响应无 status 字段
- **WHEN** pollEndpoint 返回 `{"result":{...}}`（无 status / state / code 任何字段）
- **AND** 用户未配置
- **THEN** auto-detect 全部候选路径 MUST 返回 null
- **AND** 系统 MUST 按"未完成"处理，继续轮询
- **AND** MUST NOT 误判为 FAILED

#### Scenario: 响应含 status 但值不在白名单
- **WHEN** pollEndpoint 返回 `{"status":"RUNNING","progress":0.5}`（运行中）
- **AND** 用户未配置
- **THEN** auto-detect 命中 `$.status` 但 `RUNNING` ∉ SUCCESS/FAILURE 白名单
- **AND** 系统 MUST 按"未完成"处理，继续轮询
- **AND** MUST NOT 误判为 COMPLETED 或 FAILED

#### Scenario: 大小写不敏感
- **WHEN** pollEndpoint 返回 `{"status":"success"}`（小写）
- **THEN** auto-detect MUST 识别为 SUCCESS 终态

### Requirement: auto-detect 命中 FAILURE 时复用 evaluateFailure 失败标记链路

auto-detect 命中 FAILURE 终态值时，系统 MUST 走与 `evaluateFailure` 一致的失败标记链路：
- 调 `updatePollResult(taskId, "FAILED", null, errMsg)` 写状态
- 写 `async_polling_audit_log` 记录失败
- 触发 `triggerChatReplyIfTerminal` 触发 LLM 续答

不允许 auto-detect 路径绕过任何上述步骤。

#### Scenario: auto-detect 失败标记链路完整
- **WHEN** auto-detect 命中 `FAILED` 终态
- **THEN** 系统 MUST 写 `pollResult` 字段 = `FAILED`
- **AND** 系统 MUST 写一条 `phase=GATEWAY_POLL_COMPLETE` 的审计日志
- **AND** 系统 MUST 触发 LLM 续答

### Requirement: auto-detect 命中 SUCCESS 时复用 COMPLETED 标记链路

auto-detect 命中 SUCCESS 终态值时，系统 MUST 走与用户配置路径命中的 COMPLETED 一致的标记链路：
- 调 `extractResult` 抽 `result_json_path` 字段；若 `result_json_path` 为空则用整个 pollResponse 作为 result
- 调 `updatePollResult(taskId, "COMPLETED", result, null)` 写状态
- 写审计日志
- 触发 LLM 续答

#### Scenario: auto-detect 成功链路
- **WHEN** auto-detect 命中 `SUCCESS` 终态
- **AND** skill 配置了 `resultJsonPath=$.payload`
- **THEN** 系统 MUST 抽 `$.payload` 字段作为 result
- **AND** 系统 MUST 标 COMPLETED + 写 result + 触发续答

#### Scenario: 无 resultJsonPath 兜底
- **WHEN** auto-detect 命中 `SUCCESS` 终态
- **AND** skill 没配 `resultJsonPath`
- **THEN** 系统 MUST 把整个 pollResponse 作为 result

