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

import { ref, provide, inject, type InjectionKey, type Ref } from 'vue'
import { MessagePlugin } from 'tdesign-vue-next'
import type { FileType, UploadFileInfo } from '../types/fileUpload'
import {
  getFileTypeFromName,
  validateFile,
  validateTotalSize,
  validateFileCount,
} from '../utils/fileValidator'

// ============================================================
// 1. 类型与 InjectionKey
// ============================================================

/** 状态结构：5 个 FileType 分组的 UploadFileInfo 列表 */
export interface FileUploadState {
  uploadedFiles: Ref<Record<FileType, UploadFileInfo[]>>
  isUploading: Ref<boolean>
  uploadError: Ref<string | null>
  addFiles: (files: File[]) => Promise<void>
  removeFile: (fileId: string) => void
  clearFiles: () => void
  getAllParsedText: () => string
  getFileNamesForMemory: () => string[]
  parseFileContent: (file: UploadFileInfo) => Promise<string>
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

export function provideFileUpload(): FileUploadState {
  const uploadedFiles = ref<Record<FileType, UploadFileInfo[]>>(emptyGroups())
  const isUploading = ref(false)
  const uploadError = ref<string | null>(null)

  // ---- addFiles ----
  async function addFiles(files: File[]): Promise<void> {
    if (!files || files.length === 0) return

    for (const file of files) {
      // 1. 通过扩展名识别 FileType（找不到则视为不支持）
      const fileType = getFileTypeFromName(file.name)
      if (!fileType) {
        MessagePlugin.error(`不支持的文件格式：${file.name}`)
        continue
      }

      // 2. 累计大小校验（基于同类型已有文件 + 当前文件）
      const existingOfType = uploadedFiles.value[fileType]
      const totalResult = validateTotalSize(existingOfType, file, fileType)
      if (!totalResult.valid) {
        for (const err of totalResult.errors) {
          MessagePlugin.error(err)
        }
        continue
      }

      // 3. 数量校验
      const countResult = validateFileCount(existingOfType, fileType)
      if (!countResult.valid) {
        for (const err of countResult.errors) {
          MessagePlugin.error(err)
        }
        continue
      }

      // 4. 组合校验（格式 / 大小 / 加密）
      const validation = await validateFile(file, existingOfType)
      if (!validation.valid) {
        for (const err of validation.errors) {
          MessagePlugin.error(err)
        }
        continue
      }

      // 5. 通过校验，构建 UploadFileInfo
      const info: UploadFileInfo = {
        id: genId(),
        file,
        fileName: file.name,
        fileType,
        size: file.size,
        status: 'pending',
        uploadedAt: Date.now(),
      }

      // image 类型额外生成预览 URL
      if (fileType === 'image') {
        info.previewUrl = URL.createObjectURL(file)
      }

      uploadedFiles.value[fileType].push(info)
    }
  }

  // ---- removeFile ----
  function removeFile(fileId: string): void {
    for (const fileType of Object.keys(uploadedFiles.value) as FileType[]) {
      const list = uploadedFiles.value[fileType]
      const removed = list.find((f) => f.id === fileId)
      if (removed) {
        if (removed.previewUrl) {
          URL.revokeObjectURL(removed.previewUrl)
        }
        const idx = list.indexOf(removed)
        list.splice(idx, 1)
        return
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
  function getAllParsedText(): string {
    const parts: string[] = []
    for (const fileType of Object.keys(uploadedFiles.value) as FileType[]) {
      for (const f of uploadedFiles.value[fileType]) {
        if (f.status === 'parsed' && f.parsedText) {
          parts.push(`--- 文件：${f.fileName} ---\n${f.parsedText}`)
        }
      }
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

  // ---- parseFileContent (占位) ----
  // 任务 4-5 完成后回填实际解析逻辑（docxParser / xlsxParser / pptxParser / imageOcr）
  async function parseFileContent(file: UploadFileInfo): Promise<string> {
    if (file.status === 'parsed') {
      return file.parsedText ?? ''
    }

    file.status = 'parsing'
    // 占位：当前不实现实际解析，状态保持 'parsing' 等待任务 4-5 接入
    // 任务 4-5 完成后，此处会替换为：
    //   const parser = getParser(file.fileType)
    //   const text = await parser(file.file)
    //   file.parsedText = text
    //   file.status = 'parsed'
    return ''
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
  }
  provide(FileUploadKey, state)
  return state
}

export function useFileUpload(): FileUploadState {
  const provided = inject(FileUploadKey)
  if (provided) return provided

  // 降级：返回本地初始化的 state（单组件独立使用，不通过 provide 共享）
  const uploadedFiles = ref<Record<FileType, UploadFileInfo[]>>(emptyGroups())
  const isUploading = ref(false)
  const uploadError = ref<string | null>(null)

  async function addFiles(files: File[]): Promise<void> {
    if (!files || files.length === 0) return
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
    }
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

  function getAllParsedText(): string {
    const parts: string[] = []
    for (const ft of Object.keys(uploadedFiles.value) as FileType[]) {
      for (const f of uploadedFiles.value[ft]) {
        if (f.status === 'parsed' && f.parsedText) {
          parts.push(`--- 文件：${f.fileName} ---\n${f.parsedText}`)
        }
      }
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
    return ''
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
  }
  return state
}
