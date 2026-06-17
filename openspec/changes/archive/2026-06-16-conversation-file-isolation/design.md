## Context

当前系统已有会话级 Skill 隔离（`conversations.enabled_skills` + agent-core 过滤）。文件管理工具（`file_list`/`file_delete`/`file_clear_all`/`file_detail`）暴露在 LLM tool 层面，但操作范围是用户全部文件——没有任何会话维度过滤。

会议/对话场景下，用户在不同对话中处理不同文件，需要隔离。同时需要支持跨会话共享文件的机制（用户手动勾选）。

约束：
- 文件工具的入口是 `FileToolService.execute()` → `FileManageService`，与 `enabled_skills` 的 agent-core 侧过滤不同，文件过滤更适合在 Java 层做（不涉及 tool schema 动态调整）
- `file_list`/`file_delete`/`file_clear_all`/`file_detail` 直接暴露给 LLM，LLM 不知道 conversation 上下文——需 gateway 侧根据请求上下文注入

## Goals / Non-Goals

**Goals:**
- `conversations` 表新增 `enabled_files` JSON 字段，维护该对话可操作的文件 ID 列表
- `file_list` 查询结果按 `enabled_files` 过滤，只返回当前会话有权限的文件
- `file_delete`/`file_detail` 增加 `enabled_files` 归属校验（在现有 `userId` 校验之上）
- `file_clear_all` 仅清空当前会话内文件（而非用户全部文件）
- 用户上传文件时自动绑定到当前会话
- 前端支持每个会话独立勾选可操作的文件

**Non-Goals:**
- 不改变 `md_*`/`excel_*`/`txt_*`/`word_*` 等文件内容操作工具的行为（它们通过 `fileId` 参数定位文件，authZ 已在 `FileToolService.execute` 入口完成）
- 不在 agent-core 侧做文件过滤（保持与 Skill 过滤的架构区分）
- 不修改 LLM 系统提示词

## Decisions

### Decision 1: Java 层过滤（非 agent-core 侧）

**选择**：在 `FileManageService` 中添加 `enabledFiles` 参数，由 `FileToolService.execute()` 根据请求上下文注入。

**理由**：文件管理工具不同于 Extension Skill——后者的工具 schema 是动态生成的（agent-core 侧控制），而文件工具的 handler 注册是固定的（gateway 侧）。在 Java 层做过滤更直接，不涉及 LangChain tool schema。

**替代方案**：像 `enabled_skills` 一样在 agent-core 侧根据 `enabledFileIds` 过滤 tool——但文件管理只有 4 个 tool，不动态变化，agent-core 侧过滤意义不大。

### Decision 2: `enabled_files` 字段格式与 `enabled_skills` 一致

**选择**：JSON 数组 `[1, 3, 5]`，存储在 `conversations` 表。

**理由**：与 `enabled_skills` 保持一致性，`ConversationService` 已有 `skillsToJson`/`enabledSkills` 的处理模式可直接复用。前端 checkbox 交互逻辑也可参考 `ConversationSkillPanel`。

### Decision 3: 上传自动绑定的时机

**选择**：在 `FileUploadController` 上传成功后，如果请求携带 `conversationId`，自动将新 `fileId` 追加到该对话的 `enabled_files`。

**理由**：用户上传文件到某对话内是最自然的绑定时机。如果用户之后需要在其他对话也使用该文件，通过前端配置面板手动勾选。

### Decision 4: 前端面板改为 Tab 布局

**选择**：将现有 `ConversationSkillPanel.vue` 扩展为 Tab 布局（Skill | 文件），而非创建独立组件。

**理由**：两个面板共享"配置"入口、conversationId 上下文、保存逻辑。合并为一个组件减少文件数量和状态同步复杂度。

**替代方案**：创建独立的 `ConversationFilePanel.vue`，在 `ConversationSidebar` 用 `v-if` 切换——虽更模块化但增加 props 透传和状态管理复杂度。

### Decision 5: `file_clear_all` 行为变更

**选择**：`file_clear_all` 的范围从"用户全部文件"改为"当前会话 `enabled_files` 中的文件"。

**理由**：LLM 在对话中调用 `file_clear_all` 时，用户期望的是清空"这个对话"的文件，而非全局清空。这与其他三个文件工具的行为逻辑一致。用户如需全局清空，可在多个对话中重复操作或由前端提供入口。

**注意**：这是 **BREAKING** 的行为变更——LLM 在调 `file_clear_all` 时的语义从"清空全部文件"变为"清空当前会话文件"。

## Risks / Trade-offs

- **[Breaking] `file_clear_all` 语义变更**：LLM 可能继续以"清空全部文件"的语义调用，但实际只会清空当前会话文件。→ 在 seeder 提示词中更新 `file_clear_all` 描述为"清空当前会话内的所有文件"。
- **[孤立文件] 用户删除对话时 `enabled_files` 中的文件不会被删**：文件归属仍是 `user_id` 维度，删除对话只移除 `enabled_files` 字段中的引用，不影响文件本身。→ 符合预期，文件是用户级资产。
- **[性能] `file_list` 查询需先获取 `enabled_files` 再过滤**：相比直接 `WHERE user_id = ?`，多了 `IN (id1, id2, ...)` 子句。→ `enabled_files` 通常在几个到几十个，性能影响可忽略。

## Migration Plan

1. `SchemaMigrationRunner` 新增 `migrateConversationEnabledFiles()`，`ALTER TABLE conversations ADD COLUMN enabled_files JSON DEFAULT NULL`
2. 存量对话的 `enabled_files` 默认为 NULL（无过滤 = 全量），行为向后兼容
3. 新创建的对话 `enabled_files` 默认为空数组 `[]`
4. 前端部署后，`ConversationSkillPanel` 扩展为 Tab 布局
5. 无需数据迁移脚本——旧对话 NULL 表示不启用过滤
