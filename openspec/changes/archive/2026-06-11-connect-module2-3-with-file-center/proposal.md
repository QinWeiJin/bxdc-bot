## Why

模块 2（前端上传体验，commit `96d5274`）和 wgj 的后端落地（commit `65e2982c`）当前是**两条并行的解析链**：

- **模块 2 前端**：`useFileUpload` → `parseDocument` → `parseDocx/mammoth`、`parseXlsx/SheetJS`、`parseTxt/FileReader` → 解析结果存 `UploadFileInfo.parsedText`，**不写回 DB**
- **wgj 后端**：`FileParserRouter` + `WordParser`(POI) + `TxtMdParser`(500字) + `PyParser`(全量) + `user_files.parsed_summary LONGTEXT` + FTP 存储

后果：① 同一份 docx 跑两份解析，结果可能不一致；② 解析结果没回写 DB，模块 5 的 `file_detail` 拿不到摘要；③ `MagicNumberValidator`（防 `.exe 改 .jpg`）只在后端，前端弹窗只是 UX 提示；④ 大文件前端解析卡 UI；⑤ 模块 3（启雷 Excel / 建建 Python）注入 LLM 时无摘要可读。

**本 change 把模块 2 前端和 wgj 后端衔接起来**：解析全部走 wgj 后端（统一一份代码），结果回写 `user_files.parsed_summary`（DB 缓存，避免重复解析），为模块 5 `file_detail` 和后续 LLM 注入铺路。

**本 change 范围限定**：只做"前端调用 wgj 后端解析 + 摘要回写 DB 链路通"。**不**删除前端 mammoth/SheetJS 依赖、**不**删除 6 个旧后端解析类（死代码）、**不**改 `package.json` — 这些清理工作留待后续独立 change。

## What Changes

- **解析路由表改造**：`frontend/src/utils/fileParser.ts` 的 `parseDocument` 把 doc/docx/txt/md/py 五个类型从"前端优先 + 后端兜底"改为"**纯后端 gateway 通道**"。**本 change 期间保留** `mammoth` / `SheetJS` / `FileReader` 三个老路径的代码（mammoth/xlsx 在 `package.json` 中保留）作为临时回滚兜底；后续清理 change 再删
- **新增 `parseFileViaGateway` 工具**：以 multipart 形式把 `File` POST 到 `POST /api/files/upload`（**新建 `FileUploadController`**，约 40 行，只调 wgj 已有 Service 层 `FtpFileService.uploadFile()` + `FileParseService.parseAndPersist()` + `UserFileMapper`，**不修改** wgj 任何 .java 文件），后端自动解析后把 `FileParseResult` 回写 `user_files.parsed_summary`；前端把摘要写入 `UploadFileInfo.parsedText` 供 LLM 注入
- **`parsed_summary` 持久化**：依赖 wgj 已在 `FileParseService.parseAndPersist()` 里实现的逻辑（`schema-mysql.sql` 已建 `user_files.parsed_summary LONGTEXT`），前端只负责调用 + 取结果
- **删除后端死代码（推迟到后续 change）**：`WordParserController/Service`、`ExcelParserController/Service`、`WordParseResponse/ExcelParseResponse` 6 个文件（commit `17048aa` 临时落地，wgj 落地后**0 引用**）— **不在本 change 范围**，留待后续清理
- **保留前端 4 步校验**（类型/大小/数量/重复）：这些是**交互层** UX 弹窗，必须在前端做（不能等 500ms 网络往返）
- **保留 `MagicNumberValidator`**：防扩展名欺骗，wgj 后端跑，前端**仅按扩展名**给"非支持格式"弹窗
- **保留 PPT 解析**（PptParserController/Service）：wgj 没实现 PPT，前端 `pptParser.ts` 仍调 `/features/file/parse-ppt`

**BREAKING**：无。`module2-file-upload` 的 UI / 状态机 / 三种上传入口不变。

## Capabilities

### New Capabilities

- `frontend-gateway-parsing-bridge`：前端解析委托给 gateway 后端的统一通道，覆盖 doc/docx/txt/md/py 5 个类型；解析结果回写 `user_files.parsed_summary`。这是一份"衔接规范"，把 wgj 落地的后端解析能力"暴露"给前端 + 模块 5 `file_detail` + 后续 LLM 注入

### Modified Capabilities

- `file-upload-frontend`：模块 2 的前端 spec 需在解析流程上**收敛到后端**
  - 删除 "mammoth/SheetJS 前端解析" 场景
  - 保留 "三种上传入口" / "4 步校验" / "状态机" 不变
  - 新增 "解析委派给 gateway" 场景
  - 限额配置补 `.py` 支持（需求方案 A1 §2.2.1）

## Impact

- **前端**（`frontend/src/`）：
  - `composables/useFileUpload.ts` — 解析调用点改走 `parseFileViaGateway`
  - `utils/fileParser.ts` — 路由表"前端优先 → 纯后端"
  - `utils/gatewayParser.ts` — **新建**（`parseFileViaGateway` 实现）
  - `utils/docxParser.ts` / `xlsxParser.ts` / `txtParser.ts` — **本 change 不动**（后续清理 change 再删）
  - `components/MessageInput.vue` — UI 行为不变

- **后端**（`backend/skill-gateway/`）：
  - `controller/FileUploadController.java` — **新建**（`POST /api/files/upload` multipart 端点，约 40 行，**只调 wgj 已有 Service 层**）
  - **不动** wgj 已有的 `FileToolService` / `FileParseService` / `FtpFileService` / `UserFile` / `MagicNumberValidator` / `FileToolController`
  - **不动** 6 个旧后端解析类（死代码，本 change 留待后续清理）
  - `schema-mysql.sql` 不动（`user_files.parsed_summary` 已存在）

- **依赖**：
  - `mammoth` / `xlsx`（SheetJS）**保留**（`package.json` 不改）— 后续清理 change 再删
  - 不新增任何依赖（AGENTS.md §7.1）

- **agent-core**：不动（AGENTS.md §7.5）

- **OpenSpec specs**：
  - 新建 `frontend-gateway-parsing-bridge/spec.md`
  - 修改 `file-upload-frontend/spec.md`（删除 1 个 scenario，新增 1 个）

- **测试**：
  - 自动化：保留前端解析单测（代码没删）
  - E2E：浏览器手动验证（5.2 系列）
  - 后端：`FileParseService` 单测由 wgj 维护

## 与需求方案 A1 的对应关系

| 需求条目 | 本 change 处理 |
|---------|--------------|
| §2 文件上传（4 步校验、3 种入口、状态机） | 保留，不动 |
| §3 智能文件解析（按文件类型路由到对应解析器） | ✅ 全部走 wgj 后端 `FileParserRouter` |
| §3.2.1 Word (光建) | ✅ 走 `WordParser`(POI) |
| §3.2.2 Excel/CSV (启雷) | ⚠️ 暂返回空 `parsed_summary`，启雷后续 change 注入 |
| §3.2.3 Txt/Md (光建、壮) | ✅ 走 `TxtMdParser`(500字) |
| §3.2.4 Python (建建) | ✅ 走 `PyParser`(全量) |
| §3 解析后 JSON 注入 LLM | 本 change 只把 `parsed_summary` 准备好，**不实现 LLM 注入**（属模块 3 后续 change） |
| §4 数据处理（POI + MCP） | 不在本 change 范围（启雷/光建负责） |
| §5 文件管理 | 本 change 把 `parsed_summary` 写回 `user_files` 表，**为 §5.4 `file_detail` 返回摘要铺路** |
| §6 文件下载 | 不在本 change 范围 |
