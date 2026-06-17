<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { fileService } from '../services/fileService'
import type { UserFileRecord } from '../types/fileUpload'
import { MessagePlugin } from 'tdesign-vue-next'
import {
  DownloadIcon, DeleteIcon, RefreshIcon, UploadIcon,
  FileIcon, FileWordIcon, FileExcelIcon, FilePowerpointIcon, FilePdfIcon, FileImageIcon
} from 'tdesign-icons-vue-next'

const files = ref<UserFileRecord[]>([])
const loading = ref(false)
const error = ref('')
const fileInputRef = ref<HTMLInputElement>()

function getFileIconComponent(fileType: string) {
  const t = fileType?.toLowerCase()
  if (['doc', 'docx'].includes(t)) return FileWordIcon
  if (['xls', 'xlsx', 'csv'].includes(t)) return FileExcelIcon
  if (['ppt', 'pptx'].includes(t)) return FilePowerpointIcon
  if (t === 'pdf') return FilePdfIcon
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'bmp'].includes(t)) return FileImageIcon
  return FileIcon
}

function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const k = 1024
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  const size = (bytes / Math.pow(k, i)).toFixed(i === 0 ? 0 : 1)
  return `${size} ${units[i]}`
}

function formatTime(timeStr: string): string {
  if (!timeStr) return '-'
  const d = new Date(timeStr)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

async function fetchFiles() {
  loading.value = true
  error.value = ''
  try {
    files.value = await fileService.listFiles()
    // 当前页超出范围时回退到最后一页
    const maxPage = Math.max(1, Math.ceil(files.value.length / pageSize.value))
    if (currentPage.value > maxPage) currentPage.value = maxPage
  } catch (e: any) {
    error.value = e.message || '获取文件列表失败'
  } finally {
    loading.value = false
  }
}

function triggerUpload() {
  fileInputRef.value?.click()
}

async function handleUpload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return

  if (file.size > 10 * 1024 * 1024) {
    MessagePlugin.error('文件大小超过限制（最大 10 MiB）')
    input.value = ''
    return
  }
  const allowed = ['.doc', '.docx', '.xls', '.xlsx', '.csv', '.ppt', '.pptx', '.txt', '.md', '.py', '.png', '.jpg', '.jpeg', '.webp']
  const ext = '.' + file.name.split('.').pop()?.toLowerCase()
  if (!allowed.includes(ext)) {
    MessagePlugin.error('不支持的文件类型')
    input.value = ''
    return
  }

  try {
    await fileService.uploadFile(file)
    MessagePlugin.success('上传成功')
    await fetchFiles()
  } catch (e: any) {
    MessagePlugin.error(e.message || '上传失败')
  } finally {
    input.value = ''
  }
}

async function handleDownload(file: UserFileRecord) {
  try {
    await fileService.downloadFile(file.id)
  } catch (e: any) {
    MessagePlugin.error(e.message || '下载失败')
  }
}

async function handleDelete(file: UserFileRecord) {
  try {
    await fileService.deleteFile(file.id)
    files.value = files.value.filter(f => f.id !== file.id)
    // 当前页清空时回退到上一页
    const maxPage = Math.max(1, Math.ceil(files.value.length / pageSize.value))
    if (currentPage.value > maxPage) currentPage.value = maxPage
    MessagePlugin.success('已删除')
  } catch (e: any) {
    MessagePlugin.error(e.message || '删除失败')
  }
}

const columns = [
  { colKey: 'fileName', title: '文件名' },
  { colKey: 'fileType', title: '类型', width: 90 },
  { colKey: 'fileSize', title: '大小', width: 100, align: 'center' as const },
  { colKey: 'uploadTime', title: '上传时间', width: 160 },
  { colKey: 'actions', title: '操作', width: 100 }
]

const currentPage = ref(1)
const pageSize = ref(10)
const pageSizeOptions = [10, 20, 50]

const paginatedFiles = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return files.value.slice(start, start + pageSize.value)
})

function onPageChange(pageInfo: { current: number; pageSize: number }) {
  if (pageInfo.pageSize !== pageSize.value) {
    // 切换每页条数时回到第 1 页
    currentPage.value = 1
    pageSize.value = pageInfo.pageSize
  } else {
    currentPage.value = pageInfo.current
  }
}

onMounted(() => {
  fetchFiles()
})
</script>

<template>
  <div class="file-manager-page">
    <!-- Header -->
    <div class="page-header">
      <h2>文件管理<span v-if="!loading && !error" class="file-count">（共 {{ files.length }} 个文件）</span></h2>
    </div>

    <!-- Error / Loading / Empty / Table -->
    <div class="table-area">
      <!-- Error -->
      <div v-if="error" class="error-area">
        <t-alert theme="error" :message="error" />
        <t-button variant="outline" @click="fetchFiles">
          <template #icon><RefreshIcon /></template>
          重试
        </t-button>
      </div>

      <!-- Loading -->
      <div v-if="loading" class="loading-state">
        <t-loading text="加载中..." />
      </div>

      <!-- Empty -->
      <div v-else-if="!error && files.length === 0" class="empty-state">
        <FileIcon style="font-size: 48px; color: var(--td-text-color-placeholder)" />
        <p>暂无文件，请上传。</p>
        <t-button theme="primary" variant="outline" @click="triggerUpload">
          <template #icon><UploadIcon /></template>
          上传文件
        </t-button>
      </div>

      <!-- File Table -->
      <t-table
        v-else-if="!loading"
        :data="paginatedFiles"
        :columns="columns"
        row-key="id"
        size="small"
        :bordered="false"
        :stripe="false"
      >
        <template #fileName="{ row }">
          <div class="file-name-cell">
            <component :is="getFileIconComponent(row.fileType)" style="font-size:18px;color:var(--td-text-color-secondary)" />
            <span class="file-name-text" @click="handleDownload(row)" :title="row.originalFileName">
              {{ row.originalFileName }}
            </span>
          </div>
        </template>
        <template #fileType="{ row }">
          <t-tag size="small" variant="light">{{ (row.fileType || '').toUpperCase() }}</t-tag>
        </template>
        <template #fileSize="{ row }">
          <span>{{ formatFileSize(row.fileSize) }}</span>
        </template>
        <template #uploadTime="{ row }">
          <span>{{ formatTime(row.uploadTime) }}</span>
        </template>
        <template #actions="{ row }">
          <t-space>
            <t-button variant="text" shape="square" @click="handleDownload(row)" title="下载">
              <template #icon><DownloadIcon /></template>
            </t-button>
            <t-popconfirm content="确定删除？" @confirm="handleDelete(row)">
              <t-button variant="text" theme="danger" shape="square" title="删除">
                <template #icon><DeleteIcon /></template>
              </t-button>
            </t-popconfirm>
          </t-space>
        </template>
      </t-table>
    </div>

    <!-- Pagination bar (fixed above upload) -->
    <div v-if="!loading && !error" class="pagination-bar">
      <t-pagination
        :current="currentPage"
        :page-size="pageSize"
        :total="files.length"
        :page-size-options="pageSizeOptions"
        @change="onPageChange"
      />
    </div>

    <!-- Bottom upload bar -->
    <div class="bottom-bar">
      <t-button theme="primary" @click="triggerUpload">
        <template #icon><UploadIcon /></template>
        上传文件
      </t-button>
      <input
        ref="fileInputRef"
        type="file"
        style="display:none"
        accept=".doc,.docx,.xls,.xlsx,.csv,.ppt,.pptx,.txt,.md,.py,.png,.jpg,.jpeg,.webp"
        @change="handleUpload"
      />
    </div>
  </div>
</template>

<style scoped>
.file-manager-page {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.page-header {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.page-header h2 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.table-area {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 0;
}

.pagination-bar {
  display: flex;
  justify-content: center;
  padding: 12px 24px;
  background: var(--td-bg-color-container);
  flex-shrink: 0;
}

.bottom-bar {
  display: flex;
  justify-content: center;
  padding: 12px 0;
  border-top: 1px solid var(--td-component-stroke);
  background: var(--td-bg-color-container);
  flex-shrink: 0;
}

.file-count {
  font-size: 13px;
  color: var(--td-text-color-secondary);
  margin-left: 8px;
}

.file-name-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.file-name-text {
  color: var(--td-brand-color);
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-name-text:hover {
  text-decoration: underline;
}

.loading-state,
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 0;
  color: var(--td-text-color-secondary);
  gap: 12px;
}

.error-area {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
</style>
