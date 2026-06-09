# Proposal: java-gateway-doc-excel-parser

## 动机

当前文档解析存在以下问题：

1. **`.doc` / `.xls` 旧格式无法解析**：当前走 `agentFallback` → `POST /features/file/parse-document`，但 agent-core 没有该端点，导致始终失败。
2. **`.docx` / `.xlsx` 前端解析不稳定**：依赖浏览器端 mammoth.js 和 SheetJS，部分场景（如大文件、复杂排版）可能失败，缺少兜底机制。
3. **PPT 解析已成功迁移到 Java gateway**（上期 change `frontend-ppt-parser-via-java`），验证了「Java gateway + Apache POI」方案可行。

## 目标

1. 在 skill-gateway 中新增两个端点，统一处理 Word 和 Excel 文件的纯文本提取
2. `.docx` / `.xlsx` 前端解析失败时**自动 fallback 到 Java gateway**（前端优先，Java 兜底）
3. `.doc` / `.xls` 旧格式**直接走 Java gateway**（替换掉始终失败的 agentFallback）
4. 移除对 agent-core 解析端点的依赖，统一使用 Java gateway 作为文档解析方案

## 影响范围

| 模块 | 影响 |
|------|------|
| skill-gateway (Java) | 新增 WordParserService / WordParserController、ExcelParserService / ExcelParserController |
| pom.xml | 无需新增依赖（POI 5.2.3 已在 PPT 解析项目中添加，包含 HWPF/XWPF 全套） |
| serve-proxy.js | 新增 `/features/file/parse-word` 和 `/features/file/parse-excel` 代理规则 |
| nginx | 新增对应 location 规则 |
| vite.config.ts | 新增开发代理规则 |
| fileParser.ts | 改造路由逻辑：.docx/.xlsx 前端优先 + Java 兜底；.doc/.xls 直接走 Java |
| docxParser.ts / xlsxParser.ts | 无须修改（仅作为优先方案） |
| types/fileUpload.ts | 无须修改（限额已有） |

## Non-Goals

- 不修改 .txt / .md 解析路径（仍走前端 FileReader）
- 不修改 PPT 解析路径（上期已完成）
- 不修改文件上传 UI 和限额配置
- 不新增 OCR / 图片解析能力
- Word/Excel 仅提取纯文本，不保留格式、表格结构、样式
