/**
 * 文档内容解析统一入口
 *
 * 解析全部委托给 skill-gateway 后端 `POST /api/files/upload`：
 * 一次 HTTP 调用完成"上传 + 解析 + 落库（user_files.parsed_summary）"。
 *
 * 路由表（按 FileType，所有类型一律走 gateway 通道）：
 *   - word (.doc / .docx) → gateway WordParser (POI)
 *   - excel (.xls / .xlsx / .csv) → gateway（启雷未注入时返回空 parsed_summary）
 *   - txt / md / py → gateway TxtMdParser / PyParser
 *   - ppt → 保持原 `pptParser` 路径（wgj 后端未实现 PPT 解析）
 *
 * 老路径（mammoth/SheetJS/FileReader）保留在仓库内（docxParser.ts / xlsxParser.ts /
 * txtParser.ts 文件本身不删），后续清理 change 再删。本 change 期间它们的 `parseDocx` /
 * `parseXlsx` / `parseTxt` 不再被调用，作为紧急回滚兜底。
 *
 * @module utils/fileParser
 */

import type { FileType } from '@/types/fileUpload'
import { parseFileViaGateway } from './gatewayParser'

/**
 * 解析文档文件，返回纯文本内容
 *
 * @param file - 浏览器 File 对象
 * @param fileType - 文件分类（来自 FILE_UPLOAD_CONFIG）
 * @param signal - 可选的 AbortSignal
 * @returns 解析后的纯文本字符串（实际是后端返回的 `parsed_summary` JSON）
 * @throws 解析失败或服务不可用时抛出含中文描述的 Error；用户取消时抛 AbortError
 */
export async function parseDocument(
  file: File,
  fileType: FileType,
  signal?: AbortSignal,
  conversationId?: string | null,
): Promise<string> {
  // PPT 仍走旧的专用解析器（gateway 暂不解析 PPT）
  if (fileType === 'ppt') {
    const { parsePpt } = await import('./pptParser')
    return parsePpt(file, signal)
  }

  // 其他类型全部走 gateway 解析通道
  return parseFileViaGateway(file, fileType, signal, conversationId)
}
