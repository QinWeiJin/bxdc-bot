# excel-parser

## Purpose

skill-gateway 提供 Excel（.xls + .xlsx）文件纯文本提取 HTTP 服务，使用 Apache POI 遍历所有 sheet 并输出 tab 分隔的文本。

## Requirements

### Requirement: 端点存在

skill-gateway 必须暴露 `POST /api/features/file/parse-excel` 端点，接受 multipart/form-data 上传。

#### Scenario: 成功解析 .xlsx

- **WHEN** client 上传合法 .xlsx 文件（≤ 1 MiB）
- **THEN** skill-gateway 返回 HTTP 200，body 为 `{ "text": "...", "sheetCount": N }`
- **AND** `text` 格式为每个 sheet 以 `--- Sheet: {name} ---` 开头，内容为 tab 分隔的表格文本

#### Scenario: 成功解析旧格式 .xls

- **WHEN** client 上传合法 .xls 文件（≤ 1 MiB）
- **THEN** skill-gateway 返回 HTTP 200，body 为 `{ "text": "...", "sheetCount": N }`

#### Scenario: 多 sheet 工作簿

- **WHEN** client 上传包含多个 sheet 的 .xlsx 文件
- **THEN** 响应 `text` 包含所有 sheet
- **AND** 每个 sheet 以 `--- Sheet: {name} ---` 标题分隔
- **AND** sheet 之间以空行分隔

#### Scenario: 含空单元格

- **WHEN** sheet 包含空单元格
- **THEN** 空单元格输出空字符串，列之间用 tab 保持对齐

#### Scenario: 文件超过 1 MiB

- **WHEN** client 上传 > 1 MiB 的 .xlsx
- **THEN** skill-gateway 返回 HTTP 413
- **AND** body 为 `{ "code": "EXCEL_TOO_LARGE", "message": "文件大小超过限制（最大 1 MiB）", "maxBytes": 1048576 }`

#### Scenario: 文件损坏

- **WHEN** client 上传损坏的 .xlsx 文件
- **THEN** skill-gateway 返回 HTTP 400
- **AND** body 为 `{ "code": "EXCEL_PARSE_ERROR", "message": "Excel 文件解析失败：..." }`

#### Scenario: 不支持的文件类型

- **WHEN** client 上传非 .xls/.xlsx 文件
- **THEN** skill-gateway 返回 HTTP 400
- **AND** body 为 `{ "code": "EXCEL_UNSUPPORTED_TYPE", "message": "仅支持 .xls 和 .xlsx 格式" }`

### Requirement: 80KB 截断

解析结果超过 80KB 时在 Java 端截断，末尾追加 `... [内容已截断，原 X KB]` 标记。
