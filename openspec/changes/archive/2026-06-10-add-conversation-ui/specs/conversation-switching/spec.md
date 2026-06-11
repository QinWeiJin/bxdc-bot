## ADDED Requirements

### Requirement: 对话切换清空当前消息

切换对话时，系统 SHALL 清空当前聊天区域的所有消息，替代为目标对话的历史消息。

#### Scenario: 点击对话切换
- **WHEN** 用户点击对话 B（当前在对话 A，消息区域有 10 条消息）
- **THEN** 消息区域清空，显示 loading 状态，加载对话 B 的历史消息后渲染

#### Scenario: 切换回原对话
- **WHEN** 用户从对话 B 切回对话 A
- **THEN** 消息区域重新加载对话 A 的历史消息（不从内存缓存恢复）

### Requirement: 中止进行中的 SSE 流

切换对话时，如果当前对话有进行中的 SSE 流式响应，系统 SHALL 立即中止该请求。

#### Scenario: 流式输出中切换对话
- **WHEN** 对话 A 正在接收 SSE 流式响应（assistant 消息实时渲染中），用户点击对话 B
- **THEN** 对话 A 的 fetch reader 被 abort，对话 A 的消息不再更新；对话 B 的消息区域清空并加载

#### Scenario: 中止后不再处理残留事件
- **WHEN** SSE 流被 abort 后，网络层仍可能有少量残留数据
- **THEN** 系统丢弃残留数据，不写入任何对话的消息数组

### Requirement: 切换时加载历史消息

切换到一个对话后，系统 SHALL 调用 `GET /api/conversations/:id` 加载该对话的历史消息（最近 50 条），按时间正序渲染（旧消息在上、新消息在下）。

#### Scenario: 加载有历史消息的对话
- **WHEN** 用户切换到对话 B（有 30 条历史消息）
- **THEN** 聊天区域显示这 30 条消息，时间正序排列

#### Scenario: 加载无历史的对话
- **WHEN** 用户切换到刚创建的空对话
- **THEN** 聊天区域为空，显示欢迎语或默认提示

### Requirement: 向上滚动加载更早消息

当用户向上滚动到消息列表顶部时，系统 SHALL 自动触发 cursor 分页加载更早的消息。

#### Scenario: 触发分页加载
- **WHEN** 用户向上滚动到消息列表顶部，且还有更多历史消息（hasMore === true）
- **THEN** 系统传入最后一条消息的 created_at 作为 cursor，请求下一页 50 条消息，加载后追加到消息列表顶部

#### Scenario: 没有更多消息
- **WHEN** 用户向上滚动到消息列表顶部，但 hasMore === false
- **THEN** 系统不发起请求，在消息列表顶部显示"没有更多消息"

#### Scenario: 加载中状态
- **WHEN** 分页请求正在进行中
- **THEN** 消息列表顶部显示 loading 指示器，用户仍可正常阅读已有消息和输入新消息
