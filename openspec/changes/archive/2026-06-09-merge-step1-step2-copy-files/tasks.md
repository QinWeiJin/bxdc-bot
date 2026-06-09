## 1. Step 1：搬运新增文件（约 60 个，零冲突） ✅ 完成

### 1.1 agent-core 新增

- [x] 1.1.1 `agent-core/src/utils/conversation-logger.ts`
- [x] 1.1.2 `agent-core/src/utils/thinking-mode.ts`
- [x] 1.1.3 `agent-core/src/utils/thinking-mode.spec.ts`
- [x] 1.1.4 `agent-core/third-party/tesseract.js/package-lock.json`
- [x] 1.1.5 `agent-core/openspec/changes/archive/2026-06-03-fix-cors-security-vulnerabilities/*`
- [x] 1.1.6 `packages/` 目录新增文件

### 1.2 skill-gateway 新增

- [x] 1.2.1 `config/DedupConfig.java`
- [x] 1.2.2 `config/SchemaMigrationRunner.java`
- [x] 1.2.3 `config/StartupRecoveryRunner.java`
- [x] 1.2.4 `controller/AsyncTaskNotificationController.java`
- [x] 1.2.5 `controller/ConversationLogController.java`
- [x] 1.2.6 `controller/ExcelParserController.java`
- [x] 1.2.7 `controller/PptParserController.java`
- [x] 1.2.8 `controller/ToolCallLogController.java`
- [x] 1.2.9 `controller/WordParserController.java`
- [x] 1.2.10 `dto/AsyncTaskNotificationDto.java` + Excel/Word/Ppt DTO
- [x] 1.2.11 `entity/ConversationLog.java`
- [x] 1.2.12 `entity/ToolCallLog.java`
- [x] 1.2.13 `exception/ExcelParseException.java` + Ppt + Word
- [x] 1.2.14 `mapper/ConversationLogMapper.java`
- [x] 1.2.15 `mapper/ToolCallLogMapper.java`
- [x] 1.2.16 `service/ExcelParserService.java`
- [x] 1.2.17 `service/PptParserService.java`
- [x] 1.2.18 `service/WordParserService.java`
- [x] 1.2.19 `test/` 新增测试文件 + OpenSpec archives

### 1.3 frontend 新增

- [x] 1.3.1 `components/TaskNotificationBell.vue`
- [x] 1.3.2 `components/ThinkingMode.vue`
- [x] 1.3.3 `services/api.ts`
- [x] 1.3.4 `types/fileUpload.ts`
- [x] 1.3.5 `utils/chatDownload.ts`
- [x] 1.3.6 `utils/datetime.ts`
- [x] 1.3.7 `utils/docxParser.ts`
- [x] 1.3.8 `utils/fileParser.ts`
- [x] 1.3.9 `utils/fileValidator.ts`
- [x] 1.3.10 `utils/fileValidator.test.ts`
- [x] 1.3.11 `utils/gatewayDocParser.ts`
- [x] 1.3.12 `utils/gatewayExcelParser.ts`
- [x] 1.3.13 `utils/pptParser.ts`
- [x] 1.3.14 `utils/txtParser.ts`
- [x] 1.3.15 `utils/vendorLoader.ts`
- [x] 1.3.16 `utils/xlsxParser.ts`
- [x] 1.3.17 `vendor/xlsx.d.ts`
- [x] 1.3.18 `views/FileParserTest.vue`

### 1.4 根目录新增

- [x] 1.4.1 `AGENTS.md`
- [x] 1.4.2 `package-lock.json`
- [x] 1.4.3 `openspec/changes/archive/*`
- [x] 1.4.4 `openspec/specs/*`

## 2. Step 2：搬运仅一方修改的文件 ✅ 完成

### 2.1 agent-core 修改

- [x] 2.1.1 `src/controller/agent.controller.ts`
- [x] 2.1.2 `src/controller/memory.controller.ts`
- [x] 2.1.3 `src/features/avatar/service.ts`
- [x] 2.1.4 `src/features/optimize-text/optimize-text.service.ts`
- [x] 2.1.5 `src/main.ts`
- [x] 2.1.6 `src/mem/memory.service.ts`
- [x] 2.1.7 `src/skills/skill.manager.ts`
- [x] 2.1.8 `src/utils/history-sanitize.ts`
- [x] 2.1.9 `src/utils/llm-merge.ts`
- [x] 2.1.10 `src/utils/logger.service.ts`

### 2.2 skill-gateway 修改

- [x] 2.2.1 `pom.xml`
- [x] 2.2.2 `config/MybatisPlusConfig.java`
- [x] 2.2.3 `config/SecurityConfig.java`
- [x] 2.2.4 `controller/TaskController.java`
- [x] 2.2.5 `controller/UserController.java`
- [x] 2.2.6 `entity/AsyncTask.java`
- [x] 2.2.7 `entity/Skill.java`
- [x] 2.2.8 `entity/User.java`
- [x] 2.2.9 `mapper/AsyncTaskMapper.java`
- [x] 2.2.10 `service/ApiProxyService.java`
- [x] 2.2.11 `service/AsyncPollingAuditService.java`
- [x] 2.2.12 `service/AsyncTaskPollingService.java`
- [x] 2.2.13 `service/SkillService.java`
- [x] 2.2.14 `service/UserService.java`
- [x] 2.2.15 `schema-mysql.sql` ⚠️
- [x] 2.2.16 `schema.sql`
- [x] 2.2.17 `test/resources/application.properties`

### 2.3 frontend 修改

- [x] 2.3.1 `src/App.vue`
- [x] 2.3.2 `src/components/Layout.vue`
- [x] 2.3.3 `src/components/MessageInput.vue`
- [x] 2.3.4 `src/components/MessageList.vue`
- [x] 2.3.5 `src/router/index.ts`
- [x] 2.3.6 `src/views/ChatView.vue`
- [x] 2.3.7 `vite.config.ts`
- [x] 2.3.8 `package-lock.json`

### 2.4 其他

- [x] 2.4.1 `.gitignore`
- [x] 2.4.2 `deploy/nginx/fishtank.single-host.conf`
- [x] 2.4.3 `openspec/specs/api-extension-skill-llm-tool-call/spec.md`

## 3. 验证 ✅ 全部通过

- [x] 3.1 新增文件: 171 A（包含 OpenSpec archives 和项目配置）
- [x] 3.2 修改文件: 31 M
- [x] 3.3 7 个冲突文件未被覆盖（agent.ts/java-skills.ts/SkillController.java/AsyncTaskPollingScheduler.java/SkillManagementModal.vue/useChat.ts/skillEditor.ts）
- [x] 3.4 无 dist/ 文件被搬运（已用 `git checkout HEAD -- ...` 还原）
