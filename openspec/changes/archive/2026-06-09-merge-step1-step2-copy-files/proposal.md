## Why

将 lijianlong/low-version 的 58 个增量 commit（思考模式、异步通知中心、文档解析、文件上传等）合入 temp 分支的重构架构。直接 git merge 会产生大量冲突（上次已验证失败），改为分步搬运：先搬运无冲突的新增文件和修改文件，再手动融合 7 个冲突文件。

## What Changes

- **Step 1**：从 lijianlong/low-version 搬运所有新增（A）源文件到 temp 分支（约 50+ 文件，零冲突）
- **Step 2**：从 lijianlong/low-version 搬运所有仅 lijianlong 修改过的文件（约 73 文件，排除双方都改的 7 个冲突文件）

## Capabilities

<!-- 本次是代码搬运操作，不涉及新能力或已有能力的规格变更 -->
<!-- No new or modified capabilities - this is a file-copy merge operation -->

## Impact

- **agent-core**：新增 thinking-mode、conversation-logger 等工具模块，skill.manager.ts / agent.controller.ts 等被覆盖更新
- **skill-gateway**：新增文档解析器（Excel/Word/PPT）、异步通知中心（DTO/Controller）、审计表实体（ConversationLog/ToolCallLog）、配置类（DedupConfig/SchemaMigrationRunner）等
- **frontend**：新增文件上传/解析工具链、通知中心、思考模式组件，ChatView/MessageInput/MessageList 等被覆盖更新
- **不涉及**：7 个冲突文件（agent.ts、java-skills.ts、SkillController.java 等）将在下一步单独处理
