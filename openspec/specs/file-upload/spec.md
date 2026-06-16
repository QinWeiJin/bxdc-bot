# file-upload Specification

## Purpose
TBD - created by archiving change add-file-upload-types. Update Purpose after archive.
## Requirements
### Requirement: FileType 联合类型

The system SHALL define a `FileType` TypeScript union type with exactly 5 values: `'word' | 'excel' | 'ppt' | 'txt' | 'image'`.

#### Scenario: TypeScript 编译期穷尽性检查
- **WHEN** 业务代码使用 `switch` 语句覆盖全部 5 个 `FileType` 值
- **THEN** TypeScript 编译器 SHALL 不报 `noFallthroughCasesInSwitch` / `not all code paths return value` 错误
- **AND** 若未来新增第 6 个 `FileType` 值，所有未覆盖的 `switch` SHALL 触发编译错误（穷尽性保证）

### Requirement: UploadFileInfo 接口

The system SHALL define a `UploadFileInfo` interface describing the runtime state of a single uploaded file, with required fields: `id`, `file`, `fileName`, `fileType`, `size`, `status`, `uploadedAt`; and optional fields: `parsedText`, `errorMessage`, `previewUrl`, `ocrConfidence`, `compliance`, `decrypt`.

#### Scenario: 上传中文件处于 pending 状态
- **WHEN** 用户选择文件后尚未触发解析
- **THEN** `status` SHALL 处于 `'pending'`
- **AND** `parsedText` / `errorMessage` SHALL 为 `undefined`

#### Scenario: 解析成功的图片含 OCR 置信度
- **WHEN** 图片 OCR 完成
- **THEN** `status` SHALL 变为 `'parsed'`
- **AND** `parsedText` SHALL 包含识别文字
- **AND** `ocrConfidence` SHALL 为 `[0, 1]` 之间的 number

### Requirement: 限额配置 FILE_UPLOAD_CONFIG

The system SHALL export a `FILE_UPLOAD_CONFIG` constant of type `FileUploadConfig` with the following limits:

| FileType | MAX_COUNT | MAX_SIZE_PER_FILE | MAX_TOTAL_SIZE | ACCEPTED_EXTENSIONS |
|----------|-----------|-------------------|----------------|---------------------|
| word     | 3         | 5 MiB             | 15 MiB         | `.doc`, `.docx`     |
| excel    | 2         | 1 MiB             | 2 MiB          | `.xls`, `.xlsx`     |
| ppt      | 3         | 10 MiB            | 30 MiB         | `.ppt`, `.pptx`     |
| txt      | Infinity  | 0.3 MiB           | Infinity       | `.txt`, `.md`       |
| image    | 10        | 5 MiB             | 30 MiB         | `.png`, `.jpg`, `.jpeg`, `.webp` |

#### Scenario: 业务代码按类型读取限额
- **WHEN** 校验工具读取 word 类型限额
- **THEN** `FILE_UPLOAD_CONFIG.MAX_COUNT.word` SHALL 等于 `3`
- **AND** `FILE_UPLOAD_CONFIG.MAX_SIZE_PER_FILE.word` SHALL 等于 `5 * 1024 * 1024`
- **AND** `FILE_UPLOAD_CONFIG.ACCEPTED_EXTENSIONS.word` SHALL 是 `['.doc', '.docx']`

#### Scenario: txt 类型的无限额
- **WHEN** 业务代码读取 txt 类型数量上限
- **THEN** `FILE_UPLOAD_CONFIG.MAX_COUNT.txt` SHALL 等于 `Infinity`
- **AND** 校验逻辑 SHALL 跳过 txt 数量检查（视为不限）

### Requirement: 校验 / 合规 / 解密结果类型

The system SHALL define three result interfaces, each with explicit `valid` / `passed` / `success` boolean and human-readable message field:

- `FileValidationResult`: `{ valid: boolean; errors: string[]; warnings: string[] }`
- `FileComplianceResult`: `{ passed: boolean; message: string; sensitiveWords?: string[] }`
- `FileDecryptResult`: `{ success: boolean; content: string; errorMessage?: string }`

#### Scenario: 校验失败的错误聚合
- **WHEN** 单个文件触发 2 条 error
- **THEN** `errors` 数组 SHALL 包含这 2 条错误信息
- **AND** `valid` SHALL 为 `false`

#### Scenario: 合规检查命中敏感词
- **WHEN** 文件内容包含 2 个敏感词
- **THEN** `passed` SHALL 为 `false`
- **AND** `sensitiveWords` SHALL 包含这 2 个词

### Requirement: OCR 与图片解析状态类型

The system SHALL define `OcrResponse` interface and `ImageParsedStatus` enum.

`OcrResponse`: `{ text: string; confidence: number; language?: string; blocks?: Array<{ text: string; confidence: number; bbox?: number[] }> }`

`ImageParsedStatus`: enum with values `PENDING`, `PARSING`, `PARSED`, `FAILED`.

#### Scenario: OCR 响应包含识别文字
- **WHEN** agent-core 返回 OCR 结果
- **THEN** `text` SHALL 为识别出的字符串
- **AND** `confidence` SHALL 为 `[0, 1]` 之间的数字
- **AND** `language` 和 `blocks` 为可选字段，缺失时业务代码 SHALL 优雅降级（按 `text` 即可）

#### Scenario: 图片解析失败
- **WHEN** agent-core 端 OCR 调用失败
- **THEN** 业务代码 SHALL 设置 `status: 'failed'` 且 `errorMessage` 非空
- **AND** 若使用 `ImageParsedStatus` 枚举，状态 SHALL 为 `ImageParsedStatus.FAILED`

### Requirement: 展示辅助常量

The system SHALL export `FILE_TYPE_LABELS`, `FILE_TYPE_ICONS`, and `FILE_INPUT_ACCEPT` constants:

- `FILE_TYPE_LABELS`: 中文展示名（如 `'word' -> 'Word 文档'`）
- `FILE_TYPE_ICONS`: emoji 图标
- `FILE_INPUT_ACCEPT`: 逗号拼接的全部扩展名（用于 `<input type="file" accept="...">`）

#### Scenario: 拼接 accept 字符串
- **WHEN** 业务代码使用 `FILE_INPUT_ACCEPT` 作为 `<input>` 的 accept 属性
- **THEN** SHALL 包含全部 5 个 FileType 的扩展名（`.doc,.docx,.xls,.xlsx,...,.webp`）
- **AND** SHALL 不包含通配符（仅列具体扩展名）

### Requirement: File Upload via Click
The system SHALL support file upload via clicking an upload icon.
When the user clicks the icon, a file picker dialog SHALL open, allowing selection of word (.doc, .docx) and excel (.xls, .xlsx) files initially; csv, txt, md, py files SHALL also be supported.

#### Scenario: User clicks upload icon
- **WHEN** user clicks the upload icon in the chat interface
- **THEN** a native file picker opens allowing selection of supported file types

### Requirement: File Upload via Drag and Drop
The system SHALL support file upload by dragging files into the chat input area.

#### Scenario: User drags file into chat input
- **WHEN** user drags a supported file into the chat input area
- **THEN** the file is queued for upload and validation

### Requirement: File Upload via Ctrl+V (Paste)
The system SHALL support pasting files from clipboard into the chat input area via Ctrl+V as a secondary upload method.

#### Scenario: User pastes file with Ctrl+V
- **WHEN** user presses Ctrl+V with a supported file in clipboard in the chat input area
- **THEN** the file is queued for upload and validation

### Requirement: File Type Validation by Magic Number
The system SHALL validate uploaded files by their magic number (file signature), not just file extension.
Only doc, docx, xls, xlsx, csv, txt, and md files SHALL be accepted.

#### Scenario: User uploads a valid docx file
- **WHEN** user uploads a file whose magic number matches .docx
- **THEN** the system accepts the file

#### Scenario: User uploads a disallowed file type (e.g., .exe renamed to .docx)
- **WHEN** user uploads a file whose magic number indicates an unsupported type
- **THEN** the system SHALL show a popup: "当前仅支持doc、docx、xls、xlsx、csv、txt以及md文件的上传"

### Requirement: File Size Validation
The system SHALL validate that each uploaded file does not exceed 10MB.
For .py files, the limit SHALL also be 10MB per file.

#### Scenario: File size exceeds 10MB
- **WHEN** user uploads a file larger than 10MB
- **THEN** the system SHALL show a popup: "文件大小超过10MB，请修改后重试。"

#### Scenario: File size within limit
- **WHEN** user uploads a file of 5MB
- **THEN** the system SHALL accept the file

### Requirement: File Count Validation
The system SHALL limit the number of files per session to 5.
Users MAY upload up to 5 files in a single operation, or accumulate them across multiple upload actions within the same session.

#### Scenario: File count exceeds 5
- **WHEN** a session already has 5 files and user attempts to upload another
- **THEN** the system SHALL show a popup: "单次最多上传5个文件，请减少选择。"

### Requirement: Duplicate File Detection
The system SHALL detect duplicate files across all sessions for the same user directory.
When a file with the same name already exists, the system SHALL prompt the user for replacement.

#### Scenario: Duplicate file detected
- **WHEN** user uploads a file whose name already exists in their directory
- **THEN** the system SHALL show a popup: "该文件已于{upload_time}上传，是否进行替换？"

#### Scenario: User chooses to replace
- **WHEN** user clicks "Replace" on the duplicate prompt
- **THEN** the system SHALL delete the existing file and store the new one

#### Scenario: User chooses not to replace
- **WHEN** user clicks "Cancel" on the duplicate prompt
- **THEN** the system SHALL reject the new upload

### Requirement: Upload Progress Indicator
The system SHALL display a loading spinner during file upload and parsing.
When upload and parsing complete, the spinner SHALL disappear and the file indicator SHALL change color.

#### Scenario: File uploading
- **WHEN** a file is being uploaded
- **THEN** a spinning indicator is shown on the file entry

#### Scenario: File upload and parse complete
- **WHEN** file upload and parsing are both complete
- **THEN** the spinner disappears and the file entry color changes to indicate readiness

### Requirement: Cancel Upload
The system SHALL allow users to cancel uploaded files by clicking an "x" button on each file entry.
Files that have already been used in the conversation SHALL NOT be cancelable.

#### Scenario: User cancels a pending file
- **WHEN** user clicks "x" on a file that hasn't been used in conversation
- **THEN** the file is removed from the upload queue and will not be referenced in the conversation

#### Scenario: User tries to cancel a used file
- **WHEN** user clicks "x" on a file already referenced in conversation
- **THEN** the cancel action is ignored and the file remains

### Requirement: File Storage on FTP
The system SHALL store uploaded files in the user's dedicated FTP directory.
Files SHALL be named by their original filename.

