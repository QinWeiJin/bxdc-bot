## 1. pom.xml 加 5 个 flexmark 依赖

- [x] 1.1 编辑 `backend/skill-gateway/pom.xml`，在 `<dependencies>` 中加 5 个 dependency（**user 已授权引入第三方包**）：
  ```xml
  <dependency>
    <groupId>com.vladsch.flexmark</groupId>
    <artifactId>flexmark</artifactId>
    <version>0.62.2</version>
  </dependency>
  <dependency>
    <groupId>com.vladsch.flexmark</groupId>
    <artifactId>flexmark-ext-tables</artifactId>
    <version>0.62.2</version>
  </dependency>
  <dependency>
    <groupId>com.vladsch.flexmark</groupId>
    <artifactId>flexmark-ext-gfm-tasklist</artifactId>
    <version>0.62.2</version>
  </dependency>
  <dependency>
    <groupId>com.vladsch.flexmark</groupId>
    <artifactId>flexmark-ext-yaml-front-matter</artifactId>
    <version>0.62.2</version>
  </dependency>
  <dependency>
    <groupId>com.vladsch.flexmark</groupId>
    <artifactId>flexmark-ext-gfm-strikethrough</artifactId>
    <version>0.62.2</version>
  </dependency>
  ```
  > **0.62.2 命名差异**（vs 0.64.8）：核心叫 `flexmark`（非 `flexmark-core`），任务列表叫 `flexmark-ext-gfm-tasklist`（非 `flexmark-ext-tasklist`）。
- [x] 1.2 `cd backend/skill-gateway && mvn -s settings.xml compile` 让 aliyun mirror 自动下载 5 个 jar 到 `.m2/repository/com/vladsch/flexmark/`（BUILD SUCCESS，23s）
- [x] 1.3 验证 `ls .m2/repository/com/vladsch/flexmark/flexmark/0.62.2/flexmark-0.62.2.jar` 等 5 个 jar 存在（+ 8 个传递依赖的 `flexmark-util-*` 系列）

## 2. 新增 MdToolService（9 个 handler，flexmark Parser 驱动）

- [x] 2.1 新建 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/tools/MdToolService.java`（≈ 870 行）
- [x] 2.2 类顶部声明 `static final Parser MD_PARSER`（4 个 ext 全部注册：tables / gfm-tasklist / gfm-strikethrough / yaml-front-matter）
- [x] 2.3 共用 helper（`ensureMdFile` / `readAllText` / `extractTextContent` / `writeBackNewFile` / 7 个 param 读取函数 / `extractFrontmatter` / `joinLinesRange`）
- [x] 2.4 `@PostConstruct registerHandlers()` 注册 9 个 handler 到 `FileToolService.handlers` map（log 输出注册数 = 9）
- [x] 2.5 **flexmark 0.62.2 API 适配修正**（踩坑 2 处）：
  - `Node / NodeVisitor / VisitHandler / Visitor` 在 `com.vladsch.flexmark.util.ast.*`（不是 `.ast`）
  - `Visitor<N>` 是接口（只有 1 个 `visit(N)` 方法），不是 `NodeVisitorBase` 类
  - `TaskListItem` 的方法是 `isItemDoneMarker()`（不是 `isItemDone()`）
  - `ListBlock` 无 `isOrdered()` / `getBulletMarker()`，要用 `instanceof BulletList` / `OrderedList` + `getOpeningMarker()` / `getStartNumber()` / `getDelimiter()`

## 3. 6 个纯查询 handler（flexmark AST 遍历）

- [x] 3.1 `mdImages` — `VisitHandler<Image>` 拿 `getStartLineNumber() + getText() + getTitle() + getUrl()`；返 `[{lineNumber, alt, title, url, kind}]`（当前只支持 inline；reference 可作为后续扩展）
- [x] 3.2 `mdHeadings` — `VisitHandler<Heading>`；返扁平 list `[{level, text, lineNumber}]`
- [x] 3.3 `mdTable` — `VisitHandler<TableBlock>` 遍历 `TableRow` + `TableCell`；返 `[{lineNumber, header, rows, rawMarkdown}]`
- [x] 3.4 `mdListItems` — `VisitHandler<ListItem>` + `instanceof BulletList/OrderedList` 判断有序/无序；返 `[{lineNumber, marker, text, indent, ordered}]`
- [x] 3.5 `mdTasks` — `VisitHandler<TaskListItem>`，读 `isItemDoneMarker()`；返 `[{lineNumber, checked, text, indent, rawLine}]`
- [x] 3.6 `mdEmphasis` — 4 个 `VisitHandler<{StrongEmphasis,Emphasis,Strikethrough,Code}>`；返 `[{lineNumber, style, text}]`

## 4. md_toc 嵌套化 handler

- [x] 4.1 复用 `collectHeadings()` 得扁平 list，`buildTocTree()` 构造 outline tree（栈 + children 数组）；支持跳级（H1 → H3 直接嵌套，不补 H2）；返 `{toc: [...], headingCount}`

## 5. md_filter_section 写回 handler

- [x] 5.1 解析 `path: string[]` + `mode: "keep"|"remove"` + `matchMode: "first"|"all"`
- [x] 5.2 扫 `Heading` 节点，栈匹配 `path`（path 顺序对应标题层级嵌套）
- [x] 5.3 keep：保留命中章节（含其下子节）+ 文档最前面的 frontmatter（手写正则识别 `--- ... ---`）；remove：保留其余
- [x] 5.4 字符串切片拼回新内容；`ftpFileService.uploadFile()` 写新文件；`userFileMapper.insert(new UserFile)` 新行
- [x] 5.5 返 `{originalFileId, newFileId, newFileName, ftpPath, lineCount}`
- [x] 5.6 path 不存在 → `FileToolResponse.error("section not found: <path>")`

## 6. md_merge 写回 handler

- [x] 6.1 解析 `sourceFileIds: number[]`（≥ 2）+ `frontmatterConflict`（默认 `"error"`）+ `prefixHeaders`（默认 `true`）
- [x] 6.2 遍历 sourceFileIds：校验 `user_id == currentUserId`（不一致 → `error("access denied for file id X")`）；校验 `fileType ∈ {md, markdown}`（不一致 → `error("all source files must be .md/.markdown")`）
- [x] 6.3 解析每源 frontmatter（手写正则 `extractFrontmatter()`，< 1ms/源）；按 `frontmatterConflict` 策略决定保留哪个
- [x] 6.4 拼接：`prefixHeaders=true` 时每源前加 `# {originalFileName}\n\n`；最后 `ftpFileService.uploadFile` + `userFileMapper.insert` 写新文件
- [x] 6.5 返 `{newFileId, newFileName, sourceFileIds, totalLines, frontmatterKept}`

## 7. FileToolSeeder 加 9 行（沿用 wgj 模式）

- [x] 7.1 在 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/config/FileToolSeeder.java` 的 `seedFileOperate()` 序列末尾加 9 行（id=23~31, type=EXTENSION, kind=file_tool）

## 8. 重新打 jar + 重启 gateway + 端到端冒烟

- [x] 8.1 `mvn -s settings.xml clean package -DskipTests -U`（jar 内含 MdToolService + 5 个 flexmark jar + 9 行 seeder）
- [x] 8.2 重启 gateway（`.dev-ftp-test/stop-gateway.sh` + `.dev-ftp-test/start-gateway.sh`）
- [x] 8.3 启动日志确认 `Seeded skill: md_xxx (id=23~31, type=EXTENSION, kind=file_tool)` 共 9 条
- [x] 8.4 MySQL 查 `SELECT id, name, type FROM skills WHERE name LIKE 'md%' ORDER BY id;` 确认 9 条
- [x] 8.5~8.7 cURL 测全部 9 个 handler（通过 `POST /api/files/tools/execute`）：全部 200 + 正确 JSON
- [x] 8.8 单测：`backend/skill-gateway/src/test/java/com/lobsterai/skillgateway/service/tools/MdToolServiceTest.java` — 15 个用例覆盖 9 个 handler 核心逻辑（mock FTP/DB，无外部依赖），`mvn test` BUILD SUCCESS

### 端到端测试结果（9/9 通过）

| Handler | 结果 | 关键输出 |
|---------|------|---------|
| md_images | ✅ | 1 image, alt/title/url/kind 正确 |
| md_headings | ✅ | 4 headings level 1-3, lineNumber 正确 |
| md_table | ✅ | header=["Name","Age"], rows=[["Alice","30"],["Bob","25"]] |
| md_list_items | ✅ | count=0 (rich.md 无普通 list item) |
| md_tasks | ✅ | 3 tasks (2 done, 1 pending), nested 不合并 |
| md_emphasis | ✅ | 3 spans, 围栏代码块内不检测 |
| md_toc | ✅ | 嵌套树正确 (H1→H2→H3 + H1→H2) |
| md_filter_section | ✅ | newFileId=28, keep Features 章节, SQL insert 成功 |
| md_merge | ✅ | newFileId=29, 2 文件合并 58 行, frontmatter 冲突处理正确 |

### flexmark 0.62.2 踩坑记录
- `TaskListItem.getContentChars()` 在 0.62.2 有 bug 返空 → 改用 `getChars()` 截第一行
- TableBlock 子结构是 `TableHead→TableRow` / `TableSeparator`（跳过）/ `TableBody→TableRow[]`
- `TableCell.getText()` 拿纯文本
- `Visitor<N>` 是接口（只有 `visit(N)` 方法），非 `NodeVisitorBase` 类
- `ListBlock` 无 `isOrdered()` 方法，用 `instanceof BulletList` / `OrderedList` 判断
