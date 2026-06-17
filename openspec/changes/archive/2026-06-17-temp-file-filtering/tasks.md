## 1. 提取 `getTempFileName` 为公共方法

- [ ] 1.1 把 `ExcelFileToolService.getTempFileName()` 提到 `FtpFileService`（或新建 `FileToolHelper`），返回 `baseName_temp.ext`
- [ ] 1.2 `ExcelFileToolService` 改调公共方法（重构，行为不变）
- [ ] 1.3 `FtpFileService` 已有 `generateStorageFileName`，加 `getTempDisplayFileName` 静态方法

## 2. Word 工具 3 处临时文件名加 `_temp` 后缀

- [ ] 2.1 `word_write`：`newFile.setOriginalFileName(originalFileName)` → `getTempDisplayFileName(originalFileName)`
- [ ] 2.2 `word_replace_text`：`newFile.setOriginalFileName(userFile.getOriginalFileName())` → `getTempDisplayFileName(userFile.getOriginalFileName())`
- [ ] 2.3 `word_template_fill`：同上

## 3. Mapper 新增过滤方法

- [ ] 3.1 `UserFileMapper` 新增 `findByUserIdExcludeTemp(String userId)` — WHERE user_id = ? AND source_file_id IS NULL
- [ ] 3.2 `UserFileMapper` 新增 `findByUserIdAndOriginalFileNameExcludeTemp(String userId, String fileName)` — 同上 + original_file_name = ?

## 4. Check-duplicate 排除临时文件

- [ ] 4.1 `FileUploadController.checkDuplicate()` 改用 `findByUserIdAndOriginalFileNameExcludeTemp`

## 5. 文件列表排除临时文件

- [ ] 5.1 `FileUploadController.listFiles()` 改用 `findByUserIdExcludeTemp`
- [ ] 5.2 `FileToolService.listFiles()` 改用 `findByUserIdExcludeTemp`

## 6. 验证

- [ ] 6.1 `cd backend/skill-gateway && mvn -s ./settings.xml compile` 静默通过
- [ ] 6.2 `cd frontend && npx vue-tsc -b` 静默通过
- [ ] 6.3 `git status` 确认无 dist/.m2/node_modules 污染
- [ ] 6.4 重启 skill-gateway 验证
