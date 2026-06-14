/**
 * useMemory Composable
 *
 * 任务 4（add-file-upload-composable 后续：add-memory-init-and-clear）产出物。
 * 集中封装与 agent-core MemoryController 的 HTTP 通信：
 *   - getMemoryStatus(userId): GET /memory/status → { enabled: boolean }
 *   - deleteUserMemory(userId): POST /memory/delete → 清空 mem0
 *   - addUserMemory(userId, text): POST /memory/add → 写入一条初始化记忆
 *
 * 三个方法都强制带 X-User-Id header（spec memory-initialization-flow 跨用户守卫）。
 * 错误处理统一：HTTP 非 2xx 或后端返回 { code: 4xx/5xx } 都 throw 带原消息的 Error，
 * 供 ProfileEditModal 弹 toast 友好显示。
 *
 * 基础 URL 走 agentUrl() 模式（指向 agent-core / VITE_AGENT_URL），
 * 与 useChat.ts:844 现有 /memory/add 调用保持一致。
 *
 * @module composables/useMemory
 */

import { agentUrl } from '../services/config'

/** Memory status shape per spec memory-initialization-flow Decision 7 */
export interface MemoryStatus {
  enabled: boolean
}

/**
 * 统一从 fetch Response 提取后端错误消息。
 * 后端在失败时返回 { code, message }，成功时直接是数据（不包 code/message 也能跑）。
 */
async function extractErrorMessage(res: Response, fallback: string): Promise<string> {
  try {
    const body = await res.json()
    if (body && typeof body.message === 'string') return body.message
    if (body && typeof body.error === 'string') return body.error
  } catch {
    // 响应不是 JSON
  }
  return fallback
}

/**
 * 查询记忆功能开关状态。
 * 不调 mem0，不抛错（后端内部 try/catch，已返回 { enabled: false } on mem0 down）。
 */
export async function getMemoryStatus(userId: string): Promise<MemoryStatus> {
  if (!userId) throw new Error('getMemoryStatus: userId is required')
  const res = await fetch(agentUrl(`/memory/status?userId=${encodeURIComponent(userId)}`), {
    method: 'GET',
    headers: {
      'Content-Type': 'application/json',
      // /memory/status 严格说没要求 X-User-Id（只读全局开关），
      // 但为了与 add/delete 统一调用模式，仍然带上（后端不校验也不报错）。
      'X-User-Id': userId,
    },
  })
  if (!res.ok) {
    const msg = await extractErrorMessage(res, `查询记忆状态失败 (${res.status})`)
    throw new Error(msg)
  }
  const body = await res.json()
  // 兼容：后端可能返回 { enabled } 或被某种 wrapper 包了一层
  return { enabled: Boolean(body?.enabled) }
}

/**
 * 全量删除该用户的长期记忆。
 * 后端会校验 X-User-Id header 与 body userId 一致，否则 403。
 */
export async function deleteUserMemory(userId: string): Promise<void> {
  if (!userId) throw new Error('deleteUserMemory: userId is required')
  const res = await fetch(agentUrl('/memory/delete'), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': userId,
    },
    body: JSON.stringify({ userId }),
  })
  if (!res.ok) {
    const msg = await extractErrorMessage(res, `清空失败 (${res.status})`)
    throw new Error(msg)
  }
  // 后端成功时返回 { code: 200, message: '记忆已清空', userId }
  // 不再读 body，调用方通过"无 throw"判定成功
}

/**
 * 添加一条初始化记忆。
 * text 已在 ProfileEditModal 内按 spec 拼接好（frontend 拼接，不在 backend 拼接）。
 * 后端会校验 X-User-Id header 与 body userId 一致，否则 403。
 */
export async function addUserMemory(userId: string, text: string): Promise<void> {
  if (!userId) throw new Error('addUserMemory: userId is required')
  if (!text || !text.trim()) throw new Error('addUserMemory: text is required')
  const res = await fetch(agentUrl('/memory/add'), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-User-Id': userId,
    },
    body: JSON.stringify({ userId, text, role: 'user' }),
  })
  if (!res.ok) {
    const msg = await extractErrorMessage(res, `记忆初始化失败 (${res.status})`)
    throw new Error(msg)
  }
}

/**
 * useMemory 命名空间导出（与 useUser / useChat 风格一致）。
 * 调用方：`import { useMemory } from '@/composables/useMemory'`。
 */
export function useMemory() {
  return {
    getMemoryStatus,
    deleteUserMemory,
    addUserMemory,
  }
}

export default useMemory
