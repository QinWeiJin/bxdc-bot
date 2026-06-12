export interface Conversation {
  id: number
  conversation_id: string
  name: string
  enabled_skills: string // JSON string from backend, e.g. "[1, 3, 5]"
  status: string
  is_published?: boolean
  api_description?: string | null
  api_key?: string | null
  created_at: string
  updated_at: string
}

export interface ConversationMessage {
  message_id: string
  role: 'user' | 'assistant' | 'tool' | 'system'
  content: string
  skill_calls: string | null // JSON string
  skill_outputs: string | null // JSON string
  source?: 'web' | 'api'
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

export interface ApiCallLog {
  id: number
  callerId: string | null
  instruction: string
  reply: string | null
  toolCallCount: number
  durationMs: number
  status: string
  errorMessage: string | null
  createdAt: string
}

export interface CallLogsResponse {
  logs: ApiCallLog[]
  total: number
  hasMore: boolean
}

export interface PublishResponse {
  conversation: Conversation
  apiKey: string
}

export interface ApiKeyResponse {
  apiKey: string
}
