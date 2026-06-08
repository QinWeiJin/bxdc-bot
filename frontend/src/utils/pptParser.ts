/**
 * PPT 文档解析器
 * 
 * 直接调用 skill-gateway 的 POST /features/file/parse-ppt 端点解析 PPT 文件。
 * 支持 .ppt（旧二进制格式）和 .pptx（OOXML 格式），仅提取文字内容。
 * 
 * 路由：前端请求 /features/file/parse-ppt → serve-proxy → skill-gateway 18080/api/features/file/parse-ppt
 * 
 * @module utils/pptParser
 */

import { agentUrl } from '@/services/config'

/** PPT 解析超时（毫秒） */
const PPT_PARSE_TIMEOUT_MS = 60_000

/**
 * 解析 PPT 文件，返回纯文本内容
 * 
 * @param file - 浏览器 File 对象（.ppt 或 .pptx）
 * @param signal - 可选的 AbortSignal
 * @returns 解析后的纯文本字符串
 * @throws 解析失败或服务不可用时抛出含中文描述的 Error
 */
export async function parsePpt(file: File, signal?: AbortSignal): Promise<string> {
  const form = new FormData()
  form.append('file', file)

  let abortSignal: AbortSignal
  let timer: ReturnType<typeof setTimeout> | undefined

  if (signal) {
    abortSignal = signal
  } else {
    const controller = new AbortController()
    timer = setTimeout(() => controller.abort(), PPT_PARSE_TIMEOUT_MS)
    abortSignal = controller.signal
  }

  let response: Response
  try {
    response = await fetch(agentUrl('/features/file/parse-ppt'), {
      method: 'POST',
      body: form,
      signal: abortSignal,
    })
  } catch (e) {
    clearTimeout(timer)
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new Error('PPT 解析超时，请稍后重试')
    }
    throw new Error('PPT 解析服务暂不可用，请联系管理员')
  } finally {
    clearTimeout(timer)
  }

  if (!response.ok) {
    const errorData = await response.json().catch(() => null)
    if (errorData && typeof errorData.message === 'string') {
      throw new Error(`PPT 解析失败：${errorData.message}`)
    }
    throw new Error(`PPT 解析失败：HTTP ${response.status}`)
  }

  const data = await response.json()
  if (data && typeof data.text === 'string') {
    return data.text
  }

  throw new Error('PPT 解析服务返回格式异常')
}