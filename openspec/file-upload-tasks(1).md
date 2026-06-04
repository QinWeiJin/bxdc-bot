# 文件上传功能 - 任务拆解文档

## 概述

在聊天框增加文件上传按钮，支持文档（Word/Excel/PPT/TXT/MD）和图片（PNG/JPEG/WebP）上传。上传文件解析文字内容拼接到大模型 message，文件名称传给 `/memory/add`。文件合规审计和解密功能在 Java skill-gateway 中暴露接口（后续扩展），OCR 识别和文件内容读取在 agent-core 中实现，后续可扩展至 Java 端。

### 当前架构分析

```
┌─────────────────┐     SSE(/agent/run)     ┌──────────────────┐     HTTP     ┌───────────────────┐
│   Frontend       │ ◄────────────────────── │   agent-core     │ ◄────────── │  skill-gateway    │
│  (Vue 3 + TDesign)│                         │  (NestJS/TS)    │             │  (Spring Boot)    │
│                  │ ──────────────────────► │                  │             │                   │
│  MessageInput.vue│  POST /agent/run        │  agent.controller│             │  SecurityFilter   │
│  useChat.ts      │                         │  memory.service  │             │  Audit Service    │
│  MessageList.vue │  POST /memory/add       │                  │             │                   │
└─────────────────┘                         └──────────────────┘             └───────────────────┘
```

- 用户消息 → `MessageInput.vue` → `useChat.sendMessage()` → `POST /agent/run` → SSE 流式返回
- 对话完成后 → `/memory/add` 存储记忆
- 文件合规/解密 → Java skill-gateway（仅暴露接口）
- 文件内容解析 → 前端优先（新格式 .docx/.xlsx/.pptx），旧格式回退 agent-core
- 图片 OCR 识别 → agent-core 处理，后续可扩展至 Java 端

### 核心约束

| 文件类型 | 格式 | 数量上限 | 单文件上限 | 总大小上限 | 备注 |
|---------|------|---------|-----------|-----------|------|
| Word | .doc/.docx | 3 | 5MB | 15MB | 文字提取 |
| Excel | .xls/.xlsx | 2 | 1MB | 2MB | 文字提取 |
| PPT | .ppt/.pptx | 3 | 10MB | 30MB | 仅识别文字 |
| TXT/MD | .txt/.md | 不限 | 0.3MB | - | 纯文本 |
| 图片 | .png/.jpeg/.webp | 10 | - | - | 仅文字识别 |

---

## 任务拆解

### 任务 1：类型定义与常量配置

**目标**：定义文件上传相关的 TypeScript 类型、常量、校验规则，为后续所有任务提供类型基础。

**涉及文件**：
- 新建 `frontend/src/types/fileUpload.ts`

**具体工作**：
- [ ] 1.1 定义 `FileType` 联合类型：`'word' | 'excel' | 'ppt' | 'txt' | 'image'`
- [ ] 1.2 定义 `UploadFileInfo` 接口：id、file、fileName、fileType、size、status、parsedText 等字段
- [ ] 1.3 定义 `FileUploadConfig` 和各类型限额常量（MAX_COUNT、MAX_SIZE_PER_FILE、MAX_TOTAL_SIZE、ACCEPTED_EXTENSIONS）
- [ ] 1.4 定义 `FileValidationResult` 接口：valid、errors[]、warnings[]
- [ ] 1.5 定义 `FileComplianceResult` 接口：passed、message、sensitiveWords[]
- [ ] 1.6 定义 `FileDecryptResult` 接口：success、content、errorMessage
- [ ] 1.7 定义 OCR 相关类型：`OcrResponse`（text、confidence）、`ImageParsedStatus` 状态枚举

**影响分析**：
- 最小化修改，纯新增文件，不修改已有代码
- 为后续所有任务提供类型基础

---

### 任务 2：文件校验工具

**目标**：实现前端文件格式、大小、数量校验逻辑。

**涉及文件**：
- 新建 `frontend/src/utils/fileValidator.ts`

**具体工作**：
- [ ] 2.1 实现 `getFileTypeFromName(fileName: string)` 函数，根据扩展名映射到 `FileType`
- [ ] 2.2 实现 `validateFileExtension(file: File)` 校验格式是否在允许列表中
- [ ] 2.3 实现 `validateFileSize(file: File, fileType: FileType)` 校验单文件大小
- [ ] 2.4 实现 `validateTotalSize(files: File[], fileType: FileType)` 校验同类型总大小
- [ ] 2.5 实现 `validateFileCount(files: File[], fileType: FileType)` 校验同类型数量
- [ ] 2.6 实现组合校验函数 `validateFile(file: File, existingFiles: UploadFileInfo[]): FileValidationResult`
- [ ] 2.7 判断是否加密文件的辅助函数 `isEncryptedFile(file: File)`（通过魔数检测）

**影响分析**：
- 纯新增工具文件，不修改已有代码
- 可独立进行单元测试

---

### 任务 3：useFileUpload Composable

**目标**：创建文件上传的状态管理 composable，管理上传文件列表、校验、解析生命周期。

**涉及文件**：
- 新建 `frontend/src/composables/useFileUpload.ts`

**具体工作**：
- [ ] 3.1 声明响应式状态：`uploadedFiles`（按 `FileType` 分组）、`isUploading`、`uploadError`
- [ ] 3.2 实现 `addFiles(files: File[])` 方法：遍历文件 → 校验 → 确认格式 → 加入列表
- [ ] 3.3 实现 `removeFile(fileId: string)` 方法：从列表中移除单个文件
- [ ] 3.4 实现 `clearFiles()` 方法：清空所有已上传文件
- [ ] 3.5 实现 `getAllParsedText()` 方法：汇总所有已解析文件的文字内容
- [ ] 3.6 实现 `getFileNamesForMemory()` 方法：获取所有文件名清单（用于 /memory/add）
- [ ] 3.7 实现 `parseFileContent(file: UploadFileInfo)` 方法：根据类型调用对应的解析器
- [ ] 3.8 提供 `provide/inject` 模式（与 useChat 一致）

**影响分析**：
- 纯新增 composable，不修改已有代码
- 用 provide/inject 注入到 ChatView 层级

---

### 任务 4：文档内容解析器（前端版）

**目标**：在前端实现 Office 文档的文字内容提取，优先处理新格式（.docx/.xlsx/.pptx），旧格式回退 agent-core。

**涉及文件**：
- 新建 `frontend/src/utils/docxParser.ts`
- 新建 `frontend/src/utils/xlsxParser.ts`
- 新建 `frontend/src/utils/pptxParser.ts`
- 新建 `frontend/src/utils/fileParser.ts`（统一入口）

**具体工作**：
- [ ] 4.1 安装依赖：`mammoth`（docx 解析）、`xlsx`（SheetJS，xlsx 解析）
- [ ] 4.2 实现 `parseDocx(file: File): Promise<string>` 使用 mammoth 提取纯文本
- [ ] 4.3 实现 `parseXlsx(file: File): Promise<string>` 使用 xlsx 库读取所有 sheet 的文本内容
- [ ] 4.4 实现 `parsePptx(file: File): Promise<string>` 解析 pptx zip 内 slides 的文本
  - pptx 本质是 zip，可解压读取 `ppt/slides/slide*.xml` 中的 `<a:t>` 标签
  - 方案：使用 `JSZip` + XML 解析，或引入轻量 pptx 解析库
- [ ] 4.5 实现 `parseTxt(file: File): Promise<string>` 使用 FileReader 直接读取
- [ ] 4.6 实现统一入口 `parseDocument(file: File, fileType: FileType): Promise<string>`
  - 新格式（.docx/.xlsx/.pptx）走前端解析
  - 旧格式（.doc/.xls/.ppt）回退调用 agent-core 接口
- [ ] 4.7 处理大文件分片读取（如 TXT > 100KB 分段）

**影响分析**：
- 纯新增工具文件
- 新增依赖：`mammoth`（~150KB gzip）、`xlsx`（~300KB gzip）
- 旧格式回退不影响已有功能

---

### 任务 5：图片 OCR 解析器（agent-core 处理）

**目标**：图片 OCR 识别由 agent-core 处理，前端负责上传图片至 agent-core 并获取识别文字结果，后续可扩展至 Java skill-gateway 实现更丰富的 OCR 能力。

**涉及文件**：
- 新建 `frontend/src/utils/imageOcr.ts`

**具体工作**：
- [ ] 5.1 实现 `ocrImageRemote(file: File): Promise<string>` 函数，将图片上传到 agent-core `/features/file/ocr-image` 端点处理
- [ ] 5.2 处理 OCR 请求的 loading 状态与错误反馈
- [ ] 5.3 图片预览缩略图生成（使用 URL.createObjectURL）
- [ ] 5.4 预留 Java 端 OCR 扩展点：`imageOcr.ts` 中封装统一入口，后续切换后端只需改请求路径

**影响分析**：
- 前端不引入 OCR 库依赖，体积无影响
- OCR 能力由 agent-core 节点承载，运行时需确保 agent-core 已部署
- 后续 Java 端扩展现有 `/features/file/ocr-image` 的底层实现即可，前端无需改动

---

### 任务 6：MessageInput.vue 文件上传按钮

**目标**：在聊天输入框区域增加文件上传触发按钮和已选文件列表展示。

**涉及文件**：
- 修改 `frontend/src/components/MessageInput.vue`

**具体工作**：
- [ ] 6.1 在输入框左侧添加文件上传图标按钮（使用 TDesign Upload 或自定义 `<input type="file">`）
- [ ] 6.2 配置 `accept` 属性限制可选文件类型
  ```typescript
  const ACCEPT = '.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.md,.png,.jpg,.jpeg,.webp'
  ```
- [ ] 6.3 支持多文件选择（`multiple`）
- [ ] 6.4 在输入框区域上方显示已选文件列表（文件名 + 类型图标 + 大小 + 删除按钮）
- [ ] 6.5 文件列表按类型分组展示（文档 / 图片）
- [ ] 6.6 文件上传后自动触发解析，解析中显示 loading 状态
- [ ] 6.7 校验失败时弹出错误提示（使用 TDesign Message 组件）
- [ ] 6.8 适配移动端响应式布局

**影响分析**：
- 修改 `MessageInput.vue`，增加上传按钮 + 文件预览区
- 引入 `useFileUpload` composable
- 不影响已有消息发送逻辑

---

### 任务 7：ChatView.vue 集成

**目标**：在 ChatView 中 provide 文件上传状态，协调文件清理生命周期。

**涉及文件**：
- 修改 `frontend/src/views/ChatView.vue`

**具体工作**：
- [ ] 7.1 调用 `provideFileUpload()` 注入文件上传状态
- [ ] 7.2 「新建对话」时调用 `clearFiles()` 清空已上传文件
- [ ] 7.3 页面卸载时（`onUnmounted`）调用 `clearFiles()`
- [ ] 7.4 路由切换离开聊天页时清理文件

**影响分析**：
- 在 ChatView.vue 中新增 provide 调用
- 与现有的 provideChat() 模式一致
- 不影响已有逻辑

---

### 任务 8：useChat.ts 消息发送集成

**目标**：修改消息发送逻辑，将已解析的文件内容拼接到用户 message 中。

**涉及文件**：
- 修改 `frontend/src/composables/useChat.ts`

**具体工作**：
- [ ] 8.1 `sendMessage` 方法增加可选的 `attachedFiles: UploadFileInfo[]` 参数
- [ ] 8.2 组装发送给大模型的消息内容：
  ```
  [用户消息文本]

  [上传文件内容]
  --- 文件：xxx.docx ---
  <文件解析文字内容>
  --- 文件：xxx.xlsx ---
  <文件解析文字内容>
  ```
- [ ] 8.3 `/memory/add` 调用时仅传文件名清单（不传文件内容）：
  ```typescript
  await addMemory(userId, `本次对话涉及文件：${fileNames.join('、')}`, 'system')
  ```
- [ ] 8.4 `sendMessage` 调用完成后清除文件状态

**影响分析**：
- 修改 `useChat.ts` 的 `sendMessage` 方法签名和内容组装逻辑
- 修改量小，仅增加文件内容拼接步骤
- 不影响已有纯文本消息发送

---

### 任务 9：agent-core 文件内容处理端点

**目标**：在 agent-core 中新增文件内容读取和 OCR 端点，前者作为前端旧格式文档解析的兜底，后者作为图片 OCR 的主路径。

**涉及文件**：
- 新建 `backend/agent-core/src/features/file-processor/file-processor.controller.ts`
- 新建 `backend/agent-core/src/features/file-processor/file-processor.service.ts`
- 修改 `backend/agent-core/src/app.module.ts`

**具体工作**：
- [ ] 9.1 创建 `FileProcessorController`
  - `POST /features/file/parse-document`：接收旧格式文档，返回解析文字（兜底）
  - `POST /features/file/ocr-image`：接收图片，返回 OCR 识别文字（主路径，后续可切换底层实现为 Java 端）
- [ ] 9.2 创建 `FileProcessorService`
  - 旧格式文档处理（.doc/.xls/.ppt）通过调用 Java gateway 或使用 Node.js 库
  - 图片 OCR 使用 `tesseract.js`（Node.js 端）处理，后续可替换为 Java 端实现
  - 预留 `ocrProvider` 配置项，支持 `'node'` / `'java'` 切换
- [ ] 9.3 文件接收使用 multer 内存存储（不落盘）
- [ ] 9.4 处理完成后立即释放文件内存
- [ ] 9.5 在 `app.module.ts` 注册 Controller 和 Service

**影响分析**：
- 纯新增 feature 模块，遵循现有 `features/avatar/`、`features/optimize-text/` 模式
- 仅需在 `app.module.ts` 中新增一行 imports
- 不影响已有 agent 流程

---

### 任务 10：Java skill-gateway 合规审计接口（骨架）

**目标**：在 Java 项目中暴露文件合规检查和加密文件解密接口骨架，供后续扩展。

**涉及文件**：
- 新建 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/controller/FileSecurityController.java`
- 新建 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/service/FileSecurityService.java`
- 新建 `backend/skill-gateway/src/main/java/com/lobsterai/skillgateway/dto/FileSecurityResult.java`

**具体工作**：
- [ ] 10.1 创建 `FileSecurityController`
  - `POST /api/file/compliance-check`：接收文件内容，返回合规审计结果（骨架）
  - `POST /api/file/decrypt`：接收加密文件引用，返回解密状态（骨架）
- [ ] 10.2 创建 `FileSecurityService`
  - `checkCompliance(content: String, fileName: String)`：合规审计骨架
    - 当前返回 `{ passed: true, message: "合规审计功能待实现" }`
    - 后续对接安全部敏感词库
  - `decryptFile(fileRef: String, userId: String, permissions: List<String>)`：解密骨架
    - 当前返回 `{ success: true, content: "" }`
    - 后续对接加密平台接口
- [ ] 10.3 创建 DTO 类 `FileComplianceRequest`、`FileDecryptRequest`
- [ ] 10.4 创建 DTO 类 `FileSecurityResult`（统一返回格式）
- [ ] 10.5 配置 SecurityConfig 放行新接口（或要求认证）

**影响分析**：
- 纯新增 Controller + Service，不影响已有功能
- 接口为骨架实现，返回默认通过（不阻塞正常流程）
- 后续安全部对接时仅需修改 Service 实现

---

### 任务 11：文件刷新/清理机制

**目标**：确保「新建对话」和「页面刷新」后文件状态被清除。

**涉及文件**：
- 修改 `frontend/src/composables/useFileUpload.ts`（已在任务 3 中实现 clearFiles）
- 修改 `frontend/src/views/ChatView.vue`（已在任务 7 中实现）

**具体工作**：
- [ ] 11.1 确认 `clearFiles()` 在 `useFileUpload` 中正确实现
- [ ] 11.2 确认 ChatView `onUnmounted` 钩子调用 `clearFiles()`
- [ ] 11.3 确认新建对话时触发 `clearFiles()`
- [ ] 11.4 确认前端不进行任何文件本地缓存（不存 localStorage/IndexedDB）
- [ ] 11.5 agent-core 处理文件后不落盘（仅内存处理）

**影响分析**：
- 不新增文件，在已有任务中完成
- 确认性任务

---

### 任务 12：前端依赖安装与构建验证

**目标**：安装新增依赖，确保构建通过。

**涉及文件**：
- 修改 `frontend/package.json`（添加依赖）

**具体工作**：
- [ ] 12.1 `npm install mammoth xlsx`（文档解析）
- [ ] 12.2 运行 `npm run build` 确保构建成功
- [ ] 12.3 运行 `npm run test` 确保已有测试全部通过
- [ ] 12.4 检查打包体积增量是否可接受

**影响分析**：
- 修改 `package.json`
- 需验证构建和已有测试

---

## 依赖关系图

```
任务 1 (类型定义)
  ├─► 任务 2 (校验工具)
  │     └─► 任务 3 (useFileUpload)
  │           ├─► 任务 6 (MessageInput 按钮)
  │           │     └─► 任务 7 (ChatView 集成)
  │           │           └─► 任务 11 (刷新清理)
  │           └─► 任务 8 (useChat 集成)
  │
  ├─► 任务 4 (文档解析器)
  │     └─► 任务 9 (agent-core 兜底)
  │
  ├─► 任务 5 (图片 OCR)
  │     └─► 任务 9 (agent-core OCR 主路径，后续 Java 扩展)
  │
  └─► 任务 10 (Java 合规接口)

任务 12 (依赖安装) - 在任务 4 之前完成
```

**推荐执行顺序**：1 → 2 → 3 → {4, 5, 10（并行）} → 6 → 7 → 8 → 11 → 12

---

## 接口汇总

### 新增前端接口调用

| 方法 | 路径 | 用途 | 调用方 |
|------|------|------|--------|
| POST | `/features/file/parse-document` | 旧格式文档解析（兜底） | useFileUpload |
| POST | `/features/file/ocr-image` | 图片 OCR 识别（主路径，后续可切换底层为 Java 实现） | useFileUpload |
| POST | `/api/file/compliance-check` | 文件合规审计（骨架） | useFileUpload（预留） |
| POST | `/api/file/decrypt` | 加密文件解密（骨架） | useFileUpload（预留） |

### 修改已有调用

| 原有调用 | 修改内容 |
|---------|---------|
| `POST /agent/run` | body 中 instruction 拼接文件解析文字 |
| `POST /memory/add` | text 追加本次对话涉及文件名 |

---

## 修改文件清单

| 文件路径 | 操作类型 | 说明 |
|---------|---------|------|
| `frontend/src/types/fileUpload.ts` | **新增** | 文件上传类型定义与常量 |
| `frontend/src/utils/fileValidator.ts` | **新增** | 文件格式/大小/数量校验 |
| `frontend/src/composables/useFileUpload.ts` | **新增** | 文件上传状态管理 |
| `frontend/src/utils/docxParser.ts` | **新增** | DOCX 文字提取 |
| `frontend/src/utils/xlsxParser.ts` | **新增** | XLSX 文字提取 |
| `frontend/src/utils/pptxParser.ts` | **新增** | PPTX 文字提取 |
| `frontend/src/utils/fileParser.ts` | **新增** | 文档解析统一入口 |
| `frontend/src/utils/imageOcr.ts` | **新增** | 图片 OCR（调用 agent-core） |
| `frontend/src/components/MessageInput.vue` | **修改** | 增加上传按钮 + 文件预览 |
| `frontend/src/views/ChatView.vue` | **修改** | provide 文件上传状态 + 清理 |
| `frontend/src/composables/useChat.ts` | **修改** | 消息内容拼接文件文字 |
| `frontend/package.json` | **修改** | 新增 mammoth、xlsx 依赖 |
| `backend/agent-core/src/features/file-processor/file-processor.controller.ts` | **新增** | 文件处理端点 |
| `backend/agent-core/src/features/file-processor/file-processor.service.ts` | **新增** | 文件处理逻辑 |
| `backend/agent-core/src/app.module.ts` | **修改** | 注册 FileProcessor 模块 |
| `backend/skill-gateway/.../controller/FileSecurityController.java` | **新增** | 合规审计接口骨架 |
| `backend/skill-gateway/.../service/FileSecurityService.java` | **新增** | 合规审计服务骨架 |
| `backend/skill-gateway/.../dto/FileSecurityResult.java` | **新增** | DTO 类 |

---

## 风险与注意事项

1. **旧格式文档处理**：.doc/.xls/.ppt 是二进制旧格式，前端 JS 库支持有限，需 agent-core 兜底处理
2. **大文件性能**：Word/PPT 单文件可达 5-10MB，解析时需考虑内存和 UI 阻塞（使用 Web Worker）
3. **依赖体积**：mammoth ~150KB gzip、xlsx ~300KB gzip，注意打包体积
4. **文件不落盘**：agent-core 使用 multer 内存存储，处理完立即释放，确保不留存
5. **合规审计**：当前为骨架实现（默认通过），后续对接安全部可能修改接口签名
6. **加密文件**：当前为骨架实现，后续需对接授权平台和加密平台的具体 API
7. **移动端适配**：文件上传按钮和预览在窄屏设备上需调整布局
8. **OCR 扩展**：当前 OCR 由 agent-core 节点的 tesseract.js 处理，后续可迁移至 Java 端以利用更成熟的 OCR 引擎
