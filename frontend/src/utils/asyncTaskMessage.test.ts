import { describe, expect, it } from 'vitest'
import {
  extractLine,
  getStatusClass,
  getStatusText,
  parseTaskMeta,
  shouldCollapseSummary,
  showFallback,
  showSkeleton,
  SUMMARY_COLLAPSE_THRESHOLD,
} from './asyncTaskMessage'

describe('asyncTaskMessage utils', () => {
  describe('extractLine', () => {
    it('returns empty string for empty input', () => {
      expect(extractLine('', /foo/)).toBe('')
    })

    it('extracts first capture group', () => {
      expect(extractLine('工具：async-time-tool', /工具：(.+)/)).toBe('async-time-tool')
    })

    it('returns empty string when no match', () => {
      expect(extractLine('nothing here', /missing:(.+)/)).toBe('')
    })

    it('trims whitespace', () => {
      expect(extractLine('完成时间：2026-06-12 10:30:00  ', /完成时间：(.+)/)).toBe('2026-06-12 10:30:00')
    })
  })

  describe('getStatusClass', () => {
    it('maps SUCCESS/COMPLETED to success variant', () => {
      expect(getStatusClass('SUCCESS')).toBe('async-status async-status--success')
      expect(getStatusClass('COMPLETED')).toBe('async-status async-status--completed')
    })

    it('maps FAILED/TIMEOUT to dedicated variants', () => {
      expect(getStatusClass('FAILED')).toBe('async-status async-status--failed')
      expect(getStatusClass('TIMEOUT')).toBe('async-status async-status--timeout')
    })

    it('defaults to FAILED for null/undefined/empty', () => {
      expect(getStatusClass(null)).toBe('async-status async-status--failed')
      expect(getStatusClass(undefined)).toBe('async-status async-status--failed')
      expect(getStatusClass('')).toBe('async-status async-status--failed')
    })

    it('lowercases input', () => {
      expect(getStatusClass('success')).toBe('async-status async-status--success')
    })
  })

  describe('getStatusText', () => {
    it('returns Chinese label per status', () => {
      expect(getStatusText('SUCCESS')).toBe('成功')
      expect(getStatusText('COMPLETED')).toBe('成功')
      expect(getStatusText('TIMEOUT')).toBe('超时')
      expect(getStatusText('FAILED')).toBe('失败')
    })

    it('defaults to 失败 for unknown', () => {
      expect(getStatusText('UNKNOWN')).toBe('失败')
      expect(getStatusText(null)).toBe('失败')
      expect(getStatusText(undefined)).toBe('失败')
    })
  })

  describe('shouldCollapseSummary', () => {
    it('returns false when text is null/undefined', () => {
      expect(shouldCollapseSummary(null)).toBe(false)
      expect(shouldCollapseSummary(undefined)).toBe(false)
    })

    it('returns false when text is below threshold', () => {
      const short = 'a'.repeat(SUMMARY_COLLAPSE_THRESHOLD)
      expect(shouldCollapseSummary(short)).toBe(false)
    })

    it('returns true when text exceeds threshold', () => {
      const long = 'a'.repeat(SUMMARY_COLLAPSE_THRESHOLD + 1)
      expect(shouldCollapseSummary(long)).toBe(true)
    })

    it('honors custom threshold', () => {
      expect(shouldCollapseSummary('12345', 10)).toBe(false)
      expect(shouldCollapseSummary('12345678901', 10)).toBe(true)
    })
  })

  describe('showSkeleton', () => {
    it('true when pending=1 and no summaryText', () => {
      expect(showSkeleton(1, null)).toBe(true)
      expect(showSkeleton(1, undefined)).toBe(true)
      expect(showSkeleton(1, '')).toBe(true)
    })

    it('false when pending=0', () => {
      expect(showSkeleton(0, null)).toBe(false)
    })

    it('false when summaryText present even if pending=1', () => {
      expect(showSkeleton(1, 'some text')).toBe(false)
    })
  })

  describe('showFallback', () => {
    it('true when done (pending=0) but summaryText is empty', () => {
      expect(showFallback(0, null)).toBe(true)
      expect(showFallback(0, '')).toBe(true)
      expect(showFallback(0, undefined)).toBe(true)
    })

    it('false when pending=1 (should show skeleton instead)', () => {
      expect(showFallback(1, null)).toBe(false)
    })

    it('false when summaryText present', () => {
      expect(showFallback(0, 'real text')).toBe(false)
    })
  })

  describe('parseTaskMeta', () => {
    it('extracts all meta fields from standard content', () => {
      const content = [
        '## 异步任务完成',
        '',
        '- 任务 ID：42',
        '- 工具：async-time-tool',
        '- 外部任务 ID：ext-99',
        '- 状态：SUCCESS',
        '- 完成时间：2026-06-12T10:30:00',
        '- 错误信息：some error',
        '',
        '### 任务参数',
        '',
        '```json',
        '{}',
        '```',
        '',
        '### 任务结果',
        '',
        '```',
        'result data',
        '```',
      ].join('\n')

      const meta = parseTaskMeta(content)
      expect(meta.taskId).toBe('42')
      expect(meta.toolName).toBe('async-time-tool')
      expect(meta.externalTaskId).toBe('ext-99')
      expect(meta.status).toBe('SUCCESS')
      expect(meta.finishedAt).toBe('2026-06-12T10:30:00')
      expect(meta.errorMessage).toBe('some error')
    })

    it('returns empty fields for null/empty content', () => {
      const meta = parseTaskMeta(null)
      expect(meta.taskId).toBe('')
      expect(meta.toolName).toBe('')
      expect(meta.externalTaskId).toBe('')
      expect(meta.status).toBe('')
      expect(meta.finishedAt).toBe('')
      expect(meta.errorMessage).toBe('')
    })

    it('returns empty fields when content has no meta lines', () => {
      const meta = parseTaskMeta('just plain text with no metadata')
      expect(meta.toolName).toBe('')
      expect(meta.finishedAt).toBe('')
    })
  })
})