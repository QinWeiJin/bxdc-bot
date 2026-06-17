## Why

文件工具（Excel/Word）操作的临时文件与用户上传的原文件混在一起：check-duplicate 校验会命中临时文件的 `_temp` 后缀名，文件列表也展示了工具中间产物，干扰用户操作和判断。

当前 `user_files` 表已有 `source_file_id` 字段（临时文件指向源文件 ID，原文件为 NULL），Excel 工具已用 `_temp` 后缀命名，但 Word 工具命名不一致，且列表/查重都没过滤。

## What Changes

- **Word 工具**（`word_write`, `word_replace_text`, `word_template_fill`）的 `originalFileName` 统一加 `_temp` 后缀（与 Excel 工具一致）
- **Check-duplicate**（`GET /api/files/check-duplicate`）查询条件加 `source_file_id IS NULL`，忽略临时文件
- **文件列表**：
  - `GET /api/files`（前端配置面板）加 `source_file_id IS NULL` 过滤
  - `file_list` 工具（LLM 工具调用）加 `source_file_id IS NULL` 过滤
- **新增 mapper 方法**：`findByUserIdExcludeTemp`（替代无过滤查询）

**无 BREAKING 变更**。原文件行为完全不变，仅隐藏临时文件 + 统一命名。

## Capabilities

### New Capabilities
- `temp-file-filtering`：临时文件的标识、命名、过滤规则

### Modified Capabilities
- `file-upload-dedup`：check-duplicate 加 temp 过滤
- `file-management`：listFiles / file_list 加 temp 过滤

## Impact

- **后端**：
  - `UserFileMapper` — 新增 `findByUserIdExcludeTemp` / `findByUserIdAndOriginalFileNameExcludeTemp`
  - `FileUploadController.checkDuplicate()` — 改用排除 temp 的查询
  - `FileUploadController.listFiles()` — 改用排除 temp 的查询
  - `FileToolService.listFiles()` — 改用排除 temp 的查询
  - `WordToolService` — 3 处 `setOriginalFileName` 加 `_temp` 后缀
- **前端**：无需改动（列表/查重接口返回数据自动干净）
- **测试**：3 个新单测（check-duplicate 跳过 temp / listFiles 跳过 temp / word_write 生成 `_temp` 命名）
