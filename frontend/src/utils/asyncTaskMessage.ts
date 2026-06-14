/**
 * AsyncTaskResultMessage 组件的纯逻辑工具（open spec: async-task-result-echo-to-chat）
 *
 * 把组件里的纯函数（无 DOM 依赖）抽出来，方便 vitest 单测覆盖。
 * 组件只保留模板 + DOM 相关逻辑（复制、跳转通知中心）。
 */

export type AsyncTaskStatus = 'SUCCESS' | 'COMPLETED' | 'FAILED' | 'TIMEOUT' | string

/** 长总结的折叠阈值（与组件内 SUMMARY_COLLAPSE_THRESHOLD 同步）。 */
export const SUMMARY_COLLAPSE_THRESHOLD = 500

/**
 * 用正则从文本里提取一行匹配项的第一捕获组，并 trim。
 */
export function extractLine(text: string, regex: RegExp): string {
  if (!text) return ''
  const m = text.match(regex)
  return m && m[1] ? m[1].trim() : ''
}

/**
 * 状态 → CSS 类名。
 */
export function getStatusClass(status: AsyncTaskStatus | null | undefined): string {
  const s = (status || 'FAILED').toUpperCase()
  return `async-status async-status--${s.toLowerCase()}`
}

/**
 * 状态 → 中文标签。
 */
export function getStatusText(status: AsyncTaskStatus | null | undefined): string {
  const s = (status || 'FAILED').toUpperCase()
  if (s === 'SUCCESS' || s === 'COMPLETED') return '成功'
  if (s === 'TIMEOUT') return '超时'
  return '失败'
}

/**
 * 总结文本是否需要折叠（> threshold 字）。
 */
export function shouldCollapseSummary(text: string | null | undefined, threshold = SUMMARY_COLLAPSE_THRESHOLD): boolean {
  return (text?.length ?? 0) > threshold
}

/**
 * 是否显示骨架屏（pending + 无 summaryText）。
 */
export function showSkeleton(summaryPending: number | null | undefined, summaryText: string | null | undefined): boolean {
  return summaryPending === 1 && !summaryText
}

/**
 * 是否显示降级提示（done 但 summaryText 为空 → LLM 续答失败）。
 */
export function showFallback(summaryPending: number | null | undefined, summaryText: string | null | undefined): boolean {
  return summaryPending === 0 && !summaryText
}

/**
 * 从任务结果文本里解析元信息（工具名、完成时间、错误信息、外部任务 ID、状态）。
 *
 * 文本格式见 AsyncTaskChatReplyService.buildTaskResultText()：
 *   ## 异步任务完成
 *   - 任务 ID：123
 *   - 工具：xxx
 *   - 外部任务 ID：ext-99
 *   - 状态：SUCCESS
 *   - 完成时间：2026-06-12T10:30
 *   - 错误信息：xxx
 *   ### 任务参数
 *   ### 任务结果
 */
export interface TaskMeta {
  taskId: string
  toolName: string
  externalTaskId: string
  status: string
  finishedAt: string
  errorMessage: string
}

export function parseTaskMeta(content: string | null | undefined): TaskMeta {
  return {
    taskId: extractLine(content || '', /任务 ID：(.+)/),
    toolName: extractLine(content || '', /工具：(.+)/),
    externalTaskId: extractLine(content || '', /外部任务 ID：(.+)/),
    status: extractLine(content || '', /状态：(.+)/),
    finishedAt: extractLine(content || '', /完成时间：(.+)/),
    errorMessage: extractLine(content || '', /错误信息：(.+)/),
  }
}