# Capability: temp-file-filtering

> **Purpose**: 文件工具生成的临时文件在列表和查重中不可见，统一 `_temp` 命名规则。

## ADDED Requirements

### Requirement: 临时文件命名统一为 `_temp` 后缀

写文件的 tool（`word_write`, `word_replace_text`, `word_template_fill`, 所有 Excel 写操作）生成的临时文件 `original_file_name` SHALL 为 `源文件名_temp.ext` 格式。

#### Scenario: word_write 生成临时文件
- **WHEN** 对 `report.docx` 调 `word_write` 生成新文档
- **THEN** 新 `user_files` 行的 `original_file_name` 为 `report_temp.docx`
- **AND** `source_file_id` = 源文件 ID

#### Scenario: word_replace_text 生成临时文件
- **WHEN** 对 `contract.docx` 调 `word_replace_text` 替换文本
- **THEN** 新行的 `original_file_name` 为 `contract_temp.docx`
- **AND** `source_file_id` = 源文件 ID

### Requirement: Check-duplicate 排除临时文件

`GET /api/files/check-duplicate?fileName=xxx` SHALL 不匹配 `source_file_id IS NOT NULL` 的行。

#### Scenario: 查重不命中临时文件
- **WHEN** 用户已有 `report_temp.docx`（临时文件，source_file_id=5）和 `report.docx`（原文件，source_file_id=NULL）
- **AND** 用户上传 `report.docx`
- **THEN** `check-duplicate?fileName=report.docx` 返回 `exists=true`（命中原文件）
- **AND** 不会因 `_temp` 后缀命中误报

#### Scenario: 查重跳过已存在的临时文件
- **WHEN** 用户只有 `data_temp.xlsx`（临时文件，source_file_id=3）
- **AND** 用户上传 `data_temp.xlsx`
- **THEN** `check-duplicate?fileName=data_temp.xlsx` 返回 `exists=false`（临时文件被跳过）

### Requirement: 文件列表排除临时文件

`GET /api/files` 和 `file_list` 工具 SHALL 不返回 `source_file_id IS NOT NULL` 的行。

#### Scenario: 前端列表不展示临时文件
- **WHEN** 用户有 `a.docx`（原）、`a_temp.docx`（临时）、`b.xlsx`（原）
- **THEN** `GET /api/files` 返回 2 条：`a.docx` + `b.xlsx`
- **AND** `file_list` 工具返回 2 条：同上

#### Scenario: file_detail 仍可查临时文件
- **WHEN** 临时文件 ID=99
- **THEN** 通过 `file_detail(fileId=99)` 仍返回其元数据（不受过滤）
