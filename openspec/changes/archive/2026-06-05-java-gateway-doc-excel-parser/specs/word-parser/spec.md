# word-parser

## Purpose

skill-gateway 提供 Word（.doc + .docx）文件纯文本提取 HTTP 服务，使用 Apache POI 提取文档中的文字内容。

## Requirements

### Requirement: 端点存在

skill-gateway 必须暴露 `POST /api/features/file/parse-word` 端点，接受 multipart/form-data 上传。

#### Scenario: 成功解析 .docx

- **WHEN** client 上传合法 .docx 文件（≤ 5 MiB）
- **THEN** skill-gateway 返回 HTTP 200，body 为 `{ "text": "...", "pageCount": N }`
- **AND** `text` 包含文档中提取的纯文本

#### Scenario: 成功解析旧格式 .doc

- **WHEN** client 上传合法 .doc 文件（≤ 5 MiB）
- **THEN** skill-gateway 返回 HTTP 200，body 为 `{ "text": "...", "pageCount": N }`

#### Scenario: 文件超过 5 MiB

- **WHEN** client 上传 > 5 MiB 的 .docx
- **THEN** skill-gateway 返回 HTTP 413
- **AND** body 为 `{ "code": "WORD_TOO_LARGE", "message": "文件大小超过限制（最大 5 MiB）", "maxBytes": 5242880 }`

#### Scenario: 文件损坏

- **WHEN** client 上传损坏的 Word 文件
- **THEN** skill-gateway 返回 HTTP 400
- **AND** body 为 `{ "code": "WORD_PARSE_ERROR", "message": "Word 文档解析失败：..." }`

#### Scenario: 不支持的文件类型

- **WHEN** client 上传非 .doc/.docx 文件
- **THEN** skill-gateway 返回 HTTP 400
- **AND** body 为 `{ "code": "WORD_UNSUPPORTED_TYPE", "message": "仅支持 .doc 和 .docx 格式" }`

### Requirement: 仅文字输出

Word 解析结果不得包含格式标记、样式、元数据，仅返回纯文本。

### Requirement: 80KB 截断

解析结果超过 80KB 时在 Java 端截断，末尾追加 `... [内容已截断，原 X KB]` 标记。
