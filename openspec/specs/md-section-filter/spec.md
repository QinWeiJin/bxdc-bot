# md-section-filter Specification

## Purpose
Provides md_filter_section File Tool that uses flexmark-java AST to filter Markdown document sections by exact heading text match (keep or remove mode). Supports YAML frontmatter preservation. Writes filtered content as a NEW user_files record without modifying the original file.

## Requirements

### Requirement: md_filter_section 按标题删除/保留章节

The system SHALL provide a `md_filter_section` handler that uses flexmark-java AST to locate `Heading` nodes by exact text match, then filters the document content by keeping or removing matched sections. The tool SHALL accept `keep` (string[], optional) or `remove` (string[], optional) — exactly one must be non-empty. `fileRef` SHALL be a required parameter identifying the source file (original filename or numeric ID). The filtered content SHALL be written as a NEW `user_files` record (the original file is never modified). The response SHALL include `originalFileId, newFileId, newFileName, ftpPath, lineCount`. YAML frontmatter at the top of the file SHALL be preserved in both keep and remove modes. A section is defined as: from the matched heading line through all content until the next heading of the same or higher level (or end of file).

#### Scenario: remove 模式删除单个三级标题章节
- **WHEN** 文件含 `#  刑法`, `## 刑法的解释`, `### 知识体系` 及其内容，`params={fileRef:"刑法.md", remove:["知识体系"]}`
- **THEN** 新文件保留 `#  刑法`, `[TOC]`, `## 刑法的解释`，移除 `### 知识体系` 及其下所有内容

#### Scenario: keep 模式保留指定章节
- **WHEN** 文件含 `# A` / `# B` 两章，`params={fileRef:"doc.md", keep:["B"]}`
- **THEN** 新文件只含 `# B` 及其下内容（不含 `# A`）

#### Scenario: 标题不存在返回错误
- **WHEN** `params={fileRef:"doc.md", remove:["不存在章节"]}` 但文件中无此标题
- **THEN** 返回 error 响应，message 含 "Heading not found: \"不存在章节\""

#### Scenario: keep 和 remove 都为空返回错误
- **WHEN** `params={fileRef:"doc.md"}` 既无 `keep` 也无 `remove`
- **THEN** 返回 error 响应，message 含 "Either 'keep' or 'remove' is required"

#### Scenario: 含 YAML frontmatter 时保留
- **WHEN** 文件首部有 `---\ntitle: x\n---` frontmatter，`params={fileRef:"doc.md", remove:["某章节"]}`
- **THEN** 新文件保留 frontmatter，仅移除指定章节

#### Scenario: 同层级多个匹配标题全部处理
- **WHEN** 文件含 `## A` / `## B` / `## C`，`params={fileRef:"doc.md", remove:["A","C"]}`
- **THEN** 新文件只含 `## B` 章节

#### Scenario: 新文件 DB 记录与 FTP 文件一致
- **WHEN** 过滤操作完成后生成新文件
- **THEN** `user_files` 表中 `file_name` 字段值 SHALL 等于 FTP 磁盘上实际存储的文件名
