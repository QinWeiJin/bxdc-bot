export interface Conversation {
  id: number
  conversation_id: string
  name: string
  enabled_skills: string // JSON string from backend, e.g. "[1, 3, 5]"
  /**
   * open spec: conversation-file-isolation — 该对话可操作的文件 ID 列表
   * null = 存量对话（不启用文件隔离，向后兼容）
   */
  enabled_files?: string | null
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
  /**
   * 消息来源。async-task-result-echo-to-chat change:
   * - 'web' / 'api' 老值
   * - 'ASYNC_TASK_RESULT' 新增：异步任务完成后回灌到对话的消息
   */
  source?: 'web' | 'api' | 'ASYNC_TASK_RESULT' | 'BXDCBOT_RUN_RESULT'
  /** 异步任务结果消息关联的 async_tasks.id（NULL=普通消息） */
  async_task_id?: string | null
  /** LLM 续答是否尚未生成（1=pending，0=done） */
  summary_pending?: number | null
  /** LLM 续答生成的自然语言总结 */
  summary_text?: string | null
  /** 异步任务结果消息：LLM 续答完成时间（ISO 字符串） */
  summary_generated_at?: string | null
  /** BxdcbotRun：当 Bxdcbot 自主规划调子 skill 时，子 async task 记录 parent_tool_id（runId），用于通知中心按 run 过滤 */
  parent_tool_id?: string | null
  /** BxdcbotRun：Bxdcbot 自规划 skillId */
  parent_skill_id?: number | null
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
