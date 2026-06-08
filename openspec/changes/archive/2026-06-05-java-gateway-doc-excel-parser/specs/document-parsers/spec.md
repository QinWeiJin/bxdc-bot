# document-parsers

## Purpose

前端文档解析统一入口（`frontend/src/utils/fileParser.ts`）按文件类型路由到不同的解析实现。本次修改 Word 和 Excel 分支：

- `.docx` → 前端 mammoth 优先，失败时 fallback 到 Java gateway
- `.doc` → 直接走 Java gateway（替换始终失败的 agentFallback）
- `.xlsx` → 前端 SheetJS 优先，失败时 fallback 到 Java gateway
- `.xls` → 直接走 Java gateway（替换始终失败的 agentFallback）
- 移除 `agentFallback` 函数（不再有调用方）

## Requirements

### Requirement: .docx 前端优先 + Java 兜底

`fileParser.ts` 处理 `.docx` 时优先用 mammoth 解析，失败时 fallback 到 Java gateway。

#### Scenario: mammoth 解析成功

- **WHEN** fileType === 'word' 且 ext === '.docx'，mammoth 解析成功
- **THEN** 直接返回 mammoth 的解析结果
- **AND** 不调用 Java gateway

#### Scenario: mammoth 解析失败，fallback Java

- **WHEN** fileType === 'word' 且 ext === '.docx'，mammoth 抛出异常或超过 5 秒未完成
- **THEN** 立刻调用 `parseWord(file)`（gatewayDocParser）
- **AND** POST 到 `/features/file/parse-word`

#### Scenario: mammoth 解析超时，fallback Java

- **WHEN** fileType === 'word' 且 ext === '.docx'，mammoth 超过 5 秒未返回
- **THEN** 中止前端解析，自动 fallback 到 `parseWord(file)`
- **AND** 不等待 mammoth 完成

### Requirement: .doc 直接走 Java gateway

`fileParser.ts` 处理 `.doc` 时不再调用 `agentFallback`，改为调用 Java gateway。

#### Scenario: 上传 .doc

- **WHEN** fileType === 'word' 且 ext === '.doc'
- **THEN** 调用 `parseWord(file)`（gatewayDocParser）
- **AND** POST 到 `/features/file/parse-word`

### Requirement: .xlsx 前端优先 + Java 兜底

#### Scenario: SheetJS 解析成功

- **WHEN** fileType === 'excel' 且 ext === '.xlsx'，SheetJS 解析成功
- **THEN** 直接返回 SheetJS 的解析结果
- **AND** 不调用 Java gateway

#### Scenario: SheetJS 解析失败，fallback Java

- **WHEN** fileType === 'excel' 且 ext === '.xlsx'，SheetJS 抛出异常或超过 5 秒未完成
- **THEN** 立刻调用 `parseExcel(file)`（gatewayExcelParser）
- **AND** POST 到 `/features/file/parse-excel`

#### Scenario: SheetJS 解析超时，fallback Java

- **WHEN** fileType === 'excel' 且 ext === '.xlsx'，SheetJS 超过 5 秒未返回
- **THEN** 中止前端解析，自动 fallback 到 `parseExcel(file)`
- **AND** 不等待 SheetJS 完成

### Requirement: .xls 直接走 Java gateway

#### Scenario: 上传 .xls

- **WHEN** fileType === 'excel' 且 ext === '.xls'
- **THEN** 调用 `parseExcel(file)`（gatewayExcelParser）
- **AND** POST 到 `/features/file/parse-excel`

### Requirement: 移除 agentFallback

`agentFallback` 函数不再被任何分支调用，应删除。

#### Scenario: agentFallback 已移除

- **WHEN** 查看 `fileParser.ts` 源码
- **THEN** 不存在 `agentFallback` 函数定义
- **AND** PPT 分支仍正常工作（不受影响）

### Requirement: 代理路由配置

serve-proxy 和 nginx 必须把 `/features/file/parse-word` 和 `/features/file/parse-excel` 路由到 skill-gateway (18080)。

#### Scenario: 前端 fetch /features/file/parse-word

- **WHEN** 浏览器请求 `http://localhost:8080/features/file/parse-word`
- **THEN** 转发到 `http://127.0.0.1:18080/api/features/file/parse-word`

#### Scenario: 其他 /features/* 请求不受影响

- **WHEN** 浏览器请求 `/features/anything-else`
- **THEN** 仍走 agent-core 3000
