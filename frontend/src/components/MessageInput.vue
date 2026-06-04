<script setup lang="ts">
import { ref, computed } from 'vue'
import { ChatSender as TChatSender } from '@tdesign-vue-next/chat'
import { DeleteIcon } from 'tdesign-icons-vue-next'
import { useChat } from '../composables/useChat'
import { useUser } from '../composables/useUser'
import { useFileUpload } from '../composables/useFileUpload'
import { FILE_INPUT_ACCEPT, FILE_TYPE_ICONS, FILE_TYPE_LABELS } from '../types/fileUpload'
import type { FileType, UploadFileInfo } from '../types/fileUpload'

const { sendMessage, isThinking } = useChat()
const { currentUser } = useUser()
const fileUpload = useFileUpload()
const input = ref('')
const fileInputRef = ref<HTMLInputElement | null>(null)

/** 扁平化所有已上传文件 */
const allFiles = computed<UploadFileInfo[]>(() => {
  const groups = fileUpload.uploadedFiles.value
  return [
    ...groups.word,
    ...groups.excel,
    ...groups.ppt,
    ...groups.txt,
    ...groups.image,
  ]
})

/** 文档分组（word/excel/ppt/txt） */
const documentFiles = computed(() => {
  const g = fileUpload.uploadedFiles.value
  return [...g.word, ...g.excel, ...g.ppt, ...g.txt]
})

/** 图片分组 */
const imageFiles = computed(() => fileUpload.uploadedFiles.value.image)

/** 是否显示文件列表 */
const showFileList = computed(() => allFiles.value.length > 0)

/** 触发文件选择器 */
function triggerFilePicker() {
  fileInputRef.value?.click()
}

/** 文件选择回调 */
async function onFileChange(e: Event) {
  const target = e.target as HTMLInputElement
  if (!target.files || target.files.length === 0) return

  const files = Array.from(target.files)
  await fileUpload.addFiles(files)

  // 自动触发解析（任务 4-5 完成后会真正工作）
  const fresh = allFiles.value.slice(-files.length)
  await Promise.all(fresh.map((f) => fileUpload.parseFileContent(f)))

  // 重置 input 以便下次能选同名文件
  target.value = ''
}

/** 移除文件 */
function onRemoveFile(id: string) {
  fileUpload.removeFile(id)
}

/** 格式化文件大小 */
function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(2)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(2)} MiB`
}

/** 获取文件类型展示名 */
function getFileLabel(type: FileType): string {
  return FILE_TYPE_LABELS[type]
}

async function handleSend(value: string) {
  // @ts-ignore
  const text = (typeof value === 'string' ? value : value?.text || '').trim()
  if (!text || isThinking.value) return
  input.value = ''
  // 任务 8：若有文件则一并传给 sendMessage（拼接内容 + /memory/add + clearFiles）
  const files = allFiles.value
  if (files.length > 0) {
    await sendMessage(text, currentUser.value?.id, files)
  } else {
    await sendMessage(text, currentUser.value?.id)
  }
}
</script>

<template>
  <div class="input-container">
    <!-- 隐藏的文件 input -->
    <input
      ref="fileInputRef"
      type="file"
      :accept="FILE_INPUT_ACCEPT"
      multiple
      style="display: none"
      @change="onFileChange"
    />

    <!-- 已选文件列表（仅在有文件时显示） -->
    <div v-if="showFileList" class="file-list">
      <!-- 文档分组 -->
      <div v-if="documentFiles.length > 0" class="file-list-group">
        <div class="file-list-group-title">文档 ({{ documentFiles.length }})</div>
        <div class="file-list-items">
          <div v-for="f in documentFiles" :key="f.id" class="file-list-item">
            <span class="file-icon">{{ FILE_TYPE_ICONS[f.fileType] }}</span>
            <div class="file-info">
              <div class="file-name" :title="f.fileName">{{ f.fileName }}</div>
              <div class="file-meta">
                <span>{{ getFileLabel(f.fileType) }}</span>
                <span class="dot">·</span>
                <span>{{ formatSize(f.size) }}</span>
                <span v-if="f.status === 'parsing'" class="status status-parsing">解析中...</span>
                <span v-else-if="f.status === 'parsed'" class="status status-parsed">✓</span>
                <span v-else-if="f.status === 'failed'" class="status status-failed" :title="f.errorMessage">失败</span>
              </div>
            </div>
            <t-button
              size="small"
              variant="text"
              theme="default"
              class="file-remove"
              @click="onRemoveFile(f.id)"
            >
              <template #icon><DeleteIcon /></template>
            </t-button>
          </div>
        </div>
      </div>

      <!-- 图片分组 -->
      <div v-if="imageFiles.length > 0" class="file-list-group">
        <div class="file-list-group-title">图片 ({{ imageFiles.length }})</div>
        <div class="file-list-items file-list-images">
          <div v-for="f in imageFiles" :key="f.id" class="file-list-item file-list-image-item">
            <div class="image-thumb">
              <img v-if="f.previewUrl" :src="f.previewUrl" :alt="f.fileName" />
              <span v-else class="file-icon">{{ FILE_TYPE_ICONS.image }}</span>
            </div>
            <div class="file-info">
              <div class="file-name" :title="f.fileName">{{ f.fileName }}</div>
              <div class="file-meta">
                <span>{{ formatSize(f.size) }}</span>
                <span v-if="f.status === 'parsing'" class="status status-parsing">识别中...</span>
                <span v-else-if="f.status === 'parsed'" class="status status-parsed">✓</span>
                <span v-else-if="f.status === 'failed'" class="status status-failed" :title="f.errorMessage">失败</span>
              </div>
            </div>
            <t-button
              size="small"
              variant="text"
              theme="default"
              class="file-remove"
              @click="onRemoveFile(f.id)"
            >
              <template #icon><DeleteIcon /></template>
            </t-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 文本输入区 + 上传按钮 -->
    <div class="chat-sender-row">
      <TChatSender
        v-model="input"
        class="chat-sender"
        :loading="isThinking"
        placeholder="输入消息，Enter 发送，Shift + Enter 换行"
        :textarea-props="{ autosize: { minRows: 1, maxRows: 6 } }"
        @send="handleSend"
      />
      <t-tooltip content="上传文件">
        <button
          type="button"
          class="upload-btn"
          :disabled="isThinking"
          @click="triggerFilePicker"
        >
          <span class="upload-emoji">📎</span>
        </button>
      </t-tooltip>
    </div>

    <p class="input-disclaimer">AI 生成内容可能有误，请注意甄别。</p>
  </div>
</template>

<style scoped>
.input-container {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

/* ---------- 文件列表 ---------- */
.file-list {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 0;
}

.file-list-group {
  width: 100%;
  background-color: var(--td-bg-color-secondarycontainer);
  border-radius: 8px;
  padding: 8px 12px;
  border: 1px solid var(--td-border-level-2-color);
}

.file-list-group-title {
  font-size: 12px;
  color: var(--td-text-color-secondary);
  margin-bottom: 6px;
  font-weight: 500;
}

.file-list-items {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.file-list-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  border-radius: 6px;
  background-color: var(--td-bg-color-container);
  transition: background-color 0.15s;
}

.file-list-item:hover {
  background-color: var(--td-bg-color-secondarycontainer-hover);
}

.file-icon {
  font-size: 22px;
  line-height: 1;
  flex-shrink: 0;
}

.image-thumb {
  width: 48px;
  height: 48px;
  border-radius: 4px;
  overflow: hidden;
  flex-shrink: 0;
  background-color: var(--td-bg-color-secondarycontainer);
  display: flex;
  align-items: center;
  justify-content: center;
}

.image-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.file-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.file-name {
  font-size: 13px;
  color: var(--td-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.file-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--td-text-color-placeholder);
}

.file-meta .dot {
  margin: 0 2px;
}

.status {
  margin-left: 4px;
  font-weight: 500;
}

.status-parsing {
  color: var(--td-brand-color);
}

.status-parsed {
  color: var(--td-success-color);
}

.status-failed {
  color: var(--td-error-color);
}

.file-remove {
  flex-shrink: 0;
  color: var(--td-text-color-secondary);
}

/* ---------- 输入行 ---------- */
.chat-sender-row {
  position: relative;
  width: 100%;
}

.upload-btn {
  position: absolute;
  right: 52px;
  bottom: 7px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  background: transparent;
  border: 0;
  border-radius: 8px;
  color: var(--td-text-color-secondary);
  cursor: pointer;
  z-index: 2;
  transition: background-color 0.15s, color 0.15s;
}

.upload-btn:hover:not(:disabled) {
  background-color: var(--td-bg-color-container-hover);
  color: var(--td-text-color-primary);
}

.upload-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.upload-emoji {
  display: inline-block;
  font-size: 22px;
  line-height: 1;
  transform: scaleX(1.45);
}

.chat-sender {
  flex: 1;
  min-width: 0;
}

/* ---------- 通用样式 ---------- */
.input-disclaimer {
  margin: 0;
  font-size: 11px;
  line-height: 1.4;
  color: var(--td-text-color-placeholder);
  text-align: center;
}

:deep(.t-chat-sender) {
  border-radius: 12px;
}

:deep(.t-chat-sender__textarea),
:deep(.t-textarea__inner) {
  border-radius: 12px;
}

/* ---------- 移动端适配 ---------- */
@media (max-width: 768px) {
  .file-list-group {
    padding: 6px 8px;
  }

  .file-list-item {
    padding: 4px 6px;
  }

  .file-name {
    font-size: 12px;
  }

  .file-meta {
    font-size: 10px;
  }

  .image-thumb {
    width: 40px;
    height: 40px;
  }
}
</style>
