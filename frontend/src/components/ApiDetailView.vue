<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { MessagePlugin, type PaginationProps } from 'tdesign-vue-next'
import { EditIcon } from 'tdesign-icons-vue-next'
import { useConversations } from '../composables/useConversations'
import { useUser } from '../composables/useUser'
import { fetchCallLogs, fetchApiKey, regenerateApiKey, updateApiDescription } from '../services/api'
import type { ApiCallLog } from '../types/conversation'

const props = defineProps<{
  conversationId: string
}>()

const { currentUser } = useUser()
const conversations = useConversations()
const conv = computed(() => conversations.currentConversation.value)

const callLogs = ref<ApiCallLog[]>([])
const total = ref(0)
const pagination = ref<PaginationProps>({ current: 1, pageSize: 20 })
const loading = ref(false)
const expandedRowKeys = ref<number[]>([])

// API Key state
const maskedApiKey = ref('')
const regenerating = ref(false)

// Description editing state
const editingDescription = ref(false)
const descriptionDraft = ref('')
const savingDescription = ref(false)

const displayUrl = computed(() => `${window.location.origin}/api/agent-chat`)

async function loadCallLogs() {
  if (!currentUser.value) return
  loading.value = true
  try {
    const res = await fetchCallLogs(
      currentUser.value.id,
      props.conversationId,
      pagination.value.current as number,
      pagination.value.pageSize as number,
    )
    callLogs.value = res.logs
    total.value = res.total
  } catch (e) {
    console.error('Failed to load call logs:', e)
  } finally {
    loading.value = false
  }
}

async function loadApiKey() {
  if (!currentUser.value) return
  try {
    const res = await fetchApiKey(currentUser.value.id, props.conversationId)
    const key = res.apiKey
    if (key && key.length > 8) {
      maskedApiKey.value = key.substring(0, 2) + '****...****' + key.slice(-4)
    } else {
      maskedApiKey.value = key || ''
    }
  } catch (e) {
    console.error('Failed to load API key:', e)
  }
}

async function copyApiKey() {
  if (!currentUser.value) return
  try {
    const res = await fetchApiKey(currentUser.value.id, props.conversationId)
    await navigator.clipboard.writeText(res.apiKey)
    MessagePlugin.success('已复制到剪贴板')
  } catch (e) {
    MessagePlugin.error('复制失败')
  }
}

async function handleRegenerate() {
  if (!currentUser.value) return
  regenerating.value = true
  try {
    const res = await regenerateApiKey(currentUser.value.id, props.conversationId)
    const key = res.apiKey
    maskedApiKey.value = key.substring(0, 2) + '****...****' + key.slice(-4)
    MessagePlugin.success('API Key 已重新生成')
  } catch (e) {
    MessagePlugin.error('重新生成失败')
  } finally {
    regenerating.value = false
  }
}

function startEditDescription() {
  descriptionDraft.value = conv.value?.api_description || ''
  editingDescription.value = true
}

function cancelEditDescription() {
  editingDescription.value = false
}

async function saveDescription() {
  if (!currentUser.value) return
  savingDescription.value = true
  try {
    const res = await updateApiDescription(
      currentUser.value.id,
      props.conversationId,
      descriptionDraft.value.trim(),
    )
    // Update local cache
    if (res.conversation && conversations.conversations.value) {
      const idx = conversations.conversations.value.findIndex(
        (c) => c.conversation_id === props.conversationId,
      )
      if (idx >= 0) {
        conversations.conversations.value.splice(idx, 1, res.conversation)
      }
    }
    editingDescription.value = false
    MessagePlugin.success('描述已更新')
  } catch (e) {
    MessagePlugin.error('更新失败')
  } finally {
    savingDescription.value = false
  }
}

function onPageChange(pageInfo: PaginationProps) {
  pagination.value = pageInfo
  loadCallLogs()
}

function statusTagTheme(status: string): 'success' | 'danger' | 'warning' | 'default' {
  switch (status) {
    case 'success': return 'success'
    case 'error': return 'danger'
    case 'timeout': return 'warning'
    default: return 'default'
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'success': return '成功'
    case 'error': return '失败'
    case 'timeout': return '超时'
    case 'running': return '进行中'
    default: return status
  }
}

watch(() => props.conversationId, () => {
  loadCallLogs()
  loadApiKey()
}, { immediate: true })
</script>

<template>
  <div class="api-detail">
    <div class="api-info-card">
      <div class="api-section">
        <label>API 描述</label>
        <div v-if="!editingDescription" class="api-desc-row">
          <p class="api-desc">{{ conv?.api_description || '暂无描述' }}</p>
          <t-button size="small" variant="text" @click="startEditDescription">
            <template #icon><EditIcon /></template>
            编辑
          </t-button>
        </div>
        <div v-else class="api-desc-edit">
          <t-textarea
            v-model="descriptionDraft"
            placeholder="描述这个 API 的用途，会注入到对话上下文中"
            :autosize="{ minRows: 2, maxRows: 5 }"
          />
          <div class="api-desc-actions">
            <t-button size="small" theme="primary" :loading="savingDescription" @click="saveDescription">保存</t-button>
            <t-button size="small" variant="outline" @click="cancelEditDescription">取消</t-button>
          </div>
        </div>
      </div>

      <div class="api-section">
        <label>API Key</label>
        <div class="api-key-row">
          <code class="api-key">{{ maskedApiKey || '加载中...' }}</code>
          <t-button size="small" variant="outline" @click="copyApiKey">复制</t-button>
          <t-button
            size="small"
            variant="outline"
            theme="warning"
            :loading="regenerating"
            @click="handleRegenerate"
          >
            重新生成
          </t-button>
        </div>
        <p class="api-hint">重新生成后旧 Key 立即失效</p>
      </div>

      <div class="api-section">
        <label>调用地址</label>
        <code class="api-endpoint">POST {{ displayUrl }}</code>
      </div>

      <div class="api-section">
        <label>请求格式</label>
        <pre class="api-code"><code>{
  "apiKey": "c_xxxx...",
  "instruction": "你的问题",
  "callerId": "optional-caller-id"
}</code></pre>
      </div>

      <div class="api-section">
        <label>调用示例</label>
        <pre class="api-code"><code>curl -X POST {{ displayUrl }} \
  -H "Content-Type: application/json" \
  -d '{{
    JSON.stringify({ apiKey: 'c_xxxx...', instruction: '你的问题' }, null, 2)
  }}'</code></pre>
      </div>
    </div>

    <div class="call-logs-section">
      <h3>API 调用记录</h3>
      <t-table
        :data="callLogs"
        :columns="[
          { colKey: 'callerId', title: '调用方', width: 120, ellipsis: true },
          { colKey: 'instruction', title: '输入', width: 200, ellipsis: true },
          { colKey: 'reply', title: '输出', width: 200, ellipsis: true },
          { colKey: 'toolCallCount', title: '工具调用次数', width: 110, align: 'center' },
          { colKey: 'durationMs', title: '耗时', width: 90, align: 'center', cell: (_h: any, ctx: any) => ctx.row.durationMs + 'ms' },
          { colKey: 'status', title: '状态', width: 80, align: 'center' },
          { colKey: 'createdAt', title: '时间', width: 170 },
        ]"
        :pagination="{ ...pagination, total }"
        :loading="loading"
        :expanded-row-keys="expandedRowKeys"
        row-key="id"
        hover
        stripe
        size="small"
        @page-change="onPageChange"
        @expand-change="(keys: number[]) => (expandedRowKeys = keys)"
      >
        <template #status="{ row }">
          <t-tag :theme="statusTagTheme(row.status)" variant="light" size="small">
            {{ statusLabel(row.status) }}
          </t-tag>
        </template>
        <template #expandedRow="{ row }">
          <div class="expanded-row">
            <div class="expanded-field">
              <strong>输入:</strong>
              <pre>{{ row.instruction }}</pre>
            </div>
            <div class="expanded-field">
              <strong>输出:</strong>
              <pre>{{ row.reply || (row.errorMessage ? '错误: ' + row.errorMessage : '无') }}</pre>
            </div>
          </div>
        </template>
      </t-table>
    </div>
  </div>
</template>

<style scoped>
.api-detail {
  padding: 24px;
  overflow-y: auto;
  height: 100%;
}

.api-info-card {
  background: var(--td-bg-color-container);
  border: 1px solid var(--td-component-stroke);
  border-radius: 6px;
  padding: 20px;
  margin-bottom: 24px;
}

.api-section {
  margin-bottom: 16px;
}
.api-section:last-child {
  margin-bottom: 0;
}
.api-section label {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--td-text-color-secondary);
  margin-bottom: 6px;
}

.api-desc {
  color: var(--td-text-color-primary);
  font-size: 14px;
  line-height: 1.6;
  margin: 0;
  flex: 1;
}

.api-desc-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.api-desc-edit {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.api-desc-actions {
  display: flex;
  gap: 8px;
}

.api-key-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.api-key {
  font-family: monospace;
  font-size: 14px;
  background: var(--td-bg-color-component);
  padding: 4px 10px;
  border-radius: 4px;
  color: var(--td-brand-color);
}

.api-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--td-text-color-placeholder);
}

.api-endpoint {
  display: block;
  font-family: monospace;
  font-size: 14px;
  background: var(--td-bg-color-component);
  padding: 4px 10px;
  border-radius: 4px;
}

.api-code {
  margin: 0;
  font-size: 12px;
  background: var(--td-bg-color-component);
  padding: 10px 14px;
  border-radius: 4px;
  overflow-x: auto;
}
.api-code code {
  font-family: monospace;
}

.call-logs-section h3 {
  margin: 0 0 12px;
  font-size: 16px;
  font-weight: 600;
}

.expanded-row {
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.expanded-field {
  font-size: 13px;
}
.expanded-field strong {
  display: block;
  margin-bottom: 4px;
  color: var(--td-text-color-secondary);
}
.expanded-field pre {
  margin: 0;
  font-size: 12px;
  background: var(--td-bg-color-component);
  padding: 8px 12px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
}
</style>
