## Why

当前文件上传功能对 `.ppt` / `.pptx` 的解析走 `agentFallback`（POST `agent-core/features/file/parse-document`），**agent-core 没有该端点**。结果：
- 用户上传 PPT 文件 → 解析失败（status=`failed`）
- PPT 是业务核心场景之一（产品介绍、培训材料）
- 之前规划用 agent-core 解析（任务 9-10）依赖 Python + Java 链路，工程复杂度高、迭代慢

**业务需求**（已确认，参考 `frontend/src/types/fileUpload.ts` 限额表）：
- PPT (.ppt + .pptx) **仅识别文字**（不需要图片 / 图表 / 动画 / 母版）
- 数量 ≤ 3 个
- 单文件 ≤ 10 MiB
- 总体 ≤ 30 MiB

**新方向**：直接用 Java（skill-gateway）实现 PPT 文本解析，前端 `parseDocument` 通过 `agentUrl('/features/file/parse-ppt')` 调用 skill-gateway 的新端点。**绕过 agent-core**。

**好处**：
- skill-gateway 已用 Java、Spring Boot、Maven，零新技术栈
- 前端只改 1 处路由（`fileParser.ts` 走 skill-gateway）
- 限额 / 解析逻辑全在 Java 一处实现，易维护

## What Changes

- **新增 capability** `ppt-text-parser`：skill-gateway 提供 PPT 纯文本解析 HTTP 接口
- **修改 capability** `document-parsers`：前端 PPT 解析从 `agentFallback` 切换到 skill-gateway 直接调用
- **新增能力**：skill-gateway `POST /api/features/file/parse-ppt`
  - 请求：multipart/form-data，字段名 `file`（.ppt / .pptx）
  - 响应：`{ "text": "...", "slideCount": N }`
  - 仅提取文字（`org.apache.poi.xslf.XSLFExtractor` / `HSLFExtractor`）
  - 30 秒超时
  - 大小校验在 Java 端做（≤ 10 MiB）
- **前端改造**：
  - `fileParser.ts`：ppt 分支调用 `agentUrl('/features/file/parse-ppt')` 替代 `agentFallback`
  - `serve-proxy.js`：把 `/features` 路由目标改为 skill-gateway（18080）而非 agent-core（3000）
    - 或者更安全：**只把 `/features/file/parse-ppt` 单独指向 skill-gateway**，保留其他 `/features/*` 指向 agent-core
- **限额表保持不变**（`FILE_UPLOAD_CONFIG.ppt` 已正确：count 3、size 10 MiB、total 30 MiB）

## Capabilities

### New Capabilities
- `ppt-text-parser`：Java 端 PPT（pptx + ppt）纯文本提取 HTTP 服务

### Modified Capabilities
- `document-parsers`：前端 PPT 解析从 agent-core 兜底改为直接调用 skill-gateway

## Impact

**新增/修改的文件**：
- `backend/skill-gateway/pom.xml`：新增 Apache POI 依赖（`poi-ooxml` + `poi-scratchpad`）
- `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/controller/PptParserController.java`：新增
- `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/PptParserService.java`：新增
- `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/dto/PptParseResponse.java`：新增
- `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/exception/ParseErrorCode.java`：新增（错误码）
- `frontend/src/utils/fileParser.ts`：ppt 分支改用 `parsePptViaJava`
- `frontend/src/utils/pptParser.ts`：新增（封装 fetch 调用）
- `serve-proxy.js`：调整 `/features/file/parse-ppt` 路由目标

**影响范围**：
- 解析：ppt 文件从「失败」变「成功」
- 性能：单 PPT < 30s 解析完成（与原 agentFallback 相当）
- CORS：skill-gateway 已有 `SecurityConfig` 配 CORS，PPT 端点不需额外
- 限额：前端校验已做，Java 端仅做防御性二次校验
- 不影响 docx / xlsx / txt / md（仍走原解析路径）
- 不影响 doc / xls（仍走 agentFallback 失败，本次不修）

**依赖版本**：
- Apache POI 5.x（Java 8 兼容 → 5.2.x，参考 skill-gateway 现有 JDK 8 配置）
