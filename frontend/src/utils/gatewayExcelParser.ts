/**
 * Excel 表格 Gateway 解析器
 * 
 * 调用 skill-gateway 的 POST /features/file/parse-excel 端点解析 Excel 文件。
 * 作为 .xlsx 前端 SheetJS 解析失败/超时后的兜底，以及 .xls 格式的唯一解析方案。
 * 
 * @module utils/gatewayExcelParser
 */

import { agentUrl } from '@/services/config'

const PARSE_TIMEOUT_MS = 30_000

/**
 * 通过 Java gateway 解析 Excel 文件，返回 tab 分隔的文本
 */
export async function parseExcel(file: File, signal?: AbortSignal): Promise<string> {
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
    response = await fetch(agentUrl('/features/file/parse-excel'), {
      method: 'POST',
      body: form,
      signal: abortSignal,
    })
  } catch (e) {
    clearTimeout(timer)
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new Error('Excel 解析超时，请稍后重试')
    }
    throw new Error('Excel 解析服务暂不可用，请联系管理员')
  } finally {
    clearTimeout(timer)
  }

  if (!response.ok) {
    const errorData = await response.json().catch(() => null)
    if (errorData && typeof errorData.message === 'string') {
      throw new Error(`Excel 解析失败：${errorData.message}`)
    }
    throw new Error(`Excel 解析失败：HTTP ${response.status}`)
  }

  const data = await response.json()
  if (data && typeof data.text === 'string') {
    return data.text
  }

  throw new Error('Excel 解析服务返回格式异常')
}
