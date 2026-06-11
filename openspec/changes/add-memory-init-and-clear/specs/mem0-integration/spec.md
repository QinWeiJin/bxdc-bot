# mem0-integration Specification (delta)

## ADDED Requirements

### Requirement: mem0 服务提供按用户 ID 全量删除记忆的接口

mem0 服务 MUST 提供一个 `POST /deletemem` 接口，用于按 `userid` 全量删除该用户的所有长期记忆。请求参数格式 MUST 与现有 `/msearch` 对齐（`userid` 必填），不强制要求语义匹配字段（`sentence` 留空即全量删除）。

#### Scenario: 按 userid 全量删除
- **WHEN** 客户端调用 `POST /deletemem` 携带 `{ userid: <id>, sentence: '' }`（sentence 可省略）
- **THEN** mem0 服务 MUST 删除该 `userid` 名下所有记忆条目
- **AND** 返回 `{ code: 200, message: "..." }` 表示成功

#### Scenario: 缺少 userid 拒绝执行
- **WHEN** 客户端调用 `POST /deletemem` 但请求体缺少 `userid` 字段或 `userid` 为空字符串
- **THEN** mem0 服务 MUST 返回 `{ code: <4xx>, message: "userid is required" }`
- **AND** MUST NOT 删除任何记忆

#### Scenario: 删除不存在的用户
- **WHEN** 客户端调用 `POST /deletemem` 携带的 `userid` 在 mem0 中无任何记忆
- **THEN** mem0 服务 MUST 仍返回 `{ code: 200, message: "..." }`（幂等，不抛错）

#### Scenario: 跨用户隔离
- **WHEN** 多个用户的记忆共存于 mem0
- **THEN** `POST /deletemem` MUST 仅删除指定 `userid` 的记忆，**不**影响其他用户
