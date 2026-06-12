/**
 * Gateway 解析统一通道
 *
 * 流程（异步 + 轮询）：
 *   1. POST /api/files/upload — 同步返回 202 + fileId + status=PARSING
 *   2. GET  /api/files/{fileId} 轮询（每 1s 一次）直到 status 变为 READY 或 FAILED
 *   3. 返回 parsedSummary 字符串
 *
 * gateway 内部：
 *   - 同步段：FTP 落盘 + DB insert（约 80-150ms）
 *   - 异步段（@Async）：解析 + updateById 回写 parsedSummary
 *
 * 相比旧的"一个 HTTP 等到底"模式，HTTP 响应时间从 ~2-3s 降到 ~150ms，
 * 大文件 / PDF / Word 解析在后台线程跑，不阻塞用户操作。
 *
 * @module utils/gatewayParser
 */

import { apiUrl } from '@/services/config'
import type { FileType } from '@/types/fileUpload'

/** 上传阶段超时（毫秒）：同步段（multipart 落盘 + DB insert） */
const UPLOAD_TIMEOUT_MS = 30_000

/** 轮询阶段超时（毫秒）：整个解析流程最长等多久 */
const POLL_TIMEOUT_MS = 60_000

/** 轮询间隔（毫秒） */
const POLL_INTERVAL_MS = 1_000

/** 调 GET /api/files/{id} 拉取状态 + 解析结果 */
async function fetchFileStatus(
  fileId: number,
  userId: string,
  signal: AbortSignal,
): Promise<{ status: string; parsedSummary?: string; fileName?: string; fileType?: string }> {
  const res = await fetch(apiUrl(`/api/files/${fileId}`), {
    method: 'GET',
    headers: { 'X-User-Id': userId },
    signal,
    credentials: 'include',
  })
  if (!res.ok) {
    const err = await res.json().catch(() => null)
    const msg = err?.message || err?.error
    throw new Error(typeof msg === 'string' && msg ? msg : `查询文件状态失败：HTTP ${res.status}`)
  }
  return res.json()
}

/**
 * 通过 Java gateway 上传文件 + 轮询拿解析结果
 *
 * @param file 浏览器 File 对象
 * @param fileType 文件分类（保留参数以便未来按类型路由）
 * @param signal 可选的 AbortSignal（cancel 取消时触发）
 * @returns 后端返回的 parsed_summary 字符串（JSON 格式）
 * @throws 失败时抛含中文消息的 Error；用户取消时抛 AbortError
 */
export async function parseFileViaGateway(
  file: File,
  _fileType: FileType,
  signal?: AbortSignal,
): Promise<string> {
  const form = new FormData()
  form.append('file', file)

  const userId = localStorage.getItem('user_id')
  if (!userId) {
    throw new Error('未登录，无法上传文件')
  }

  // ---- 合并外部 signal 和上传超时 ----
  const uploadController = new AbortController()
  const uploadTimer = setTimeout(() => uploadController.abort(), UPLOAD_TIMEOUT_MS)
  const abortSignal = signal
    ? composeAbortSignals(signal, uploadController.signal)
    : uploadController.signal

  let response: Response
  try {
    response = await fetch(apiUrl('/api/files/upload'), {
      method: 'POST',
      headers: { 'X-User-Id': userId },
      body: form,
      signal: abortSignal,
      credentials: 'include',
    })
  } catch (e) {
    clearTimeout(uploadTimer)
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw e
    }
    throw new Error('文件解析服务暂不可用，请稍后重试')
  }
  clearTimeout(uploadTimer)

  if (!response.ok && response.status !== 202) {
    const errorData = await response.json().catch(() => null)
    const message = errorData?.message || errorData?.error
    if (typeof message === 'string' && message.length > 0) {
      throw new Error(message)
    }
    throw new Error(`文件上传失败：HTTP ${response.status}`)
  }

  const uploadData = await response.json()
  // 202 Accepted: { fileId, status: "PARSING", fileName, fileType, size }
  const fileId: number | undefined = uploadData?.fileId
  if (!fileId) {
    throw new Error('上传响应缺少 fileId 字段')
  }

  // ---- 轮询 GET /api/files/{id} 直到 READY/FAILED ----
  // 同一 controller 复用：cancel() 时 polling 也立刻停
  const pollController = new AbortController()
  const pollTimer = setTimeout(() => pollController.abort(), POLL_TIMEOUT_MS)
  if (signal) {
    signal.addEventListener('abort', () => pollController.abort(), { once: true })
  }

  try {
    const start = Date.now()
    while (true) {
      if (pollController.signal.aborted) {
        throw new DOMException('解析超时或已取消', 'AbortError')
      }
      const data = await fetchFileStatus(fileId, userId, pollController.signal)
      if (data.status === 'READY') {
        return data.parsedSummary ?? ''
      }
      if (data.status === 'FAILED') {
        throw new Error('后端解析失败')
      }
      // 还在 PARSING，等下一轮
      if (Date.now() - start >= POLL_TIMEOUT_MS) {
        throw new Error(`文件解析超时（${POLL_TIMEOUT_MS / 1000}s）`)
      }
      await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS))
    }
  } finally {
    clearTimeout(pollTimer)
  }
}

/**
 * 合并多个 AbortSignal：任一触发 abort 时新 signal 也 abort。
 * 用原生 AbortController 实现，避免引入第三方包（AGENTS.md 5.1）。
 */
function composeAbortSignals(...signals: AbortSignal[]): AbortSignal {
  const ctrl = new AbortController()
  for (const s of signals) {
    if (s.aborted) {
      ctrl.abort()
      break
    }
    s.addEventListener('abort', () => ctrl.abort(), { once: true })
  }
  return ctrl.signal
}
