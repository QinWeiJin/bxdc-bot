# Design: java-gateway-doc-excel-parser

## Context

### 现状
- 前端 docx/xlsx 使用 mammoth.js / SheetJS 解析，doc/xls 走 agentFallback 始终失败
- PPT 解析已迁移到 Java gateway（`POST /api/features/file/parse-ppt`）
- skill-gateway 已有 POI 5.2.3 全套依赖（poi-ooxml + poi-scratchpad），包含 HWPF/XWPF
- 前端 `fileParser.ts` 按 FileType + 扩展名分派解析器
- 限额在前端 `FILE_UPLOAD_CONFIG` 中：Word ≤ 5MB/个, Excel ≤ 1MB/个

### 选型

**Apache POI 5.2.3**（已在 skill-gateway 中）：
- **Word**：
  - `.docx` → `XWPFDocument` + `XWPFWordExtractor`（poi-ooxml 已引入）
  - `.doc` → `HWPFDocument` + `WordExtractor`（poi-scratchpad 已引入）
- **Excel**：
  - `.xlsx` → `XSSFWorkbook` → 遍历 sheet/row/cell（poi-ooxml 已引入）
  - `.xls` → `HSSFWorkbook` → 遍历 sheet/row/cell（poi 核心包已引入）

## Decisions

### 1. 端点设计

| 端点 | 方法 | 格式支持 | 限额 |
|------|------|---------|------|
| `POST /api/features/file/parse-word` | multipart/form-data | .doc, .docx | ≤ 5 MiB |
| `POST /api/features/file/parse-excel` | multipart/form-data | .xls, .xlsx | ≤ 1 MiB |

响应格式统一：
```json
// 成功
{ "text": "提取的纯文本", "pageCount": N }          // Word
{ "text": "表格文本", "sheetCount": N }               // Excel

// 失败
{ "code": "WORD_TOO_LARGE", "message": "..." }
{ "code": "EXCEL_PARSE_ERROR", "message": "..." }
```

### 2. Word 解析策略

**`.docx`（OOXML）**：
```java
try (XWPFDocument doc = new XWPFDocument(bais)) {
    XWPFWordExtractor extractor = new XWPFWordExtractor(doc);
    String text = extractor.getText();
}
```

**`.doc`（二进制）**：
```java
try (HWPFDocument doc = new HWPFDocument(bais)) {
    WordExtractor extractor = new WordExtractor(doc);
    String text = extractor.getText();
}
```

### 3. Excel 解析策略

遍历所有 sheet，每行用 tab 拼接单元格，sheet 之间空行分隔。

```java
try (XSSFWorkbook wb = new XSSFWorkbook(bais)) {  // .xlsx
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < wb.getNumberOfSheets(); i++) {
        Sheet sheet = wb.getSheetAt(i);
        sb.append("--- Sheet: ").append(sheet.getSheetName()).append(" ---\n");
        for (Row row : sheet) {
            for (Cell cell : row) { /* toString + \t */ }
            sb.append("\n");
        }
        sb.append("\n");
    }
}
```

空单元格输出空字符串，保持列对齐（tab 分隔）。

### 4. 前端路由改造（关键）

`.docx` / `.xlsx` **双层策略**：先前端解析，**解析失败或超时（5s）** 立刻 fallback 到 Java gateway。

```ts
const FRONTEND_PARSE_TIMEOUT_MS = 5_000

async function parseWithFallback<T>(
  frontendFn: () => Promise<T>,
  fallbackFn: () => Promise<T>,
): Promise<T> {
  try {
    return await Promise.race([
      frontendFn(),
      new Promise<never>((_, reject) =>
        setTimeout(() => reject(new Error('前端解析超时')), FRONTEND_PARSE_TIMEOUT_MS)
      ),
    ])
  } catch {
    return fallbackFn()
  }
}

// Word
if (fileType === 'word') {
  if (ext === '.docx') {
    return parseWithFallback(
      async () => { const { parseDocx } = await import('./docxParser'); return parseDocx(file) },
      async () => { const { parseWord } = await import('./gatewayDocParser'); return parseWord(file, signal) },
    )
  }
  const { parseWord } = await import('./gatewayDocParser')
  return parseWord(file, signal)
}

// Excel
if (fileType === 'excel') {
  if (ext === '.xlsx') {
    return parseWithFallback(
      async () => { const { parseXlsx } = await import('./xlsxParser'); return parseXlsx(file) },
      async () => { const { parseExcel } = await import('./gatewayExcelParser'); return parseExcel(file, signal) },
    )
  }
  const { parseExcel } = await import('./gatewayExcelParser')
  return parseExcel(file, signal)
}
```

### 5. serve-proxy / nginx 路由

规则必须在 `/features/` 之前：

```js
// serve-proxy.js PROXY_RULES
{ prefix: '/features/file/parse-word', target: 'http://127.0.0.1:18080', rewrite: (url) => url.replace('/features', '/api/features') },
{ prefix: '/features/file/parse-excel', target: 'http://127.0.0.1:18080', rewrite: (url) => url.replace('/features', '/api/features') },
{ prefix: '/features/file/parse-ppt', target: '...' },  // 已有
{ prefix: '/features', target: 'http://127.0.0.1:3000' },
```

### 6. 前端解析器文件

- 新建 `gatewayDocParser.ts`：封装 `POST /features/file/parse-word`
- 新建 `gatewayExcelParser.ts`：封装 `POST /features/file/parse-excel`
- 保留 `docxParser.ts` / `xlsxParser.ts`：作为优先方案
- 删除 `agentFallback` 函数（不再有调用方）

### 7. 文本截断

与 PPT 解析一致，在 Java 端应用 80KB 截断（`TEXT_MAX_BYTES = 80 * 1024`），超限追加 `... [内容已截断，原 X KB]`。

## Risks

- **风险**：`.doc` 旧格式解析质量 — HWPF 对复杂 .doc 文件支持不如 XWPF 完善
  - **缓解**：仅提取纯文本（`WordExtractor.getText()`），不解析复杂排版
- **风险**：Excel 大表格内存 — 遍历所有 cell 可能导致大对象
  - **缓解**：Excel 限额只有 1MB/个，POI 对 1MB 以内文件的内存占用可控

## Open Questions

- `.docx` / `.xlsx` 是否完全移除前端解析，直接 100% 走 Java？还是保持「前端优先 + Java 兜底」？
  - **决策**：保持「前端优先 + Java 兜底」，因为 mammoth 对 .docx 的提取质量优于 POI 的简单 `getText()`。
