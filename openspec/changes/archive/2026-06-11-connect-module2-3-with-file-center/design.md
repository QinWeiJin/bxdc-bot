## Context

- **模块 2 前端**（commit `96d5274`，change `module2-file-upload`）落地了 ① 三种上传入口（图标 / 拖拽 / Ctrl+V），② 4 步校验（类型/大小/数量/重复），③ 状态机（pending → parsing → parsed/failed/skipped），④ 取消上传。前端 `parseDocument` 当前走"前端优先 + 5s 超时兜底后端"的双轨制

- **wgj 后端落地**（commit `65e2982c`）交付 ① `FtpConfig` + `FtpFileService`（FTP 上传/下载/列表/删除），② `user_files` 表（14 字段含 `parsed_summary LONGTEXT`），③ `FileParserRouter`（doc/docx → `WordParser` POI，txt/md → `TxtMdParser` 自上而下抽 500 字，py → `PyParser` 全量解析），④ `MagicNumberValidator`，⑤ `FileToolService`（tool=`file_list` / `file_delete` / `file_clear_all` / `file_detail`），⑥ `FileToolController`（`/api/files/**`），⑦ `FileAccessInterceptor`

- **现状问题**：
  ① 前端装了两份解析库（`mammoth` + `xlsx`），解析结果可能与后端 POI 不一致
  ② 前端解析的 `parsedText` 没有回写 `user_files.parsed_summary`，模块 5 的 `file_detail` 拿不到摘要
  ③ 大文件（接近 10MB）解析在前端会卡 UI
  ④ `MagicNumberValidator` 在前端缺失，`.exe 改 .jpg` 能通过前端类型校验
  ⑤ 启雷的 Excel 解析器未注入（前端暂用 SheetJS），建建的 Python 解析器 wgj 已实现但前端没走

## Goals / Non-Goals

**Goals：**

- 把 doc/docx/txt/md/py 五个类型的解析**完全收归后端**（wgj 已有），前端只负责上传交互和状态展示
- 删除前端 mammoth + SheetJS 两个第三方依赖（AGENTS.md §7.1：解析归后端，前端没理由装）
- 解析结果回写 `user_files.parsed_summary`（multipart 一次 HTTP 调用，由 wgj 的 `FtpFileService.upload` 自动完成），为模块 5 `file_detail` 和后续 LLM 注入铺路
- 保留前端 4 步校验（类型/大小/数量/重复）和三种上传入口不变 — 这些是**交互层**校验，跨页面响应必须在前端做
- 魔数校验由 wgj 后端 `MagicNumberValidator` 兜底（防 `.exe 改 .jpg`），前端**仅按扩展名**给"非支持格式"弹窗
- 改动后端代码**最小化**：新建独立 `FileUploadController`（约 40 行），只调 wgj 已有 Service 层（`FtpFileService.uploadFile()` + `FileParseService.parseAndPersist()` + `UserFileMapper`），**不修改** wgj 任何已有 `.java` 文件
- 为模块 3（启雷 Excel）和模块 5（光建 `file_detail`）留好接入点 — 不破坏 wgj 已建立的解析器注册模式

**Non-Goals：**

- **不修改** wgj 的 `FileParseService` / `WordParser` / `TxtMdParser` / `PyParser` / `FtpFileService` / `UserFile` 实体
- **不修改** `MagicNumberValidator` 逻辑
- **不修改** `schema-mysql.sql`（`parsed_summary` 字段已存在）
- **不实现** Excel 解析（启雷后续 change）；只确保前端"看到 .xlsx → 调后端 → 后端路由到 Excel parser"链路通，parser 本身后续注入
- **不修改** `SystemSkillService` 现有的 KIND 分发
- **不实现** LLM 提示词注入（属于模块 3 后续 change，本 change 只把 `parsed_summary` 准备好）
- **不修改** `file-detail` / `file-list` 等已落地的 tool（如果 wgj 的 `file_list` 已能从 `parsed_summary` 读取摘要，本 change 不动它）

## Decisions

### Decision 1: 前端解析路由表从"前端优先"改为"纯后端"

**Before**（`module2-file-upload` 的现状）：

```ts
// .docx 前端优先：mammoth
return parseWithFallback(
  async () => parseDocx(file),  // 5s 超时
  async () => parseWord(file, signal)  // 后端 POI
)
```

**After**：

```ts
// .docx → 纯后端（一次 HTTP 调用，无前端 fallback）
const { parseFileViaGateway } = await import('./gatewayParser')
return parseFileViaGateway(file, fileType, signal)
```

**理由**：

- 解析逻辑一份代码（后端）一处维护，避免前端 mammoth 和后端 POI 结果不一致
- 前端不需要 mammoth 库（≈500KB gzip 125KB），不需要 SheetJS（≈430KB gzip 143KB），首屏加载快 200ms+
- 5s 超时 fallback 在网络差时会让用户看到"先转圈→再转圈→才出结果"的差体验
- 走 `POST /api/files/upload`（新建 `FileUploadController`）一次调用完成"上传+解析+落库"，最省事

**备选方案**（已考虑但不取）：

- A. 保留前端解析 + 解析完后回写 `parsed_summary`：开发量更小，但两份解析库并存，长期维护成本高，不符合 §7.1
- B. 完全去掉"5s 超时"机制，但保留前端解析：与 A 同

### Decision 2: 新建 `FileUploadController`，不修改 wgj 任何已有 .java 文件

**现状**：wgj 的 `FileToolController` 只有 3 个端点（`GET /api/files/tools`、`POST /api/files/tools/execute`、`GET /api/files/health`），**没有 multipart 文件上传端点**。但 wgj 已有完整的 Service 层供调用。

**方案**：

新建 `FileUploadController.java`（约 40 行，独立新文件），端点 `POST /api/files/upload`：

```
@PostMapping("/upload")
public ResponseEntity<?> uploadFile(
    @RequestParam("file") MultipartFile file,
    HttpServletRequest request
) {
    String userId = AamTokenUtil.requireUserId(request);
    String originalFileName = file.getOriginalFilename();
    
    // 1. 调 wgj 已有 FtpFileService — 存 FTP
    String ftpPath = ftpFileService.uploadFile(userId, originalFileName, file.getInputStream());
    
    // 2. 建 UserFile 实体 — 调 wgj 已有 UserFileMapper
    UserFile userFile = buildUserFile(userId, originalFileName, ftpPath, file.getSize());
    userFileMapper.insert(userFile);
    
    // 3. 调 wgj 已有 FileParseService — 解析 + 回写 parsed_summary
    FileParseResult result = fileParseService.parseAndPersist(userFile);
    
    // 4. 返回
    return ResponseEntity.ok(Map.of("fileId", userFile.getId(), "parsedSummary", userFile.getParsedSummary()));
}
```

**关键原则**：
- FileUploadController **只写 40 行编排代码**，不写业务逻辑
- 所有业务方法完全调用 wgj 已有 Service：`FtpFileService.uploadFile()`、`FileParseService.parseAndPersist()`、`UserFileMapper.insert()`
- **不修改** wgj 的 `FileToolService`、`FileToolSeeder`、`FileToolController`、`FileParseService` 等任何已有 `.java` 文件
- 不新增 `system_skills` 表行（不需要注册为 Tool，前端直接调 HTTP）

**备选方案**（已考虑但不取）：

- A. 在 `FileToolService` 加 `file_upload_and_parse` handler + `FileToolSeeder` 注册：直接修改 wgj 文件，不符合「尽量不要改 wgj 代码」约束
- B. 前端用 `FormData` 调 `POST /api/system-skills/execute`：该端点走 JSON body，不是 multipart，无法传文件

### Decision 3: 解析进度反馈策略

`module2-file-upload` 当前的 UI 行为：上传后立即显示"解析中..." → 完成后变绿 ✓。本 change 保留这个状态机不变，但底层从"前端 5s 解析"变成"后端 HTTP 调用（FTP + POI）"。

UX 行为：
- 用户拖入 docx → 前端调后端 `POST /api/files/upload` → `status='parsing'` → 蓝色"解析中..."
- 后端返回（一般 200-2000ms） → `status='parsed'` → 绿色 ✓
- 后端返回 4xx/5xx → `status='failed'` → 红色"+"，hover 显示错误信息

**不做**的：实时进度条（后端解析通常是几百 ms 内完成，进度条价值低）。

**未来增量**：当后端解析超过 1s 时再考虑 SSE 流式进度；当前不需要。

### Decision 4: 错误处理

后端解析失败的 4 种情况：
- **魔数校验失败**（`.exe 改 .jpg`）→ 后端 `MagicNumberValidator` 拒绝 → 前端 `status='failed'` + 显示"文件类型与扩展名不匹配"
- **解析器未实现**（启雷的 Excel 还没注入）→ 后端 `FileParserRouter` 抛 `IllegalArgumentException` → wgj 的 `FtpFileService.upload` 应**捕获并返回空 `parsed_summary`**（不抛错），前端 `status='parsed' + parsedText=""`（不是 failed）
- **后端服务不可用**（网络/网关挂）→ axios 抛网络错 → 前端显示"解析失败，请稍后重试"
- **用户取消**（点击 ×）→ AbortController 触发 → `status='skipped'`

### Decision 5: 拆解改动范围（后端改动最小化 — 只新增独立文件）

| 改动 | 前端 | 后端 | 数据库 |
|------|------|------|--------|
| 解析路由表"前端优先 → 纯后端" | ✅ | — | — |
| 新建 `gatewayParser.ts` 调 `POST /api/files/upload` | ✅ | — | — |
| 新建 `FileUploadController.java`（约 40 行，独立新文件） | — | ✅ | — |
| 调 wgj 已有 `FtpFileService.uploadFile()` | — | —（已有） | — |
| 调 wgj 已有 `FileParseService.parseAndPersist()` | — | —（已有） | — |
| 删 `mammoth` / `xlsx` dep（推迟） | ⏸ | — | — |
| 删 `docxParser.ts` / `xlsxParser.ts`（推迟） | ⏸ | — | — |
| 删 6 个旧后端解析类（死代码，推迟） | — | ⏸ | — |

**关键点**：本 change 后端只用写 **1 个新文件**（`FileUploadController.java`），wgj 所有已有 `.java` 文件**零修改**。解析、FTP 存储、DB 写回全部复用 wgj Service 层。

## Risks / Trade-offs

- **R1**：wgj 的 `FileToolController` **没有** `POST /api/files/upload` 端点
  - **缓解**：新建 `FileUploadController.java`（约 40 行），只调 wgj 已有 Service 层。需要确认的只有 Service 方法签名（`FtpFileService.uploadFile()`、`FileParseService.parseAndPersist()` 等），已验证均可直接调用

- **R2**：前端到后端解析的 HTTP 往返增加 ~50-200ms 延迟（局域网 gateway）
  - **缓解**：用户感知不到（解析结果 < 1s 出来仍"瞬间"），且本地解析 0ms 的代价是首屏加载 200KB+ 第三方库，权衡后选后端

- **R3**：解析失败信息从前端可控变成后端可控
  - **缓解**：wgj 的 `FileParseService` 已经在抛 `IllegalArgumentException` 和 `Exception` 两种，前端 catch 后把 `e.message` 显示给用户（中文）。已在 `module2-file-upload` 的 `parseFileContent` 里有 `file.errorMessage = e.message` 字段，无需新加

- **R4**：启雷的 Excel 解析器未实做时，前端上传 .xlsx 行为必须不报错
  - **缓解**：wgj 的 `FileParserRouter` 当前对 .xlsx 返回空 `parsed_summary`（不抛错），前端 `status='parsed' + parsedText=""`（不是 failed），文件正常入 FTP + DB

- **R5**：依赖 `parsed_summary` 完整地存储在 DB
  - **缓解**：wgj 的 `user_files.parsed_summary LONGTEXT` 字段已存在，`FileParseService.parseAndPersist()` 已实现"解析 + 序列化 + UPDATE DB"完整流程，新建的 `FileUploadController` 直接调用即可，前端不替模块 3 做截断决策

## Migration Plan

**Phase 0 — 准备**（无需用户操作）：

- 阅读 wgj 已有 Service 方法签名：`FtpFileService.uploadFile()`、`FileParseService.parseAndPersist()`、`UserFileMapper.insert()`，**确认参数可直接调用**（1 个任务）

**Phase 1 — 后端：新建 FileUploadController**（mandatory）：

1. 新建 `FileUploadController.java`：`@PostMapping("/api/files/upload")` multipart 端点，约 40 行编排代码
2. 编译 + 自测（4 个任务）

**Phase 2 — 前端改造**（核心）：

1. 改 `fileParser.ts` 的 `parseDocument`：5 个类型（doc/docx/txt/md/py）全部走 `parseFileViaGateway`
2. 新建 `utils/gatewayParser.ts`：`parseFileViaGateway(file, type, signal)`，调 `POST /api/files/upload`
3. `useFileUpload.parseFileContent` 调用点不变（接口保持），内部从 `parseDocument` 改为 `parseFileViaGateway`
4. **保留** `docxParser.ts` / `xlsxParser.ts` / `txtParser.ts`（作为回滚兜底）
5. **保留** `package.json` 中的 `mammoth` / `xlsx`（作为回滚兜底）

**Phase 3 — 验证**：

- 单元测试：保留前端解析单测（解析代码没删，仅调用点改了），保留 4 步校验单测
- E2E：浏览器手动验证
  - 拖入 docx → 5s 内变绿 ✓
  - 拖入 txt → 5s 内变绿 ✓
  - 拖入 .exe 改名为 .jpg → 弹出"类型不支持"（前端）→ 即使绕过前端，后端 `MagicNumberValidator` 也会拒绝
  - 拖入 .xlsx → 变绿 ✓（parsedText 为空字符串，不是红色）
- OpenSpec validate：`openspec validate connect-module2-3-with-file-center --strict`

**回滚策略**：

- 本 change **保留** `docxParser.ts` / `xlsxParser.ts` / `txtParser.ts` 老路径代码（仅删除调用点，不删文件），紧急时可在 5 分钟内 git revert 调用点改动
- 解析回归 fallback：保留 `mammoth` / `xlsx` 第三方包，回滚时无需 `npm install`

## Open Questions

- **Q1**：wgj 的 `FileToolController` 当前**是否提供 `POST /api/files/upload` 端点**？
  - 答：**已确认不提供**（`FileToolController` 只有 `GET /api/files/tools`、`POST /api/files/tools/execute`、`GET /api/files/health` 三个端点）。
  - 本 change 采取：**新建 `FileUploadController.java`**（约 40 行），只调 wgj 已有 Service 层，不修改 wgj 任何已有 `.java` 文件

- **Q2**：启雷的 Excel 解析器（commit 未确认）当前是否已注入到 `FileParserRouter`？
  - 答：阅读 `FileParserRouter` 的 bean 注册代码可确认
  - 如果是 → .xlsx 上传立即可用
  - 如果否 → wgj 当前对 .xlsx 是"未实现"路径，返回空 `parsed_summary`（不报错）

- **Q3**：模块 5 `file_detail` 当前**是否已能从 `user_files.parsed_summary` 读取摘要**？
  - 答：阅读 `FileToolService.execute` 的 `file_detail` case 可确认
  - 如果是 → 模块 5 落地完整，本 change 不动
  - 如果否 → 不在本 change 范围（属模块 5 后续 change）

## Out of Scope（明确不做）

- Excel/CSV 解析（启雷负责）
- LLM 提示词注入（模块 3 后续）
- 异步轮询 / fire-and-forget 改造（如果大文件需要走异步，那是另一个 change）
- agent-core 改动（按 AGENTS.md §7.5）
- `file_list` / `file_delete` / `file_clear_all` 改动（wgj 已落地）
