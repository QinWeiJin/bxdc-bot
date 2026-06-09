/**
 * Word 文档 Gateway 解析器
 * 
 * 调用 skill-gateway 的 POST /features/file/parse-word 端点解析 Word 文件。
 * 作为 .docx 前端 mammoth 解析失败/超时后的兜底，以及 .doc 格式的唯一解析方案。
 * 
 * @module utils/gatewayDocParser
 */

import { agentUrl } from '@/services/config'

const PARSE_TIMEOUT_MS = 30_000

/**
 * 通过 Java gateway 解析 Word 文件，返回纯文本内容
 */
export async function parseWord(file: File, signal?: AbortSignal): Promise<string> {
  const form = new FormData()
  form.append('file', file)

  let abortSignal: AbortSignal
  let timer: ReturnType<typeof setTimeout> | undefined

  if (signal) {
    abortSignal = signal
  } else {
    const controller = new AbortController()
    timer = setTimeout(() => controller.abort(), PARSE_TIMEOUT_MS)
    abortSignal = controller.signal
  }

  let response: Response
  try {
    response = await fetch(agentUrl('/features/file/parse-word'), {
      method: 'POST',
      body: form,
      signal: abortSignal,
    })
  } catch (e) {
    clearTimeout(timer)
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new Error('Word 解析超时，请稍后重试')
    }
    throw new Error('Word 解析服务暂不可用，请联系管理员')
  } finally {
    clearTimeout(timer)
  }

  if (!response.ok) {
    const errorData = await response.json().catch(() => null)
    if (errorData && typeof errorData.message === 'string') {
      throw new Error(`Word 解析失败：${errorData.message}`)
    }
    throw new Error(`Word 解析失败：HTTP ${response.status}`)
  }

  const data = await response.json()
  if (data && typeof data.text === 'string') {
    return data.text
  }

  throw new Error('Word 解析服务返回格式异常')
}
