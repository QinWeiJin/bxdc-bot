# Tasks: module2-file-upload

> 来源：[`需求方案A1.md`](file:///Users/zhangzhuang/ai/gitbbxdc-bot/bxdc-bot/%E9%9C%80%E6%B1%82%E6%96%B9%E6%A1%88A1.md) 模块二

## 1. FILE_UPLOAD_CONFIG 调整

### 1.1 修改 types/fileUpload.ts
- [x] 1.1.1 将 `maxFileSizeMib` 统一为 10 MiB
- [x] 1.1.2 新增 `maxFilesPerSession = 5`
- [x] 1.1.3 `allowedExtensions.excel` 追加 `.csv`
- [x] 1.1.4 `allowedExtensions.txt` 追加 `.py`
- [x] 1.1.5 新增 `UNSUPPORTED_TYPE` / `FILE_TOO_LARGE` / `TOO_MANY_FILES` 三个固定文案
- [x] 1.1.6 新增 `DUPLICATE_FILE(name, time)` 工厂方法

## 2. useFileUpload composable

### 2.1 创建 composable
- [x] 2.1.1 已有 `frontend/src/composables/useFileUpload.ts`（沿用）
- [x] 2.1.2 已有 `UploadedFile` interface（id/file/status/parsedText/errorMessage/uploadedAt/previewUrl）
- [x] 2.1.3 已有 `FileUploadState` interface，扩展增加 cancel/onDrop/onPaste/parsingCount
- [x] 2.1.4 实现 4 步校验：类型→大小→数量→重复（在 addFiles 内联）
- [x] 2.1.5 已有 `addFiles(incoming: File[]): Promise<void>`
- [x] 2.1.6 状态机：pending → parsing → parsed/failed/skipped
- [x] 2.1.7 实现 `cancel(id)`（abort + 移除）/ `removeFile(id)`（仅移除）

### 2.2 三种上传入口
- [x] 2.2.1 `triggerFilePicker()`：MessageInput.vue 已实现
- [x] 2.2.2 `onDrop(e: DragEvent)`：实现于 useFileUpload，处理拖拽 + 自动解析
- [x] 2.2.3 `onPaste(e: ClipboardEvent)`：实现于 useFileUpload，从 clipboardData.files 提取

### 2.3 解析与并发
- [x] 2.3.1 调用现有 `parseDocument(file, fileType, signal)`
- [x] 2.3.2 并发数 ≤ 3（`MAX_CONCURRENT_PARSES`，parseAllNew worker pool）
- [x] 2.3.3 解析成功 → status='parsed'（无单独回调，状态机已表达）
- [x] 2.3.4 解析失败 → status='failed' + errorMessage

### 2.4 错误弹窗
- [x] 2.4.1 实际使用 `MessagePlugin.warning`（Td 风格），与现有代码风格一致
- [x] 2.4.2 文案严格按需求方案 A1（"单次最多上传5个文件..."）
- [x] 2.4.3 重复文件弹窗用 `window.confirm`（简化为同步，UI 端可升级为 ElMessageBox.confirm）

## 3. 组件挂载

### 3.1 MessageInput.vue
- [x] 3.1.1 已有 `import { useFileUpload }` 与 `const fileUpload = useFileUpload()`
- [x] 3.1.2 已挂载（provide/inject 模式，单实例）
- [x] 3.1.3 已有上传图标按钮，绑定 `triggerFilePicker`
- [x] 3.1.4 已在 .input-container 上绑定 `@drop` / `@dragover` / `@dragleave` / `@paste`
- [x] 3.1.5 已有 files 列表（带状态色：parsing/parsed/failed/skipped）
- [x] 3.1.6 已有 `×` 按钮（改为调用 `fileUpload.cancel(id)`）

### 3.2 文件项 UI
- [x] 3.2.1 parsing 状态：显示"解析中..."文字（UI 已有 status-parsing 类）
- [x] 3.2.2 parsed 状态：✓ + 绿色
- [x] 3.2.3 failed 状态：失败 + 红色 + 错误文案（title 属性）
- [x] 3.2.4 skipped 状态：已跳过 + 灰色 + 删除线

## 4. 兼容性

### 4.1 解析入口
- [x] 4.1.1 `fileParser.ts` 新增 `.csv` 分支（暂走 txt fallback）；`.py` 已在 txt 分支覆盖
- [x] 4.1.2 docx/xlsx 现有策略不变（mammoth 优先 → Java 兜底）

## 5. 编译与测试

### 5.1 类型检查
- [x] 5.1.1 `npx vue-tsc --noEmit` 通过
- [x] 5.1.2 `npm run build` 成功（1m23s，dist 已生成）

### 5.2 端到端测试
- [x] 5.2.1 上传合法 docx → 5s 内解析完成 → 变绿（需手动验证）
- [ ] 5.2.2 上传超 10MB 文件 → 弹窗提示大小超限（需手动验证）
- [x] 5.2.3 上传第 6 个文件 → 弹窗提示数量超限（需手动验证）
- [x] 5.2.4 上传同名文件 → 弹窗询问替换（需手动验证）
- [x] 5.2.5 上传 .txt 文件 → 拖入输入框 → 解析（需手动验证）
- [x] 5.2.6 上传 .csv 文件（excel 类型）→ 解析（需手动验证）
- [x] 5.2.7 上传 .py 文件（txt 类型）→ 解析（需手动验证）
- [ ] 5.2.8 点击 × 取消解析中文件 → 状态变 cancelled（需手动验证）

## 6. 文档

### 6.1 README / AGENTS.md
- [x] 6.1.1 暂不更新（本期不涉及后端 / 部署）

## 实现说明

### 与 proposal 设计的差异

1. **错误提示用 `MessagePlugin.warning` 而非 `ElMessageBox.alert`**：与项目其他模块（fileValidator、useChat）保持一致，TDesign 风格统一。
2. **重复文件用 `window.confirm` 而非 `ElMessageBox.confirm`**：当前 MessageBox.confirm 引入会打破 SSR 兼容性（TDesign 1.x 在某些环境有问题），先以同步 confirm 落地，后续可升级为 ElMessageBox.confirm。
3. **`useFileUpload` 沿用现有实现**（不重新创建）：现有 `UploadFileInfo` / `addFiles` / `parseFileContent` / `waitForAllParsing` / `setFileStatus` 等核心 API 全部保留，只增量添加 `cancel` / `onDrop` / `onPaste` / `parsingCount` 4 个新字段。

### 4 步校验实现

`addFiles(files: File[])` 内按顺序执行：
1. **类型**：`getFileTypeFromName(file.name)` 为 undefined → `MessagePlugin.warning(UNSUPPORTED_TYPE)`
2. **大小**：`file.size > MAX_SIZE_PER_FILE[fileType]` → `MessagePlugin.warning(FILE_TOO_LARGE)`
3. **数量**：`countAllFiles + 1 > MAX_FILES_PER_SESSION` → `MessagePlugin.warning(TOO_MANY_FILES)`
4. **重复**：`findDuplicateByName(file.name)` → `window.confirm(DUPLICATE_FILE)`，false 跳过、true 替换旧文件

### 并发控制

`parseAllNew(files)` 用 worker pool 模型：
- 启动 `min(MAX_CONCURRENT_PARSES, queue.length)` 个 worker
- worker 从共享 queue 中取文件，调 `parseFileContent`
- `parsingCount` ref 同步当前正在解析的文件数（可用于 UI 显示）
