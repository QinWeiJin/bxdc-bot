import type { Message } from '../composables/useChat'

/**
 * 获取当前时间戳字符串，用于文件名
 */
function timestamp(): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}_${pad(now.getHours())}-${pad(now.getMinutes())}`
}

/**
 * 将 ISO 时间戳转为可读格式
 */
function formatTime(ts: number): string {
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/**
 * 构建 Markdown 内容
 */
export function buildMarkdownContent(messages: Message[]): string {
  const lines: string[] = []
  lines.push('# BXDC.bot 对话记录')
  lines.push('')
  lines.push(`> 导出时间：${new Date().toLocaleString('zh-CN')}`)
  lines.push('')
  lines.push('---')
  lines.push('')

  for (const msg of messages) {
    const time = formatTime(msg.timestamp)
    if (msg.role === 'user') {
      lines.push(`### 用户 (${time})`)
    } else {
      lines.push(`### BXDC.bot (${time})`)
    }
    lines.push('')
    lines.push(msg.content || '')
    lines.push('')
    lines.push('---')
    lines.push('')
  }

  return lines.join('\n')
}

/**
 * 下载 Markdown 文件
 */
export function downloadMarkdown(messages: Message[]): void {
  if (!messages.length) {
    return
  }
  const content = buildMarkdownContent(messages)
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' })
  downloadBlob(blob, `bxdc-chat-${timestamp()}.md`)
}

/**
 * 将消息内容转为安全的 HTML（转义特殊字符）
 */
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 构建 PDF 渲染用的 HTML 字符串
 */
function buildPdfHtml(messages: Message[]): string {
  const rows: string[] = []

  for (const msg of messages) {
    const label = msg.role === 'user' ? '用户' : 'BXDC.bot'
    const time = formatTime(msg.timestamp)
    const body = escapeHtml(msg.content || '')
      .replace(/\n/g, '<br>')
    const roleColor = msg.role === 'user' ? '#2563eb' : '#059669'

    rows.push(`
      <div style="margin-bottom: 12px; padding: 8px 12px; border-left: 3px solid ${roleColor}; background: #f8fafc; border-radius: 4px;">
        <div style="font-size: 12px; color: ${roleColor}; font-weight: 600; margin-bottom: 4px;">
          ${escapeHtml(label)} · ${escapeHtml(time)}
        </div>
        <div style="font-size: 13px; color: #1e293b; line-height: 1.6; white-space: pre-wrap;">
          ${body}
        </div>
      </div>`)
  }

  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", "Helvetica Neue", sans-serif;
      padding: 20px;
      color: #1e293b;
    }
    .title { font-size: 22px; font-weight: 700; color: #0f172a; margin-bottom: 4px; }
    .subtitle { font-size: 11px; color: #64748b; margin-bottom: 12px; }
    .divider { height: 1px; background: #e2e8f0; margin-bottom: 16px; }
  </style>
</head>
<body>
  <div class="title">BXDC.bot 对话记录</div>
  <div class="subtitle">导出时间：${escapeHtml(new Date().toLocaleString('zh-CN'))}</div>
  <div class="divider"></div>
  ${rows.join('')}
</body>
</html>`
}

/**
 * 下载 PDF 文件（动态 import jspdf，使用 html() 方法通过浏览器 DOM 渲染中文）
 */
export async function downloadPdf(messages: Message[]): Promise<void> {
  if (!messages.length) {
    return
  }

  const { jsPDF } = await import('jspdf')
  const doc = new jsPDF({ orientation: 'portrait', unit: 'mm', format: 'a4' })

  const html = buildPdfHtml(messages)

  // 在 body 下创建一个隐藏容器用于 html() 渲染
  const container = document.createElement('div')
  container.style.cssText = 'position:absolute;left:-9999px;top:0;width:190mm;'
  container.innerHTML = html
  document.body.appendChild(container)

  try {
    await doc.html(container, {
      callback: (doc) => {
        doc.save(`bxdc-chat-${timestamp()}.pdf`)
      },
      margin: [10, 10, 10, 10],
      autoPaging: 'text',
      width: 190,
      windowWidth: 800,
    })
  } finally {
    document.body.removeChild(container)
  }
}

/**
 * 触发浏览器下载 Blob
 */
function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
