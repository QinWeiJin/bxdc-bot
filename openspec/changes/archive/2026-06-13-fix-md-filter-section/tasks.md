## 1. 修复 writeBackNewFile DB-FTP 文件名不一致

- [x] 1.1 移除 `MdToolService.writeBackNewFile` 中的 `generateNewStorageName()` 调用，改为传入原始文件名给 `FtpFileService.uploadFile`
- [x] 1.2 从 `FtpFileService.uploadFile` 返回值提取实际存储名（`fullPath.substring(fullPath.lastIndexOf('/') + 1)`）写入 `newFile.setFileName()`
- [x] 1.3 删除 `MdToolService.generateNewStorageName()` 死代码方法
- [x] 1.4 编译验证: `cd backend/skill-gateway && mvn -s ./settings.xml compile`

## 2. 验证 remove 模式节边界计算

- [x] 2.1 检查 `mdFilterSection` remove 模式下 `titleLine = range[0] - 1` 逻辑是否在 frontmatter 存在时正确处理
- [x] 2.2 用测试文件（`/Users/zhangzhuang/ai/gitbbxdc-bot/bxdc-bot/.dev-ftp-test/ftp-root/files/811003/5afa512a.md`）手工调用 API 验证 remove 模式：
  - `curl -X POST http://127.0.0.1:18080/api/files/tools/execute -H "X-User-Id: 811003" -d '{"toolName":"md_filter_section","fileRef":"46","params":{"remove":["知识体系"]}}'`
  - 验证输出文件行数正确（6 行，移除 `### 知识体系` 整节）
- [x] 2.3 验证 keep 模式同样场景：
  - `curl -X POST http://127.0.0.1:18080/api/files/tools/execute -H "X-User-Id: 811003" -d '{"toolName":"md_filter_section","fileRef":"46","params":{"keep":["知识体系"]}}'`
  - 验证输出文件只含 `### 知识体系` 节

## 3. 修复单元测试: API 对齐

- [x] 3.1 将 `mdFilterSection_keepMode_shouldKeepSectionWithFrontmatter` 测试的 params 从 `{path, mode}` 改为 `{keep}` (或 `{remove}`)
- [x] 3.2 将 `mdFilterSection_missingPath_shouldReturnError` 改为 `mdFilterSection_missingHeading_shouldReturnError`，用 `remove: ["NoSuchSection"]` 验证
- [x] 3.3 将 `mdFilterSection_emptyPath_shouldReturnError` 改为 `mdFilterSection_emptyKeepAndRemove_shouldReturnError`，传空 params
- [x] 3.4 将 `mdFilterSection_removeMode_shouldRemoveSection` 的 params 从 `{path, mode}` 改为 `{remove}`
- [x] 3.5 运行所有测试: `cd backend/skill-gateway && mvn -s ./settings.xml test -Dtest=MdToolServiceTest`

## 4. End-to-end 验证

- [x] 4.1 确认 `FileRefResolver` 按文件名 `刑法.md` 能正确解析（需先重置 FTP 文件为原始内容）
- [x] 4.2 确认结果文件 `newFileName` 与 `ftpPath` 尾段一致（DB 名 = FTP 名）
- [x] 4.3 确认结果文件可通过 `GET /api/files/download/{newFileId}` 下载且内容正确
