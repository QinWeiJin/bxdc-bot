import { apiUrl, agentUrl } from './config'
import type {
  ConversationListResponse,
  ConversationDetailResponse,
  SaveMessagesRequest,
  SaveMessagesResponse,
  Conversation,
  CallLogsResponse,
  PublishResponse,
  ApiKeyResponse,
} from '../types/conversation'

export async function createTask(content: string, userId?: string, history?: any[], sessionId?: string): Promise<{ id: string }> {
  const response = await fetch(apiUrl('/api/tasks'), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ content, userId, history, sessionId }),
  })

  if (!response.ok) {
    throw new Error('Failed to create task')
  }

  return response.json()
}

export function getEventSourceUrl(taskId: string): string {
  return apiUrl(`/api/tasks/${taskId}/events`)
}

export function getAgentStreamUrl(): string {
  return agentUrl('/agent/run')
}

export async function confirmAction(
  sessionId: string,
  toolCallId: string,
  confirmed: boolean,
  adjustedParams?: Record<string, unknown>,
): Promise<void> {
  const response = await fetch(agentUrl('/agent/confirm'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, toolCallId, confirmed, adjustedParams }),
  })

  if (!response.ok) {
    const detail =
      response.status === 404
        ? 'No pending confirmation (session may have expired).'
        : `Confirm request failed (${response.status})`
    throw new Error(detail)
  }
}

function authHeaders(userId: string): Record<string, string> {
  return {
    'Content-Type': 'application/json',
    'X-User-Id': userId,
  }
}

export async function fetchConversations(userId: string): Promise<ConversationListResponse> {
  const response = await fetch(apiUrl('/api/conversations'), {
    headers: { 'X-User-Id': userId },
  })
  if (!response.ok) throw new Error('Failed to fetch conversations')
  return response.json()
}

export async function createConversation(
  userId: string,
  name?: string,
  enabledSkills?: number[],
): Promise<Conversation> {
  const response = await fetch(apiUrl('/api/conversations'), {
    method: 'POST',
    headers: authHeaders(userId),
    body: JSON.stringify({ name: name || '', enabled_skills: enabledSkills || [] }),
  })
  if (!response.ok) throw new Error('Failed to create conversation')
  return response.json()
}

export async function fetchConversation(
  userId: string,
  conversationId: string,
  cursor?: string,
  limit?: number,
): Promise<ConversationDetailResponse> {
  const params = new URLSearchParams()
  if (cursor) params.set('cursor', cursor)
  if (limit) params.set('limit', String(limit))
  const qs = params.toString() ? `?${params.toString()}` : ''
  const response = await fetch(apiUrl(`/api/conversations/${conversationId}${qs}`), {
    headers: { 'X-User-Id': userId },
  })
  if (!response.ok) throw new Error('Failed to fetch conversation')
  return response.json()
}

export async function updateConversation(
  userId: string,
  conversationId: string,
  data: { name?: string; enabled_skills?: number[] },
): Promise<Conversation> {
  const response = await fetch(apiUrl(`/api/conversations/${conversationId}`), {
    method: 'PUT',
    headers: authHeaders(userId),
    body: JSON.stringify(data),
  })
  if (!response.ok) throw new Error('Failed to update conversation')
  return response.json()
}

export async function deleteConversation(userId: string, conversationId: string): Promise<void> {
  const response = await fetch(apiUrl(`/api/conversations/${conversationId}`), {
    method: 'DELETE',
    headers: { 'X-User-Id': userId },
  })
  if (!response.ok) throw new Error('Failed to delete conversation')
}

export async function saveMessages(
  userId: string,
  conversationId: string,
  messages: SaveMessagesRequest['messages'],
): Promise<SaveMessagesResponse> {
  const response = await fetch(apiUrl(`/api/conversations/${conversationId}/messages`), {
    method: 'POST',
    headers: authHeaders(userId),
    body: JSON.stringify({ messages }),
  })
  if (!response.ok) throw new Error('Failed to save messages')
  return response.json()
}

export async function publishConversation(
  userId: string,
  conversationId: string,
  apiDescription: string,
): Promise<PublishResponse> {
  const response = await fetch(apiUrl(`/api/conversations/${conversationId}/publish`), {
    method: 'PUT',
    headers: authHeaders(userId),
    body: JSON.stringify({ apiDescription }),
  })
  if (!response.ok) {
    const body = await response.json().catch(() => ({}))
    throw new Error((body as any).error || 'Failed to publish conversation')
  }
  return response.json()
}

export async function fetchCallLogs(
  userId: string,
  conversationId: string,
  page = 1,
  size = 20,
): Promise<CallLogsResponse> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  const response = await fetch(apiUrl(`/api/conversations/${conversationId}/call-logs?${params}`), {
    headers: { 'X-User-Id': userId },
  })
  if (!response.ok) throw new Error('Failed to fetch call logs')
  return response.json()
}

export async function regenerateApiKey(
  userId: string,
  conversationId: string,
): Promise<ApiKeyResponse> {
  const response = await fetch(apiUrl(`/api/conversations/${conversationId}/regenerate-api-key`), {
    method: 'PUT',
    headers: authHeaders(userId),
  })
  if (!response.ok) throw new Error('Failed to regenerate API key')
  return response.json()
}

export async function fetchApiKey(
  userId: string,
  conversationId: string,
): Promise<ApiKeyResponse> {
  const response = await fetch(apiUrl(`/api/conversations/${conversationId}/api-key`), {
    headers: { 'X-User-Id': userId },
  })
  if (!response.ok) throw new Error('Failed to fetch API key')
  return response.json()
}
