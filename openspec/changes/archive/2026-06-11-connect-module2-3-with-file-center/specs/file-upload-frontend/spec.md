# file-upload-frontend（delta）

本文件是 `connect-module2-3-with-file-center` change 的 delta spec。
`module2-file-upload` 尚未归档到主 spec 树，本 delta 通过 `## ADDED Requirements` 直接为该 capability 添加新要求（archive `module2-file-upload` 时这些 ADDED 会与 module2-file-upload 的 delta 合并）。

---

## ADDED Requirements

### Requirement: 解析委托给 gateway 后端

The system SHALL delegate gateway 后端做 doc/docx/txt/md/py 五个类型的解析，**禁止在前端使用 mammoth / SheetJS / FileReader 等做内容提取**。前端只负责：
1. 把文件 multipart 上传到 gateway `POST /api/files/upload`
2. 等待 gateway 返回 `parsed_summary` JSON
3. 把 `parsed_summary` 写入 `UploadFileInfo.parsedText`

#### Scenario: docx 走 gateway
- **WHEN** 上传合法 `.docx` 文件
- **THEN** 前端 SHALL 调用 `POST /api/files/upload`（multipart + `X-User-Id` header）
- **AND** SHALL 等待 gateway 返回 `parsed_summary`（wgj `WordParser` POI 解析）
- **AND** SHALL 写入 `UploadFileInfo.parsedText`
- **AND** SHALL 设置 `status='parsed'`

#### Scenario: txt/md 走 gateway
- **WHEN** 上传合法 `.txt` 或 `.md` 文件
- **THEN** 前端 SHALL 调 gateway 解析
- **AND** 解析结果 SHALL 包含**自上而下 500 字**摘要（wgj `TxtMdParser`，与需求方案 A1 §3.2.3 对齐）

#### Scenario: py 走 gateway 全量
- **WHEN** 上传合法 `.py` 文件
- **THEN** 前端 SHALL 调 gateway 解析
- **AND** 解析结果 SHALL 包含**完整内容**（不截取，wgj `PyParser`，与需求方案 A1 §3.2.4 对齐）

#### Scenario: 解析失败显示错误
- **WHEN** gateway 返回 4xx/5xx（魔数校验失败 / 类型不支持 / FTP 不可用）
- **THEN** 前端 SHALL 设置 `status='failed'`
- **AND** SHALL 把 gateway 错误消息（中文）写入 `UploadFileInfo.errorMessage`

#### Scenario: Excel/CSV 暂未实现时返回空 parsed_summary
- **WHEN** 上传 `.xls`/`.xlsx`/`.csv` 且 wgj 的 `FileParserRouter` 没有对应 parser
- **THEN** 前端 SHALL 仍把文件上传成功（status='parsed'）
- **AND** `parsedText` SHALL 为空字符串（不是错误状态）
- **AND** 文件 SHALL 已存入 FTP + `user_files` 表（仅 `parsed_summary` 为空）

---

## ADDED Requirements

### Requirement: 限额配置（已补 .py 支持）

The system SHALL configure `FILE_UPLOAD_CONFIG` per the following table. 需求方案 A1 §2.2.1 明确支持 `.py`，本 change MUST 在 `allowedExtensions.txt` 中加入 `.py`，`unsupportedMessage` 同步更新。

> 原文允许列表只有 `.txt` 和 `.md`，需求方案 A1 §2.2.1 明确支持 `.py`，本 change 同步。

```ts
{
  maxFileSizeMib: { word: 10, excel: 10, ppt: 10, txt: 10, image: 10 },
  maxFilesPerSession: 5,
  allowedExtensions: {
    word: ['.doc', '.docx'],
    excel: ['.xls', '.xlsx', '.csv'],
    ppt: ['.ppt', '.pptx'],
    txt: ['.txt', '.md', '.py'],
    image: ['.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg'],
  },
  unsupportedMessage: '当前仅支持doc、docx、xls、xlsx、csv、txt、md、py文件的上传',
  fileTooLargeMessage: '文件大小超过10Mb，请修改后重试。',
  tooManyFilesMessage: '单次最多上传5个文件，请减少选择。',
}
```

#### Scenario: py 文件被接受为 txt 类型
- **WHEN** 上传 `script.py` 且 `fileType === 'txt'`
- **THEN** 类型校验通过
- **AND** 解析 SHALL 走 gateway 后端 `PyParser`（不是前端 FileReader）
