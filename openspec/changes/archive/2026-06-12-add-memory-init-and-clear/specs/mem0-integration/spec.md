# mem0-integration Specification (delta)

## ADDED Requirements

### Requirement: mem0 服务提供按用户 ID 全量删除记忆的接口

mem0 服务 MUST 提供一个 `POST /deletemem` 接口，用于按 `userid` 全量删除该用户的所有长期记忆。请求参数格式 MUST 与现有 `/madd` **完全对齐**（`userid` 必填，`sentencein` / `sentenceout` 可省略或空字符串），不强制要求语义匹配字段（两个 sentence 字段都留空即全量删除）。

**契约**：
- **请求**：`POST {MEM0_URL}/deletemem`，body `{ sentencein?: string, sentenceout?: string, userid: string }`（userid 必填，sentencein / sentenceout 可省略）
- **成功响应**：`{ code: 200, message: '提示信息', userid: xxx, time: xxx }`
- **失败响应**：`{ code: 400, message: '用户记忆删除失败' }`（如 userid 缺失 / 删除失败）

#### Scenario: 按 userid 全量删除
- **WHEN** 客户端调用 `POST /deletemem` 携带 `{ userid: <id>, sentencein: '', sentenceout: '' }`（sentencein / sentenceout 可省略）
- **THEN** mem0 服务 MUST 删除该 `userid` 名下所有记忆条目
- **AND** 返回 `{ code: 200, message: '...', userid: <id>, time: <ts> }` 表示成功

#### Scenario: 缺少 userid 拒绝执行
- **WHEN** 客户端调用 `POST /deletemem` 但请求体缺少 `userid` 字段或 `userid` 为空字符串
- **THEN** mem0 服务 MUST 返回 `{ code: 400, message: '用户记忆删除失败' }`
- **AND** MUST NOT 删除任何记忆

#### Scenario: 删除不存在的用户
- **WHEN** 客户端调用 `POST /deletemem` 携带的 `userid` 在 mem0 中无任何记忆
- **THEN** mem0 服务 MUST 仍返回 `{ code: 200, message: '...', userid: <id>, time: <ts> }`（幂等，不抛错）

#### Scenario: 跨用户隔离
- **WHEN** 多个用户的记忆共存于 mem0
- **THEN** `POST /deletemem` MUST 仅删除指定 `userid` 的记忆，**不**影响其他用户

#### Scenario: 与 /madd 参数对齐
- **WHEN** mem0 服务实现 `/deletemem` 端点
- **THEN** 请求体 MUST 包含 `sentencein` / `sentenceout` / `userid` 三个字段（与 `/madd` 一致）
- **AND** 与 `/madd` 不同：`/deletemem` 中 `sentencein` / `sentenceout` 可省略/为空（删除不需要语义内容）

### Requirement: mem0 其他端点契约保持不变

本次 change **只**新增 `/deletemem` 端点，mem0 服务的其他端点契约 MUST **保持不变**：

- `POST /madd` —— 添加记忆（参数 `{ sentencein, sentenceout, userid }`，响应 `{ code, message }`）
- `POST /msearch` —— 语义检索记忆（参数 `{ sentence, userid, topk }`，响应 `{ code, message, details: [...] }`）
- `POST /dreamsearch` —— 梦境检索（参数 `{ sentence, userid, topk }`，响应 `{ code, message, details: [...] }`）

**不**通过本仓库修改 mem0 服务。如果未来 mem0 服务需要变更其他端点，**不**在本 change scope 内。

#### Scenario: bxdc-bot 调用其他 mem0 端点不受影响
- **WHEN** 本次 change 部署后 bxdc-bot 调用 `/madd` / `/msearch` / `/dreamsearch`
- **THEN** 请求/响应契约 MUST 与本 change 部署前完全一致
- **AND** MUST NOT 出现请求参数变化或响应字段缺失
