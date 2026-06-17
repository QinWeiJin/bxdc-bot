# md-extension-skills Specification

## Purpose
Provides 9 Markdown extension File Tools (md_images, md_headings, md_table, md_list_items, md_tasks, md_emphasis, md_toc, md_filter_section, md_merge) powered by flexmark-java 0.64.8. Registered as kind=FILE_TOOL EXTENSION Skills via FileToolSeeder. Pure-query tools return JSON without modifying the original file; write-back tools produce new user_files records.

## Requirements

### Requirement: md 扩展工具集 (9 个 tool，flexmark-java 驱动)

The system SHALL provide 9 md-extension File Tools via `kind=file_tool` EXTENSION Skills（自动通过 `FileToolSeeder.seedFileOperate()` 注册到 `skills` 表，启动时自动 seed；agent-core 经 `loadGatewayExtendedTools` 链路发现并包装为 LLM tool 调用）。解析 SHALL 统一使用 **flexmark-java 0.64.8** 的 5 个 module（`flexmark-core` + `flexmark-ext-tables` + `flexmark-ext-tasklist` + `flexmark-ext-yaml-front-matter` + `flexmark-ext-gfm-strikethrough`），9 个 tool 共享 1 个 `static final Parser` 实例（在 `MdToolService` 类初始化时构造，线程安全）。Tool names SHALL be: `md_images`, `md_headings`, `md_table`, `md_list_items`, `md_tasks`, `md_emphasis`, `md_toc`, `md_filter_section`, `md_merge`. All tools SHALL reject non-`.md`/`.markdown` files with an error. Pure-query tools (1-7) SHALL return JSON without modifying the original file. Write-back tools (8: `md_filter_section`, 9: `md_merge`) SHALL produce a new `user_files` record and return `{originalFileId, newFileId, newFileName, ftpPath}`.

#### Scenario: 启动时 seed 9 条 md_* Skill
- **WHEN** gateway 启动且 `FileToolSeeder.run()` 执行
- **THEN** `skills` 表中 SHALL 新增 9 条 `name IN ('md_images', 'md_headings', 'md_table', 'md_list_items', 'md_tasks', 'md_emphasis', 'md_toc', 'md_filter_section', 'md_merge')` 且 `type=EXTENSION, kind=file_tool` 的记录
- **AND** 重复启动 SHALL NOT 创建重复记录（幂等）

#### Scenario: LLM 经 file_tool 链路调用
- **WHEN** LLM 调 tool `md_headings`，parameters 为 `{"fileRef":"周报.md", "params":{}}`
- **THEN** gateway SHALL 走 `case "file_tool"` dispatch → `FileToolService.execute(userId, "md_headings", params)` → `MdToolService.mdHeadings(...)`
- **AND** 响应 `output` 字段 SHALL 是 `{level, text, lineNumber}[]` 数组

#### Scenario: 非 md 文件被拒
- **WHEN** 任何 md_* tool 被调用且 userFile.fileType 不是 `md` 或 `markdown`
- **THEN** 返回 error 响应，message 含 "only .md/.markdown files are supported"

#### Scenario: 现有 txt_*/word_*/file_* 不受影响
- **WHEN** MdToolService 注册 9 个 handler 后
- **THEN** 已有的 20 个 `FileToolService` handler SHALL 保持不变（纯增量）

#### Scenario: flexmark Parser 线程安全
- **WHEN** 多个 LLM session 并发调不同的 md_* tool
- **THEN** 共享的 `static final Parser` SHALL 正确处理并发（flexmark 自身线程安全 + 我们的 9 个 handler 只读不写共享状态）

### Requirement: md_images 提取所有图片引用

The system SHALL provide a `md_images` handler that uses flexmark AST (`Image` + `ReferenceImage` nodes) and returns each image reference as `{ lineNumber, alt, title, url, kind }`. `kind` SHALL be `inline` (`![alt](url)`) or `reference` (`![alt][ref]` with `[ref]: url` resolved via `Document.get(Reference.class)`).

#### Scenario: 提取内联图片
- **WHEN** 文件含 `![logo](https://example.com/logo.png "公司 Logo")` 在第 3 行
- **THEN** 返回数组中应包含一条 `{ lineNumber: 3, alt: "logo", title: "公司 Logo", url: "https://example.com/logo.png", kind: "inline" }`

#### Scenario: 提取引用式图片（ref 已定义）
- **WHEN** 文件含 `![alt][fig1]`（line 5）且文末有 `[fig1]: img/foo.jpg "caption"`（line 20）
- **THEN** 返回数组中应包含 `{ lineNumber: 5, alt: "alt", title: "caption", url: "img/foo.jpg", kind: "reference" }`

#### Scenario: 引用式图片（ref 未定义）
- **WHEN** 文件含 `![alt][missing]` 但无对应 `[missing]: ...` 定义
- **THEN** 该条 SHALL NOT 加入返回数组（ref 解析失败，跳过）

### Requirement: md_headings 提取全层级标题

The system SHALL provide a `md_headings` handler that uses flexmark AST (`Heading` + `SetExtHeading` nodes) and returns a flat array of all headings as `{ level, text, lineNumber }`. flexmark's `Heading.getLevel()` 1-6 covers ATX + closed ATX. `SetExtHeading` (= / -) is also captured as level 1 (===) or level 2 (---).

#### Scenario: 多层级标题
- **WHEN** 文件含 `# H1` (line 1), `## H2` (line 3), `### H3` (line 5)
- **THEN** 返回数组按行号升序：`{level:1, text:"H1", lineNumber:1}, {level:2, text:"H2", lineNumber:3}, {level:3, text:"H3", lineNumber:5}`

#### Scenario: Setext 标题
- **WHEN** 文件含 `Section A\n=========`（line 1-2）
- **THEN** 返回 `{level:1, text:"Section A", lineNumber:1}`

#### Scenario: 空文件
- **WHEN** 文件不含任何 `#` 标题
- **THEN** 返回空数组

### Requirement: md_table 提取 GFM 表格

The system SHALL provide a `md_table` handler that uses `flexmark-ext-tables` AST (`TableBlock` + `TableRow` + `TableCell` nodes) and returns each table as `{ lineNumber, header, rows, rawMarkdown }`. flexmark 严格 GFM 规范，**自动处理对齐说明符**（`:---:` / `---:` 等）。

#### Scenario: 标准 2x3 表格
- **WHEN** 文件含
  ```
  | Name | Age |
  | --- | --- |
  | Alice | 30 |
  | Bob   | 25 |
  ```
  starting at line 4
- **THEN** 返回 `{ lineNumber: 4, header: ["Name","Age"], rows: [["Alice","30"],["Bob","25"]], rawMarkdown: "<原文>" }`

#### Scenario: 多个表格
- **WHEN** 文件含 2 个独立 GFM 表格
- **THEN** 返回数组长度 2

#### Scenario: 对齐说明符
- **WHEN** 文件含 `| Name | Age |\n| :--- | ---: |\n| A | 1 |`
- **THEN** 返回 header `["Name","Age"]`（flexmark 自动识别 `:---` 和 `---:`）

#### Scenario: 不是 GFM 表格（缺少分隔行）
- **WHEN** 文本形如 `| a | b |\n| c | d |` 但无 `|---|---|` 分隔行
- **THEN** flexmark 不会识别为 `TableBlock`；该文本 SHALL NOT 加入返回数组

### Requirement: md_list_items 提取所有列表项

The system SHALL provide a `md_list_items` handler that uses flexmark AST (`ListItem` + `ListBlock` parents) and returns each list item as `{ lineNumber, marker, text, indent, ordered }`. `marker` SHALL be the literal marker (`-`, `*`, `+`, `1.`, etc.); `ordered` SHALL be `true` for `ListBlock` whose `isOrdered()` returns true.

#### Scenario: 嵌套缩进
- **WHEN** 文件含 `  - level2` 前有 2 个空格
- **THEN** `indent:2, marker:"-", ordered:false, text:"level2"`

#### Scenario: 有序列表
- **WHEN** 文件含 `1. first` (line 1), `2. second` (line 2)
- **THEN** 返回 2 条，`marker:"1."` 和 `"2."`，`ordered:true`

#### Scenario: 嵌套子列表
- **WHEN** 文件含 `- parent` 含 `  - child` 子项
- **THEN** 返回 2 条（parent 和 child），child `indent:2`

### Requirement: md_tasks 提取任务清单项

The system SHALL provide a `md_tasks` handler that uses `flexmark-ext-tasklist` AST (`TaskListItem` + `TaskListItemExtension` 读取 `isDone()` attribute) and returns each task item as `{ lineNumber, checked, text, indent, rawLine }`. `checked` SHALL be `true` for `[x]` / `[X]`, `false` for `[ ]`.

#### Scenario: 混合任务状态
- **WHEN** 文件含 `- [ ] todo1` (line 1), `- [x] done1` (line 2)
- **THEN** 返回 2 条；第 1 条 `checked:false, text:"todo1"`，第 2 条 `checked:true, text:"done1"`

#### Scenario: 大写 X
- **WHEN** 文件含 `- [X] done`
- **THEN** `checked:true`

#### Scenario: 非 task list item（普通列表项）
- **WHEN** 文件含 `- regular item`（无 `[ ]` 标记）
- **THEN** 该项 SHALL NOT 加入返回数组（只在 `md_list_items` 出现）

### Requirement: md_emphasis 提取加粗/斜体片段

The system SHALL provide a `md_emphasis` handler that uses flexmark AST inline nodes (`StrongEmphasis` / `Emphasis` / `Strikethrough` from `flexmark-ext-gfm-strikethrough` / `Code`) and returns each emphasis span as `{ lineNumber, style, text }`. `style` SHALL be one of `bold` / `italic` / `strikethrough` / `code`. **围栏代码块内不解析**（flexmark 自身保证 — `FencedCodeBlock` 内的 inline 不会被遍历到 emphasis visitor）。

#### Scenario: 加粗与斜体
- **WHEN** 文件含 `**bold**` 在 line 1 和 `*italic*` 在 line 2
- **THEN** 返回 `{lineNumber:1, style:"bold", text:"bold"}` 和 `{lineNumber:2, style:"italic", text:"italic"}`

#### Scenario: 删除线
- **WHEN** 文件含 `~~removed~~`
- **THEN** 返回 `{style:"strikethrough", text:"removed"}`

#### Scenario: 行内代码
- **WHEN** 文件含 `` `code` ``
- **THEN** 返回 `{style:"code", text:"code"}`

#### Scenario: 围栏代码块内不解析
- **WHEN** 文件含 ```` ``` ```` 围栏代码块，块内含 `**fake**`
- **THEN** 不应返回该 fake 加粗（flexmark 隔离 FencedCodeBlock 内的 inline 解析）

### Requirement: md_toc 生成文档目录

The system SHALL provide a `md_toc` handler that reuses the `collectHeadings()` private method (shared with `md_headings`) and returns a nested outline tree. Each tree node SHALL be `{ level, text, lineNumber, children }`. Skipped levels (e.g., H1 → H3) MUST NOT create empty intermediate nodes (A's children directly contains A.1).

#### Scenario: 嵌套 H1/H2/H3
- **WHEN** 文件含 `# A` (1), `## A.1` (3), `### A.1.1` (5), `# B` (10)
- **THEN** 返回根节点数组长度 2（A 和 B），A 的 children 含 A.1，A.1 的 children 含 A.1.1

#### Scenario: 跳级（H1 → H3）
- **WHEN** 文件含 `# A` 然后 `### A.1`（跳过 H2）
- **THEN** A 的 children 直接包含 A.1（不补 H2 节点）

#### Scenario: 空文件
- **WHEN** 文件不含任何 `#` 标题
- **THEN** 返回 `{ toc: [], headingCount: 0 }`

### Requirement: md_filter_section 按章节路径保留/删除

The system SHALL provide a `md_filter_section` handler that scans `Heading` nodes, stack-matches `path` (ordered, from outermost inward), and keeps/removes the matched subtree. The tool SHALL accept `path` (string[], required) + `mode` (`"keep"` | `"remove"`, default `"keep"`) + `matchMode` (`"first"` | `"all"`, default `"first"`). The filtered content SHALL be written as a NEW `user_files` record (original unchanged). Response SHALL include `originalFileId, newFileId, newFileName, ftpPath, lineCount`. YAML front matter (from `flexmark-ext-yaml-front-matter`) at the top of the file SHALL be preserved in keep mode.

#### Scenario: keep 模式
- **WHEN** 文件含 `# A` / `## A.1` / `# B` / `## B.1`，`params={path:["A"], mode:"keep"}`
- **THEN** 新文件只含 `# A` 及其下全部内容（不含 `# B` 段落）

#### Scenario: remove 模式
- **WHEN** 文件含 `# A` / `## A.1` / `# B`，`params={path:["A"], mode:"remove"}`
- **THEN** 新文件只含 `# B` 及其下内容

#### Scenario: 嵌套路径
- **WHEN** `params={path:["A","A.1"], mode:"keep"}`
- **THEN** 新文件只保留 A.1 子章节

#### Scenario: 路径不存在
- **WHEN** `params={path:["X"]}` 但文件无 X 标题
- **THEN** 返回 error 响应，message 含 "section not found: X"

#### Scenario: 含 YAML frontmatter
- **WHEN** 文件首部有 `---\ntitle: x\n---` frontmatter，`params={path:["A"], mode:"keep"}`
- **THEN** 新文件保留 frontmatter + `# A` 章节内容

#### Scenario: 原文件未被修改
- **WHEN** 任何 mode 都执行完成
- **THEN** 原 userFile 记录不变；返回结果含 `originalFileId, newFileId, newFileName`

### Requirement: md_merge 多 Markdown 文件拼接合并

The system SHALL provide a `md_merge` handler that concatenates multiple user files into a single new file. `params.sourceFileIds` SHALL be a non-empty array of `userFile.id` values (length ≥ 2). `frontmatterConflict` SHALL be `"first"` (keep first file's frontmatter) | `"last"` (keep last) | `"error"` (return error if multiple frontmatters present), default `"error"`. `prefixHeaders` SHALL be boolean (default `true`). **实现策略**：不重新 parse（性能考虑），直接 `String.join("\n\n", sourcesContent)` + 加 frontmatter + 加 header prefix。YAML frontmatter 解析复用 `flexmark-ext-yaml-front-matter` AST（从每个源 parse 一次仅取 frontmatter 节点，< 1ms）。The result SHALL be written as a new `user_files` record.

#### Scenario: 简单拼接
- **WHEN** `params={sourceFileIds:[1, 2]}`，文件 1 含 "Hello"、文件 2 含 "World"
- **THEN** 新文件内容为：
  ```
  # file1.md
  Hello

  # file2.md
  World
  ```

#### Scenario: frontmatter 冲突 error
- **WHEN** 两个源文件都有 YAML frontmatter（`---` 包裹），且 `frontmatterConflict="error"`
- **THEN** 返回 error 响应，message 含 "frontmatter conflict"

#### Scenario: frontmatter 冲突 first
- **WHEN** 同上但 `frontmatterConflict="first"`
- **THEN** 新文件 frontmatter 取第一个源文件的（其他文件的 frontmatter 转为 `<!-- merged from filename.md -->` 注释保留）

#### Scenario: 至少 2 个源文件
- **WHEN** `sourceFileIds` 长度 < 2
- **THEN** 返回 error，message 含 "sourceFileIds must contain at least 2 file ids"

#### Scenario: 跨用户文件访问拒绝
- **WHEN** `sourceFileIds` 中任一文件 `user_id` 与当前 `userId` 不一致
- **THEN** 返回 error，message 含 "access denied for file id X"

#### Scenario: 全部源文件必须为 md/markdown
- **WHEN** 任何源文件扩展名不是 `md` 或 `markdown`
- **THEN** 返回 error，message 含 "all source files must be .md/.markdown"
