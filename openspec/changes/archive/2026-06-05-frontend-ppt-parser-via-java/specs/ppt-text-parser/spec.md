# ppt-text-parser

## Purpose

skill-gateway 提供 PPT（.ppt + .pptx）文件纯文本提取 HTTP 服务，仅识别文字内容，支持业务场景（产品介绍、培训材料上传）的核心需求。

## Requirements

### Requirement: 端点存在

skill-gateway 必须暴露 `POST /api/features/file/parse-ppt` 端点，接受 multipart/form-data 上传，返回解析后的纯文本。

#### Scenario: 成功解析 .pptx

- **WHEN** client 上传合法 .pptx 文件（≤ 10 MiB）
- **THEN** skill-gateway 返回 HTTP 200，body 为 `{ "text": "...", "slideCount": N }`
- **AND** `text` 包含所有 slide 的文字（slide 间以 `\n` 分隔）

#### Scenario: 成功解析旧格式 .ppt

- **WHEN** client 上传合法 .ppt 文件（≤ 10 MiB）
- **THEN** skill-gateway 返回 HTTP 200，body 为 `{ "text": "...", "slideCount": N }`

#### Scenario: 文件超过 10 MiB

- **WHEN** client 上传 > 10 MiB 的 .pptx
- **THEN** skill-gateway 返回 HTTP 413
- **AND** body 为 `{ "code": "PPT_TOO_LARGE", "message": "...", "maxBytes": 10485760 }`

#### Scenario: 文件损坏

- **WHEN** client 上传损坏的 .pptx（不是合法 ZIP 结构）
- **THEN** skill-gateway 返回 HTTP 400
- **AND** body 为 `{ "code": "PPT_PARSE_ERROR", "message": "..." }`

#### Scenario: 文件被加密

- **WHEN** client 上传加密 .pptx（POI 抛 EncryptedPowerPointFileException）
- **THEN** skill-gateway 返回 HTTP 400
- **AND** body 为 `{ "code": "PPT_ENCRYPTED", "message": "..." }`

#### Scenario: 解析超时

- **WHEN** 解析耗时 > 30 秒
- **THEN** skill-gateway 返回 HTTP 504
- **AND** body 为 `{ "code": "PPT_TIMEOUT", "message": "..." }`

### Requirement: 仅文字输出

PPT 解析结果不得包含图片 / 图表 / 母版等非文字元素的描述，仅返回纯文本。

#### Scenario: PPT 含图片

- **WHEN** 上传的 .pptx 含 1 张图片 + 5 段文字
- **THEN** 响应 `text` 字段不包含图片描述
- **AND** 仅包含 5 段文字内容（用 `\n` 拼接）

### Requirement: 80KB 截断

PPT 解析结果超过 80KB 时在 Java 端截断，避免响应体过大。

#### Scenario: PPT 解析结果 > 80KB

- **WHEN** 解析 .pptx 输出 > 80KB 文本
- **THEN** 响应 `text` 字段被截断到 80KB
- **AND** 末尾追加 `... [内容已截断，原 X KB]` 标记

### Requirement: 仅解析 ppt / pptx

端点必须拒绝非 .ppt / .pptx 文件。

#### Scenario: 上传 .docx

- **WHEN** client 上传 .docx 文件
- **THEN** skill-gateway 返回 HTTP 415
- **AND** body 为 `{ "code": "PPT_UNSUPPORTED_TYPE", "message": "..." }`

#### Scenario: 上传 .pdf

- **WHEN** client 上传 .pdf 文件
- **THEN** skill-gateway 返回 HTTP 415

### Requirement: 安全

- 接口必须仅接受 multipart/form-data 编码
- 文件名不能包含路径穿越（`../`）
- 必须复用 skill-gateway 现有 CORS 配置（不允许额外放宽）
- 必须复用 skill-gateway 现有 SecurityFilter（鉴权）
