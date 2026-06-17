## Context

`md_filter_section` 是 9 个 md-extension File Tool 之一（module4-md-extension-skills），用于按标题删除或保留 Markdown 章节。当前实现基于 flexmark-java 0.62.2 AST 解析，通过 `FileToolController → FileToolService → MdToolService` 链路暴露为 LLM 可调用工具。

**当前问题**：
1. `writeBackNewFile` 中 `generateNewStorageName()` 自生成文件名（如 `7173432c.md`），但 `FtpFileService.uploadFile` 内部也调用 `generateStorageFileName()` 再生成一次（如 `adb326f1.md`），导致 DB 记录的文件名与实际 FTP 文件不一致
2. `FileRefResolver.findByUserIdAndOriginalFileName` 按 `uploadTime DESC` 取最新文件，一次过滤后再次调用会解析到已过滤的短文件
3. remove 模式下标题行删除的边界计算使用 `startLineNumber + 1 - 1 = startLineNumber`，与 `range[0]` 语义有耦合

## Goals / Non-Goals

**Goals:**
- 修复 `writeBackNewFile` 的 DB-FTP 文件名不一致问题：改为从 `FtpFileService.uploadFile` 返回值中提取实际存储名
- 修复 remove 模式下节边界计算，确保标题行自身及其下属全部内容被移除
- 对齐 4 个单元测试与当前 `keep`/`remove` API（测试使用旧 `path`/`mode` API）
- 验证含 frontmatter 的文件两端场景

**Non-Goals:**
- 不引入新的第三方依赖（flexmark-java 已就位）
- 不修改 `FileToolController` / `FileToolService` / `FileRefResolver` 的公共 API
- 不改变 LLM tool schema（`keep`/`remove` 参数名不变）
- 不修改 agent-core 或前端
- 不在本次 change 中引入"按层级路径匹配"（`path: ["A", "A.1"]`）——那是 module4 spec 定义但从未实现的原始设计，属于 feature addition 而非 bug fix

## Decisions

### Decision 1: 文件名统一由 FtpFileService 生成
- **选择**: `writeBackNewFile` 传入原始文件名（如 `刑法.md`），由 `FtpFileService.uploadFile` 统一生成 UUID 存储名，再从返回的完整路径中提取实际文件名写入 DB
- **替代方案**: 让 `FtpFileService.uploadFile` 接收"已预生成的文件名"参数，停止内部二次生成 → 排斥原因：修改 `FtpFileService` 公共签名影响其他 3 个调用方（`TxtToolService`、`WordToolService`），风险更大
- **理由**: 最小改动面，遵守 FtpFileService 的单点命名策略

### Decision 2: 保留现有 keep/remove 平面标题匹配 API
- **选择**: 维持当前 `keep`/`remove` 字符串数组 API（与 `FileToolSeeder` schema 一致），不做层次化路径匹配
- **替代方案**: 改为 module4 spec 定义的 `path`（层次化数组）+ `mode` → 排斥原因：这属于 feature redesign，LLM tool schema 已固化，改动会破坏现有 LLM 调用契约
- **理由**: 平面标题匹配对 LLM 更友好（Agent 只需知道标题文本），且 `FileToolSeeder.mdFilterSectionSchema()` 已定义 `keep`/`remove` 的描述给 LLM

### Decision 3: 节边界使用 flexmark AST 行号（0-based）
- **选择**: 继承当前实现，使用 `Heading.getStartLineNumber()` + `Heading.getLevel()` 计算节范围
- **替代方案**: 手写正则按 `^#{1,6}\s` 行扫描 → 排斥原因：无法处理代码块内的 `#` 行、ATX 闭合标题（`### 标题 ###`）、Setext 标题等边界情况
- **理由**: flexmark 已处理所有 GFM 标题变体，AST 行号准确可靠

## Risks / Trade-offs

- **[Risk] 同名多版本文件解析歧义**: `FileRefResolver` 按 `uploadTime DESC` 取最新文件，多次过滤后 fileRef=`刑法.md` 指向已过滤的短文件 → Mitigation: 这是设计行为（`file_list` 会列出所有版本），如需精确指定用文件 ID（`fileRef=46`）
- **[Risk] 标题文本含 flexmark 内联格式时不匹配**: 如 `### **加粗标题**` 提取文本为 `加粗标题`，可能与用户传入的 `**加粗标题**` 不一致 → Mitigation: `extractTextContent` 已剥离 AST 格式，用户应传入纯文本标题

## Migration Plan

无需迁移。修复仅限于 `MdToolService.java` 的 `writeBackNewFile` 和 `mdFilterSection` 方法，以及 `MdToolServiceTest.java` 的 4 个测试用例。无 DB schema 变更，无配置变更。

## Open Questions

- 是否需要支持模糊标题匹配（trim 首尾空格 + 忽略大小写）？当前使用精确 `equals`，但 spec 描述是"匹配"
- `FileRefResolver` 的"同名取最新"行为是否需要 spec 文档化？当前无约束
