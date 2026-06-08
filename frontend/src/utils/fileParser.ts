/**
 * 文档内容解析统一入口
 *
 * 根据 FileType 和文件扩展名自动路由到对应的前端解析器或 Java gateway 兜底端点。
 * 各解析器通过动态 import() 按需延迟加载。
 *
 * 路由表：
 *   .docx       → parseDocx()           （mammoth，前端优先）
 *              → parseWord()            （Java gateway 兜底，失败/超时时）
 *   .doc        → parseWord()           （Java gateway，旧格式）
 *   .xlsx       → parseXlsx()           （SheetJS，前端优先）
 *              → parseExcel()           （Java gateway 兜底，失败/超时时）
 *   .xls        → parseExcel()          （Java gateway，旧格式）
 *   .ppt/.pptx  → parsePpt()            （Java gateway）
 *   .txt/.md    → parseTxt()            （FileReader，零依赖）
 *
 * @module utils/fileParser
 */

import type { FileType } from '@/types/fileUpload'

/** 前端解析超时阈值（毫秒），超时立即 fallback 到 Java gateway */
const FRONTEND_PARSE_TIMEOUT_MS = 5_000

/**
 * 获取文件扩展名（小写，含点号）
 */
function getExt(name: string): string {
  const i = name.lastIndexOf('.')
  return i >= 0 ? name.slice(i).toLowerCase() : ''
}

/**
 * 标记前端解析超时，专用于区分"超时"和"业务错误"。
 *
 * 注：mammoth/SheetJS 都是同步阻塞 JS，没有真正的取消机制；超时后前台任务仍会跑
 * （后果：CPU 占用一段时间），但用户不会被卡住。
 */
class FrontendParseTimeoutError extends Error {
  constructor() {
    super('前端解析超时')
    this.name = 'FrontendParseTimeoutError'
  }
}

async function parseWithFallback<T>(
  frontendFn: () => Promise<T>,
  fallbackFn: () => Promise<T>,
): Promise<T> {
  let frontendError: Error | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    const result = await Promise.race([
      frontendFn(),
      new Promise<never>((_, reject) => {
        timer = setTimeout(
          () => reject(new FrontendParseTimeoutError()),
          FRONTEND_PARSE_TIMEOUT_MS,
        )
      }),
    ])
    clearTimeout(timer)
    return result
  } catch (e) {
    clearTimeout(timer)
    frontendError = e instanceof Error ? e : new Error(String(e))
  }
  // 走到这里说明前端解析失败（超时或业务错误），尝试 gateway 兜底
  try {
    return await fallbackFn()
  } catch (gatewayError) {
    // gateway 也失败：优先展示前端错误（通常信息更精确，含文件名/库错误细节）
    // 超时场景下，前端错误为"前端解析超时"，gateway 可能成功（POI 没超时）就返回 gateway 结果
    throw frontendError
  }
}

/**
 * 解析文档文件，返回纯文本内容
 *
 * @param file - 浏览器 File 对象
 * @param fileType - 文件分类（来自 FILE_UPLOAD_CONFIG）
 * @param signal - 可选的 AbortSignal
 * @returns 解析后的纯文本字符串
 * @throws 解析失败或服务不可用时抛出含中文描述的 Error
 */
export async function parseDocument(
  file: File,
  fileType: FileType,
  signal?: AbortSignal,
): Promise<string> {
  const ext = getExt(file.name)

  // ── Word ──
  if (fileType === 'word') {
    if (ext === '.docx') {
      return parseWithFallback(
        async () => { const { parseDocx } = await import('./docxParser'); return parseDocx(file) },
        async () => { const { parseWord } = await import('./gatewayDocParser'); return parseWord(file, signal) },
      )
    }
    // .doc → Java gateway
    const { parseWord } = await import('./gatewayDocParser')
    return parseWord(file, signal)
  }

  // ── Excel ──
  if (fileType === 'excel') {
    if (ext === '.xlsx') {
      return parseWithFallback(
        async () => { const { parseXlsx } = await import('./xlsxParser'); return parseXlsx(file) },
        async () => { const { parseExcel } = await import('./gatewayExcelParser'); return parseExcel(file, signal) },
      )
    }
    // .xls → Java gateway
    const { parseExcel } = await import('./gatewayExcelParser')
    return parseExcel(file, signal)
  }

  // ── PPT ──
  if (fileType === 'ppt') {
    const { parsePpt } = await import('./pptParser')
    return parsePpt(file, signal)
  }

  // ── TXT / MD ──
  if (fileType === 'txt') {
    const { parseTxt } = await import('./txtParser')
    return parseTxt(file, signal)
  }

  // 兜底：未知 fileType → 返回空（不再有 agentFallback）
  throw new Error(`不支持的文件类型：${fileType}`)
}
