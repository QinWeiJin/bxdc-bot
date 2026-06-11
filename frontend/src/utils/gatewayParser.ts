/**
 * Gateway 解析统一通道
 *
 * 调用 skill-gateway 的 POST /api/files/upload 端点上传文件并获取解析结果。
 * gateway 内部会：FTP 存文件 → 调 FileParseService → 写回 user_files.parsed_summary
 *
 * 本工具替代原先"前端优先 + 5s 超时 fallback"的双轨制，
 * 统一为「前端上传 + 后端解析」一条链路（一个 HTTP 调用完成"上传+解析+落库"）。
 *
 * 覆盖文件类型（与需求方案 A1 §3 一致）：
 *   - doc, docx, txt, md, py
 *   - xls, xlsx, csv（启雷未注入时返回空 parsed_summary）
 *
 * @module utils/gatewayParser
 */

import { apiUrl } from '@/services/config'
import type { FileType } from '@/types/fileUpload'

/** 上传超时阈值（毫秒）：含 FTP + 解析的最大耗时 */
const UPLOAD_TIMEOUT_MS = 60_000

/**
 * 通过 Java gateway 上传文件 + 获取解析结果
 *
 * @param file 浏览器 File 对象
 * @param fileType 文件分类（来自 FILE_UPLOAD_CONFIG；当前实现中保留以便未来路由）
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

  // X-User-Id header 由 FileAccessInterceptor 强制要求
  const userId = localStorage.getItem('user_id')
  if (!userId) {
    throw new Error('未登录，无法上传文件')
  }

  const headers: Record<string, string> = {
    'X-User-Id': userId,
  }
  // FormData 模式下不要手动设 Content-Type（fetch 会自动加 multipart boundary）

  let abortSignal: AbortSignal
  let timer: ReturnType<typeof setTimeout> | undefined

  if (signal) {
    abortSignal = signal
  } else {
    const controller = new AbortController()
    timer = setTimeout(() => controller.abort(), UPLOAD_TIMEOUT_MS)
    abortSignal = controller.signal
  }

  let response: Response
  try {
    response = await fetch(apiUrl('/api/files/upload'), {
      method: 'POST',
      headers,
      body: form,
      signal: abortSignal,
      credentials: 'include',
    })
  } catch (e) {
    clearTimeout(timer)
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw e
    }
    throw new Error('文件解析服务暂不可用，请稍后重试')
  } finally {
    clearTimeout(timer)
  }

  if (!response.ok) {
    const errorData = await response.json().catch(() => null)
    // 兼容两种错误格式：{ error, message } 或 { error, message }
    const message = errorData?.message || errorData?.error
    if (typeof message === 'string' && message.length > 0) {
      throw new Error(message)
    }
    throw new Error(`文件解析失败：HTTP ${response.status}`)
  }

  const data = await response.json()
  // 成功响应：{ fileId, parsedSummary, fileName, fileType }
  // parsedSummary 可能是：
  //   - JSON 字符串（doc/docx/txt/md/py 由 wgj 解析器生成）
  //   - 空字符串（xlsx/csv 在启雷未注入时）
  if (data && typeof data.parsedSummary === 'string') {
    return data.parsedSummary
  }

  // 后端没返回 parsedSummary 字段，按空摘要处理（不报错）
  return ''
}
