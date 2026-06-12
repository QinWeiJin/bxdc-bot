<script setup lang="ts">
import { onMounted, onErrorCaptured, nextTick, watch, ref } from 'vue'
import { useRoute } from 'vue-router'
import { provideChat, type Message, type ToolInvocation } from '../composables/useChat'
import { provideConversations, useConversations } from '../composables/useConversations'
import { useUser } from '../composables/useUser'
import type { ConversationMessage } from '../types/conversation'

import Layout from '../components/Layout.vue'
import MessageList from '../components/MessageList.vue'
import MessageInput from '../components/MessageInput.vue'
import ApiDetailView from '../components/ApiDetailView.vue'

const { error, messages, addMessage, fetchGreeting, saveMessageCallback } = provideChat()
// provideConversations must be called before useConversations (parent proviides to Layout child)
provideConversations()
const conversations = useConversations()
const { currentUser } = useUser()

const showApiDetail = ref(false)

// Destructure for template (auto-unwrapping only works on top-level refs)
const currentConvId = conversations.currentConversationId

// Watch for conversation switch: auto-detect if published
watch(
  () => conversations.currentConversation.value?.is_published,
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
