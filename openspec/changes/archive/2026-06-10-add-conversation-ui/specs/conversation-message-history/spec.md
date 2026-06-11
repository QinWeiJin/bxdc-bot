## ADDED Requirements

### Requirement: 消息持久化落库

用户发送消息后，当 SSE 流式响应完成后，系统 SHALL 将本轮对话的所有消息（用户消息 + assistant 消息 + tool 消息）批量保存到服务端。

#### Scenario: SSE 结束后自动保存
- **WHEN** 用户发送"帮我查服务器状态"，assistant 调用 tool 并完成 SSE 流
- **THEN** 系统调用 `POST /api/conversations/:id/messages`，提交包含 user、assistant（含 skill_calls）、tool 消息的数组

#### Scenario: 落库失败不影响用户
- **WHEN** 消息落库 API 返回错误（网络异常或服务端故障）
- **THEN** 用户已看到的消息保持不变，控制台输出错误日志，不影响后续发送

#### Scenario: 消息包含 tool_call 信息
- **WHEN** assistant 消息包含 `skill_calls` 字段（调用了某个 Skill）
- **THEN** `skill_calls` JSON 正确序列化并保存到 `conversation_messages.skill_calls`

### Requirement: 历史消息分页加载

`GET /api/conversations/:id` 接口 SHALL 返回对话的近期消息和分页信息。消息按 `created_at` 降序返回（最新的在前），前端反转后正序渲染。分页通过 `cursor` 参数实现（传入上一条消息的 `created_at` 时间戳）。

#### Scenario: 首次加载默认 50 条
- **WHEN** 用户切换到一个有 60 条历史消息的对话
- **THEN** 接口返回最近 50 条消息，`hasMore` 为 `true`

#### Scenario: cursor 分页加载更多
- **WHEN** 用户向上滚动触发分页，传入最早那条消息的 created_at 作为 cursor
- **THEN** 接口返回该时间点之前的 50 条消息，追加到消息列表顶部

#### Scenario: 全量加载完毕
- **WHEN** 对话的所有历史消息都已加载
- **THEN** `hasMore` 为 `false`，向上滚动不再触发请求

### Requirement: 历史消息渲染保真度

加载的历史消息 SHALL 完整还原原始对话内容，包括消息内容、角色标识、tool_call 信息。

#### Scenario: assistant 消息渲染 tool_call
- **WHEN** 历史消息中包含一条 assistant 消息的 `skill_calls` 为 `{"name": "query_server"}`
- **THEN** 聊天区域渲染该消息时显示"调用了 query_server 技能"的标记

#### Scenario: tool 消息渲染
- **WHEN** 历史消息中包含一条 role 为 "tool" 的消息
- **THEN** 聊天区域以 tool 消息样式渲染（区别于 user 和 assistant 消息的样式）
