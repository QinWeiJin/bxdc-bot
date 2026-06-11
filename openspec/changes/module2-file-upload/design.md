# Design: module2-file-upload

## 1. 整体架构

```mermaid
flowchart LR
    UI[MessageInput.vue] -->|点击按钮 / 拖拽 / 粘贴| Comp[useFileUpload composable]
    Comp -->|File[] 输入| Valid[Validate 4 步]
    Valid -->|失败| Dialog[弹窗提示]
    Valid -->|通过| State[上传状态机]
    State -->|pending / uploading / done| UI
    Comp -->|解析文本| Parser[parseDocument fileParser.ts]
    State -->|trigger| Parser
    Parser -->|纯文本| State
    State -->|callback| UI

    style Comp fill:#c8e6c9,color:#1a5e20
    style Valid fill:#fff3e0,color:#e65100
    style State fill:#e3f2fd,color:#0d47a1
```

## 2. 上传 Composable 设计

### 2.1 接口签名

```ts
// frontend/src/composables/useFileUpload.ts
export interface UseFileUploadOptions {
  fileType: FileType                // 当前消息的目标 fileType（word/excel/...）
  onParsed: (file: UploadedFile) => void
  onError: (error: UploadError) => void
}

export interface UploadedFile {
  id: string
  file: File
  status: 'pending' | 'parsing' | 'done' | 'failed' | 'cancelled'
  parsedText?: string
  errorMessage?: string
  uploadedAt: number
}

export function useFileUpload(opts: UseFileUploadOptions): {
  files: Ref<UploadedFile[]>
  upload: (incoming: File[]) => Promise<void>
  cancel: (id: string) => void
  remove: (id: string) => void
  onDrop: (e: DragEvent) => void
  onPaste: (e: ClipboardEvent) => void
  triggerFilePicker: () => void
}
```

### 2.2 状态机

```
pending → parsing → done
                ↘  failed
                ↘  cancelled（用户点击 ×）
```

## 3. 4 步校验

| 步骤 | 校验 | 失败处理 | 错误码 |
|------|------|---------|--------|
| 1 | 类型 | 弹窗 | `UNSUPPORTED_TYPE` |
| 2 | 大小 | 弹窗 | `FILE_TOO_LARGE` |
| 3 | 数量 | 弹窗 | `TOO_MANY_FILES` |
| 4 | 重复 | 弹窗（带选项） | `DUPLICATE_FILE` |

**重复校验**（步骤 4）特殊：弹窗让用户选择"替换"或"取消"：
- 替换：删除已存在的同名 fileId，添加新文件
- 取消：跳过该文件，继续处理后续

## 4. 三种上传入口

| 入口 | 触发方式 | 实现 |
|------|---------|------|
| 图标点击 | 隐藏 `<input type="file" multiple>` + ref 触发 | `triggerFilePicker()` |
| 拖拽 | `@dragover` / `@drop` 在聊天输入框上 | `onDrop(e)` |
| `Ctrl+V` 粘贴 | 监听 `paste` 事件，提取 `e.clipboardData?.files` | `onPaste(e)` |

## 5. FILE_UPLOAD_CONFIG 调整

```ts
// frontend/src/types/fileUpload.ts
export const FILE_UPLOAD_CONFIG: FileUploadConfig = {
  maxFileSizeMib: {
    word: 10,      // 需求 2.2.2：10MB
    excel: 10,     // 需求 2.2.2：10MB
    ppt: 10,       // 现状
    txt: 10,       // 需求 2.2.2
    image: 10,     // 现状
  },
  maxFilesPerSession: 5,            // 需求 2.2.3
  maxConcurrent: 3,                  // 内部并发上限
  allowedExtensions: {
    word: ['.doc', '.docx'],
    excel: ['.xls', '.xlsx', '.csv'], // +csv
    ppt: ['.ppt', '.pptx'],
    txt: ['.txt', '.md', '.py'],      // +py（解析暂走 txt）
    image: ['.png', '.jpg', '.jpeg', '.gif', '.webp', '.svg'],
  },
  unsupportedMessage: '当前仅支持doc、docx、xls、xlsx、csv、txt以及md文件的上传',
  fileTooLargeMessage: '文件大小超过10Mb，请修改后重试。',
  tooManyFilesMessage: '单次最多上传5个文件，请减少选择。',
  duplicateFileMessage: (name: string, time: string) =>
    `该文件已于${time}上传，是否进行替换？`,
}
```

## 6. 错误提示统一出口

```ts
// frontend/src/composables/useFileUpload.ts
function showValidationError(code: ValidationErrorCode, fileName?: string) {
  const messages = {
    UNSUPPORTED_TYPE: FILE_UPLOAD_CONFIG.unsupportedMessage,
    FILE_TOO_LARGE: FILE_UPLOAD_CONFIG.fileTooLargeMessage,
    TOO_MANY_FILES: FILE_UPLOAD_CONFIG.tooManyFilesMessage,
    DUPLICATE_FILE: FILE_UPLOAD_CONFIG.duplicateFileMessage(...),
  }
  ElMessageBox.alert(messages[code], '上传提示', { type: 'warning' })
}
```

## 7. 取消上传实现

每个 `UploadedFile` 持有自己的 `AbortController`，点击 `×` 时：
```ts
const cancel = (id: string) => {
  const f = files.value.find(x => x.id === id)
  f?.controller?.abort()
  f.status = 'cancelled'
  // 立即从列表移除（或保留 0.5s 显示过渡）
  setTimeout(() => remove(id), 500)
}
```

## 8. 交互效果

```scss
.uploaded-file {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  transition: all 0.3s;

  &.parsing {
    border-color: #909399;
    .status-dot { animation: spin 1s linear infinite; }
  }

  &.done {
    border-color: #67c23a;
    color: #67c23a;
    background: #f0f9eb;
  }

  &.failed {
    border-color: #f56c6c;
    color: #f56c6c;
    background: #fef0f0;
  }
}
```

## 9. 测试要点

- [ ] 单元测试：`useFileUpload` 的 4 步校验逻辑
- [ ] 集成测试：模拟拖拽 / 粘贴事件
- [ ] E2E 测试：完整上传 → 解析 → 变色 → 取消流程

## 10. 风险

| 风险 | 缓解 |
|------|------|
| 拖拽时与文件拖入导致页面跳转 | `@dragover.prevent` 阻止默认 |
| 粘贴非文件内容（截图）干扰 | 校验 `clipboardData.files.length > 0` |
| 并发解析占用主线程 | `maxConcurrent = 3` 限制 |
| 重复校验需要"全量目录"，但本期无后端 | 暂时基于当前 session 内 `files` 去重（标注 TODO） |
