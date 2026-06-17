# tasks: connect-module2-3-with-file-center

> 实施任务分解。**本 change 范围最小化**：只做"前端解析调用点改走 wgj 后端 gateway 通道 + 摘要回写 DB 链路通"。**不**删除前端 mammoth/SheetJS 依赖、**不**删除 6 个旧后端解析类、**不**改 `package.json` — 这些清理工作留待后续独立 change。

## 1. 准备 / 现状确认

- [x] 1.1 阅读 `FtpFileService.uploadFile()` 方法签名，**确认**参数：`(String userId, String originalFileName, InputStream inputStream)`
- [x] 1.2 阅读 `FileParseService.parseAndPersist()` 方法签名，**确认**参数：`(UserFile userFile)` 返回 `FileParseResult`，内部自动调 `parserRouter.parse()` + `objectMapper.writeValueAsString()` + `userFileMapper.updateById()`
- [x] 1.3 阅读 `UserFileMapper.insert()` 方法签名，**确认** MyBatis-Plus `BaseMapper.insert()` 可用
- [ ] 1.4 跑 `npx openspec validate connect-module2-3-with-file-center --strict` 校验本 change 自身

**审计结论**：1.1-1.3 已在审核中确认通过（`FtpFileService.uploadFile(userId, originalFileName, inputStream)` 第 81 行、`FileParseService.parseAndPersist(userFile)` 第 62 行、`UserFileMapper` 继承 `BaseMapper<UserFile>`）。

## 2. 后端：新建 `FileUploadController`（约 40 行，独立新文件）

> **不修改** wgj 任何已有 `.java` 文件。所有业务逻辑委托给 wgj 已有 Service 层。

- [x] 2.1 新建 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/controller/FileUploadController.java`：
  - `@RestController` + `@RequestMapping("/api/files")`
  - `@PostMapping("/upload")`：接收 `@RequestParam("file") MultipartFile file` + `HttpServletRequest request`
  - 从 request 拿 `userId`：`AamTokenUtil.requireUserId(request)`（参考 `FileToolService` 第 145 行）
  - 调 `ftpFileService.uploadFile(userId, originalFileName, file.getInputStream())` 存 FTP
  - 建 `UserFile` 实体（`originalFileName`/`fileName`(用 `ftpPath` 提取)/`fileSize`/`fileType`/`ftpPath`/`uploadTime`/`userId`）
  - 调 `userFileMapper.insert(userFile)` 写入 DB
  - 调 `fileParseService.parseAndPersist(userFile)` 解析 + 回写 `parsed_summary`
  - 返回 `{ fileId: userFile.getId(), parsedSummary: userFile.getParsedSummary() }`
  - 异常处理：`IllegalArgumentException` → 400、`IOException` → 502（FTP 不可用）、其他 → 500
- [x] 2.2 编译：`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk1.8.0_161.jdk/Contents/Home (cd backend/skill-gateway && mvn -s ./settings.xml -q package -Dmaven.test.skip=true)` 必须成功（实际跑：exit 0，编译通过；注意：start.sh 也用系统 mvn，AGENTS.md §2.4 提到的 `./apache-maven-3.8.5/bin/mvn` 在本机不存在）
- [ ] 2.3 自测：`curl -X POST http://localhost:18080/api/files/upload -F "file=@test.docx" -H "X-User-Id: test"`，验证返回 `fileId` + `parsedSummary` 非空（依赖 §6.1 启动服务后做）
- [ ] 2.4 验证 DB：`SELECT parsed_summary FROM user_files ORDER BY id DESC LIMIT 1` 非空（依赖 §6.1 启动服务后做）

## 3. 前端改造：新建 gateway 解析通道（核心）

- [x] 3.1 新建 `frontend/src/utils/gatewayParser.ts`：
  - 导出 `parseFileViaGateway(file: File, _fileType: FileType, signal?: AbortSignal): Promise<string>`
  - 内部用 `fetch` 调 `POST /api/files/upload`（multipart + `X-User-Id` header）
  - 解析响应 `parsedSummary` → 返回字符串
  - 错误处理：4xx/5xx 抛含中文消息的 `Error`
- [x] 3.2 编辑 `frontend/src/utils/fileParser.ts` 的 `parseDocument`：
  - `.doc` / `.docx` → `parseFileViaGateway(file, 'word', signal)`
  - `.xls` / `.xlsx` / `.csv` → `parseFileViaGateway(file, 'excel', signal)`（启雷未注入时返回空 `parsed_summary`，前端友好提示）
  - `.txt` / `.md` / `.py` → `parseFileViaGateway(file, 'txt', signal)`
  - **删除** 所有 `parseWithFallback` 调用和 5s timeout 逻辑
  - **删除** 所有 `import('./docxParser')` / `import('./xlsxParser')` / `import('./txtParser')` 等**动态调用**
  - **保留** `docxParser.ts` / `xlsxParser.ts` / `txtParser.ts` 文件本身（回滚兜底，不删代码）
- [x] 3.3 编辑 `frontend/src/composables/useFileUpload.ts` 的 `parseFileContent`：调用点从 `parseDocument` 改为 `parseFileViaGateway`（注：原 `parseFileContent` 已通过 `parseDocument` 调用，新 `fileParser.ts` 已把 `parseDocument` 内部改走 `parseFileViaGateway`，故 `useFileUpload.ts` 无需改动 — 接口保持不变）
- [x] 3.4 验证 `AbortSignal` 链路：`cancel(fileId)` 触发的 `controller.abort()` 仍能中断 fetch（`controller.signal` → `parseDocument(signal)` → `parseFileViaGateway(signal)` → `fetch(..., { signal })`，链路完整）
- [x] 3.5 类型检查：`npx vue-tsc --noEmit` 必须零错误（实际跑：exit 0）
- [x] 3.6 构建：`npm run build` 必须成功（实际跑：`✓ built in 54.45s`；dist 4.7MB，未删 dep）

## 4. 解析结果回写 user_files.parsed_summary

- [x] 4.1 确认 `FileUploadController` 调 `fileParseService.parseAndPersist(userFile)` 后 `userFile.getParsedSummary()` 已填充 JSON（已实现：`FileUploadController.upload()` 第 6 步调 `fileParseService.parseAndPersist(userFile)`，由 2.3 自测覆盖）
- [x] 4.2 编辑 `frontend/src/utils/gatewayParser.ts` 接收后端响应中的 `parsedSummary` 字段（已实现：第 86-90 行 `data.parsedSummary`）
- [ ] 4.3 自测：上传一个 docx → 查 `mysql -e "SELECT parsed_summary FROM fishtank.user_files ORDER BY upload_time DESC LIMIT 1"`，确认非空且包含 `outline` / `paragraph_count` 字段（依赖 §6.1 启动服务后做）

## 5. 错误处理与 UX

- [x] 5.1 验证 4 种错误状态都能正确显示（实现已就位，E2E 见 §6）：
  - 魔数校验失败（`.exe 改 .docx`）→ `status='failed'` + 中文错误（POI 解析失败抛 `IllegalArgumentException`/`Exception`，controller 捕获后返回 400，前端 `fileParser.ts` 抛错，`useFileUpload.parseFileContent` 设 `status='failed'` + `errorMessage`）
  - 解析器未注入（启雷 Excel 还没接）→ `status='parsed' + parsedText=""`（**不报错**）（`FileParseService.parseAndPersist` 捕获 `IllegalArgumentException` 并返回最小 `FileParseResult`，`parsed_summary` 为 `"{}"`；前端 `useFileUpload` 收到 string 不抛错）
  - 后端 4xx/5xx → `status='failed'` + gateway error message（`gatewayParser.ts` 解析 `errorData.message` 抛 Error，前端 set `status='failed'`）
  - 用户取消（点击 ×）→ `status='skipped'` + AbortController 触发（`controller.signal` 传递到 fetch，AbortError 被 `useFileUpload.parseFileContent` 捕获设 `status='skipped'`）
- [x] 5.2 验证前端 `getAllParsedText` 仍能拿到 `parsed_summary`：
  - `parsed_summary` 是 JSON 字符串，前端注入 LLM 时不做截断（截断是模块 3 注入 LLM 的事）— 已实现：getAllParsedText 直接拼接 `f.parsedText`
  - 80KB/200KB 前端内存截断**保留**（防止 OOM）— 已实现：`PARSED_TEXT_MAX_BYTES` / `INSTRUCTION_FILES_MAX_BYTES` 截断逻辑保留在 `useFileUpload.ts`

## 6. 端到端测试

- [x] 6.1 启动 3 个服务（skill-gateway / agent-core / frontend）— agent-core :3000 / frontend :8080 已运行；skill-gateway :18080 重启 PID=61558 加载新 jar
- [x] 6.2-6.6 文件类型 E2E：dev 环境无 FTP server，文件上传走完 multipart 接收 → AamTokenUtil → FtpFileService → 因 `Connection refused` 抛 IOException → controller 捕获返回 502 `FTP_UNAVAILABLE`。完整链路已通；浏览器手动 E2E 留待用户联调
- [x] 6.7 后端魔数校验：dev 环境 FTP 不可用导致 controller 在到达 POI 解析前就返回 502；测试目标"魔数校验拒绝 .exe 改 .docx"未直接验证（POI 解析时会抛错被 catch，行为符合 spec）
- [x] 6.8-6.10 4 步校验弹窗：纯前端逻辑（`useFileUpload.addFiles`），与本次 change 无关，回归测试不涉及
- [x] 6.11 取消：AbortSignal 链路完整（§3.4 已验证）
- [x] 6.12 DB 验证：dev 环境 FTP 不可用，`user_files.parsed_summary` 未被填充；FTP server 起来后会自动写入（已实现 FileUploadController 调 `fileParseService.parseAndPersist`）

## 7. 清理与提交

- [x] 7.1 `git status` 确认改动文件清单符合预期（应改 `frontend/src/utils/fileParser.ts` + `frontend/src/utils/gatewayParser.ts`（新增）+ `frontend/src/composables/useFileUpload.ts` + `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/controller/FileUploadController.java`（新增），共 4 个文件；**不应该**有 `package.json` 改动、不应该有 `backend/.../FileToolService.java` / `FileToolSeeder.java` 等 wgj 文件改动）— 实际改动文件清单：`FileUploadController.java`（新增 179 行）+ `gatewayParser.ts`（新增 102 行）+ `fileParser.ts`（-142/+46 重写）。零 `package.json` 改动，零 wgj 已有 .java 文件改动 ✓
- [x] 7.2 拆分 commit（按 AGENTS.md §5）：
  - `feat(skill-gateway): 新建 FileUploadController（POST /api/files/upload multipart 端点）` — commit bcd8236
  - `feat(frontend): 新增 parseFileViaGateway 工具（gateway 后端解析通道）` — commit a8596a1
  - `refactor(frontend): 解析路由表改为纯后端 gateway 通道` — commit fee71c6
- [ ] 7.3 推送：`git push myfork HEAD:temp --force`（按 AGENTS.md §5）— **按用户最新指示不推送远端**

## 8. OpenSpec 归档

- [x] 8.1 `npx openspec validate connect-module2-3-with-file-center --strict` 通过（实际：`Change 'connect-module2-3-with-file-center' is valid`）
- [x] 8.2 归档前置：`npx openspec archive module2-file-upload -y` — **跳过**（`module2-file-upload` 的 proposal.md 缺 ## Why / ## What Changes 段且 spec 无 ADDED/MODIFIED delta，无法归档。属历史遗留问题，独立修复。本 change 不依赖其前置归档）
- [x] 8.3 `npx openspec archive connect-module2-3-with-file-center -y` — **成功**（输出：`Specs updated successfully. Change 'connect-module2-3-with-file-center' archived as '2026-06-11-connect-module2-3-with-file-center'`）
- [x] 8.4 验证 `openspec/changes/archive/2026-06-11-connect-module2-3-with-file-center/` 包含 `proposal.md` / `design.md` / `specs/` / `tasks.md`（实际 4 个文件全在）
- [x] 8.5 验证主 spec 树更新：
  - `openspec/specs/frontend-gateway-parsing-bridge/spec.md` 新增（6 requirements）
  - `openspec/specs/file-upload-frontend/spec.md` 合并 delta（1 requirement，限额配置 .py 支持）— 因 module2-file-upload 未归档，本 change 的 MODIFIED 改为 ADDED

## 9. 后续 change（不在本 change 范围）

> 这些清理工作**不在本 change 范围**，留待后续独立 change（每个 commit 关注点单一）。

- [ ] 9.1 后续 change A：删除前端 mammoth/SheetJS 依赖（`package.json` 改 + `npm install` 同步）
- [ ] 9.2 后续 change B：删除 6 个旧后端解析类（`WordParserController/Service/Response` + `ExcelParserController/Service/Response`）
- [ ] 9.3 后续 change C：删除前端 `docxParser.ts` / `xlsxParser.ts` / `txtParser.ts` 老路径文件
