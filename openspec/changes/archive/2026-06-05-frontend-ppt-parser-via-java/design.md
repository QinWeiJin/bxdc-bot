## Context

### 现状
- 前端 ppt 文件 → `fileParser.ts` → `agentFallback` → `POST http://127.0.0.1:3000/features/file/parse-document`
- agent-core 没有该端点 → catch 抛错 → useFileUpload 设 status=`failed`
- serve-proxy 已有 `/features` 路由（指向 agent-core 3000）

### 业务要求（用户确认）
- PPT (.ppt + .pptx) 仅识别文字（不需要图片 / 图表 / 母版）
- 数量 ≤ 3，单文件 ≤ 10 MiB，总体 ≤ 30 MiB
- 前端直接访问 Java 接口（不走 agent-core）

### 选型
**Apache POI 5.2.x**（Java 8 兼容）：
- `.pptx` → `org.apache.poi.xslf.usermodel.XMLSlideShow` + `XSLFExtractor` 提取所有 slide 文本
- `.ppt`（旧二进制）→ `org.apache.poi.hslf.usermodel.HSLFSlideShow` + `HSLFExtractor`
- 纯文本（`XSLFExtractor.getText()`）已满足「仅识别文字」需求
- 依赖：`poi-ooxml`（含 XSLF + OPC 协议）+ `poi-scratchpad`（含 HSLF）
- 体积：~6MB，已是 skill-gateway 项目的标准依赖

### 关键约束
- skill-gateway 当前是 **Spring Boot 2.7 + JDK 8**（参考 `pom.xml`）
- 选 POI 5.2.x 兼容 Java 8（POI 5.3+ 要求 Java 11）
- 大文件解析不阻塞 Tomcat 线程：用 `@Async` 或显式 `ThreadPoolTaskExecutor` 不可行（30s 内完成，直接同步即可）
- 错误处理：PPT 损坏 / 加密 / 超时 → 返回 4xx + 错误信息（前端 catch 后显示「失败」）

## Goals / Non-Goals

**Goals:**
- skill-gateway 提供 `POST /api/features/file/parse-ppt` 端点
- 支持 .ppt + .pptx，仅返回纯文本
- 单文件 ≤ 10 MiB（Java 端二次校验），超过返回 413
- 解析 30 秒超时（Tomcat 端 read timeout + 应用层 `Future.get(30s)`）
- 前端 ppt 分支从 `agentFallback` 切换到新端点
- serve-proxy `/features/file/parse-ppt` 路由指向 skill-gateway 18080

**Non-Goals:**
- 不实现图片 / 图表 / 母版识别（仅文字）
- 不实现 PPT 内容审计（任务 10 范围）
- 不修改 doc / xls 解析（仍走 agentFallback 失败）
- 不重写前端文件上传 UI（限额已在 FILE_UPLOAD_CONFIG 中）

## Decisions

### 1. Apache POI 选 5.2.5（最后兼容 JDK 8 的版本）

```xml
<dependency>
  <groupId>org.apache.poi</groupId>
  <artifactId>poi-ooxml</artifactId>
  <version>5.2.5</version>
</dependency>
<dependency>
  <groupId>org.apache.poi</groupId>
  <artifactId>poi-scratchpad</artifactId>
  <version>5.2.5</version>
</dependency>
```

POI 5.2.5 是 POI 5 系列最后兼容 JDK 8 的版本（5.3+ 升到 Java 11）。skill-gateway 仍在 JDK 8（见 `pom.xml` 的 `maven.compiler.source`）。

### 2. 端点路径：`POST /api/features/file/parse-ppt`

- `/api` 前缀：与 skill-gateway 其他端点一致
- `/features/file/parse-ppt`：与原 `agentFallback` 路径对齐，前端改动最小（只换 host 即可）
- 响应：`{ "text": "...", "slideCount": N }`

### 3. 仅返回纯文本（XSLFExtractor.getText()）

```java
try (XMLSlideShow ppt = new XMLSlideShow(fis)) {
  XSLFExtractor extractor = new XSLFExtractor(ppt);
  String text = extractor.getText();
  int slideCount = ppt.getSlides().size();
  return new PptParseResponse(text, slideCount);
}
```

`XSLFExtractor.getText()` 会自动按 slide 顺序拼接，slide 之间用 `\n` 分隔。`extractAll()` 不需要（仅文字）。

### 4. 大小校验双层

- **前端**（已有）：FILE_UPLOAD_CONFIG 限额，校验失败时不让上传
- **Java**（新增）：Multipart 接收后检查 `file.getSize() > 10 MiB` → 返回 413

双层防御避免恶意请求绕过前端。

### 5. 路由：serve-proxy 单独配置 `/features/file/parse-ppt`

```js
const PROXY_RULES = [
  { prefix: '/api/conversation/files', target: 'http://127.0.0.1:3000' },
  { prefix: '/api', target: 'http://127.0.0.1:18080' },
  { prefix: '/features/file/parse-ppt', target: 'http://127.0.0.1:18080' },  // 新增
  { prefix: '/features', target: 'http://127.0.0.1:3000' },
  { prefix: '/agent', target: 'http://127.0.0.1:3000' },
];
```

**关键**：`/features/file/parse-ppt` 规则必须放在 `/features` 之前（`startsWith` 匹配是按顺序的）。

### 6. 前端 pptParser.ts：直接 fetch 走 skill-gateway

```ts
// frontend/src/utils/pptParser.ts
import { agentUrl } from '@/services/config'

export async function parsePpt(file: File, signal?: AbortSignal): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch(agentUrl('/features/file/parse-ppt'), {
    method: 'POST',
    body: form,
    signal,
  })
  if (!res.ok) {
    const err = await res.text().catch(() => '')
    throw new Error(`PPT 解析失败：${res.status} ${err.slice(0, 100)}`)
  }
  const data = await res.json()
  return data.text as string
}
```

`agentUrl` 当前是 `'/'`（相对路径）+ 前端 base URL，自动经 serve-proxy 转发到 18080。

### 7. 错误响应格式

```json
// 4xx
{ "code": "PPT_TOO_LARGE", "message": "文件超过 10 MiB", "maxBytes": 10485760 }
// 5xx
{ "code": "PPT_PARSE_ERROR", "message": "PPT 文件损坏：xxx", "details": "..." }
```

前端 `parsePpt` 把 `code` + `message` 拼到抛出的 Error 中，让 useFileUpload 写入 `errorMessage` 字段。

## Risks / Trade-offs

- **风险**：POI 解析大 PPT（30 MiB）内存峰值可能 200MB+ → skill-gateway 默认 `-Xmx` 是多少？
  - **缓解**：查 skill-gateway `pom.xml` / `start.sh` 的 JVM 参数；如未设，加上 `-Xmx1024m`
- **风险**：POI 对损坏 PPT 容错差，可能直接抛 `Exception` 让 Tomcat 500
  - **缓解**：用 `try-with-resources` + catch `POIXMLException` / `EncryptedPowerPointFileException` 等，转换为自定义 4xx 响应
- **风险**：服务重启时 POI 类加载首次慢（~3s）
  - **缓解**：skill-gateway 已有 `StartupRecoveryRunner` 等预热；考虑在 `ApplicationReadyEvent` 中执行一次 dummy parse
- **权衡**：只支持文字（不识别图表 / 图片）— 业务明确要求，符合预期
- **权衡**：在 Java 端处理（而非 agent-core）— 减少链路，简化部署
- **权衡**：不优化 doc / xls（旧二进制）解析 — 留待后续 change

## Open Questions

- 是否要复用前端的 `PARSED_TEXT_MAX_BYTES`（80KB）做 PPT 解析结果的截断？
  - **决策**：在 Java 端就截断，避免网络传输浪费。设 `PPT_TEXT_MAX_BYTES = 80 * 1024`，超出追加 `... [已截断]` 标记
- skill-gateway 是否需要单独的限流（如每用户每分钟 10 次）？
  - **决策**：本次不做，依赖 Spring Security 的认证 + 前端限额。如发现滥用再补
- 是否做 PptParserController 的 OpenAPI 文档（springdoc）？
  - **决策**：本次不做（项目暂无 springdoc 依赖），手动维护接口描述
