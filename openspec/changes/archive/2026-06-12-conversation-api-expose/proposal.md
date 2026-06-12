## Why

当前 BXDC.bot 的 Agent 对话能力仅限平台内 Web UI 使用，无法被外部系统集成调用。需要将对话以 API 形式对外发布，使第三方系统可通过 HTTP 调用。发布后对话页面变为 API 详情视图，展示描述、地址和调用记录。

## What Changes

- **发布入口**：左侧对话列表每个对话项右侧增加"发布为API"按钮
- **发布弹窗**：点击后弹出，要求填写"API 描述"（描述该 API 提供的服务），提交后对话进入"已发布"状态
- **已发布对话页面**：不再展示聊天对话框，改为展示：
  - API 描述文本
  - API Key（掩码展示，支持复制和重新生成）
  - API 调用地址（`POST /api/agent-chat`）
  - 请求格式 JSON 示例
  - API 调用记录表格（调用方输入、Agent 输出、调用时间、响应耗时）
- **API Key 生成**：发布时自动生成独立 API Key（格式 `c_` + 32 位 hex），每个对话独立，泄露只影响单对话
- **API 调用逻辑**：gateway 通过 API Key 定位对话，将 API 描述作为 `role: 'system'` 消息注入 `history` 数组传给 LLM，同步返回回复
- **并发隔离**：每次 API 调用生成独立 sessionId，多人可同时调用同一对话，互不影响
- **数据库变更**：
  - `conversations` 新增 `is_published` + `api_description` + `api_key` + `api_key_hash` 字段
  - `conversation_messages` 新增 `source` 字段（`'web'` / `'api'`）
  - 新增 `api_call_logs` 表记录每次 API 调用的详情

## Capabilities

### New Capabilities
- `conversation-api-publish`: 对话发布为 API 的完整能力，包含发布弹窗、API 描述注入、已发布视图、调用记录

### Modified Capabilities
<!-- 不修改已有 spec -->

## Impact

- `backend/skill-gateway/` — 新增 `ConversationApiController`、`ConversationApiService`；Entity 加字段；新增 `ApiCallLog` entity + mapper；Schema SQL 变更
- `backend/agent-core/` — 无需修改
- `frontend/src/components/` — 新增 `PublishApiModal.vue`、`ApiDetailView.vue`；修改 `ConversationSidebar.vue`（加"发布为API"按钮）、`ChatView.vue`（根据 `is_published` 条件渲染）
- `frontend/src/composables/` — `useConversations` 加 `publishConversation` 方法
- 数据库 — 3 处变更（2 张表加列 + 1 张新表）
