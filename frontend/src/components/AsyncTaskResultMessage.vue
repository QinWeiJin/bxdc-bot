<script setup lang="ts">
import { computed, ref } from 'vue'
import { MessagePlugin } from 'tdesign-vue-next'
import MarkdownRender from './MarkdownRender.vue'
import {
  getStatusClass as utilGetStatusClass,
  getStatusText as utilGetStatusText,
  showSkeleton as utilShowSkeleton,
  showFallback as utilShowFallback,
  parseTaskMeta as utilParseTaskMeta,
} from '@/utils/asyncTaskMessage'

/**
 * 异步任务结果消息专用 UI 组件
 *
 * open spec: async-task-result-echo-to-chat
 *
 * 视觉特性：
 * - 🔔 icon + "异步任务" 标签（区别普通对话气泡）
 * - 状态徽章（SUCCESS 绿 / FAILED 红 / TIMEOUT 黄）
 * - 折叠原始结果（默认折叠，避免消息太长）
 * - LLM 总结区（summary_pending=true 时显示骨架屏；> 500 字自动折叠）
 * - 复制按钮（一键复制 LLM 总结）
 * - "查看完整任务"链接（跳通知中心，需 asyncTaskId）
 *
 * 纯逻辑（状态类名 / 折叠判断 / 文本解析）抽到 utils/asyncTaskMessage.ts，由 vitest 单测覆盖。
 */
const props = defineProps<{
  /** 任务状态（SUCCESS / FAILED / TIMEOUT）；从 message.content 解析 */
  status: 'SUCCESS' | 'COMPLETED' | 'FAILED' | 'TIMEOUT'
  /** 任务原始结果文本（可能很长） */
  content: string
  /** 关联的 async_tasks.id（用于跳通知中心） */
  asyncTaskId?: string | null
  /** LLM 续答是否尚未生成（1=pending，0=done） */
  summaryPending?: number | null
  /** LLM 续答生成的总结（Markdown 文本） */
  summaryText?: string | null
}>()

const rawExpanded = ref(false)
/** 总结内容是否展开：默认 false —— 一打开对话流，只看到"📝 助手总结 + 复制 + 下载助手总结"
 *  这一栏，不会被长文撑爆。点 header 任意位置（或 chevron）展开/收起。 */
const summaryExpanded = ref(false)

/** LLM 总结是否要展示（pending 状态显示骨架；null/空显示降级） */
const showSummarySkeleton = computed(() => utilShowSkeleton(props.summaryPending, props.summaryText))

const showSummaryFallback = computed(() => utilShowFallback(props.summaryPending, props.summaryText))

const statusClass = computed(() => utilGetStatusClass(props.status))
const statusText = computed(() => utilGetStatusText(props.status))

const toolName = computed(() => utilParseTaskMeta(props.content).toolName)
const finishedAt = computed(() => utilParseTaskMeta(props.content).finishedAt)

async function copySummary() {
  if (!props.summaryText) {
    MessagePlugin.warning('总结为空，无法复制')
    return
  }
  try {
    await navigator.clipboard.writeText(props.summaryText)
    MessagePlugin.success('总结已复制到剪贴板')
  } catch (e) {
    // 兜底：用 textarea execCommand
    try {
      const ta = document.createElement('textarea')
      ta.value = props.summaryText
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
      MessagePlugin.success('总结已复制')
    } catch (e2) {
      MessagePlugin.error('复制失败，请手动选择文本')
    }
  }
}

function downloadSummary() {
  if (!props.summaryText) {
    MessagePlugin.warning('总结为空，无法下载')
    return
  }
  try {
    const blob = new Blob([props.summaryText], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `async-task-summary-${props.asyncTaskId || Date.now()}.md`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    // 1s 后再 revoke，避免某些浏览器 click 后还需要 URL 有效
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    MessagePlugin.success('总结已下载')
  } catch (e) {
    MessagePlugin.error('下载失败，请手动复制文本')
  }
}
</script>

<template>
  <div class="async-task-message">
    <div class="async-task-header">
      <span class="async-task-icon">🔔</span>
      <span class="async-task-label">异步任务</span>
      <span :class="statusClass">{{ statusText }}</span>
      <span v-if="toolName" class="async-task-tool">· {{ toolName }}</span>
    </div>

    <!-- 原始任务结果（默认折叠） -->
    <details ref="rawRef" class="async-task-raw" :open="rawExpanded" @toggle="rawExpanded = ($event.target as HTMLDetailsElement).open">
      <summary>任务原始结果（点击展开）</summary>
      <pre class="async-task-raw-content">{{ content }}</pre>
    </details>

    <!-- LLM 总结区 -->
    <div class="async-task-summary">
      <div
        class="async-task-summary-header"
        role="button"
        tabindex="0"
        @click="summaryExpanded = !summaryExpanded"
        @keydown.enter.prevent="summaryExpanded = !summaryExpanded"
        @keydown.space.prevent="summaryExpanded = !summaryExpanded"
      >
        <span class="async-task-summary-label">
          <span class="async-task-summary-chevron">{{ summaryExpanded ? '▾' : '▸' }}</span>
          📝 助手总结
          <span v-if="!summaryExpanded && summaryText" class="async-task-summary-meta">
            （{{ summaryText.length }} 字）
          </span>
        </span>
        <div class="async-task-summary-actions" @click.stop>
          <button v-if="!showSummarySkeleton && summaryText" class="t-button t-button--small" @click="copySummary">复制</button>
          <button v-if="!showSummarySkeleton && summaryText" class="t-button t-button--small t-button--theme-primary" @click="downloadSummary">
            下载助手总结
          </button>
        </div>
      </div>

      <div v-show="summaryExpanded">
        <div v-if="showSummarySkeleton" class="async-task-summary-skeleton">
          <div class="skeleton-line" style="width: 92%"></div>
          <div class="skeleton-line" style="width: 80%"></div>
          <div class="skeleton-line" style="width: 65%"></div>
          <div class="async-task-summary-skeleton-text">系统正在生成总结...</div>
        </div>

        <div v-else-if="showSummaryFallback" class="async-task-summary-fallback">
          {{ summaryText || '任务已完成（系统未生成总结，可在下方"任务原始结果"展开）' }}
        </div>

        <div v-else class="async-task-summary-content">
          <MarkdownRender :content="summaryText || ''" />
        </div>
      </div>
    </div>

    <div v-if="finishedAt" class="async-task-footer">
      完成于 {{ finishedAt }}
    </div>
  </div>
</template>

<style scoped>
.async-task-message {
  background: var(--td-bg-color-container-hover, #f5f5f5);
  border: 1px solid var(--td-component-stroke, #e7e7e7);
  border-radius: 8px;
  padding: 12px 16px;
  margin: 8px 0;
  max-width: 100%;
  font-size: 14px;
}

.async-task-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-weight: 500;
}

.async-task-icon {
  font-size: 18px;
}

.async-task-label {
  color: var(--td-text-color-primary, #333);
}

.async-status {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
}
.async-status--success,
.async-status--completed {
  background: #d4edda;
  color: #155724;
}
.async-status--failed {
  background: #f8d7da;
  color: #721c24;
}
.async-status--timeout {
  background: #fff3cd;
  color: #856404;
}

.async-task-tool {
  color: var(--td-text-color-secondary, #666);
  font-size: 13px;
  font-weight: 400;
}

.async-task-raw {
  margin: 8px 0;
  font-size: 13px;
  color: var(--td-text-color-secondary, #555);
}
.async-task-raw summary {
  cursor: pointer;
  user-select: none;
  padding: 4px 0;
}
.async-task-raw-content {
  background: var(--td-bg-color-container, #fafafa);
  padding: 8px 12px;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 400px;
  overflow: auto;
  margin: 4px 0 0 0;
  font-size: 12px;
  line-height: 1.5;
}

.async-task-summary {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--td-component-stroke, #e7e7e7);
}

.async-task-summary-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  cursor: pointer;
  user-select: none;
  border-radius: 4px;
  padding: 2px 4px;
  margin-left: -4px;
  margin-right: -4px;
  transition: background 0.15s;
}
.async-task-summary-header:hover {
  background: rgba(0, 0, 0, 0.03);
}
.async-task-summary-header:focus-visible {
  outline: 2px solid var(--td-brand-color, #0052d9);
  outline-offset: 2px;
}

.async-task-summary-label {
  font-weight: 500;
  color: var(--td-text-color-primary, #333);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.async-task-summary-chevron {
  display: inline-block;
  width: 12px;
  font-size: 12px;
  color: var(--td-text-color-secondary, #666);
  transition: transform 0.15s;
}

.async-task-summary-meta {
  font-weight: 400;
  font-size: 12px;
  color: var(--td-text-color-secondary, #888);
  margin-left: 4px;
}

.async-task-summary-actions {
  display: flex;
  gap: 8px;
}

.t-button {
  padding: 4px 12px;
  font-size: 12px;
  border: 1px solid var(--td-component-stroke, #ddd);
  border-radius: 4px;
  background: white;
  cursor: pointer;
  transition: all 0.2s;
}
.t-button:hover {
  border-color: var(--td-brand-color, #0052d9);
  color: var(--td-brand-color, #0052d9);
}
.t-button--theme-primary {
  background: var(--td-brand-color, #0052d9);
  color: white;
  border-color: var(--td-brand-color, #0052d9);
}
.t-button--theme-primary:hover {
  opacity: 0.9;
  color: white;
}

.async-task-summary-content {
  font-size: 14px;
  line-height: 1.6;
  margin-top: 4px;
}

.async-task-summary-skeleton {
  padding: 8px 0;
}
.skeleton-line {
  height: 14px;
  background: linear-gradient(90deg, #ececec 25%, #f5f5f5 50%, #ececec 75%);
  background-size: 200% 100%;
  animation: skeleton-shimmer 1.5s infinite;
  border-radius: 4px;
  margin: 6px 0;
}
@keyframes skeleton-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
.async-task-summary-skeleton-text {
  margin-top: 8px;
  font-size: 12px;
  color: var(--td-text-color-placeholder, #999);
  font-style: italic;
}

.async-task-summary-fallback {
  color: var(--td-text-color-secondary, #666);
  font-size: 13px;
  font-style: italic;
  padding: 4px 0;
}

.async-task-footer {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--td-component-stroke, #f0f0f0);
  font-size: 12px;
  color: var(--td-text-color-placeholder, #999);
}

@media (max-width: 600px) {
  .async-task-message {
    padding: 10px 12px;
  }
  .async-task-summary-content.is-collapsed {
    /* 移动端也保持 2 行高度，避免长总结撑满屏幕 */
    max-height: 3.4em;
  }
}
</style>
