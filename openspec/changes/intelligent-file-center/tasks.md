# Tasks: 智能文件中心

> **所有功能在 skill-gateway (Java, JDK 1.8) 中实现，不修改 agent-core。**
> 标注规则：
> - **🔴 光建** = 李光建负责，需着重关注
> - 启雷 = 启雷负责
> - 壮 = 壮负责

---

## 1. 基础设施搭建

- [x] 1.1 FTP 客户端配置 — 配置 FTP 服务器连接参数（host/port/username/password），写入 `application.properties` -- **🔴 光建**
- [x] 1.2 魔数校验工具类 — 基于 Apache Tika 实现 `MagicNumberValidator`，校验文件真实类型 -- **🔴 光建**
- [x] 1.3 文件存储 Service 封装 — `FtpFileService`：上传、下载、删除、列表、目录创建 -- **🔴 光建**
- [x] 1.4 实体类与数据库表 — `UserFile` 实体（id, userId, fileName, fileSize, fileType, uploadTime, ftpPath, downloadUrl）-- **🔴 光建**

---

## 2. 模块一：用户隔离 -- 🔴 光建 🔴

- [x] 2.1 AAM Token 解析 — 从请求头提取 AAM token，解析出用户唯一标识（userId）-- **🔴 光建**
- [x] 2.2 FTP 用户目录自动创建 — 首次登录/首次文件操作时，自动在 FTP 创建 `/{userId}/` 目录 -- **🔴 光建**
- [x] 2.3 文件访问权限拦截 — Controller 层校验 userId，确保用户只能操作自己的文件 -- **🔴 光建**

---

## 3. 模块二：文件上传 — 壮

- [ ] 3.1 后端上传 API — `POST /api/files/upload`，接收 multipart file，执行校验链后存储到 FTP -- 壮
- [ ] 3.2 文件类型校验（魔数）— 调用 `MagicNumberValidator`，非允许类型返回错误提示"当前仅支持doc、docx、xls、xlsx、csv、txt以及md文件的上传" -- 壮
- [ ] 3.3 文件大小校验 — 超过 10MB 返回"文件大小超过10MB，请修改后重试。" -- 壮
- [ ] 3.4 文件数量校验 — 单 session 不超过 5 个，超限返回"单次最多上传5个文件，请减少选择。" -- 壮
- [ ] 3.5 重复文件校验 — 同一 userId 目录下检查同名文件，存在则返回"该文件已于{time}上传，是否进行替换？"，替换时先删旧再存新 -- 壮
- [ ] 3.6 前端：点击上传图标 — 弹出文件选择器，支持 doc/docx/xls/xlsx/csv/txt/md/py -- 壮
- [ ] 3.7 前端：拖拽上传 — 聊天输入框支持拖拽文件 -- 壮
- [ ] 3.8 前端：Ctrl+V 粘贴上传 — 聊天输入框支持 Ctrl+V 粘贴文件 -- 壮
- [ ] 3.9 前端：上传进度指示（转圈）— 上传中显示 spinner，完成后变色 -- 壮
- [ ] 3.10 前端：取消上传 — 文件旁 "x" 按钮，已使用的文件不可取消 -- 壮

---

## 4. 模块三：智能文件解析

### 4.1 解析器路由 -- 🔴 光建 🔴

- [x] 4.1.1 文件类型路由器 — `FileParserRouter`，根据魔数/扩展名分发到对应解析器 -- **🔴 光建**
- [x] 4.1.2 下载 URL 字段 — 解析结果 JSON 中增加 `download_url` 字段，上传时生成 -- **🔴 光建**

### 4.2 Word 解析器 -- 🔴 光建 🔴

- [x] 4.2.1 标题/章节提取 — 使用 Apache POI XWPF/HWPF 提取一级、二级、三级标题及章节结构 -- **🔴 光建**
- [x] 4.2.2 无标题降级 — 优先后大号字体内容 → 编号内容 → 前 500 字符 -- **🔴 光建**
- [x] 4.2.3 表格提取 — 提取表格及其标题行、位置描述 -- **🔴 光建**
- [x] 4.2.4 图片提取 — 提取图片索引、位置、尺寸 -- **🔴 光建**
- [x] 4.2.5 JSON 序列化 — 按规范格式输出解析 JSON（见需求文档示例）-- **🔴 光建**

### 4.3 TXT 解析器 -- 🔴 光建 🔴

- [x] 4.3.1 TXT 解析器 — 从前往后抽取 500 字转化为 JSON -- **🔴 光建**
- [x] 4.3.2 MD 解析器 — 从前往后抽取 500 字转化为 JSON -- **🔴 光建**

### 4.4 Python 文件解析 -- 🔴 光建 🔴

- [x] 4.4.1 Py 文件解析器 — .py 文件全量解析（不抽离），文件数量 ≤5，单文件 ≤10MB -- **🔴 光建**

### 4.5 Excel 解析器 — 启雷

- [ ] 4.5.1 Sheet 页提取 — 获取所有 sheet 名称、行列数 -- 启雷
- [ ] 4.5.2 列头提取 — 提取各列标题栏及数据类型推断 -- 启雷
- [ ] 4.5.3 统计信息 — 数值列：min/max/mean/median/std/negative_count；日期列：min_date/max_date；分类列：categories/category_counts -- 启雷
- [ ] 4.5.4 元数据提取 — has_formula, has_merged_cells, merged_cell_ranges, has_chart, has_pivot_table, frozen_panes -- 启雷
- [ ] 4.5.5 无标题降级 — 随机抽取前 10 行数据 -- 启雷
- [ ] 4.5.6 JSON 序列化 — 按规范格式输出 -- 启雷

### 4.6 CSV 解析器 — 启雷

- [ ] 4.6.1 CSV 解析器 — 自动检测分隔符和编码，输出同 Excel columns 格式 -- 启雷

### 4.7 MD 解析器 — 壮

- [ ] 4.7.1 MD 解析器 — 从前往后抽取 500 字转化为 JSON -- 壮

### 4.8 大模型上下文注入 -- 🔴 光建 🔴

- [x] 4.8.1 系统提示词组装 — 将解析 JSON 注入到系统提示词中 -- **🔴 光建**
- [x] 4.8.2 上传任务完成标记 — 解析完成后标记文件上传任务完成 -- **🔴 光建**

---

## 5. 模块四：数据处理 HTTP API

> 所有 API 以标准 HTTP REST 端点形式在 skill-gateway 中实现，供 agent-core 通过 HTTP 调用，复用现有 Tool 调用机制。

### 5.1 HTTP API 基础设施 -- 🔴 光建 🔴

- [x] 5.1.1 HTTP API Controller 层 — `FileToolController` 统一注册所有文件操作 API 端点 -- **🔴 光建**
- [x] 5.1.2 API 入参/出参规范 — 定义标准 JSON 请求/响应结构（fileRef, params, output）-- **🔴 光建**
- [x] 5.1.3 文件引用解析 — 根据 session 中的 fileName 解析到 FTP 路径 -- **🔴 光建**

### 5.2 Excel 操作 API — 启雷（13 个）

- [ ] 5.2.1 `excel_read` — 读 Excel 文件内容 -- 启雷
- [ ] 5.2.2 `excel_write` — 写 Excel 文件 -- 启雷
- [ ] 5.2.3 `excel_filter` — 按列条件过滤 -- 启雷
- [ ] 5.2.4 `excel_sort` — 按列排序（单列/多列，升降序）-- 启雷
- [ ] 5.2.5 `excel_aggregate` — 聚合统计（sum/count/avg/min/max/median/std），支持 group-by -- 启雷
- [ ] 5.2.6 `excel_pivot` — 透视分析 -- 启雷
- [ ] 5.2.7 `excel_calculate` — 列运算（加减乘除）-- 启雷
- [ ] 5.2.8 `excel_select_columns` — 列选择 -- 启雷
- [ ] 5.2.9 `excel_clean` — 数据清洗（去空、trim、规范化）-- 启雷
- [ ] 5.2.10 `excel_merge` — 多表合并（inner join/left join/union）-- 启雷
- [ ] 5.2.11 `excel_convert` — 格式转换（xls↔xlsx↔csv）-- 启雷
- [ ] 5.2.12 `excel_apply_style` — 条件格式/样式着色 -- 启雷
- [ ] 5.2.13 `excel_validate` — 合规校验 -- 启雷

### 5.3 Word 操作 API -- 🔴 光建 🔴（6 个 API）

- [ ] 5.3.1 `word_read` — 读 Word 文档全文 -- **🔴 光建**
- [ ] 5.3.2 `word_write` — 写 Word 文档 -- **🔴 光建**
- [ ] 5.3.3 `word_extract_content` — 内容提取（标题/段落/表格/图片）-- **🔴 光建**
- [ ] 5.3.4 `word_search_keyword` — 关键字搜索（含上下文和位置）-- **🔴 光建**
- [ ] 5.3.5 `word_replace_text` — 内容替换 -- **🔴 光建**
- [ ] 5.3.6 `word_template_fill` — 模板填充 -- **🔴 光建**

### 5.4 TXT 操作 API -- 🔴 光建 🔴（10 个 API）

- [ ] 5.4.1 `txt_read` — 读 TXT 文件 -- **🔴 光建**
- [ ] 5.4.2 `txt_write` — 写 TXT 文件 -- **🔴 光建**
- [ ] 5.4.3 `txt_keyword_lines` — 关键词行提取 -- **🔴 光建**
- [ ] 5.4.4 `txt_regex` — 正则匹配/提取 -- **🔴 光建**
- [ ] 5.4.5 `txt_line_range` — 行范围提取 -- **🔴 光建**
- [ ] 5.4.6 `txt_section` — MD 标题章节提取 -- **🔴 光建**
- [ ] 5.4.7 `txt_stats` — 字符数/词数/行数统计 -- **🔴 光建**
- [ ] 5.4.8 `txt_distinct_lines` — 去重行 -- **🔴 光建**
- [ ] 5.4.9 `txt_sort_lines` — 排序行 -- **🔴 光建**
- [ ] 5.4.10 `txt_keyword_freq` — 关键词频率统计 -- **🔴 光建**

### 5.5 MD 操作 API — 壮（11 个 API）

- [ ] 5.5.1 `md_read` — 读 MD 文件 -- 壮
- [ ] 5.5.2 `md_write` — 写 MD 文件 -- 壮
- [ ] 5.5.3 `md_images` — 提取所有图片引用 -- 壮
- [ ] 5.5.4 `md_headings` — 提取所有标题（含层级）-- 壮
- [ ] 5.5.5 `md_table` — 提取所有表格 -- 壮
- [ ] 5.5.6 `md_list_items` — 提取所有列表项 -- 壮
- [ ] 5.5.7 `md_tasks` — 提取所有任务项（task list）-- 壮
- [ ] 5.5.8 `md_emphasis` — 提取所有加粗/斜体 -- 壮
- [ ] 5.5.9 `md_toc` — 提取目录 TOC -- 壮
- [ ] 5.5.10 `md_filter_section` — 删除/保留指定章节 -- 壮
- [ ] 5.5.11 `md_merge` — 拼接多个 MD（frontmatter 去重）-- 壮
- [ ] 5.5.12 `ocr_image` — OCR 图片文字提取，返回纯文本；校验图片格式（png/jpg/jpeg/bmp/tiff），若未安装 Tesseract 则提示用户（**新增 Tess4J 依赖评审通过后执行**）-- 壮

---

## 6. 模块五：文件管理 -- 🔴 光建

- [ ] 6.1 `file_list` HTTP API — 列出用户所有文件（文件名、大小、上传时间、downloadUrl）-- **🔴 光建**
- [ ] 6.2 `file_delete` HTTP API — 删除指定文件（二次确认：LLM 先问用户确认文件名，用户确认后才删）-- **🔴 光建**
- [ ] 6.3 `file_clear_all` HTTP API — 清空所有文件（二次确认流程同 file_delete）-- **🔴 光建**
- [ ] 6.4 `file_detail` HTTP API — 查看文件详情（文件名、大小、上传时间、类型、解析摘要）-- **🔴 光建**

---

## 7. 模块六：文件下载 — 壮

- [ ] 7.1 修改后文件下载 API — `GET /api/files/download/{fileId}`，返回文件流 -- 壮
- [ ] 7.2 API 响应中的 download_url — 处理后的文件在 API response 中附带 download_url -- 壮
- [ ] 7.3 前端：下载按钮/链接 — 对话中文件处理完成后展示下载入口 -- 壮

---

## 8. 集成与测试

- [ ] 8.1 HTTP API 注册 — 将全部 40+ API 注册到 skill-gateway 的 Controller 层 -- **🔴 光建**
- [ ] 8.2 端到端流程测试 — 上传 → 解析 → 对话操作 → 下载 全流程测试 -- **🔴 光建**
- [ ] 8.3 用户隔离测试 — 验证跨用户文件隔离 -- **🔴 光建**
- [ ] 8.4 JDK 1.8 编译验证 — 确保 Maven `clean package ""-DskipTests""` BUILD SUCCESS（注意 PowerShell 中 Maven 参数必须用 `-DskipTests`，不可用 `-Dmaven.test.skip=true` 否则 `.` 号被 PowerShell 截断）-- **🔴 光建**

---

## 光建任务汇总 🔴

| 编号 | 模块 | 任务数 | 位置 |
|------|------|--------|------|
| 1.1-1.4 | 基础设施搭建 | 4 | §1 |
| 2.1-2.3 | 用户隔离 | 3 | §2 |
| 4.1.1-4.1.2 | 解析器路由 | 2 | §4.1 |
| 4.2.1-4.2.5 | Word 解析器 | 5 | §4.2 |
| 4.3.1-4.3.2 | TXT/MD 解析器 | 2 | §4.3 |
| 4.4.1 | Python 文件解析 | 1 | §4.4 |
| 4.8.1-4.8.2 | 大模型上下文注入 | 2 | §4.8 |
| 5.1.1-5.1.3 | HTTP API 基础设施 | 3 | §5.1 |
| 5.3.1-5.3.6 | Word 操作 API | 6 | §5.3 |
| 5.4.1-5.4.10 | TXT 操作 API | 10 | §5.4 |
| 6.1-6.4 | 文件管理 | 4 | §6 |
| 8.1-8.4 | 集成与测试 | 4 | §8 |
| **合计** | | **46** | |

## 启雷任务汇总

| 编号 | 模块 | 任务数 |
|------|------|--------|
| 4.5.1-4.5.6 | Excel 解析器 | 6 |
| 4.6.1 | CSV 解析器 | 1 |
| 5.2.1-5.2.13 | Excel 操作 API | 13 |
| **合计** | | **20** |

## 壮任务汇总

| 编号 | 模块 | 任务数 |
|------|------|--------|
| 3.1-3.10 | 文件上传 | 10 |
| 4.7.1 | MD 解析器 | 1 |
| 5.5.1-5.5.12 | MD 操作 API（含 OCR） | 12 |
| 7.1-7.3 | 文件下载 | 3 |
| **合计** | | **26** |
