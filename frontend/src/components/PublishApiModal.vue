<script setup lang="ts">
import { ref, computed } from 'vue'
import { MessagePlugin } from 'tdesign-vue-next'
import { useConversations } from '../composables/useConversations'
import { useUser } from '../composables/useUser'

const props = defineProps<{
  visible: boolean
  conversationId: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'published', apiKey: string): void
}>()

const { currentUser } = useUser()
const conversations = useConversations()

const apiDescription = ref('')
const publishing = ref(false)
const publishResult = ref<{ apiKey: string } | null>(null)

const displayUrl = computed(() => {
  return `${window.location.origin}/api/agent-chat`
})

async function handlePublish() {
  const desc = apiDescription.value.trim()
  if (!desc) {
    MessagePlugin.warning('请填写 API 描述')
    return
  }
  if (!currentUser.value) return
  publishing.value = true
  try {
    const result = await conversations.publishConversation(
      props.conversationId,
      currentUser.value.id,
      desc,
    )
    publishResult.value = result
  } catch (e: any) {
    MessagePlugin.error(e.message || '发布失败')
  } finally {
    publishing.value = false
  }
}

function handleClose() {
  apiDescription.value = ''
  publishResult.value = null
  emit('close')
}

function handleDone() {
  const key = publishResult.value?.apiKey
  apiDescription.value = ''
  publishResult.value = null
  emit('published', key || '')
}

function copyApiKey() {
  if (publishResult.value?.apiKey) {
    navigator.clipboard.writeText(publishResult.value.apiKey)
    MessagePlugin.success('已复制到剪贴板')
  }
}
</script>

<template>
  <t-dialog
    :visible="visible"
    header="发布为 API"
    :width="560"
    :footer="false"
    @close="handleClose"
  >
    <!-- Step 1: Fill description -->
    <template v-if="!publishResult">
      <t-alert theme="info" message="发布后，该对话的页面将变为 API 详情视图，不再显示聊天界面" style="margin-bottom: 16px" />
      <t-form label-width="80px">
        <t-form-item label="API 描述" required>
          <t-textarea
            v-model="apiDescription"
            placeholder="请描述该 API 提供的服务，例如：该 API 提供数据分析和报告生成服务..."
            :maxlength="2000"
            :autosize="{ minRows: 4, maxRows: 8 }"
          />
          <template #help>
            描述将作为 LLM 对话上下文，帮助 AI 理解 API 的服务范围
          </template>
        </t-form-item>
        <t-form-item label="调用地址">
          <t-input :value="displayUrl" readonly />
        </t-form-item>
      </t-form>
      <div style="display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px">
        <t-button theme="default" @click="handleClose">取消</t-button>
        <t-button theme="primary" :loading="publishing" @click="handlePublish">
          确认发布
        </t-button>
      </div>
    </template>

    <!-- Step 2: Show API Key -->
    <template v-else>
      <t-alert theme="success" message="对话已成功发布为 API" style="margin-bottom: 16px" />
      <t-alert theme="warning" message="请立即保存 API Key，关闭后将无法再次查看完整 Key" style="margin-bottom: 16px" />
      <t-form label-width="80px">
        <t-form-item label="API Key">
          <div style="display: flex; align-items: center; gap: 8px; width: 100%">
            <t-input :value="publishResult.apiKey" readonly style="flex: 1; font-family: monospace" />
            <t-button theme="primary" variant="outline" @click="copyApiKey">复制</t-button>
          </div>
        </t-form-item>
        <t-form-item label="调用方式">
          <pre style="margin: 0; font-size: 12px; background: var(--td-bg-color-component); padding: 8px; border-radius: 4px; width: 100%; overflow-x: auto"><code>curl -X POST {{ displayUrl }} \
  -H "Content-Type: application/json" \
  -d '{"apiKey": "{{ publishResult.apiKey }}", "instruction": "你的问题"}'</code></pre>
        </t-form-item>
      </t-form>
      <div style="display: flex; justify-content: flex-end; margin-top: 16px">
        <t-button theme="primary" @click="handleDone">完成</t-button>
      </div>
    </template>
  </t-dialog>
</template>
