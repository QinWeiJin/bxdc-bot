<script setup lang="ts">
import { onMounted, onUnmounted, onErrorCaptured, watch, ref } from 'vue'
import { useRoute } from 'vue-router'
import { provideChat, type Message, type ToolInvocation } from '../composables/useChat'
import { provideConversations, useConversations } from '../composables/useConversations'
import { useUser } from '../composables/useUser'
import { getConversationEventSourceUrl } from '../services/api'
import type { ConversationMessage } from '../types/conversation'

import Layout from '../components/Layout.vue'
import MessageList from '../components/MessageList.vue'
import MessageInput from '../components/MessageInput.vue'
import ApiDetailView from '../components/ApiDetailView.vue'

const { error, messages, fetchGreeting, saveMessageCallback } = provideChat()
// provideConversations must be called before useConversations (parent proviides to Layout child)
provideConversations()
const conversations = useConversations()
const { currentUser } = useUser()

const showApiDetail = ref(false)

// Destructure for template (auto-unwrapping only works on top-level refs)
const currentConvId = conversations.currentConversationId

// Watch for conversation switch: auto-detect if published
// Track conversations array changes + current conversation is_published
watch(
  () => {
    const conv = (conversations.conversations.value || []).find(
      (c) => c.conversation_id === conversations.currentConversationId.value,
    )
    return conv?.is_published ?? false
  },
  (isPublished) => {
    showApiDetail.value = isPublished === true
  },
  { immediate: true },
)

// Wire up message persistence: after SSE stream completes, save to conversation
saveMessageCallback.value = (chatMessages) => {
  if (!currentUser.value || !conversations.currentConversationId.value) return
  const msgs = chatMessages.map((m) => ({
    role: m.role,
    content: m.content,
    skill_calls: undefined,
    skill_outputs: undefined,
  }))
  conversations.persistMessages(currentUser.value.id, msgs)
}
const route = useRoute()

function convertHistoryMessages(msgs: ConversationMessage[]): Message[] {
  const result: Message[] = []
  let pendingToolInvocations: ToolInvocation[] = []

  for (const msg of msgs) {
    if (msg.role === 'user') {
      result.push({
        id: msg.message_id,
        role: 'user',
        content: msg.content,
        timestamp: new Date(msg.created_at).getTime(),
        toolInvocations: [],
        llmLogs: [],
        logTimeline: [],
      })
    } else if (msg.role === 'assistant') {
      const skillCalls = parseSkillCalls(msg.skill_calls)
      result.push({
        id: msg.message_id,
        role: 'assistant',
        content: msg.content,
        timestamp: new Date(msg.created_at).getTime(),
        toolInvocations: skillCalls,
        llmLogs: [],
        logTimeline: skillCalls.map((t) => ({ kind: 'tool' as const, id: t.id })),
        // async-task-result-echo-to-chat: 透传异步任务结果专用字段
        source: msg.source,
        asyncTaskId: msg.async_task_id,
        summaryPending: msg.summary_pending,
        summaryText: msg.summary_text,
        summaryGeneratedAt: msg.summary_generated_at
          ? new Date(msg.summary_generated_at).getTime()
          : null,
      })
      pendingToolInvocations = skillCalls
    } else if (msg.role === 'tool') {
      // Attach tool output to the last assistant message's matching tool invocation
      if (result.length > 0 && pendingToolInvocations.length > 0) {
        const lastMsg = result[result.length - 1]!
        if (lastMsg.role === 'assistant' && lastMsg.toolInvocations) {
          const target = lastMsg.toolInvocations.find(
            (t) => t.status === 'running' || t.status === 'completed',
          )
          if (target) {
            target.status = 'completed'
            target.result = msg.content
          } else if (lastMsg.toolInvocations.length > 0) {
            const lastTool = lastMsg.toolInvocations[lastMsg.toolInvocations.length - 1]!
            lastTool.result = lastTool.result
              ? lastTool.result + '\n' + msg.content
              : msg.content
          }
        }
      }
    }
  }

  return result
}

function parseSkillCalls(raw: string | null): ToolInvocation[] {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    if (Array.isArray(parsed)) {
      return parsed.map((tc: any, i: number) => ({
        id: tc.id || tc.tool_call_id || `history-${i}-${Date.now()}`,
        name: tc.name || tc.function?.name || 'unknown',
        displayName: (tc.name || tc.function?.name || 'unknown').replace(/^skill_/, '').replace(/_/g, '-'),
        kind: ((tc.name || '').startsWith('skill_') ? 'skill' : 'tool') as 'skill' | 'tool',
        status: 'completed' as const,
        arguments: tc.args || tc.arguments || undefined,
        result: undefined,
        children: [],
      }))
    }
    // Single object
    return [{
      id: parsed.id || parsed.tool_call_id || `history-0-${Date.now()}`,
      name: parsed.name || parsed.function?.name || 'unknown',
      displayName: (parsed.name || parsed.function?.name || 'unknown').replace(/^skill_/, '').replace(/_/g, '-'),
      kind: ((parsed.name || '').startsWith('skill_') ? 'skill' : 'tool') as 'skill' | 'tool',
      status: 'completed' as const,
      arguments: parsed.args || parsed.arguments || undefined,
      result: undefined,
      children: [],
    }]
  } catch {
    return []
  }
}

// ---- Conversation-level SSE 订阅 ----
// open spec: async-task-result-echo-to-chat
// 让异步任务完成时新消息自动出现在聊天流（不刷新页面）。
const conversationEventSource = ref<EventSource | null>(null)

function closeConversationSse() {
  if (conversationEventSource.value) {
    try {
      conversationEventSource.value.close()
    } catch {
      // ignore
    }
    conversationEventSource.value = null
  }
}

function connectConversationSse(conversationId: string) {
  closeConversationSse()
  if (!currentUser.value) return
  try {
    const es = new EventSource(
      getConversationEventSourceUrl(conversationId, currentUser.value.id),
    )
    es.addEventListener('message_inserted', (ev: MessageEvent) => {
      try {
        const msg = JSON.parse(ev.data) as ConversationMessage
        if (msg.role !== 'assistant') return
        // 防止重复（多 tab 订阅同一对话，或断线重连漏事件后 history 已带回）
        if (messages.value!.some((m) => m.id === msg.message_id)) return
        messages.value = [...messages.value!, convertSingleMessage(msg)]
      } catch (e) {
        console.warn('[chat-sse] message_inserted parse failed', e)
      }
    })
    es.addEventListener('message_updated', (ev: MessageEvent) => {
      try {
        const msg = JSON.parse(ev.data) as ConversationMessage
        const idx = messages.value!.findIndex((m) => m.id === msg.message_id)
        if (idx === -1) {
          // 没找到就当作 insert 兜底
          if (msg.role === 'assistant') {
            messages.value = [...messages.value!, convertSingleMessage(msg)]
          }
        } else {
          // 替换：保留 id/role/timestamp 等稳定字段，刷新 summary_* 字段
          const updated = convertSingleMessage(msg)
          const existing = messages.value![idx]!
          messages.value = [
            ...messages.value!.slice(0, idx),
            { ...existing, ...updated },
            ...messages.value!.slice(idx + 1),
          ]
        }
      } catch (e) {
        console.warn('[chat-sse] message_updated parse failed', e)
      }
    })
    es.onerror = () => {
      // EventSource 默认会自动重连；这里只记日志，不做额外处理
      console.debug('[chat-sse] connection error (will auto-reconnect)')
    }
    conversationEventSource.value = es
  } catch (e) {
    console.warn('[chat-sse] failed to open EventSource', e)
  }
}

/**
 * 单条 message DTO → 聊天消息模型。
 * 只处理 assistant（其他角色由 SSE 不发布）。
 */
function convertSingleMessage(msg: ConversationMessage): Message {
  return {
    id: msg.message_id,
    role: 'assistant',
    content: msg.content,
    timestamp: msg.created_at ? new Date(msg.created_at).getTime() : Date.now(),
    toolInvocations: parseSkillCalls(msg.skill_calls),
    llmLogs: [],
    logTimeline: [],
    // async-task-result-echo-to-chat: 透传异步任务结果专用字段
    source: msg.source,
    asyncTaskId: msg.async_task_id,
    summaryPending: msg.summary_pending,
    summaryText: msg.summary_text,
    summaryGeneratedAt: msg.summary_generated_at
      ? new Date(msg.summary_generated_at).getTime()
      : null,
  }
}

// 切换 conversation 时重连 SSE
watch(
  () => currentConvId.value,
  (id) => {
    if (id) {
      connectConversationSse(id)
    } else {
      closeConversationSse()
    }
  },
  { immediate: false },
)

onUnmounted(() => {
  closeConversationSse()
})

// Initialize conversations and load first conversation's history
onMounted(async () => {
  if (!currentUser.value) return

  await conversations.init(currentUser.value.id)

  // Load first conversation's messages
  if (conversations.currentConversationId.value) {
    const historyMessages = await conversations.switchConversation(
      conversations.currentConversationId.value,
      currentUser.value.id,
    )
    // Convert API messages to chat messages format
    if (historyMessages.length > 0) {
      messages.value = convertHistoryMessages(historyMessages)
    } else {
      // Empty conversation: show greeting
      fetchGreeting()
    }
  } else {
    fetchGreeting()
  }

  const taskId = route.query.taskId
  if (typeof taskId === 'string' && taskId) {
    try {
      sessionStorage.setItem('pendingTaskId', taskId)
    } catch {
      // ignore
    }
  }
})

// Watch for history loads triggered by sidebar
watch(
  () => conversations.historyMessages.value,
  (msgs) => {
    if (!msgs) return
    if (msgs.length === 0) {
      // Empty conversation: clear old messages and show greeting
      messages.value = []
      fetchGreeting()
      return
    }
    messages.value = convertHistoryMessages(msgs)
  },
)

onErrorCaptured((err) => {
  console.error('[ChatView captured error]:', err)
  return false
})
</script>

<template>
  <Layout @conversation-published="showApiDetail = true">
    <ApiDetailView
      v-if="showApiDetail && currentConvId"
      :key="currentConvId"
      :conversation-id="currentConvId"
    />
    <template v-else>
      <div class="chat-card">
        <div class="chat-main">
          <MessageList />
        </div>
        <t-alert
          v-if="error"
          class="chat-error"
          theme="error"
          :message="error"
        />
        <div class="chat-input-area">
          <MessageInput />
        </div>
      </div>
    </template>
  </Layout>
</template>

<style scoped>
.chat-card {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--td-bg-color-container);
  border-radius: 8px;
  overflow: hidden;
}

.chat-main {
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-error {
  flex: 0 0 auto;
  margin: 0 16px;
}

.chat-input-area {
  flex: 0 0 auto;
  padding: 8px 12px 10px;
}

@media (min-width: 768px) {
  .chat-error {
    margin: 0 24px;
  }
  .chat-input-area {
    padding: 8px 16px 10px;
  }
}
</style>
