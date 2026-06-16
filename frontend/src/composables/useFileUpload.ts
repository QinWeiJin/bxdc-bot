/**
 * useFileUpload Composable
 *
 * 任务 3（add-file-upload-composable）产出物。
 * 集中管理文件上传状态、调用任务 2 校验工具、
 * 向上提供 addFiles/removeFile/clearFiles 等方法，
 * 向下为 useChat 暴露 getAllParsedText / getFileNamesForMemory。
 *
 * 解析占位：parseFileContent 当前仅做状态流转，
 * 实际解析由任务 4 (docxParser / xlsxParser / pptxParser) 和
 * 任务 5 (imageOcr) 实现后回填。
 *
 * @module composables/useFileUpload
 */

import { ref, provide, inject, triggerRef, type InjectionKey, type Ref } from 'vue'
import { MessagePlugin } from 'tdesign-vue-next'
import type { FileType, UploadFileInfo } from '../types/fileUpload'
import {
  PARSED_TEXT_MAX_BYTES,
  INSTRUCTION_FILES_MAX_BYTES,
  FILE_UPLOAD_CONFIG,
} from '../types/fileUpload'
import {
  getFileTypeFromName,
  validateFile,
} from '../utils/fileValidator'
import { useConversations } from './useConversations'

/** 文件名查重：在所有已上传文件中查找同名（按 fileName 完全匹配） */
function findDuplicateByName(
  groups: Record<FileType, UploadFileInfo[]>,
  fileName: string,
): UploadFileInfo | undefined {
  for (const ft of Object.keys(groups) as FileType[]) {
    for (const f of groups[ft]) {
      if (f.fileName === fileName) return f
    }
  }
  return undefined
}

/** 移除指定 ID 的文件（跨分组） */
function removeFileById(
  groups: Record<FileType, UploadFileInfo[]>,
  fileId: string,
): UploadFileInfo | undefined {
  for (const ft of Object.keys(groups) as FileType[]) {
    const list = groups[ft]
    const idx = list.findIndex((f) => f.id === fileId)
    if (idx >= 0) {
      const [removed] = list.splice(idx, 1)
      return removed
    }
  }
  return undefined
}

/** 格式化时间戳为 "YYYY-MM-DD HH:mm" */
function formatTime(ts: number): string {
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}`
  )
}

/** 统计 5 个分组中的文件总数 */
function countAllFiles(groups: Record<FileType, UploadFileInfo[]>): number {
  let n = 0
  for (const ft of Object.keys(groups) as FileType[]) n += groups[ft].length
  return n
}

/** 扁平化所有分组的文件列表 */
function allFilesList(groups: Record<FileType, UploadFileInfo[]>): UploadFileInfo[] {
  const out: UploadFileInfo[] = []
  for (const ft of Object.keys(groups) as FileType[]) {
    for (const f of groups[ft]) out.push(f)
  }
  return out
}

// ============================================================
// 1. 类型与 InjectionKey
// ============================================================

/** 校验错误码（与需求方案 A1 模块二 §2.2 严格对齐） */
export type ValidationErrorCode =
  | 'UNSUPPORTED_TYPE'
  | 'FILE_TOO_LARGE'
  | 'TOO_MANY_FILES'
  | 'DUPLICATE_FILE'
  | 'EMPTY_FILE'
  | 'PARSE_TIMEOUT'

/** 状态结构：5 个 FileType 分组的 UploadFileInfo 列表 */
export interface FileUploadState {
  uploadedFiles: Ref<Record<FileType, UploadFileInfo[]>>
  isUploading: Ref<boolean>
  uploadError: Ref<string | null>
  addFiles: (files: File[]) => Promise<UploadFileInfo[]>
  removeFile: (fileId: string) => void
  clearFiles: () => void
  getAllParsedText: (files?: UploadFileInfo[]) => string
  getFileNamesForMemory: () => string[]
  parseFileContent: (file: UploadFileInfo) => Promise<string>
  /**
   * 批量解析文件（带并发限制 MAX_CONCURRENT_PARSES）。
   * 图标/拖拽/粘贴三个入口统一走此通道，避免无界并发。
   */
  parseFiles: (files: UploadFileInfo[]) => Promise<void>
  /**
   * 等待所有处于 `parsing` 状态的文件完成解析，最多等待 `timeoutMs` 毫秒后返回当前状态。
   * 返回三分类：`done`（已 parsed 或 failed）、`pending`（仍 parsing）、`failed`。
   * 任务 pass-parsed-content-to-llm 引入。
   */
  waitForAllParsing: (opts?: { timeoutMs?: number }) => Promise<{
    done: UploadFileInfo[]
    pending: UploadFileInfo[]
    failed: UploadFileInfo[]
  }>
  /**
   * 外部更新文件状态（如把 parsing 文件标为 skipped）。
   * 任务 pass-parsed-content-to-llm 引入。
   */
  setFileStatus: (fileId: string, status: UploadFileInfo['status']) => void
  /** 取消正在解析的文件（需求方案 A1 §2.3.2：点击 × 取消未使用的上传文件） */
  cancel: (fileId: string) => void
  /** 拖拽事件处理：仅当 e.dataTransfer 含文件时触发 addFiles */
  onDrop: (e: DragEvent) => Promise<void>
  /** 粘贴事件处理：仅当 clipboardData.files 非空时触发 addFiles */
  onPaste: (e: ClipboardEvent) => Promise<void>
  /** 当前正在解析的文件数（用于 UI 显示并发状态） */
  parsingCount: Ref<number>
}

const FileUploadKey: InjectionKey<FileUploadState> = Symbol('FileUploadKey')

/** 生成简短唯一 ID */
function genId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

/** 初始化 5 个空分组 */
function emptyGroups(): Record<FileType, UploadFileInfo[]> {
  return {
    word: [],
    excel: [],
    ppt: [],
    txt: [],
    image: [],
  }
}

// ============================================================
// 2. provide / useFileUpload
// ============================================================

// 模块级 state 单例：避免 useFileUpload fallback 每次 new 一个独立 ref，
// 导致"addFiles 写 A ref，UI 读 B ref"永久脱节。
// 根因：某些生命周期时点（Suspense、KeepAlive、onNodeUnmounted）inject 失败，
// 走 fallback 时跟 provideFileUpload 的 state 不是同一个对象。
let _fileUploadState: FileUploadState | null = null

export function provideFileUpload(): FileUploadState {
  // 复用已有 state（避免 App 多次 setup / HMR 重复创建）
  if (_fileUploadState) {
    provide(FileUploadKey, _fileUploadState)
    return _fileUploadState
  }
  const uploadedFiles = ref<Record<FileType, UploadFileInfo[]>>(emptyGroups())
  const isUploading = ref(false)
  const uploadError = ref<string | null>(null)
  const parsingCount = ref(0)
  /** 每文件独立的 AbortController，用于 cancel 取消正在进行的解析 */
  const abortControllers = new Map<string, AbortController>()

  // ---- addFiles ----
  // 4 步校验（需求方案 A1 §2.2）：类型 → 大小 → 数量 → 重复
  // 返回实际成功添加的文件列表（被校验跳过的不会出现在列表中）
  async function addFiles(files: File[]): Promise<UploadFileInfo[]> {
    const added: UploadFileInfo[] = []
    if (!files || files.length === 0) return added

    for (const file of files) {
      // 1. 类型校验：通过扩展名识别 FileType
      const fileType = getFileTypeFromName(file.name)
      if (!fileType) {
        MessagePlugin.warning(FILE_UPLOAD_CONFIG.MESSAGES.UNSUPPORTED_TYPE)
        continue
      }

      // 2. 大小校验：单文件 ≤ MAX_SIZE_PER_FILE[fileType]
      if (file.size > FILE_UPLOAD_CONFIG.MAX_SIZE_PER_FILE[fileType]) {
        MessagePlugin.warning(FILE_UPLOAD_CONFIG.MESSAGES.FILE_TOO_LARGE)
        continue
      }

      // 3. 数量校验：累计总数（含本次） ≤ MAX_FILES_PER_SESSION
      const currentTotal = countAllFiles(uploadedFiles.value)
      if (currentTotal + 1 > FILE_UPLOAD_CONFIG.MAX_FILES_PER_SESSION) {
        MessagePlugin.warning(FILE_UPLOAD_CONFIG.MESSAGES.TOO_MANY_FILES)
        continue
      }

      // 4. 重复校验：全量查重，存在同名则弹窗让用户选择"替换 / 取消"
      const existing = findDuplicateByName(uploadedFiles.value, file.name)
      if (existing) {
        const message = FILE_UPLOAD_CONFIG.MESSAGES.DUPLICATE_FILE(
          file.name,
          formatTime(existing.uploadedAt),
        )
        // 使用同步 confirm：UI 端可替换为 ElMessageBox.confirm
        // 这里为不引入额外 UI 依赖，使用 window.confirm（简化实现）
        const replace = window.confirm(message)
        if (!replace) {
          // 取消：跳过该文件
          continue
        }
        // 替换：移除旧的（保留 previewUrl 释放 + abort 可能的解析中 controller），添加新的
        const removed = removeFileById(uploadedFiles.value, existing.id)
        if (removed?.previewUrl) URL.revokeObjectURL(removed.previewUrl)
        const oldCtrl = abortControllers.get(existing.id)
        if (oldCtrl) {
          oldCtrl.abort()
          abortControllers.delete(existing.id)
        }
      }

      // 通过所有校验，构建 UploadFileInfo
      const info: UploadFileInfo = {
        id: genId(),
        file,
        fileName: file.name,
        fileType,
        size: file.size,
        status: 'pending',
        uploadedAt: Date.now(),
      }

      if (fileType === 'image') {
        info.previewUrl = URL.createObjectURL(file)
      }

      uploadedFiles.value[fileType].push(info)
      added.push(info)
    }

    // 防御性：addFiles 完成后立即触发解析，不再依赖 caller 调 parseFiles
    // （避免 onFileChange / onDrop / onPaste 各自漏调导致文件卡在 pending）
    if (added.length > 0) {
      // fire-and-forget；caller 也可 await parseFiles(added) 等待结果
      parseAllNew(added).catch((e) => console.error('[addFiles] auto-parse failed', e))
    }

    return added
  }

  // ---- removeFile ----
  function removeFile(fileId: string): void {
    const removed = removeFileById(uploadedFiles.value, fileId)
    if (removed?.previewUrl) URL.revokeObjectURL(removed.previewUrl)
    // 取消可能正在进行的解析
    const ctrl = abortControllers.get(fileId)
    if (ctrl) {
      ctrl.abort()
      abortControllers.delete(fileId)
    }
  }

  // ---- cancel (需求 §2.3.2：点击 × 取消未使用的上传文件) ----
  function cancel(fileId: string): void {
    const removed = removeFileById(uploadedFiles.value, fileId)
    if (!removed) return
    if (removed.previewUrl) URL.revokeObjectURL(removed.previewUrl)
    // 状态置为 skipped（保持可追溯）后立即从 UI 移除
    removed.status = 'skipped'
    const ctrl = abortControllers.get(fileId)
    if (ctrl) {
      ctrl.abort()
      abortControllers.delete(fileId)
    }
  }

  // ---- onDrop（拖拽上传）----
  async function onDrop(e: DragEvent): Promise<void> {
    e.preventDefault()
    const files = e.dataTransfer?.files
    if (!files || files.length === 0) return
    await addFiles(Array.from(files))
    // 自动解析新加入的文件
    const newest = allFilesList(uploadedFiles.value).slice(-files.length)
    await parseAllNew(newest)
  }

  // ---- onPaste（Ctrl+V 粘贴）----
  async function onPaste(e: ClipboardEvent): Promise<void> {
    const files = e.clipboardData?.files
    if (!files || files.length === 0) return
    e.preventDefault()
    await addFiles(Array.from(files))
    const newest = allFilesList(uploadedFiles.value).slice(-files.length)
    await parseAllNew(newest)
  }

  // ---- 并发解析控制 ----
  async function parseAllNew(files: UploadFileInfo[]): Promise<void> {
    const max = FILE_UPLOAD_CONFIG.MAX_CONCURRENT_PARSES
    const queue = [...files]
    const runners: Promise<void>[] = []
    for (let i = 0; i < Math.min(max, queue.length); i++) {
      runners.push(worker(queue))
    }
    await Promise.all(runners)

    async function worker(q: UploadFileInfo[]): Promise<void> {
      while (q.length > 0) {
        const next = q.shift()
        if (!next) break
        parsingCount.value++
        try {
          await parseFileContent(next)
        } catch {
          /* parseFileContent 已设置 status=failed */
        } finally {
          parsingCount.value--
        }
      }
    }
  }

  // ---- clearFiles ----
  function clearFiles(): void {
    for (const fileType of Object.keys(uploadedFiles.value) as FileType[]) {
      for (const f of uploadedFiles.value[fileType]) {
        if (f.previewUrl) {
          URL.revokeObjectURL(f.previewUrl)
        }
      }
    }
    uploadedFiles.value = emptyGroups()
    uploadError.value = null
  }

  // ---- getAllParsedText ----
  // 任务 pass-parsed-content-to-llm：增加单文件 / 总大小截断 + truncated 标记
  // 接受外部传入的 files 列表（避免在 sendMessage 内部调用 useFileUpload 时 inject 失败）
  function getAllParsedText(files?: UploadFileInfo[]): string {
    const source: UploadFileInfo[] = files
      ? files
      : (() => {
          const out: UploadFileInfo[] = []
          for (const ft of Object.keys(uploadedFiles.value) as FileType[]) {
            for (const f of uploadedFiles.value[ft]) out.push(f)
          }
          return out
        })()
    const parts: string[] = []
    let totalBytes = 0
    for (const f of source) {
        if (f.status !== 'parsed' || !f.parsedText) continue

        const encoder = new TextEncoder()
        const originalBytes = encoder.encode(f.parsedText).length
        const originalKB = Math.ceil(originalBytes / 1024)
        let text = f.parsedText
        let truncated = false

        // 单文件截断
        if (originalBytes > PARSED_TEXT_MAX_BYTES) {
          // 按字符数粗略截断（避免乱码），约 1.3 字符 / 字节
          const maxChars = Math.floor(PARSED_TEXT_MAX_BYTES / 1.3)
          text = text.slice(0, maxChars) + `\n... [内容已截断，原 ${originalKB} KB]`
          truncated = true
        }

        // 总大小截断（按 UTF-8 字节数计算当前累计）
        const currentBytes = encoder.encode(text).length
        if (totalBytes + currentBytes > INSTRUCTION_FILES_MAX_BYTES) {
          const remainingBytes = INSTRUCTION_FILES_MAX_BYTES - totalBytes
          if (remainingBytes <= 0) {
            // 配额已用完，文件整段截断为提示
            text = `... [因总大小限制已截断：${f.fileName} 内容未发送]`
            truncated = true
          } else {
            const maxChars = Math.floor(remainingBytes / 1.3)
            text = text.slice(0, maxChars) + `\n... [因总大小限制已截断]`
            truncated = true
          }
        }

        // 标记 truncated 状态（供 UI 徽标展示），仅在尚未设置时写入
        if (truncated && !f.truncated) {
          f.truncated = true
        }

        totalBytes += encoder.encode(text).length
        parts.push(`--- 文件：${f.fileName} ---\n${text}`)

        // 总配额已用完，后续文件不再追加（避免无意义拼接）
        if (totalBytes >= INSTRUCTION_FILES_MAX_BYTES) break
    }
    return parts.join('\n\n')
  }

  // ---- getFileNamesForMemory ----
  function getFileNamesForMemory(): string[] {
    const names: string[] = []
    for (const fileType of Object.keys(uploadedFiles.value) as FileType[]) {
      for (const f of uploadedFiles.value[fileType]) {
        names.push(f.fileName)
      }
    }
    return names
  }

  // ---- parseFileContent ----
  // 任务 4-5 接入 fileParser.parseDocument（docx/xlsx/txt 已实做；ppt/image 走 agent-core 兜底）
  // 模块二：注册 AbortController 用于 cancel 取消
  // 幂等表：避免 addFiles auto-trigger + caller 显式 parseFiles 双调用导致重复上传
  // 第二次调用拿到第一次的同一个 promise，HTTP 请求只发一次
  const inflightParse = new Map<string, Promise<string>>()

  async function parseFileContent(file: UploadFileInfo): Promise<string> {
    if (file.status === 'parsed') {
      return file.parsedText ?? ''
    }
    // 幂等：如果该文件正在解析，返回同一个 promise，不再发新请求
    const inflight = inflightParse.get(file.id)
    if (inflight) return inflight

    const promise = doParseFile(file)
    inflightParse.set(file.id, promise)
    try {
      return await promise
    } finally {
      inflightParse.delete(file.id)
    }
  }

  async function doParseFile(file: UploadFileInfo): Promise<string> {
    const controller = new AbortController()
    abortControllers.set(file.id, controller)
    file.status = 'parsing'
    console.log('[parseFile] start', file.id, 'status=', file.status)
    try {
      const { parseDocument } = await import('../utils/fileParser')
      const convId = useConversations().currentConversationId.value
      const text = await parseDocument(file.file, file.fileType, controller.signal, convId)
      console.log('[parseFile] parseDocument returned', file.id, 'len=', text.length)
      if (controller.signal.aborted) {
        file.status = 'skipped'
        throw new DOMException('已取消', 'AbortError')
      }
      file.parsedText = text
      file.status = 'parsed'
      console.log('[parseFile] set parsed', file.id, 'status=', file.status)
      // 强制触发响应式（防御性，应对某些情况下 Proxy 没追踪到嵌套对象 mutation）
      triggerRef(uploadedFiles)
      return text
    } catch (err) {
      console.log('[parseFile] error', file.id, err instanceof Error ? err.message : err)
      if (err instanceof DOMException && err.name === 'AbortError') {
        if (file.status !== 'skipped') file.status = 'skipped'
      } else {
        file.status = 'failed'
        file.errorMessage = err instanceof Error ? err.message : '解析失败'
      }
      triggerRef(uploadedFiles)
      throw err
    } finally {
      abortControllers.delete(file.id)
    }
  }

  // ---- waitForAllParsing ----
  // 任务 pass-parsed-content-to-llm：等待所有 parsing 状态文件完成（解析完成 / 失败 / 超时）
  function listAll(): UploadFileInfo[] {
    const out: UploadFileInfo[] = []
    for (const ft of Object.keys(uploadedFiles.value) as FileType[]) {
      for (const f of uploadedFiles.value[ft]) out.push(f)
    }
    return out
  }
  async function waitForAllParsing(opts: { timeoutMs?: number } = {}): Promise<{
    done: UploadFileInfo[]
    pending: UploadFileInfo[]
    failed: UploadFileInfo[]
  }> {
    const timeoutMs = opts.timeoutMs ?? 5000
    const all = listAll()
    const parsing = all.filter((f) => f.status === 'parsing')
    if (parsing.length === 0) {
      return {
        done: all.filter((f) => f.status === 'parsed'),
        pending: [],
        failed: all.filter((f) => f.status === 'failed'),
      }
    }
    // 通过轮询方式等待所有 parsing 完成（5s 超时）
    const start = Date.now()
    const tick = 100
    return await new Promise((resolve) => {
      const check = () => {
        const cur = listAll()
        const stillParsing = cur.filter((f) => f.status === 'parsing')
        if (stillParsing.length === 0 || Date.now() - start >= timeoutMs) {
          resolve({
            done: cur.filter((f) => f.status === 'parsed'),
            pending: cur.filter((f) => f.status === 'parsing'),
            failed: cur.filter((f) => f.status === 'failed'),
          })
        } else {
          setTimeout(check, tick)
        }
      }
      check()
    })
  }

  // ---- setFileStatus ----
  // 任务 pass-parsed-content-to-llm：外部更新文件状态（如把 parsing 标为 skipped）
  function setFileStatus(fileId: string, status: UploadFileInfo['status']): void {
    for (const ft of Object.keys(uploadedFiles.value) as FileType[]) {
      const target = uploadedFiles.value[ft].find((f) => f.id === fileId)
      if (target) {
        target.status = status
        return
      }
    }
  }

  const state: FileUploadState = {
    uploadedFiles: uploadedFiles,
    isUploading: isUploading,
    uploadError: uploadError,
    addFiles: addFiles,
    removeFile: removeFile,
    clearFiles: clearFiles,
    getAllParsedText: getAllParsedText,
    getFileNamesForMemory: getFileNamesForMemory,
    parseFileContent: parseFileContent,
    parseFiles: parseAllNew,
    waitForAllParsing: waitForAllParsing,
    setFileStatus: setFileStatus,
    cancel: cancel,
    onDrop: onDrop,
    onPaste: onPaste,
    parsingCount: parsingCount,
  }
  provide(FileUploadKey, state)
  _fileUploadState = state
  return state
}

export function useFileUpload(): FileUploadState {
  // 关键：先检查模块级单例。provide/inject 在某些时点会失败
  // （Suspense、KeepAlive、onNodeUnmounted 期间），fallback 必须返回同一个 state
  if (_fileUploadState) return _fileUploadState
  const provided = inject(FileUploadKey)
  if (provided) {
    _fileUploadState = provided
    return provided
  }

  // 降级：返回本地初始化的 state（单组件独立使用，不通过 provide 共享）
  const uploadedFiles = ref<Record<FileType, UploadFileInfo[]>>(emptyGroups())
  const isUploading = ref(false)
  const uploadError = ref<string | null>(null)

  async function addFiles(files: File[]): Promise<UploadFileInfo[]> {
    const added: UploadFileInfo[] = []
    if (!files || files.length === 0) return added
    for (const file of files) {
      const fileType = getFileTypeFromName(file.name)
      if (!fileType) {
        MessagePlugin.error(`不支持的文件格式：${file.name}`)
        continue
      }
      const validation = await validateFile(file, uploadedFiles.value[fileType])
      if (!validation.valid) {
        for (const err of validation.errors) MessagePlugin.error(err)
        continue
      }
      const info: UploadFileInfo = {
        id: genId(),
        file,
        fileName: file.name,
        fileType,
        size: file.size,
        status: 'pending',
        uploadedAt: Date.now(),
        previewUrl: fileType === 'image' ? URL.createObjectURL(file) : undefined,
      }
      uploadedFiles.value[fileType].push(info)
      added.push(info)
    }
    return added
  }

  function removeFile(fileId: string): void {
    for (const ft of Object.keys(uploadedFiles.value) as FileType[]) {
      const list = uploadedFiles.value[ft]
      const removed = list.find((f) => f.id === fileId)
      if (removed) {
        if (removed.previewUrl) URL.revokeObjectURL(removed.previewUrl)
        const idx = list.indexOf(removed)
        list.splice(idx, 1)
        return
      }
    }
  }

  function clearFiles(): void {
    for (const ft of Object.keys(uploadedFiles.value) as FileType[]) {
      for (const f of uploadedFiles.value[ft]) {
        if (f.previewUrl) URL.revokeObjectURL(f.previewUrl)
      }
    }
    uploadedFiles.value = emptyGroups()
    uploadError.value = null
  }

  function getAllParsedText(files?: UploadFileInfo[]): string {
    const source: UploadFileInfo[] = files
      ? files
      : (() => {
          const out: UploadFileInfo[] = []
          for (const ft of Object.keys(uploadedFiles.value) as FileType[]) {
            for (const f of uploadedFiles.value[ft]) out.push(f)
          }
          return out
        })()
    const parts: string[] = []
    let totalBytes = 0
    for (const f of source) {
        if (f.status !== 'parsed' || !f.parsedText) continue

        const encoder = new TextEncoder()
        const originalBytes = encoder.encode(f.parsedText).length
        const originalKB = Math.ceil(originalBytes / 1024)
        let text = f.parsedText
        let truncated = false

        if (originalBytes > PARSED_TEXT_MAX_BYTES) {
          const maxChars = Math.floor(PARSED_TEXT_MAX_BYTES / 1.3)
          text = text.slice(0, maxChars) + `\n... [内容已截断，原 ${originalKB} KB]`
          truncated = true
        }

        const currentBytes = encoder.encode(text).length
        if (totalBytes + currentBytes > INSTRUCTION_FILES_MAX_BYTES) {
          const remainingBytes = INSTRUCTION_FILES_MAX_BYTES - totalBytes
          if (remainingBytes <= 0) {
            text = `... [因总大小限制已截断：${f.fileName} 内容未发送]`
            truncated = true
          } else {
            const maxChars = Math.floor(remainingBytes / 1.3)
            text = text.slice(0, maxChars) + `\n... [因总大小限制已截断]`
            truncated = true
          }
        }

        if (truncated && !f.truncated) f.truncated = true

        totalBytes += encoder.encode(text).length
        parts.push(`--- 文件：${f.fileName} ---\n${text}`)

        if (totalBytes >= INSTRUCTION_FILES_MAX_BYTES) break
    }
    return parts.join('\n\n')
  }

  function getFileNamesForMemory(): string[] {
    const names: string[] = []
    for (const ft of Object.keys(uploadedFiles.value) as FileType[]) {
      for (const f of uploadedFiles.value[ft]) names.push(f.fileName)
    }
    return names
  }

  async function parseFileContent(file: UploadFileInfo): Promise<string> {
    if (file.status === 'parsed') return file.parsedText ?? ''
    file.status = 'parsing'
    try {
      const { parseDocument } = await import('../utils/fileParser')
      const convId = useConversations().currentConversationId.value
      const text = await parseDocument(file.file, file.fileType, undefined, convId)
      file.parsedText = text
      file.status = 'parsed'
      return text
    } catch (err) {
      file.status = 'failed'
      file.errorMessage = err instanceof Error ? err.message : '解析失败'
      throw err
    }
  }

  function listAll(): UploadFileInfo[] {
    const out: UploadFileInfo[] = []
    for (const ft of Object.keys(uploadedFiles.value) as FileType[]) {
      for (const f of uploadedFiles.value[ft]) out.push(f)
    }
    return out
  }
  async function waitForAllParsing(opts: { timeoutMs?: number } = {}): Promise<{
    done: UploadFileInfo[]
    pending: UploadFileInfo[]
    failed: UploadFileInfo[]
  }> {
    const timeoutMs = opts.timeoutMs ?? 5000
    const all = listAll()
    const parsing = all.filter((f) => f.status === 'parsing')
    if (parsing.length === 0) {
      return {
        done: all.filter((f) => f.status === 'parsed'),
        pending: [],
        failed: all.filter((f) => f.status === 'failed'),
      }
    }
    const start = Date.now()
    const tick = 100
    return await new Promise((resolve) => {
      const check = () => {
        const cur = listAll()
        const stillParsing = cur.filter((f) => f.status === 'parsing')
        if (stillParsing.length === 0 || Date.now() - start >= timeoutMs) {
          resolve({
            done: cur.filter((f) => f.status === 'parsed'),
            pending: cur.filter((f) => f.status === 'parsing'),
            failed: cur.filter((f) => f.status === 'failed'),
          })
        } else {
          setTimeout(check, tick)
        }
      }
      check()
    })
  }

  function setFileStatus(fileId: string, status: UploadFileInfo['status']): void {
    for (const ft of Object.keys(uploadedFiles.value) as FileType[]) {
      const target = uploadedFiles.value[ft].find((f) => f.id === fileId)
      if (target) {
        target.status = status
        return
      }
    }
  }

  // ---- 独立使用分支（无 provide）的简化实现 ----
  const parsingCountStub = ref(0)
  function cancelStub(fileId: string): void {
    removeFile(fileId)
  }
  async function onDropStub(e: DragEvent): Promise<void> {
    e.preventDefault()
    const files = e.dataTransfer?.files
    if (!files || files.length === 0) return
    const added = await addFiles(Array.from(files))
    await parseFilesStub(added)
  }
  async function onPasteStub(e: ClipboardEvent): Promise<void> {
    const files = e.clipboardData?.files
    if (!files || files.length === 0) return
    e.preventDefault()
    const added = await addFiles(Array.from(files))
    await parseFilesStub(added)
  }
  // 独立分支的并发解析（与 provideFileUpload 的 parseAllNew 功能等价，但简单实现）
  async function parseFilesStub(files: UploadFileInfo[]): Promise<void> {
    const max = FILE_UPLOAD_CONFIG.MAX_CONCURRENT_PARSES
    const queue = [...files]
    const runners: Promise<void>[] = []
    for (let i = 0; i < Math.min(max, queue.length); i++) {
      runners.push((async () => {
        while (queue.length > 0) {
          const next = queue.shift()!
          parsingCountStub.value++
          try { await parseFileContent(next) } catch { /* 已在 parseFileContent 设 status=failed */ }
          finally { parsingCountStub.value-- }
        }
      })())
    }
    await Promise.all(runners)
  }

  const state: FileUploadState = {
    uploadedFiles: uploadedFiles,
    isUploading: isUploading,
    uploadError: uploadError,
    addFiles: addFiles,
    removeFile: removeFile,
    clearFiles: clearFiles,
    getAllParsedText: getAllParsedText,
    getFileNamesForMemory: getFileNamesForMemory,
    parseFileContent: parseFileContent,
    parseFiles: parseFilesStub,
    waitForAllParsing: waitForAllParsing,
    setFileStatus: setFileStatus,
    cancel: cancelStub,
    onDrop: onDropStub,
    onPaste: onPasteStub,
    parsingCount: parsingCountStub,
  }
  // fallback 分支也存到模块级单例，下次 inject 失败直接返回这个
  _fileUploadState = state
  return state
}
