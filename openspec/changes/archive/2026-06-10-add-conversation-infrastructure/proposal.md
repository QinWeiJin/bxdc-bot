## Why

当前系统没有持久化的对话（Conversation）概念。用户消息仅保存在前端内存中（`useChat.ts` 的 `messages` 响应式数组），关闭页面后全部丢失。每次发送消息生成为临时 `sessionId`，无法追溯历史、切换对话、或按项目维度隔离上下文。需要搭建对话持久化的后端基础设施，为多 Session 对话系统（Phase 2、3）提供数据层支撑。

## What Changes

- **新增 `conversations` 表**：存储用户的对话会话（名称、启用 Skill ID 列表、状态、时间戳），支持用户-对话 1:N 关系。
- **新增 `conversation_messages` 表**：存储每轮对话的完整消息内容（角色、文本、工具调用、工具结果），支持分页加载历史。
- **新增 Conversation CRUD API**（`/api/conversations`）：提供对话的创建、查询列表、获取详情+历史消息、更新、删除能力。
- **新增消息落库 API**（`/api/conversations/:id/messages`）：供内部调用，批量保存 SSE 流结束后产生的本轮消息。
- **新增投产数据迁移服务**（`DataMigrationService`）：Spring Boot 启动时自动为每个存量用户创建一个默认对话，归属全量可用 Skill，确保升级后用户体验不中断。

## Capabilities

### New Capabilities
- `conversation-crud`: 对话会话的创建、查询、更新、删除 REST API，支持按用户列出所有对话、分页加载对话历史消息。
- `conversation-message-storage`: 消息持久化能力，支持批量保存消息（user/assistant/tool/system 四种角色），记录工具调用和输出。
- `production-data-migration`: 投产数据迁移能力，Spring Boot 启动时自动为存量用户创建默认对话并归属全量 Skill，幂等安全。

### Modified Capabilities
<!-- No existing spec requirements are changed in Phase 1. -->

## Impact

- **数据库**：新增 `conversations` 和 `conversation_messages` 两张表（DDL 追加到 `schema-mysql.sql`）。
- **skill-gateway**：新增 `ConversationController`、`ConversationService`、`ConversationMapper`、`ConversationMessageMapper`、`Conversation` Entity、`ConversationMessage` Entity、`DataMigrationService` 共 7 个 Java 文件。
- **SecurityConfig**：新增 `/api/conversations/**` 路由认证规则。
- **前端**：Phase 1 无前端改动。Phase 2 将消费这些 API。
- **agent-core**：Phase 1 无改动。Phase 3 将扩展 `/agent/run` 接收 `enabledSkillIds`。
- **存量功能**：无影响。两张新表与现有 `conversation_logs`（审计日志表）命名相似但用途完全不同，需在注释中明确区分。
