## Context

**现状**：
- wgj 360aeb1 commit 已建好完整链路：
  - `FileToolService` 注册 20 个 handler（4 file_* + 6 word_* + 10 txt_*）
  - `FileToolSeeder` 启动时 seed 20 条 `skills` 记录（`type=EXTENSION, kind=file_tool`）
  - `SkillExecutionService` 新增 `case "file_tool"` 分发，直接调 `fileToolService.execute(userId, toolName, params)`（**不走 loopback HTTP**）
  - agent-core 沿用 `loadGatewayExtendedTools` 拉 `GET /api/skills` 链路，**自动**发现并包装为 `DynamicStructuredTool`
- 经 `mvn package` 重打 jar + 重启 gateway（已完成，pid 48305），20 条 `skills` 记录已 seed，**端到端 file_list / file_detail 实测通过**。
- 当前**全部 27 项需求**中：§2 Word 6/6 ✅、§3 Txt 10/10 ✅、§4 Md 0/11（2 个基础由 txt_* 复用，9 个扩展 0 覆盖）。

**约束**（按 AGENTS.md §5 编程约束规范）：
- §5.1 ~~不引入新第三方包~~ → **本次用户明确授权，可引入 flexmark-java**（本提案重新评估）
- §5.3 不增 SQL 文件，Java 代码做 schema 初始化
- §5.4 JDK 1.8 语法（无 `var`/Records/`List.of`/switch 表达式）
- §5.5 不改 agent-core，走 Tool 接入

**flexmark-java 选型**（4 个 GFM 需求驱动）：
- `com.vladsch.flexmark:flexmark-core:0.64.8`（CommonMark 0.28 + PEG 解析 + 完整 source position tracking）
- `com.vladsch.flexmark:flexmark-ext-tables:0.64.8`（GFM 表格 — `md_table` 需要）
- `com.vladsch.flexmark:flexmark-ext-tasklist:0.64.8`（GFM 任务列表 — `md_tasks` 需要）
- `com.vladsch.flexmark:flexmark-ext-yaml-front-matter:0.64.8`（YAML frontmatter — `md_merge` 需要）
- `com.vladsch.flexmark:flexmark-ext-gfm-strikethrough:0.64.8`（GFM 删除线 — `md_emphasis` 需要）
- **0 传递依赖**（每个 module 都是 standalone jar，引用只有 `flexmark-core`）
- **JDK 1.8 兼容**（flexmark-java 最低要求 Java 7）

## Goals / Non-Goals

**Goals**:
- 9 个 md 扩展 tool 端到端可用（注册 handler → seed skills 记录 → LLM 经 `kind=file_tool` 链路可调 → 返回结构化结果）
- 解析层用 flexmark-java AST，**9 个 tool 共享 1 个 Parser Bean**（启动时 `static final` 初始化，避免每次调用重建）
- 每个 Tool 输出的 JSON 含必要元信息（`lineNumber` / `level` / `checked` / `text` 等），便于 LLM 二次引用
- `md_filter_section` / `md_merge` 写回新文件（与 `txt_write` 一致：UUID 重命名 + 写 `user_files` 新记录，原文件不变）
- 单测覆盖核心场景（按需）

**Non-Goals**:
- 不实现 `md_read` / `md_write`（已被 `txt_read` / `txt_write` 覆盖）
- 不实现 `md_headings` / `md_toc` 的"按章节切片"职责（已被 `txt_section` 覆盖，**互补不冲突**）
- 不动 agent-core / 前端 / 数据库 schema
- 不修 wgj 任何 .java 文件
- 不修改内网 `m2-offline-bundle.tar`（AGENTS.md §2.4 同事部署流程不需要动；bundle 增量由 deploy 同事自行处理）

## Decisions

### Decision 1: 选 **flexmark-java 0.64.8**（5 个 module）

**理由**：
- 4 个 GFM 需求（tables/tasklist/yaml-front-matter/strikethrough）**全部内置 ext**
- 一次引入 5 个 module，9 个 md tool 共享 1 个 Parser Bean
- API 设计优秀：source position 完整，AST 可遍历可重建
- 比 commonmark-java 慢 30-50%，但 md 解析不频繁（< 10MB 文件解析 < 100ms），用户感知不到
- 0 传递依赖，jar 体积可控

**替代方案**：
- **commonmark-java + 多个 ext**（2-3 个 ext）：core 200KB 轻量，但 gfm-tasklist / yaml-front-matter 无官方支持，要么自写（≈ 500 行代码）要么找第三方（增加 supply chain 风险）
- **手写正则**（5.4 风格）：1-2 个 tool 够用，9 个 tool 会膨胀到 600+ 行解析代码 + 边界 case 测试 → 投入产出比低

**决定**：选 flexmark-java。

### Decision 2: 1 个 `MdToolService` 类承载 9 个 handler（参照 `TxtToolService` 风格）

**理由**：
- 5.4 `TxtToolService` 单一类承载 10 个 txt tool，模式已验证
- 9 个 md tool 共用 1 个 `flexmark Parser` 实例（避免每次 new）

**否决**：拆 9 个 service — 过度拆分。

### Decision 3: 共享 `static final Parser` 在 `MdToolService` 内部（无需 Spring Bean）

**理由**：
- flexmark Parser 是**线程安全且无状态**的（immutable DataSet）
- 9 个 handler 调用同一 Parser 实例，启动时构造一次
- 不需要 `@Configuration` 类、不需要 `@Bean`，**0 改 wgj 的 Spring 配置**

**否决**：做成 Spring Bean — 引入额外 Spring 配置改动（违背"0 改 wgj"）。

### Decision 4: `md_headings` 与 `md_toc` 共享 `parseHeadings()` 私有方法

- `md_headings` 直接返 AST 遍历结果（扁平 list）
- `md_toc` 在内存中再做一次嵌套（栈 + children 数组）
- 共用 `collectHeadings(Node document)` 私有方法

### Decision 5: `md_filter_section` / `md_merge` 写回新文件（不修改原文件）

与 `txt_write` 一致：原文件保留，输出新文件（UUID 重命名）+ 写 `user_files` 新记录。响应明确含 `originalFileId / newFileId / newFileName`。

**对写回的实现**：
- `md_filter_section`：扫描 `Heading` 节点，栈匹配 `path`，保留/删除对应子树，重渲染回 md 文本
- `md_merge`：直接用 `String.join("\n\n", sourcesContent)`（不用重新 parse） + 加 frontmatter + 加 header prefix

### Decision 6: 沿用 wgj `seedFileOperate()` 一行注册

`FileToolSeeder.run()` 末尾加 9 行：
```java
seedFileOperate("md_images", "提取 Markdown 文件所有图片引用（内联 / 引用式）");
seedFileOperate("md_headings", "提取 Markdown 文件全层级标题（ATX + Setext）");
// ... 7 more
```

### Decision 7: 错误处理 — 内部 catch → `FileToolResponse.error(msg)`

与 `TxtToolService` 一致。flexmark 解析异常时（如极端病态输入）会抛 `Exception`，catch 后返 error 给 LLM，LLM 可自主调整。

### Decision 8: 参数 schema 沿用 wgj `fileRefSchema()`

每个 md Skill 的 `schemaProperties` 包含：
- `fileRef` (string, required) — 文件名（FileRefResolver 解析为 UserFile）
- `params` (object, additionalProperties=true) — 各 Tool 自由扩展

## flexmark AST 映射规则（9 个 tool 的实现要点）

| Tool | flexmark API 关键调用 | 返 JSON 结构 |
|------|---------------------|-------------|
| `md_images` | `visitor.visit(Image.class)` + `visit(ReferenceImage.class)` | `[{lineNumber, alt, title, url, kind}]` |
| `md_headings` | `visitor.visit(Heading.class)` + `visitor.visit(SetExtHeading.class)` | `[{level, text, lineNumber}]`（扁平） |
| `md_table` | `visitor.visit(TableBlock.class)`，遍历子节点 `TableRow` + `TableCell` | `[{lineNumber, header, rows, rawMarkdown}]` |
| `md_list_items` | `visitor.visit(ListItem.class)`，识别 ordered/unordered | `[{lineNumber, marker, text, indent, ordered}]` |
| `md_tasks` | `visitor.visit(TaskListItem.class)`（来自 `flexmark-ext-tasklist`），读 `isDone` 属性 | `[{lineNumber, checked, text, indent, rawLine}]` |
| `md_emphasis` | 遍历 Inline 节点，识别 `StrongEmphasis` / `Emphasis` / `Strikethrough`（来自 `flexmark-ext-gfm-strikethrough`）/ `Code` | `[{lineNumber, style, text}]` |
| `md_toc` | 复用 `collectHeadings()` 私有方法 + 栈构造 outline tree | `{toc: [{level, text, lineNumber, children}], headingCount}` |
| `md_filter_section` | 扫描 `Heading` 节点，栈匹配 `path` + 段落切分 | 写新文件，返 `{originalFileId, newFileId, newFileName, ftpPath, lineCount}` |
| `md_merge` | 不重新 parse，直接字符串拼接 + YAML 解析（用 `flexmark-ext-yaml-front-matter` 提取的 frontmatter 节点信息） | 写新文件，返 `{newFileId, newFileName, sourceFileIds, totalLines, frontmatterKept}` |

**关键 API 模式**：
```java
Node document = MD_PARSER.parse(content);
NodeVisitor visitor = new NodeVisitor(
    new VisitHandler<>(Heading.class, node -> { ... }),
    new VisitHandler<>(TableBlock.class, node -> { ... }),
    // ... 9 个 handler
);
visitor.visitChildren(document);  // 一次性遍历所有节点
```

## Risks / Trade-offs

| 风险 | 缓解 |
|------|------|
| [R1] flexmark 0.64.8 vs 同事 mvn 仓库版本不一致 | 指定 version 0.64.8 在 `pom.xml`；同事拉新 bundle 后即可（`m2-offline-bundle.tar` 增量 ≈ 1.5-2MB） |
| [R2] flexmark 解析性能比手写正则慢 | md 解析不在热路径，< 10MB 文件 < 100ms 解析 + 序列化，**用户感知不到** |
| [R3] 引入第三方包违反 AGENTS.md §5.1 | 用户已**明确授权**"可以引入第三方仓库"；本提案在 design.md 顶部明确重新评估 |
| [R4] `md_merge` 跨文件 frontmatter 冲突语义复杂 | spec 字段 `frontmatterConflict: "first" \| "last" \| "error"`，默认 `"error"` 强制 LLM 决策 |
| [R5] `md_filter_section` 章节路径匹配歧义 | spec 字段 `matchMode: "first" \| "all"`，默认 `"first"` |
| [R6] flexmark AST 中 `ReferenceImage` 的 url 解析依赖 ref map | 用 `Document.get(Reference.class)` 查表；解析失败的 ref 跳过（不抛 error） |
| [R7] `flexmark-ext-tasklist` 旧称 `flexmark-ext-gfm-tasklist`（0.62 前），0.64 已重命名 | pom.xml 用 0.64.8 最新名称 `flexmark-ext-tasklist` |
| [R8] 与 `txt_section` 职责重叠被同事质疑 | 明确说明：`md_headings` 返扁平 list（结构抽取），`md_toc` 返嵌套 tree（章节结构），`txt_section` 返切片文本（章节内容）；三者互补不冲突 |

## Migration Plan

无（纯增量）。部署步骤：
1. **本地 mvn 安装**（首次）：
   - `mvn -s settings.xml dependency:resolve` 让 aliyun mirror 自动下 5 个 flexmark jar 到 `.m2/repository`（≈ 1.5-2MB 增量）
   - 或同事从 `~/Desktop/m2-offline-bundle.tar` 解压
2. merge 分支后 `mvn -s settings.xml package -DskipTests`（jar 内含 `MdToolService` + 5 flexmark jar + 9 行 seeder）
3. 重启 gateway：启动时 `FileToolSeeder.run()` 自动 seed 9 条 Skill 到 `skills` 表（幂等）
4. agent-core 重启（或下次对话）→ `loadGatewayExtendedTools` 拉 `GET /api/skills` → 9 个新 `type=EXTENSION` 自动包装为 `DynamicStructuredTool` → 暴露给 LLM
5. **回滚**：删 9 条 `skills` 记录（name IN md_*）+ 撤掉 `MdToolService` 9 个 handler 注册（10 行代码）+ 撤掉 5 个 flexmark 依赖

## Open Questions

- [OQ1] `md_toc` 的目录输出是否要支持"超链接"（`[标题](#anchor)`）？— 暂不实现，输出纯文本 + level 嵌套足够 LLM 使用
- [OQ2] `md_merge` 是否要支持"文件列表由 LLM 提供 fileId 数组"？— 是（必填字段 `sourceFileIds: number[]`）
- [OQ3] 是否需要支持 `.markdown` 扩展名（除 `.md`）？— 是，参照 5.4 `txt_*` 已在 `FileToolService` 处理时认 `.md` 和 `.markdown`
- [OQ4] flexmark `Parser` 用 `static final` 共享 vs Spring `@Bean`？— 选 static final（理由见 Decision 3）— 如果未来需要多 parser 配置（比如不同 markdown flavor），再重构
