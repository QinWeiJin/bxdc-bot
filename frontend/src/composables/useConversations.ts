import { ref, computed, type ComputedRef } from 'vue'
import {
  fetchConversations,
  createConversation as apiCreateConversation,
  fetchConversation as apiFetchConversation,
  updateConversation,
  deleteConversation as apiDeleteConversation,
  saveMessages,
  publishConversation as apiPublishConversation,
} from '../services/api'
import type {
  Conversation,
  ConversationMessage,
} from '../types/conversation'

export interface ConversationsState {
  conversations: ReturnType<typeof ref<Conversation[]>>
  currentConversationId: ReturnType<typeof ref<string | null>>
  isLoadingHistory: ReturnType<typeof ref<boolean>>
  hasMoreHistory: ReturnType<typeof ref<boolean>>
  init: (userId: string) => Promise<void>
  switchConversation: (conversationId: string, userId: string) => Promise<ConversationMessage[]>
  newConversation: (userId: string) => Promise<string>
  renameConversation: (conversationId: string, name: string, userId: string) => Promise<void>
  deleteConversation: (conversationId: string, userId: string) => Promise<void>
  loadMoreMessages: (userId: string) => Promise<ConversationMessage[]>
  persistMessages: (userId: string, messages: {
    role: string
    content: string
    skill_calls?: Record<string, unknown>
    skill_outputs?: Record<string, unknown>
  }[]) => Promise<void>
  /** Parse enabled_skills from conversation and return as number array */
  getEnabledSkillIds: (conversationId: string) => number[]
  /** Add a skill id to the conversation's enabled_skills (API + local cache) */
  addEnabledSkillToConversation: (conversationId: string, userId: string, skillId: number) => Promise<void>
  /** Refresh conversations list from backend without switching current conversation */
  refreshConversations: (userId: string) => Promise<void>
  activeAbortController: ReturnType<typeof ref<AbortController | null>>
  historyMessages: ReturnType<typeof ref<ConversationMessage[]>>
  /** True when an SSE stream (Agent reply) is actively running — guards against mid-stream conversation switch */
  isProcessing: ReturnType<typeof ref<boolean>>
  /** Computed: the currently active Conversation object (undefined if none) */
  currentConversation: ComputedRef<Conversation | undefined>
  /** Publish a conversation as API */
  publishConversation: (conversationId: string, userId: string, apiDescription: string) => Promise<{ apiKey: string }>
}

function sanitizeName(raw: string): string {
  return raw
    .replace(/[#*>`\-_\n\r]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function autoNameFromMessage(content: string): string {
  const cleaned = sanitizeName(content)
  return cleaned.length > 18 ? cleaned.slice(0, 18) : cleaned
}

function parseEnabledSkillIds(raw: string | undefined | null): number[] {
  if (!raw) return []
  try {
    return JSON.parse(raw) as number[]
  } catch {
    return []
  }
}

// Module-level singleton — no need for Vue provide/inject
let _instance: ConversationsState | null = null

/** Number of messages to load per page when fetching conversation history */
const PAGE_SIZE = 20

function createConversationsState(): ConversationsState {
  const conversations = ref<Conversation[]>([])
  const currentConversationId = ref<string | null>(null)
  const isLoadingHistory = ref(false)
  const hasMoreHistory = ref(false)
  const activeAbortController = ref<AbortController | null>(null)
  const historyMessages = ref<ConversationMessage[]>([])
  const convNamedMap = ref<Record<string, boolean>>({})
  const isProcessing = ref(false)

  let userIdCache = ''

  async function init(userId: string) {
    userIdCache = userId
    await refreshConversations(userId)

    if (conversations.value.length === 0) {
      const conv = await apiCreateConversation(userId, '默认对话')
      conversations.value = [conv]
      currentConversationId.value = conv.conversation_id
      convNamedMap.value[conv.conversation_id] = true
    } else {
      currentConversationId.value = conversations.value[0]?.conversation_id ?? null
      for (const c of conversations.value) {
        if (c.name && c.name !== '新对话') {
          convNamedMap.value[c.conversation_id] = true
        }
      }
    }
  }

  async function refreshConversations(userId: string) {
    const res = await fetchConversations(userId)
    conversations.value = res.conversations || []
  }

  async function switchConversation(conversationId: string, userId: string): Promise<ConversationMessage[]> {
    if (activeAbortController.value) {
      activeAbortController.value.abort()
      activeAbortController.value = null
    }

    currentConversationId.value = conversationId
    isLoadingHistory.value = true
    hasMoreHistory.value = false

    try {
      const res = await apiFetchConversation(userId, conversationId, undefined, PAGE_SIZE)
      hasMoreHistory.value = res.hasMore
      const msgs = (res.messages || []).reverse()
      historyMessages.value = msgs
      return msgs
    } finally {
      isLoadingHistory.value = false
    }
  }

  async function newConversation(userId: string): Promise<string> {
    const conv = await apiCreateConversation(userId)
    conversations.value = [conv, ...conversations.value]
    currentConversationId.value = conv.conversation_id
    // Switch to new conversation to clear old messages and show greeting
    const msgs = await switchConversation(conv.conversation_id, userId)
    return conv.conversation_id
  }

  async function renameConversation(conversationId: string, name: string, userId: string) {
    const trimmed = name.trim()
    if (!trimmed) return
    await updateConversation(userId, conversationId, { name: trimmed })
    const idx = conversations.value.findIndex((c) => c.conversation_id === conversationId)
    if (idx >= 0) {
      conversations.value[idx] = { ...conversations.value[idx]!, name: trimmed }
    }
    convNamedMap.value[conversationId] = true
  }

  async function deleteConversation(conversationId: string, userId: string) {
    await apiDeleteConversation(userId, conversationId)
    conversations.value = conversations.value.filter((c) => c.conversation_id !== conversationId)
    if (currentConversationId.value === conversationId) {
      if (conversations.value.length > 0) {
        currentConversationId.value = conversations.value[0]?.conversation_id ?? null
      } else {
        await init(userId)
      }
    }
  }

  async function addEnabledSkillToConversation(
    conversationId: string,
    userId: string,
    skillId: number,
  ) {
    const currentIds = parseEnabledSkillIds(
      conversations.value.find((c) => c.conversation_id === conversationId)?.enabled_skills,
    )
    if (currentIds.includes(skillId)) return
    currentIds.push(skillId)
    // updateConversation 返回后端写入后的真实 Conversation，直接用
    const updated = await updateConversation(userId, conversationId, {
      enabled_skills: currentIds,
    })
    const idx = conversations.value.findIndex((c) => c.conversation_id === conversationId)
    if (idx >= 0) {
      conversations.value[idx] = updated
    }
  }

  async function loadMoreMessages(userId: string): Promise<ConversationMessage[]> {
    if (!currentConversationId.value || !hasMoreHistory.value || isLoadingHistory.value) return []
    isLoadingHistory.value = true

    try {
      const cursor = historyMessages.value[0]?.created_at
      if (!cursor) {
        hasMoreHistory.value = false
        return []
      }

      const res = await apiFetchConversation(userId, currentConversationId.value, cursor, PAGE_SIZE)
      hasMoreHistory.value = res.hasMore
      const olderMsgs = (res.messages || []).reverse()
      historyMessages.value = [...olderMsgs, ...historyMessages.value]
      return olderMsgs
    } finally {
      isLoadingHistory.value = false
    }
  }

  async function persistMessages(
    userId: string,
    messages: { role: string; content: string; skill_calls?: Record<string, unknown>; skill_outputs?: Record<string, unknown> }[],
  ) {
    if (!currentConversationId.value || messages.length === 0) return
    try {
      await saveMessages(userId, currentConversationId.value, messages)
    } catch (e) {
      console.error('[conversations] Failed to persist messages:', e)
    }

    if (!convNamedMap.value[currentConversationId.value]) {
      const firstUserMsg = messages.find((m) => m.role === 'user')
      if (firstUserMsg && firstUserMsg.content.trim()) {
        const autoName = autoNameFromMessage(firstUserMsg.content)
        if (autoName) {
          await renameConversation(currentConversationId.value, autoName, userId)
        }
      }
    }
  }

  async function publishConversationMethod(conversationId: string, userId: string, apiDescription: string): Promise<{ apiKey: string }> {
    const res = await apiPublishConversation(userId, conversationId, apiDescription)
    // Update local cache — use splice for reliable Vue 3 reactivity
    const idx = conversations.value.findIndex((c) => c.conversation_id === conversationId)
    if (idx >= 0 && res.conversation) {
      conversations.value.splice(idx, 1, res.conversation)
    }
    return { apiKey: res.apiKey }
  }

  return {
    conversations,
    currentConversationId,
    isLoadingHistory,
    hasMoreHistory,
    init,
    switchConversation,
    newConversation,
    renameConversation,
    deleteConversation,
    loadMoreMessages,
    persistMessages,
    activeAbortController,
    historyMessages,
    isProcessing,
    getEnabledSkillIds: (conversationId: string): number[] => {
      const conv = conversations.value.find((c) => c.conversation_id === conversationId)
      return parseEnabledSkillIds(conv?.enabled_skills)
    },
    addEnabledSkillToConversation,
    refreshConversations,
    currentConversation: computed(() =>
      conversations.value.find((c) => c.conversation_id === currentConversationId.value)
    ),
    publishConversation: publishConversationMethod,
  }
}

/** Initialize the singleton. Call once in App/root component before child components use `useConversations()`. */
export function provideConversations(): ConversationsState {
  _instance = createConversationsState()
  return _instance
}

/** Access the singleton from any component. Must be called after `provideConversations()`. */
export function useConversations(): ConversationsState {
  if (!_instance) throw new Error('useConversations must be used within a provider that calls provideConversations()')
  return _instance
}
