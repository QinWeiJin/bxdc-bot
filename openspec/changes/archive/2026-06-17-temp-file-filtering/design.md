## Context

`user_files` 表已有 `source_file_id` 字段（`BIGINT NULL`）：
- 原文件 = `source_file_id IS NULL`
- 临时文件 = `source_file_id = <原文件 ID>`

Excel 工具已用 `getTempFileName()` 生成 `baseName_temp.ext` 格式的 `originalFileName`。Word 工具的 3 个写操作（`word_write`, `word_replace_text`, `word_template_fill`）未加 `_temp` 后缀，`originalFileName` 使用源文件名或用户输入。

现有的 `findByUserId` / `findByUserIdAndOriginalFileName` 无过滤，返回所有行（含临时文件）。

## Goals / Non-Goals

**Goals:**
- 统一 Word 工具临时文件命名：`originalFileName = 源文件名_temp.ext`
- Check-duplicate 不命中临时文件（`source_file_id IS NOT NULL` 的行忽略）
- 文件列表（`GET /api/files` + `file_list` 工具）不展示临时文件
- 用已有 `source_file_id` 字段区分，不加新列

**Non-Goals:**
- 不改 `excel_init_temp`（已正确使用 `_temp` 命名）
- 不改 FTP 存储逻辑
- 不改前端 UI

## Decisions

### Decision 1: 用 `source_file_id IS NULL` 区分，不加新列

`user_files` 表已有 `source_file_id`。原文件 `source_file_id = NULL`，临时文件 `source_file_id != NULL`。语义上等价于 `is_original` 布尔字段。

**理由**：无 schema 变更，无数据迁移，零部署风险。

**Alternatives considered:**
- 加 `is_temp TINYINT(1)` 列：冗余，需要 ALTER TABLE + 回填旧数据
- 靠 `originalFileName LIKE '%_temp%'` 匹配：脆弱（用户可能真上传 `xxx_temp.docx`），且不准确（Word 工具当前未加后缀）

### Decision 2: Word 工具 `setOriginalFileName` 加 `_temp` 后缀

3 处修改：
- `word_write`：`newFile.setOriginalFileName(originalFileName)` → `newFile.setOriginalFileName(getTempFileName(originalFileName))`
- `word_replace_text`：`newFile.setOriginalFileName(userFile.getOriginalFileName())` → `newFile.setOriginalFileName(getTempFileName(userFile.getOriginalFileName()))`
- `word_template_fill`：同上

`getTempFileName` 方法从 `ExcelFileToolService` 提取到公共工具类（`FtpFileService` 或新建 `FileToolHelper`），供 Excel 和 Word 共用。

**Alternatives considered:**
- 复制 `getTempFileName` 到 WordToolService：DRY 违反，一致性问题
- 用 `sourceFileId != null ? originalName + "_temp" : originalName` 三元：同样要复用

### Decision 3: Mapper 新增过滤方法，不动旧方法

新增 `findByUserIdExcludeTemp` 和 `findByUserIdAndOriginalFileNameExcludeTemp`，旧 `findByUserId` / `findByUserIdAndOriginalFileName` 保留不动。

**理由**：`FileRefResolver` 等解析器可能需要查临时文件（通过 fileId），保留旧方法给内部使用。列表和查重走新方法。

## Risks / Trade-offs

- **[Risk] Word 工具已有临时文件（改名后不回溯）** — 旧数据中 Word 临时文件的 `originalFileName` 不带 `_temp` 后缀，但它们已有 `source_file_id`，列表和查重会正确过滤。只是显示名没有 `_temp` 后缀（historical）— 可接受。
- **[Risk] 用户上传 `xxx_temp.docx` 作为原文件** — 因为用 `source_file_id` 区分而不是文件名，不会有假阳性。列表/查重只看 `source_file_id IS NULL`。
- **[Trade-off] `file_detail` 工具仍可查临时文件** — 不变。通过 ID 查元数据不受过滤影响。

## Open Questions

- 无 — 设计已定
