# frontend-gateway-parsing-bridge Specification

## Purpose
TBD - created by archiving change connect-module2-3-with-file-center. Update Purpose after archive.
## Requirements
### Requirement: 前端文件解析必须委托给 gateway 后端

The system MUST delegate all document parsing for types `doc`, `docx`, `txt`, `md`, `py` to the skill-gateway backend via `POST /api/files/upload`. The frontend MUST NOT parse these types locally using third-party libraries (mammoth, SheetJS, FileReader).

#### Scenario: 拖入合法 docx
- **WHEN** user drops a valid `.docx` file into the chat input
- **THEN** the frontend MUST POST the file to `POST /api/files/upload` (multipart) with `X-User-Id` header
- **AND** the frontend MUST set `UploadFileInfo.status='parsing'` immediately
- **AND** upon gateway response, MUST set `UploadFileInfo.parsedText` to the gateway's `parsed_summary` JSON string
- **AND** MUST set `UploadFileInfo.status='parsed'`

#### Scenario: 拖入合法 .py
- **WHEN** user drops a valid `.py` file
- **THEN** the frontend MUST delegate parsing to the gateway backend
- **AND** `parsedText` MUST contain the **full file content** (no truncation, per wgj's `PyParser` and 需求方案 A1 §3.2.4)

#### Scenario: 拖入合法 .md
- **WHEN** user drops a valid `.md` file
- **THEN** the frontend MUST delegate parsing to the gateway backend
- **AND** `parsedText` MUST contain the **first 500 characters** (per wgj's `TxtMdParser` and 需求方案 A1 §3.2.3)

#### Scenario: 后端解析失败
- **WHEN** gateway returns 4xx/5xx (e.g., magic number mismatch, unsupported type, FTP unavailable)
- **THEN** the frontend MUST set `UploadFileInfo.status='failed'`
- **AND** MUST populate `UploadFileInfo.errorMessage` with the gateway error message (in Chinese)
- **AND** MUST NOT retry automatically

### Requirement: 解析结果回写 user_files.parsed_summary

The system MUST persist the gateway's `parsed_summary` into the `user_files.parsed_summary` DB column so that 模块 5 `file_detail` and后续 LLM 注入 (模块 3 后续 change) can read it without re-parsing.

#### Scenario: 上传后 DB 写入
- **WHEN** `POST /api/files/upload` is called and the gateway finishes FTP storage + parsing + DB persistence
- **THEN** the gateway MUST store the parsed `FileParseResult` JSON into `user_files.parsed_summary` for the corresponding file row
- **AND** the row's `user_id` MUST equal the `X-User-Id` from the request header (per wgj's `FileAccessInterceptor`)
- **AND** the frontend MUST return the same `parsed_summary` JSON in the upload response (or in a subsequent GET) so the frontend can populate `UploadFileInfo.parsedText`

#### Scenario: 模块 5 file_detail 读取摘要
- **WHEN** 模块 5 `file_detail` tool reads a file's details
- **THEN** the gateway MUST return the `parsed_summary` field from the `user_files` row directly (no re-parsing)

#### Scenario: 同名文件重复上传
- **WHEN** user uploads a file with the same `original_file_name` as an existing row in user's directory
- **THEN** the gateway MUST follow the replacement semantics defined in 需求方案 A1 §2.2.4 ("该文件已于xxx时间上传，是否进行替换？")
- **AND** the new `parsed_summary` MUST overwrite the old one

### Requirement: 解析请求携带 AbortSignal 支持

The frontend MUST pass an `AbortSignal` to the gateway upload call so that the user's "× 取消" action (per `module2-file-upload` 的 `cancel`) can interrupt in-flight HTTP requests.

#### Scenario: 用户点击 × 取消
- **WHEN** user clicks the "×" button on a file with `status='parsing'`
- **THEN** the frontend MUST call `controller.abort()` on the request's AbortController
- **AND** MUST set `UploadFileInfo.status='skipped'`
- **AND** MUST NOT update `parsedText`

### Requirement: 前端解析调用点必须改走 gateway 通道（暂保留老路径兜底）

The frontend's `parseDocument` MUST delegate doc/docx/txt/md/py parsing to `parseFileViaGateway` (which calls `POST /api/files/upload`). The old `mammoth` / `SheetJS` / `FileReader` paths SHOULD NOT be invoked at runtime.

**本 change 期间**：老路径代码 (`docxParser.ts` / `xlsxParser.ts` / `txtParser.ts`) 保留在仓库（不删）作为紧急回滚兜底，**`package.json` 也不动**（保留 `mammoth` / `xlsx` 依赖）。后续独立 cleanup change 再删。

#### Scenario: 老路径不再被调用
- **WHEN** this change is implemented
- **THEN** `frontend/src/utils/fileParser.ts` MUST NOT call `parseDocx` / `parseXlsx` / `parseTxt` in its `parseDocument` for the 5 supported types
- **AND** `package.json` MAY still contain `"mammoth"` and `"xlsx"` (回滚兜底, 后续 cleanup change 再删)
- **AND** the `docxParser.ts` / `xlsxParser.ts` / `txtParser.ts` files MAY still exist on disk (回滚兜底)

### Requirement: 前端 4 步校验保留

The frontend MUST continue to perform the 4-step validation (类型 / 大小 / 数量 / 重复) per 需求方案 A1 §2.2 — these are **interaction-layer** checks that fire user-visible toasts and MUST remain on the client. The gateway's `MagicNumberValidator` is **defense-in-depth** against file extension spoofing, not a replacement for client-side checks.

#### Scenario: 用户拖入 .exe 改名为 .docx
- **WHEN** user drops a file named `malware.docx` whose real content is a Windows executable
- **THEN** the frontend's 4-step validation passes (extension is `.docx`)
- **AND** the gateway's `MagicNumberValidator` MUST reject the file
- **AND** the frontend MUST display "文件类型与扩展名不匹配" toast
- **AND** MUST set `UploadFileInfo.status='failed'`

### Requirement: Excel/CSV 暂未实现时的兜底

Excel/CSV 解析由启雷后续 change 注入。本 change 期间 .xls/.xlsx/.csv 上传 MUST NOT 报错（前端不应红字），而是返回空 `parsed_summary`。

#### Scenario: 上传 .xlsx 在启雷未注入时
- **WHEN** user drops a valid `.xlsx` file and wgj's `FileParserRouter` has no Excel parser registered
- **THEN** the gateway MUST return HTTP 200 with `parsed_summary=""` (empty string, not error)
- **AND** the frontend MUST set `UploadFileInfo.status='parsed'` (parsed-empty, not failed)
- **AND** the file MUST be stored in FTP + `user_files` row, just with empty `parsed_summary`

