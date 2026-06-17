## 1. Database Schema

- [x] 1.1 在 `schema-mysql.sql` 的 `conversations` 表中新增 `enabled_files JSON DEFAULT NULL COMMENT '该对话启用的文件ID列表，如 [1, 3, 5]'`
- [x] 1.2 在 `SchemaMigrationRunner.java` 中新增 `migrateConversationEnabledFiles()` 方法，`ALTER TABLE conversations ADD COLUMN enabled_files JSON DEFAULT NULL`；在 `afterPropertiesSet()` 中调用该方法

## 2. Gateway — Entity & Mapper 层

- [x] 2.1 在 `Conversation.java` 实体中新增 `enabledFiles` 字段（`@TableField("enabled_files")`）及其 getter/setter
- [x] 2.2 `ConversationService.create()` 中 `enabled_files` 默认值设为空数组 `"[]"`
- [x] 2.3 `ConversationService.update()` 支持 `enabledFiles` 参数，序列化为 JSON 写入数据库
- [x] 2.4 `ConversationController.toConversationDto()` 返回 `enabled_files` 字段
- [x] 2.5 新增 `ConversationService.appendEnabledFile(conversationId, userId, fileId)` 方法：读 JSON 数组 → append → 写回（幂等去重）
- [x] 2.6 新增 `ConversationService.removeEnabledFileFromAllConversations(fileId)`：清理所有对话 enabled_files 中的该 fileId 引用（删除文件时调用，避免孤行）

## 3. Gateway — 文件工具会话隔离

- [x] 3.1 `FileToolService.execute()` 增加可选 `conversationId` 参数（**可选**，不影响其他 file_* 工具），从 header `X-Conversation-Id` 读取
- [x] 3.2 新增 `FileToolService.resolveEnabledFiles(conversationId, userId)`：查询 `Conversation.enabled_files` 反序列化为 `List<Long>`，返回 null 时表示全量
- [x] 3.3 `FileManageService.fileList()` 接受 `List<Long> enabledFiles` 参数：非 null 且非空时用 `IN (?)` 过滤；null 时全量返回（向后兼容）；空数组时返回空结果
- [x] 3.4 `FileManageService.fileDelete()` 和 `fileDetail()` 接受 `enabledFiles` 参数：非 null 时校验文件 ID 是否在列表中，不在则返回错误
- [x] 3.5 `FileManageService.fileClearAll()` 接受 `enabledFiles` 参数：非 null 时仅删除列表中的文件
- [x] 3.6 `FileToolSeeder` 中更新 `file_clear_all` 描述为"清空当前会话内的所有文件"（反映新语义）
- [x] 3.7 `file_delete` 成功删除文件后，调用 `ConversationService.removeEnabledFileFromAllConversations(fileId)` 清理引用
- [x] 3.8 编译验证：`bash -c 'cd backend/skill-gateway && mvn -s ./settings.xml compile'`

## 4. Gateway — 上传自动绑定 + agent-core 透传

- [x] 4.1 `FileUploadController` 上传接口接收可选 `conversationId` 参数（从 form data 或 query）
- [x] 4.2 上传成功后，若 `conversationId` 非空，调用 `ConversationService.appendEnabledFile(conversationId, userId, fileId)`
- [x] 4.3 编译验证
- [x] 4.4 `agent.controller.ts` 的 `/agent/run` 在调 gateway 时把 `conversationId` 写入自定义 header `X-Conversation-Id`（与 `X-User-Id`/`X-Session-Id` 一致）
- [x] 4.5 `JavaComputeTool`/`JavaSkillGeneratorTool` 等向 gateway 发请求时携带 `X-Conversation-Id` header

## 5. Frontend — 会话配置面板 Tab 化

- [x] 5.1 `ConversationSidebar.vue` 中"Skill配置"按钮文案改为"配置"
- [x] 5.2 `ConversationSkillPanel.vue` 扩展为 Tab 布局（"Skill" / "文件"），文件 Tab 内用 checkbox 列表展示用户全部文件
- [x] 5.3 文件 Tab 加载逻辑：通过 `/api/files/list`（user_file 查询接口）获取用户全部文件，已勾选的从 `conversation.enabled_files` 初始化
- [x] 5.4 保存时同时提交 `enabled_skills` 和 `enabled_files`
- [x] 5.5 前端构建验证：`cd frontend && npm run build`

## 6. 集成验证

- [x] 6.1 重启 gateway → `SchemaMigrationRunner` 自动执行 ALTER TABLE
- [x] 6.2 创建新对话 → 验证 `enabled_files = []`
- [x] 6.3 在该对话上传文件 → 验证文件 ID 自动加入 `enabled_files`
- [x] 6.4 在该对话调用 `file_list` → 验证仅返回 `enabled_files` 中的文件
- [x] 6.5 切换到另一对话 → 验证 `file_list` 返回不同文件列表（独立隔离）
- [x] 6.6 验证跨会话共享：对话 A 手动勾选文件 X → 对话 B 的 `file_list` 也能看到文件 X
- [x] 6.7 删除某文件 → 验证所有对话的 `enabled_files` 中该 fileId 被清理
