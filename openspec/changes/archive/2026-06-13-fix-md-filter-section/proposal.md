## Why

`md_filter_section`（按标题过滤 Markdown 章节）当前实现存在 3 个缺陷导致 LLM 调用后无法正确使用：DB-FTP 文件名双写不匹配、同文件名多版本时解析到旧文件、标题匹配后节边界计算有瑕疵。需要基于 Java + Flexmark AST 重新设计三段式方案（解析 → 定位 → 接口封装），确保 LLM 工具调用可靠。

## What Changes

- **修复** `writeBackNewFile` 的 DB-FTP 文件名不匹配：改为从 `FtpFileService.uploadFile` 返回值提取实际存储名写入 DB
- **修复** `fileRef` 按原始文件名解析时取到非预期版本的问题：不支持按 post-filter 后的文件二次查询
- **修复** `md_filter_section` remove 模式下节边界计算：标题行自身及段落间 gap 的处理
- **新增** `md_filter_section` 的单元测试覆盖 remove/keep 双向场景（含 frontmatter 保留、嵌套标题）
- **对齐** 测试与生产代码的 API 契约：测试当前使用 `path`+`mode`（旧），生产使用 `keep`/`remove`（新），需统一到 schema 定义
- **清理** 新增死代码 `generateNewStorageName` 在 `MdToolService` 中（已被 `FtpFileService.generateStorageFileName` 替代）

## Capabilities

### New Capabilities
- `md-section-filter`: 基于 Flexmark AST 的 Markdown 章节精确过滤能力（keep/remove 双模式，标题精确匹配，节边界正确计算，frontmatter 保留）

### Modified Capabilities
- `file-management`: `FileRefResolver.findByUserIdAndOriginalFileName` 按 uploadTime DESC 取最新文件的语义需明确文档化（当前无 spec 约束）

## Impact

- **Gateway Java 代码**: `MdToolService.java`（`writeBackNewFile` / `mdFilterSection` 方法修改）
- **测试**: `MdToolServiceTest.java`（4 个 `mdFilterSection` 测试用例需对齐 API）
- **无依赖变更**: flexmark-java 0.62.2 已引入，无需新增
- **无 DB schema 变更**
- **无 agent-core / 前端变更**
