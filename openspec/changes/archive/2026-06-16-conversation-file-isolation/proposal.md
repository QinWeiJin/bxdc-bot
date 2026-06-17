## Why

当前文件管理工具（`file_list`、`file_delete`、`file_clear_all`、`file_detail`）直接暴露用户全部文件，无会话维度隔离。多对话场景下，LLM 在对话 A 中可以看到对话 B 上传的文件，用户无法控制每个会话可操作的文件范围。同时，用户需要在对话间手动共享文件的能力——当前缺少这一机制。

## What Changes

- **conversations 表新增 `enabled_files` JSON 字段**：存储该会话可操作的文件 ID 列表，结构为 `[1, 3, 5]`，与现有 `enabled_skills` 字段模式一致
- **文件管理工具接入会话隔离**：`file_list` 在查询时按 `enabled_files` 过滤，`file_delete`/`file_detail` 在校验 `userId` 之外再加 `enabled_files` 归属校验。`file_clear_all` 仅操作当前会话内的文件
- **上传自动绑定**：用户在当前对话中上传文件时，该文件 ID 自动追加到当前对话的 `enabled_files`
- **前端"配置"面板扩展**：`ConversationSidebar` 的"Skill配置"按钮改为"配置"，弹出的 `ConversationSkillPanel` 扩展为 Tab 布局（Skill | 文件），文件 Tab 用 checkbox 展示用户全部文件并可勾选
- **跨会话文件共享**：用户可手动勾选同一文件到多个对话的 `enabled_files`，满足跨会话操作文件的需求
- **Java 层全程控制**：所有隔离逻辑在 gateway 侧 `FileManageService` + `ConversationService` 完成，agent-core 和 LLM 不感知文件过滤逻辑

## Capabilities

### New Capabilities
- `conversation-file-isolation`: 会话级别文件访问控制，包括 `enabled_files` 字段维护、文件工具过滤、上传自动绑定、跨会话共享

### Modified Capabilities
- `conversation-crud`: conversations 表新增 `enabled_files` JSON 字段，`POST/PUT /api/conversations` 接受 `enabled_files` 参数，`GET /api/conversations` 返回该字段
- `file-management`: `file_list`/`file_delete`/`file_clear_all`/`file_detail` 四个工具的操作范围从"用户全部文件"收窄为"当前会话已启用的文件"；`file_list` 新增 `conversationId` 上下文参数用于过滤

## Impact

- **gateway**: `Conversation` 实体、`ConversationService`、`ConversationController`、`schema-mysql.sql`（新增字段）
- **gateway**: `FileManageService`、`FileToolService`（文件工具增加 conversation 上下文）
- **gateway**: `FileUploadController`（上传时自动绑定到当前会话）
- **gateway**: `DataMigrationService`（为存量对话的 `enabled_files` 填充 null 默认值）
- **frontend**: `ConversationSidebar.vue`（"Skill配置" → "配置"）
- **frontend**: `ConversationSkillPanel.vue`（扩展为 Tab 布局，新增文件 Tab）
- **不修改**: 前端 `useChat.ts`、LLM 提示词、文件内容操作工具（如 `md_read`/`excel_filter` 等）
