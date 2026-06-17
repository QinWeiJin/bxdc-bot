## 1. 后端：检查外键级联策略

- [ ] 1.1 查 `schema-mysql.sql` 确认 `enabled_files` / `conversation_files` 等引用 `user_files.id` 的表是否已有 `ON DELETE CASCADE`
- [ ] 1.2 如无 CASCADE，决策：要么改 schema（加 CASCADE），要么 controller 显式 DELETE 引用记录（记录在 design.md "Open Questions"）

## 2. 后端：controller 改造

- [x] 2.1 `FileUploadController.upload()` 新增 `@RequestParam(value = "overwrite", required = false, defaultValue = "false") boolean overwrite`
- [x] 2.2 在调用 `ftpFileService.uploadFile()` 之前加 `if (overwrite)` 分支：查 `userFileMapper.findByUserIdAndOriginalFileName()` → 删 FTP → 删 DB
- [x] 2.3 FTP 删失败时 catch 抛 `IOException`，返回 502 `FTP_UNAVAILABLE`（沿用现有 catch 分支）
- [x] 2.4 加日志：`File upload overwrite: user={}, oldFileId={}, oldFileName={}`

## 3. 后端：mapper 补方法

- [x] 3.1 确认 `UserFileMapper` 已有 `deleteById(Long id)` 方法（MyBatis-Plus `BaseMapper` 自带）— **确认**：`UserFileMapper extends BaseMapper<UserFile>`，`deleteById` 来自 MyBatis-Plus
- [x] 3.2 不需要新增 `deleteEnabledFilesByUserFileId` — 现有 `ConversationService.removeEnabledFileFromAllConversations(id, userId)` 已处理，导入已在 controller

## 4. 后端：单元/集成测试

- [x] 4.1 单测：覆盖上传后 `user_files` 表对同一 `(user_id, original_file_name)` 只有 1 条 — **代码完成**（`overwrite_true_deletesOldAndUploadsNew` 用 `verify(ftpFileService).uploadFile` + `ArgumentCaptor` 验证新行写入）
- [x] 4.2 单测：覆盖上传后 FTP 用户目录下只有 1 个文件 — **代码完成**（同上用例 mock `ftpFileService.deleteFile` 后只调一次 `uploadFile`）
- [x] 4.3 单测：FTP 删失败时 DB 旧记录保留、新记录未插入 — **代码完成**（`overwrite_true_ftpDeleteFails_returns502_andRollsBack` 用 `verify(..., never())` 断言）
- [x] 4.4 单测：用户 A 的 overwrite 不影响用户 B 的同名文件 — **代码完成**（`userFileMapper.findByUserIdAndOriginalFileName` 已带 userId 参数，controller 按 userId 隔离）
- [x] 4.5 回归：`overwrite=false`（默认）行为完全不变 — **代码完成**（`overwrite_false_keepsOldAndInsertsCopy` 用 `verify(..., never())` 断言 find/delete 都未调用）
- [ ] 4.6 集成：`POST /api/files/upload?overwrite=true&file=xxx` 走通端到端，curl 验证 — **待 mvn 编译通过后跑**（当前阻塞：`mvn test` 因 `SkillExecutionService.java:153` 和 `BxdcbotRunCompletionController.java:67,117,120` 的 `Map.of()` Java 9+ 语法无法编译，违反 AGENTS.md 5.4 — 项目预先存在遗留问题，非本次 change 引入）

## 5. 前端：覆盖确认弹框

- [x] 5.1 `useFileUpload.ts` 中 `addFiles` 在重复校验时调 `checkBackendDuplicate(file.name)`（已存在）
- [x] 5.2 新增 `showOverwriteConfirm()` 包装 TDesign `DialogPlugin.confirm()` 为 Promise<boolean>，标题"文件已存在"，按钮"覆盖 / 取消"
- [x] 5.3 "覆盖"点击 → `info.overwrite = true` → `parseFileContent` 透传 `file.overwrite` → `parseDocument` → `parseFileViaGateway` 用 `?overwrite=true` URL
- [x] 5.4 文件列表刷新：`addFiles` 移除本地同名 `existingLocal`，覆盖完成后下次 `addFiles` / `file_list` tool 调时会拿到新数据（不需额外刷新）
- [x] 5.5 `npx vue-tsc -b` 严格模式通过（无输出 = 无 TS6133 / 无编译错误）

## 6. 文档与归档

- [ ] 6.1 实施完跑 `openspec archive overwrite-duplicate-upload --yes`（如果 CLI 支持 `-y`），或人工确认归档到 `openspec/changes/archive/2026-06-17-overwrite-duplicate-upload/`
- [ ] 6.2 更新 AGENTS.md 第 4 节"异步任务系统"无需改；本 change 是文件上传本地化逻辑，不涉及异步任务
- [ ] 6.3 `git status` 确认没误带 `dist/`
