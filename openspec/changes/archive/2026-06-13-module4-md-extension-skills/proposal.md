## Why

`需求方案A1.md` 模块四共列出 27 个 Tool（§2 Word 6 + §3 Txt 10 + §4 Md 11）。**前两节 16 个**已被 wgj 5.4 / 5.3 commit 全部实现并通过 `skills` 表对外暴露（`type=EXTENSION, kind=file_tool`）。**§4 11 个 md Tool 中 2 个基础（read/write）由 `txt_read` / `txt_write` 复用**（5.4 已支持 .md 文件）。**剩 9 个 md 扩展 tool 完全未实现**：

| 需求 §4 项 | 对应 md_* Tool | 现状 |
|------|------|------|
| 1. 提取所有图片引用 | `md_images` | ❌ 缺 |
| 2. 提取全层级标题 | `md_headings` | ❌ 缺（`txt_section` 是切片，非结构 list） |
| 3. 提取所有表格 | `md_table` | ❌ 缺（需要 GFM 表格解析） |
| 4. 提取所有列表项 | `md_list_items` | ❌ 缺 |
| 5. 提取任务清单项 | `md_tasks` | ❌ 缺（需要 GFM 任务列表） |
| 6. 提取加粗/斜体内容 | `md_emphasis` | ❌ 缺（含 `~~strikethrough~~`） |
| 7. 生成文档目录 | `md_toc` | ❌ 缺（嵌套 outline，txt_section 是切片） |
| 8. 删除/保留指定章节 | `md_filter_section` | ❌ 缺 |
| 9. 多 Md 文件拼接合并 | `md_merge` | ❌ 缺（需 YAML frontmatter 解析） |

**9 个 tool 中 4 个（`md_table` / `md_tasks` / `md_emphasis` 的删除线 / `md_merge` 的 frontmatter）需要 GFM 扩展支持**——这超出 JDK 1.8 自带正则 + String 解析的合理边界。用户已明确授权"可以引入第三方仓库"，本提案选择业内最成熟的 Markdown 解析库 **flexmark-java**：

| 候选 | GFM 表格 | 任务列表 | YAML frontmatter | 删除线 | JDK 1.8 兼容 |
|------|---------|---------|------------------|--------|------------|
| **flexmark-java** (选) | ✅ 内置 ext | ✅ 内置 ext | ✅ 内置 ext | ✅ 内置 ext | ✅ |
| commonmark-java + 3 ext | ✅ 需 gfm-tables | ❌ 无官方 ext | ❌ 无官方 ext | ✅ 需 gfm-strikethrough | ✅ |
| 手写正则（5.4 风格）| 部分（标准 pipe table）| 部分（`- [x]`）| 需手写 YAML 解析 | 部分（`~~` 简单匹配） | ✅ |

**flexmark-java 一次性满足 4 个 GFM 需求**，且 core + 4 个 ext 全部 0 传递依赖（jar 总 +1.5-2MB，对当前 67MB jar 影响 < 3%），是 ROI 最高的选择。

**本次 change 范围**：仅补这 9 个 md 扩展 tool，最大化复用 wgj 360aeb1 已建好的 `kind=file_tool` 链路（`POST /api/skills/execute` → `executeFileToolSkill` → `FileToolService.execute` → 9 个 md handler），**不修改 wgj 任何 Service / Controller / entity / schema**。

## What Changes

- **新增** 1 个文件 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/tools/MdToolService.java`（参照 `TxtToolService.java` 风格），注册 9 个 handler 到 `FileToolService.handlers` map；解析用 flexmark-java AST 遍历，**不再手写正则**
- **修改** 1 个文件 `backend/skill-gateway/pom.xml`，加 5 个 flexmark 依赖（`flexmark-core` + `flexmark-ext-tables` + `flexmark-ext-tasklist`（旧称 `flexmark-ext-gfm-tasklist`）+ `flexmark-ext-yaml-front-matter` + `flexmark-ext-gfm-strikethrough`）
- **修改** 1 个文件 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/config/FileToolSeeder.java`，加 9 行 `seedFileOperate("md_xxx", "...")`（沿用 wgj 5.4 已用模式，启动时自动 seed 9 条 `skills` 记录）
- **不新增** `md_read` / `md_write` — 复用 `txt_read` / `txt_write`（5.4 已支持 .md）
- **不重复** `txt_section` 已被覆盖的"按标题切章节" — `md_headings` 专注结构化 list，`md_toc` 专注嵌套 outline tree，与 `txt_section` 切片职责互补
- **不修改** wgj 任何 Service / Controller / entity / schema（沿用 `kind=file_tool` 链路）
- **不动** agent-core / 前端 / 数据库 schema / SQL 文件

## Capabilities

### New Capabilities

- `md-tools`: 9 个 md 扩展 File Tool 的端到端能力 — 9 个 handler 的入参/输出契约、错误处理、与 `FileToolService` 调度入口的集成、Tool 在 `skills` 表的自动 seed、LLM 经 `kind=file_tool` 链路的调用、flexmark-java AST 节点树到 JSON 输出的映射规则。

### Modified Capabilities

无。复用 `extended-skill-structured-tools` 既有的 EXTENSION Skill 注册机制 + wgj 360aeb1 `kind=file_tool` dispatch 链路。

## Impact

- **新增文件**（1 个）：`backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/tools/MdToolService.java`（约 400-500 行，含 flexmark parser 复用 + 9 个 handler + 1 个共享 `MarkdownParser` Bean）
- **修改文件**（2 个）：
  - `backend/skill-gateway/pom.xml`（+5 个 dependency）
  - `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/config/FileToolSeeder.java`（+9 行）
- **第三方包影响**：
  - 引入 5 个 flexmark 模块：`flexmark-core` + `flexmark-ext-tables` + `flexmark-ext-tasklist` + `flexmark-ext-yaml-front-matter` + `flexmark-ext-gfm-strikethrough`
  - 全部 `com.vladsch.flexmark` groupId，version 0.64.8（最新稳定版，2024-06）
  - **0 传递依赖**（flexmark core/ext 都是 standalone jar）
  - 全部兼容 JDK 1.8（flexmark-java 最低要求 Java 7）
  - jar 体积净增 +1.5-2MB（gate 当前 67MB，+3%）
- **内网 mvn 部署影响**：`m2-offline-bundle.tar` 需要重新打包（增量约 1.5-2MB），同事拉新 bundle 后即可离线 mvn
- **不修改** wgj 任何 .java 文件
- **不修改** agent-core / 前端 / 数据库 schema / SQL 文件
- **启动时自动生效**：repackage jar → 重启 gateway → 9 条新 `skills` 记录自动 seed → agent-core 拉 `GET /api/skills` 自动发现并包装为 9 个新 LLM tool
- **回滚成本**：删 9 条 `skills` 记录（id IN (md_* 的 9 行)）+ 撤掉 `MdToolService` 9 个 handler 注册（10 行代码）+ 撤掉 5 个 flexmark 依赖
