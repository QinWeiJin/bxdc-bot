export interface Conversation {
  id: number
  conversation_id: string
  name: string
  enabled_skills: string // JSON string from backend, e.g. "[1, 3, 5]"
  status: string
  created_at: string
  updated_at: string
}

export interface ConversationMessage {
  message_id: string
  role: 'user' | 'assistant' | 'tool' | 'system'
  content: string
  skill_calls: string | null // JSON string
  skill_outputs: string | null // JSON string
  created_at: string
}

export interface ConversationListResponse {
  conversations: Conversation[]
}

export interface ConversationDetailResponse {
  conversation: Conversation
  messages: ConversationMessage[]
  hasMore: boolean
}

export interface SaveMessagesRequest {
  messages: {
    role: string
    content: string
    skill_calls?: Record<string, unknown>
    skill_outputs?: Record<string, unknown>
  }[]
}

export interface SaveMessagesResponse {
  ok: boolean
  count: number
}
