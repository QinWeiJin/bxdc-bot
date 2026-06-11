## Context

BXDC.bot 平台当前仅支持文本对话。运营人员需要分析 Excel/Word/CSV/TXT/MD/Python 等文件，但缺乏文件上传、解析、操作和下载能力。本次设计构建一个完整的"智能文件中心"，全部功能在 skill-gateway（Java, JDK 1.8, Spring Boot 2.7）中实现，不修改 agent-core（NestJS）。

**关键约束：**
- JDK 1.8 兼容（不可用 `var`、`String.formatted()`、`List.of()`、`Optional.isEmpty()` 等高版本 API）
- agent-core 代码尽量不修改（按照 AGENTS.md 7.5 架构约束）
- 新增能力以 HTTP REST API 形式在 skill-gateway 中实现，通过已有的 Tool 调用机制供 LLM 调用
- 不新增第三方包（优先用已有依赖 + JDK 标准库）

## Goals / Non-Goals

**Goals:**
- 用户基于 AAM token 隔离文件，FTP 按用户目录存储
- 支持 doc/docx/xls/xlsx/csv/txt/md/py 文件上传，含魔数校验、大小/数量/重复校验
- 按文件类型智能解析为结构化 JSON
- 33+ HTTP API 端点供 agent-core 通过 HTTP 调用，进行 Excel/Word/TXT/MD 数据操作
- 对话式文件管理（列表/删除/清空/详情）
- 文件下载（处理后文件 + 主动请求）

**Non-Goals:**
- 不修改 agent-core 代码（仅通过现有 Tool 调用机制）
- 不引入新的第三方依赖（优先 Apache POI + Tika + JDK 标准库）
- 不做前端大改（通过 Tool Result JSON 由 LLM 渲染结果）
- 不做实时协作编辑

## Decisions

### 1. 技术栈选择
| 决策 | 选择 | 理由 | 替代方案 |
|------|------|------|---------|
| Excel 解析/操作 | Apache POI | 项目已有依赖，功能完整 | EasyExcel（需新依赖） |
| Word 解析/操作 | Apache POI (XWPF/HWPF) | 同上 | docx4j（更重） |
| 魔数校验 | Apache Tika | 项目已有依赖，准确检测真实文件类型 | 手写魔数表 |
| FTP 存储 | Apache Commons Net FTP | JDK 无内置 FTP 客户端 | SFTP（需额外配置） |
| Tool 调用协议 | 标准 HTTP REST API（skill-gateway 现有框架） | 复用已有基础设施，无需引入新协议 | 新建协议 |
| 文件元数据 JSON 格式 | 自定义 schema | 灵活适配 LLM 上下文 | Swagger/OpenAPI schema |

### 2. Maven 参数传递约束
在 PowerShell 环境中，`-Dmaven.test.skip=true` 参数中的 `.` 号会被 PowerShell 误解析。**必须使用 `-DskipTests` 参数形式**才能正确传递给 mvn.cmd。

### 3. PDF 解析支持（Apache PDFBox）
Word 解析器存在覆盖 PDF 文件的场景（如大型技术报告混合 Word+PDF 文件）。由于 PDFBox 已在项目依赖中（通过 Tika 传递依赖），决定同时支持 `application/pdf` MIME 类型。PDF 解析策略：
- 提取全文本内容 → 按空行分段落 → 取首段作为标题（前 200 字符）
- 统计段落数、总字符数、页数
- 产出统一 JSON 格式（类似 word 解析结构，但无章节层级/表格/图片）
- 图片提取通过 Tika AutoDetectParser 流式处理

### 4. Agent-Core 联动规范
文件操作结果通过 **Tool Result JSON** 回传给 agent-core（与现有 api/ssh Skill 相同的调用模式）。若需要前后端交互（如文件列表展示），通过 LLM 自行渲染 Markdown 表格。仅在不得不修改 agent-core 时（如新增 API 路由），才在 agent-core 中增加代码，并在 OpenSpec design 中标注。

### 5. ORC 图片 OCR 文字提取（Tess4J/Tesseract）
业务中存在嵌入文字的图片（截图文字、拍照文档），用户需要提取图片中的文字。选择 **Tess4J**（Tesseract OCR Java 封装）作为 OCR 引擎：
- 开源免费，支持 100+ 语言
- **新增依赖** `net.sourceforge.tess4j:tess4j:5.9.0`（需 AGENTS.md 7.1 评审）
- Tesseract OCR 需要单独安装（Windows exe / Linux apt），版本 >= 5.0
- 提供单一 HTTP API `ocr_image`：输入图片 URL，返回提取的文字
- 支持 png/jpg/jpeg/bmp/tiff 等常见图片格式
- 非刚需时降级为简单通知（告知用户当前未配置 OCR）

### 6. 文件解析模块与 PDF/OCR/Agent-Core 集成
三个新增能力（PDF 解析、OCR 图片文字提取、Agent-Core 联动）统一纳入 `file-parsing` 模块：
- Word 解析器新增 `pdf` 路由 → 调用 Tika + PDFBox 提取文本
- 新增独立 `OcrService` → 封装 Tess4J 调用
- 解析结果统一使用 `FileParseResult` DTO → 通过 Tool Result JSON 回传 agent-core
- 前端适配由 Agent-Core 联动规范决定（优先 LLM Markdown 渲染）

## Risks / Trade-offs

- [Risk] FTP 服务器故障导致文件操作不可用 → Mitigation: 启动时检测 FTP 连通性，异常时用户对话中提示"文件服务暂不可用"
- [Risk] Apache POI 处理超大 Excel 时内存溢出 → Mitigation: XSSFWorkbook 使用 SAX 流式读取（XSSFReader），限制单文件 10MB
- [Risk] MD 文件解析准确性依赖正则 → Mitigation: 对常见 Markdown 方言提供多套正则 fallback
- [Risk] 魔数校验可能漏过精心构造的伪装文件 → Mitigation: 双重校验（魔数 + 扩展名），不一致时弹窗警告
- [Risk] 多用户并发上传同名文件 → Mitigation: 按 session 维度去重 + 时间窗口内拒绝重复

## Migration Plan

1. 部署阶段在 FTP 服务器创建根目录
2. 新用户首次登录时自动创建 `/{aam_user_id}/` 子目录
3. 不涉及数据迁移（全新功能）
4. 回滚：删除 skill-gateway 新增代码即可，不影响现有功能
