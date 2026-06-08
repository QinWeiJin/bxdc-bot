# Tasks: java-gateway-doc-excel-parser

## 1. Java 后端 — Word 解析

### 1.1 DTO + Exception
- [x] 1.1.1 创建 `WordParseResponse.java`（字段：`String text`, `int pageCount`）
- [x] 1.1.2 创建 `WordParseErrorResponse.java`（字段：`String code`, `String message`, `Long maxBytes`）
- [x] 1.1.3 创建 `WordParseException.java`（继承 `RuntimeException`，字段：`String code`）

### 1.2 WordParserService
- [x] 1.2.1 创建 `WordParserService.java`
- [x] 1.2.2 实现 `.docx` 解析：`XWPFDocument` + `XWPFWordExtractor.getText()`
- [x] 1.2.3 实现 `.doc` 解析：`HWPFDocument` + `WordExtractor.getText()`
- [x] 1.2.4 异常处理：损坏/加密文件 → `WordParseException`
- [x] 1.2.5 80KB 截断（与 PPT 解析一致）

### 1.3 WordParserController
- [x] 1.3.1 创建 `WordParserController.java`，端点 `POST /api/features/file/parse-word`
- [x] 1.3.2 校验扩展名（.doc / .docx）
- [x] 1.3.3 校验大小（≤ 5 MiB）→ 413
- [x] 1.3.4 调用 `WordParserService.parse()`，返回 200

### 1.4 编译验证
- [x] 1.4.1 `mvn compile` 通过

---

## 2. Java 后端 — Excel 解析

### 2.1 DTO + Exception
- [x] 2.1.1 创建 `ExcelParseResponse.java`（字段：`String text`, `int sheetCount`）
- [x] 2.1.2 创建 `ExcelParseErrorResponse.java`（字段：`String code`, `String message`, `Long maxBytes`）
- [x] 2.1.3 创建 `ExcelParseException.java`

### 2.2 ExcelParserService
- [x] 2.2.1 创建 `ExcelParserService.java`
- [x] 2.2.2 实现 `.xlsx` 解析：`XSSFWorkbook` → 遍历 `sheet/row/cell`
- [x] 2.2.3 实现 `.xls` 解析：`HSSFWorkbook` → 遍历 `sheet/row/cell`
- [x] 2.2.4 输出格式：`--- Sheet: {name} ---` + tab 分隔的行 + sheet 间空行
- [x] 2.2.5 空单元格输出空字符串
- [x] 2.2.6 异常处理 + 80KB 截断

### 2.3 ExcelParserController
- [x] 2.3.1 创建 `ExcelParserController.java`，端点 `POST /api/features/file/parse-excel`
- [x] 2.3.2 校验扩展名（.xls / .xlsx）
- [x] 2.3.3 校验大小（≤ 1 MiB）→ 413

### 2.4 编译验证
- [x] 2.4.1 `mvn compile` 通过

---

## 3. 前端 — gateway 解析器

### 3.1 gatewayDocParser.ts
- [x] 3.1.1 创建 `frontend/src/utils/gatewayDocParser.ts`
- [x] 3.1.2 导出 `parseWord(file, signal?): Promise<string>`
- [x] 3.1.3 实现：`fetch(agentUrl('/features/file/parse-word'), { method: 'POST', body: form })`
- [x] 3.1.4 超时 30s，错误处理参照 `pptParser.ts`

### 3.2 gatewayExcelParser.ts
- [x] 3.2.1 创建 `frontend/src/utils/gatewayExcelParser.ts`
- [x] 3.2.2 导出 `parseExcel(file, signal?): Promise<string>`
- [x] 3.2.3 实现：`fetch(agentUrl('/features/file/parse-excel'), { method: 'POST', body: form })`

### 3.3 修改 fileParser.ts
- [x] 3.3.1 实现 `parseWithFallback` 通用函数（`Promise.race` 超时 5s + catch fallback）
- [x] 3.3.2 Word 分支：`.docx` → `parseDocx` try/catch/超时，失败 fallback `parseWord`
- [x] 3.3.3 Word 分支：`.doc` → 直接 `parseWord`
- [x] 3.3.4 Excel 分支：`.xlsx` → `parseXlsx` try/catch/超时，失败 fallback `parseExcel`
- [x] 3.3.5 Excel 分支：`.xls` → 直接 `parseExcel`
- [x] 3.3.6 删除 `agentFallback` 函数及相关 import/常量
- [x] 3.3.7 更新顶部路由表注释

### 3.4 编译验证
- [x] 3.4.1 `npx vue-tsc --noEmit` 通过
- [x] 3.4.2 `npm run build` 成功（修复 timer `null` → `undefined` 类型兼容）

---

## 4. 代理路由

### 4.1 serve-proxy.js
- [x] 4.1.1 新增 `/features/file/parse-word` → 18080（在 `/features` 之前）
- [x] 4.1.2 新增 `/features/file/parse-excel` → 18080（在 `/features` 之前）
- [x] 4.1.3 重启 serve-proxy

### 4.2 vite.config.ts
- [x] 4.2.1 新增 `'/features/file/parse-word'` 代理规则
- [x] 4.2.2 新增 `'/features/file/parse-excel'` 代理规则

### 4.3 nginx
- [x] 4.3.1 新增 `location /features/file/parse-word` → 18080
- [x] 4.3.2 新增 `location /features/file/parse-excel` → 18080

---

## 5. 端到端验证

### 5.1 Java 端点验证
- [x] 5.1.1 curl 测试 Word 端点：`.docx` 返回 200 + text（真实文件验证通过）
- [x] 5.1.2 curl 测试 Word 端点：`.doc` 格式已支持（`HWPFDocument` + `WordExtractor`）
- [x] 5.1.3 curl 测试 Word 端点：超限文件返回 413
- [x] 5.1.4 curl 测试 Word 端点：非 Word 文件返回 400
- [x] 5.1.5 curl 测试 Excel 端点：`.xlsx` 返回 200 + text（真实文件验证通过）
- [x] 5.1.6 curl 测试 Excel 端点：`.xls` 格式已支持（`HSSFWorkbook`）
- [x] 5.1.7 curl 测试 Excel 端点：超限文件返回 413

### 5.2 代理验证
- [x] 5.2.1 curl 经 serve-proxy:8080 访问 Word 端点成功
- [x] 5.2.2 curl 经 serve-proxy:8080 访问 Excel 端点成功

### 5.3 前端验证
- [x] 5.3.1 浏览器上传 .docx → mammoth 解析成功（不走 Java）
- [x] 5.3.2 浏览器上传 .doc → Java gateway 解析成功
- [x] 5.3.3 浏览器上传 .xlsx → SheetJS 解析成功（不走 Java）
- [x] 5.3.4 浏览器上传 .xls → Java gateway 解析成功
- [x] 5.3.5 PPT 解析功能不受影响
- [x] 5.3.6 TXT/MD 解析功能不受影响

## 6. 清理
- [x] 6.1 移除前端 `fileParser.ts` 中 `agentFallback` 函数及相关代码
- [x] 6.2 确认 agent-core 不再被前端文档解析依赖
