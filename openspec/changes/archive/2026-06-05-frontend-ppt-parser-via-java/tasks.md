## 0. 前置配置（必须）

- [x] 0.1 `backend/skill-gateway/src/main/resources/application.properties` 新增 multipart 上限：
  - `spring.servlet.multipart.max-file-size=11MB`
  - `spring.servlet.multipart.max-request-size=11MB`
- [x] 0.2 `start.sh` 启动 skill-gateway 时加 `-Xmx1024m`（POI 解析 30MB PPT 需要更多堆）
- [x] 0.3 SecurityConfig / CORS 验证：`.anyRequest().permitAll()` 已放行 `/api/features/**`；`app.cors.allowed-origins=http://localhost:*` 已覆盖 8080 → **无需修改**

## 1. Java 后端

### 1.1 添加 Apache POI 依赖
- [ ] 1.1.1 在 `backend/skill-gateway/pom.xml` 添加：
  - `org.apache.poi:poi-ooxml:5.2.5`
  - `org.apache.poi:poi-scratchpad:5.2.5`
  - 检查 skill-gateway 当前 JDK 版本（应是 8）
- [ ] 1.1.2 运行 `mvn -pl backend/skill-gateway dependency:tree | grep poi` 验证依赖下载成功

### 1.2 创建 PptParserService
- [ ] 1.2.1 在 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/PptParserService.java` 新建 service
- [ ] 1.2.2 实现 `parse(byte[] fileBytes, String fileName) -> PptParseResult`
  - 根据扩展名分派 `XSLFExtractor`（.pptx）或 `HSLFExtractor`（.ppt）
  - 仅返回 `extractor.getText()`，不返回图片
  - 用 try-with-resources 关闭 InputStream
- [ ] 1.2.3 添加 `PPT_TEXT_MAX_BYTES = 80 * 1024` 常量；超过时在 `text` 末尾追加 `\n... [内容已截断，原 X KB]`
- [ ] 1.2.4 捕获异常：
  - `POIXMLException` / `EncryptedPowerPointFileException` → 抛 `PptParseException(code=PPT_ENCRYPTED)`
  - `ZipException` / `InvalidFormatException` → 抛 `PptParseException(code=PPT_PARSE_ERROR)`
  - 其他 → 抛 `PptParseException(code=PPT_PARSE_ERROR, message=ex.getMessage())`

### 1.3 创建 PptParseResponse DTO
- [ ] 1.3.1 在 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/dto/PptParseResponse.java` 新建
- [ ] 1.3.2 字段：`String text`, `int slideCount`
- [ ] 1.3.3 添加 Jackson 注解（Lombok `@Data` 或手写 getter）

### 1.4 创建 PptParseErrorResponse DTO
- [ ] 1.4.1 在 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/dto/PptParseErrorResponse.java` 新建
- [ ] 1.4.2 字段：`String code`, `String message`, `Long maxBytes`（可选）
- [ ] 1.4.3 添加 `@JsonInclude(JsonInclude.Include.NON_NULL)` 避免空字段

### 1.5 创建 PptParseException
- [ ] 1.5.1 在 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/exception/PptParseException.java` 新建
- [ ] 1.5.2 字段：`String code`, `String message`
- [ ] 1.5.3 继承 `RuntimeException`

### 1.6 创建 PptParserController
- [ ] 1.6.1 在 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/controller/PptParserController.java` 新建
- [ ] 1.6.2 端点：`@PostMapping("/api/features/file/parse-ppt")`
- [ ] 1.6.3 参数：`@RequestParam("file") MultipartFile file`
- [ ] 1.6.4 实现：
  1. 校验扩展名（`fileName` 小写后必须是 `.ppt` 或 `.pptx`）→ 否则抛 415
  2. 校验大小（`file.getSize() > 10 * 1024 * 1024`）→ 抛 413
  3. 调用 `pptParserService.parse(file.getBytes(), file.getOriginalFilename())`
  4. 返回 `ResponseEntity.ok(new PptParseResponse(result.text, result.slideCount))`
- [ ] 1.6.5 添加 `@ExceptionHandler(PptParseException.class)` 映射：
  - `code=PPT_TOO_LARGE` → 413
  - `code=PPT_PARSE_ERROR` / `PPT_ENCRYPTED` / `PPT_UNSUPPORTED_TYPE` → 400
  - `code=PPT_TIMEOUT` → 504
- [ ] 1.6.6 在类顶部加 `@Slf4j`（如已用 Lombok）或手动 logger

### 1.7 配置 CORS
- [ ] 1.7.1 检查 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/config/SecurityConfig.java` 是否对 `/api/**` 放开 CORS
- [ ] 1.7.2 确认前端域名（`http://localhost:8080`）在 allowedOrigins 列表

### 1.8 编译验证
- [ ] 1.8.1 `cd backend/skill-gateway && mvn clean compile -DskipTests` 编译通过
- [ ] 1.8.2 启动 skill-gateway，确认无启动错误

## 2. serve-proxy 路由

### 2.1 修改 PROXY_RULES
- [ ] 2.1.1 在 `/Users/zhangzhuang/ai/gitbbxdc-bot/bxdc-bot/serve-proxy.js` 的 PROXY_RULES 数组中，**在 `/features` 之前**新增一条：
  ```js
  { prefix: '/features/file/parse-ppt', target: 'http://127.0.0.1:18080' },
  ```
- [ ] 2.1.2 重启 serve-proxy：`pkill -f 'node serve-proxy' && cd /path/to && node serve-proxy.js &`

## 3. 前端

### 3.1 创建 pptParser.ts
- [ ] 3.1.1 在 `frontend/src/utils/pptParser.ts` 新建
- [ ] 3.1.2 导出 `parsePpt(file: File, signal?: AbortSignal): Promise<string>`
- [ ] 3.1.3 实现：
  - `const form = new FormData(); form.append('file', file)`
  - `fetch(agentUrl('/features/file/parse-ppt'), { method: 'POST', body: form, signal })`
  - 错误处理：4xx/5xx → `throw new Error('PPT 解析失败：${res.status} ${text.slice(0, 200)}')`
  - 成功 → `return (await res.json()).text`

### 3.2 修改 fileParser.ts
- [ ] 3.2.1 在 ppt 分支（`if (fileType === 'ppt')`）改为：
  ```ts
  const { parsePpt } = await import('./pptParser')
  return parsePpt(file)
  ```
- [ ] 3.2.2 移除该分支的 `return agentFallback(file)`（doc/xls 仍保留 agentFallback）
- [ ] 3.2.3 更新顶部注释（路由表 PPT 行改为 `parsePpt()    （skill-gateway /api/features/file/parse-ppt）`）

### 3.3 验证
- [ ] 3.3.1 `cd frontend && npx vue-tsc --noEmit` 编译通过
- [ ] 3.3.2 `npm run build` 成功

## 4. 端到端验证

### 4.1 curl 验证 Java 端
- [ ] 4.1.1 `curl -X POST -F 'file=@/path/to/test.pptx' http://127.0.0.1:18080/api/features/file/parse-ppt` 返回 200 + text
- [ ] 4.1.2 用 12MB 假文件测试 → 返回 413
- [ ] 4.1.3 上传 .docx → 返回 415
- [ ] 4.1.4 上传损坏的 .pptx（用 zip 改后缀）→ 返回 400

### 4.2 serve-proxy 验证
- [ ] 4.2.1 `curl -X POST -F 'file=@test.pptx' http://127.0.0.1:8080/features/file/parse-ppt` 经 serve-proxy 转发后成功

### 4.3 前端验证
- [ ] 4.3.1 浏览器上传 .pptx，等状态变 `✓`
- [ ] 4.3.2 点发送，DevTools Network 看 `/agent/run` 请求 body 的 `instruction` 包含 `--- 文件：xxx.pptx ---` 段落
- [ ] 4.3.3 浏览器上传 .ppt，等状态变 `✓`
- [ ] 4.3.4 上传 11MB .pptx，验证 UI 显示「失败」+ tooltip 显示「PPT 解析失败：413 ...」
- [ ] 4.3.5 浏览器上传 .doc 仍走 agentFallback 失败（行为不变）
- [ ] 4.3.6 浏览器上传 .docx 仍走前端 mammoth 解析成功（不受影响）

## 5. 清理
- [ ] 5.1 移除调试日志（如有）
- [ ] 5.2 更新 `openspec/specs/document-parsers/spec.md`（如果有）
- [ ] 5.3 把 change 移到 archive（`openspec archive`）
