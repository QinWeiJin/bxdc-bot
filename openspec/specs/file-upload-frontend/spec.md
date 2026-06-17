# file-upload-frontend Specification

## Purpose
TBD - created by archiving change connect-module2-3-with-file-center. Update Purpose after archive.
## Requirements
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

