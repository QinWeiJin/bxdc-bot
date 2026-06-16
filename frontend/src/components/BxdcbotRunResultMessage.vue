<script setup lang="ts">
/**
 * Bxdcbot 自主规划 Run 完成消息专用 UI
 *
 * open spec: bxdcbot-multi-turn-async
 *
 * 视觉特性：
 * - 🤖 icon + "Bxdcbot 自主规划" 标签
 * - 状态徽章（completed 绿 / failed 红 / max_rounds 黄）
 * - 子任务摘要（折叠，默认折叠）
 * - 最终文本展示
 */
import { computed, ref } from 'vue'
import type { ToolInvocation } from '../composables/useChat'

const props = defineProps<{
  content: string
  runId?: string
  messageId?: string
  llmLogCount?: number
  toolInvocations?: ToolInvocation[]
}>()

const emit = defineEmits<{
  (e: 'openLog', messageId: string): void
}>()

const showRawLog = ref(false)
const stepsExpanded = ref(false)
const toolLogExpanded = ref(true)

interface RunSummary {
  status?: string
  parentSkillName?: string
  roundsUsed?: number
  totalLlmCalls?: number
  completedCount?: number
  failedCount?: number
  totalCount?: number
  subTaskSummary?: string
  finalText?: string
  subTaskSteps?: Array<{
    skillName: string
    status: string
    result?: string
  }>
}

const mainSkillName = computed(() => {
  // 优先级：1) 后端传来的 parentSkillName 2) 第一个主 tool 的 displayName 3) 静态文案
  if (parsed.value.parentSkillName) return parsed.value.parentSkillName
  const firstTool = props.toolInvocations?.[0]
  if (firstTool?.displayName) return firstTool.displayName
  if (firstTool?.name) return firstTool.name
  return 'Bxdcbot 自主规划'
})

const parsed = computed<RunSummary>(() => {
  try {
    return JSON.parse(props.content)
  } catch {
    return {}
  }
})

const statusLabel = computed(() => {
  switch (parsed.value.status) {
    case 'completed': return '已完成'
    case 'failed': return '失败'
    case 'max_rounds': return '已达上限'
    case 'processing': return '运行中'
    case 'awaiting_async': return '等待异步'
    default: {
      // 有 subTaskSteps 但没 status，看 toolInvocations 的状态推测
      const mainTool = props.toolInvocations?.[0]
      if (mainTool?.status === 'completed') return '已完成'
      if (mainTool?.status === 'failed') return '失败'
      if (mainTool?.status === 'running') return '运行中'
      return parsed.value.status || '处理中'
    }
  }
})

const statusClass = computed(() => {
  switch (parsed.value.status) {
    case 'completed': return 'bxdcbot-status--success'
    case 'failed': return 'bxdcbot-status--failed'
    case 'max_rounds': return 'bxdcbot-status--warning'
    default: {
      const mainTool = props.toolInvocations?.[0]
      if (mainTool?.status === 'completed') return 'bxdcbot-status--success'
      if (mainTool?.status === 'failed') return 'bxdcbot-status--failed'
      return 'bxdcbot-status--unknown'
    }
  }
})

const hasContent = computed(
  () => parsed.value.status
    || parsed.value.subTaskSummary
    || parsed.value.finalText
    || (parsed.value.subTaskSteps && parsed.value.subTaskSteps.length > 0)
    || (props.toolInvocations && props.toolInvocations.length > 0),
)

const summaryLine = computed(() => {
  const parts: string[] = []
  if (parsed.value.roundsUsed != null) parts.push(`${parsed.value.roundsUsed} 轮`)
  if (parsed.value.totalLlmCalls != null) parts.push(`${parsed.value.totalLlmCalls} 次 LLM 调用`)
  if (parsed.value.totalCount != null) {
    parts.push(`${parsed.value.totalCount} 个子任务`)
    if (parsed.value.completedCount != null) parts[parts.length - 1] += `（${parsed.value.completedCount} 完成`
    if (parsed.value.failedCount != null) parts[parts.length - 1] += `, ${parsed.value.failedCount} 失败`
    parts[parts.length - 1] += '）'
  }
  return parts.join(' · ')
})

/** 格式化子步骤结果为可读文本 */
function formatStepResult(raw: string): string {
  try {
    const obj = JSON.parse(raw)
    if (typeof obj === 'object' && obj !== null) {
      return JSON.stringify(obj, null, 2)
    }
  } catch { /* ignore */ }
  return raw
}

interface ToolLogRow {
  key: string
  variant: 'main' | 'sub'
  name: string
  status: string
  statusLabel: string
  result?: string
  parentName?: string
}

const toolLogRows = computed<ToolLogRow[]>(() => {
  const rows: ToolLogRow[] = []
  for (const tool of props.toolInvocations ?? []) {
    rows.push({
      key: `main-${tool.id}`,
      variant: 'main',
      name: tool.displayName || tool.name,
      status: tool.status,
      statusLabel:
        tool.status === 'completed'
          ? '已完成'
          : tool.status === 'failed'
          ? '调用失败'
          : '调用中',
      result: typeof tool.result === 'string'
        ? tool.result
        : tool.result != null
        ? JSON.stringify(tool.result)
        : undefined,
    })
    for (const child of tool.children ?? []) {
      rows.push({
        key: `sub-${tool.id}-${child.id}`,
        variant: 'sub',
        name: child.displayName || child.name,
        status: child.status,
        statusLabel:
          child.status === 'completed'
            ? '已完成'
            : child.status === 'failed'
            ? '调用失败'
            : '调用中',
        result: typeof child.result === 'string'
          ? child.result
          : child.result != null
          ? JSON.stringify(child.result)
          : undefined,
        parentName: tool.displayName || tool.name,
      })
    }
  }
  return rows
})

const toolLogTotalCount = computed(() => toolLogRows.value.length)
</script>

<template>
  <div v-if="hasContent" class="bxdcbot-run-message">
    <div class="bxdcbot-run-header">
      <span class="bxdcbot-run-icon">🤖</span>
      <span class="bxdcbot-run-label">{{ mainSkillName }}</span>
      <span :class="statusClass">{{ statusLabel }}</span>
    </div>

    <div v-if="summaryLine" class="bxdcbot-run-summary-line">
      {{ summaryLine }}
    </div>

    <!-- 主 Skills 运行状态 -->
    <div v-if="props.toolInvocations && props.toolInvocations.length > 0" class="bxdcbot-tools">
      <div
        v-for="tool in props.toolInvocations"
        :key="tool.id"
        class="bxdcbot-tool-item"
        :class="`bxdcbot-tool--${tool.status}`"
      >
        <span class="bxdcbot-tool-name">{{ tool.displayName }}</span>
        <span class="bxdcbot-tool-status">
          {{ tool.status === 'completed' ? '✓ 完成' : tool.status === 'running' ? '⏳ 运行中' : tool.status === 'failed' ? '✗ 失败' : '' }}
        </span>
      </div>
    </div>

    <!-- 子任务步骤列表（默认折叠） -->
    <div v-if="parsed.subTaskSteps && parsed.subTaskSteps.length > 0" class="bxdcbot-sub-steps">
      <div class="bxdcbot-sub-steps-toggle" @click="stepsExpanded = !stepsExpanded">
        <span class="bxdcbot-sub-steps-label">子任务执行步骤（{{ parsed.subTaskSteps.length }} 步）</span>
        <span class="bxdcbot-sub-steps-arrow">{{ stepsExpanded ? '▾' : '▸' }}</span>
      </div>
      <div v-if="stepsExpanded">
        <div
          v-for="(step, idx) in parsed.subTaskSteps"
          :key="idx"
          class="bxdcbot-sub-step"
        >
          <span class="bxdcbot-sub-step-idx">{{ idx + 1 }}.</span>
          <span class="bxdcbot-sub-step-name">{{ step.skillName }}</span>
          <span
            class="bxdcbot-sub-step-status"
            :class="{
              'bxdcbot-sub-step--completed': step.status === 'completed',
              'bxdcbot-sub-step--pending': step.status === 'async_pending',
            }"
          >
            {{ step.status === 'completed' ? '✓ 完成' : step.status === 'async_pending' ? '⏳ 异步' : step.status }}
          </span>
          <pre v-if="step.result" class="bxdcbot-sub-step-result">{{ formatStepResult(step.result) }}</pre>
        </div>
      </div>
    </div>

    <!-- 工具调用链（默认折叠，复用调用日志弹窗风格） -->
    <div v-if="toolLogTotalCount > 0" class="bxdcbot-tool-log">
      <div class="bxdcbot-tool-log-toggle" @click="toolLogExpanded = !toolLogExpanded">
        <span class="bxdcbot-tool-log-arrow">{{ toolLogExpanded ? '▾' : '▸' }}</span>
        <span class="bxdcbot-tool-log-label">工具调用</span>
        <span class="bxdcbot-tool-log-count">共 {{ toolLogTotalCount }} 次</span>
      </div>
      <div v-if="toolLogExpanded" class="bxdcbot-tool-log-list">
        <div
          v-for="row in toolLogRows"
          :key="row.key"
          class="bxdcbot-tool-log-row"
          :class="`bxdcbot-tool-log-row--${row.variant} bxdcbot-tool-log-row--${row.status}`"
        >
          <span class="bxdcbot-tool-log-name">
            <span v-if="row.variant === 'sub'" class="bxdcbot-tool-log-prefix">└ {{ row.parentName }} ›</span>
            {{ row.name }}
          </span>
          <span class="bxdcbot-tool-log-status">{{ row.statusLabel }}</span>
          <pre v-if="row.result" class="bxdcbot-tool-log-result">{{ formatStepResult(row.result) }}</pre>
        </div>
      </div>
    </div>

    <!-- 操作按钮 -->
    <div class="bxdcbot-run-actions">
      <button
        v-if="props.messageId && props.llmLogCount && props.llmLogCount > 0"
        class="bxdcbot-run-log-btn"
        @click="emit('openLog', props.messageId)"
      >
        日志查看
      </button>
      <button class="bxdcbot-run-log-btn" @click="showRawLog = !showRawLog">
        {{ showRawLog ? '收起原始消息' : '查看原始消息' }}
      </button>
    </div>
    <pre v-if="showRawLog" class="bxdcbot-run-raw-log">{{ content }}</pre>
  </div>

  <!-- 内容为空时（JSON 不完整 / 占位消息）做降级渲染 -->
  <div v-else class="bxdcbot-run-message">
    <div class="bxdcbot-run-header">
      <span class="bxdcbot-run-icon">🤖</span>
      <span class="bxdcbot-run-label">{{ mainSkillName }}</span>
      <span class="bxdcbot-status--unknown">处理中</span>
    </div>
    <p class="bxdcbot-run-raw">{{ content }}</p>
  </div>
</template>

<style scoped>
.bxdcbot-run-message {
  background: var(--td-bg-color-container-hover, #f0f4ff);
  border: 1px solid var(--td-component-stroke, #c7d2fe);
  border-radius: 8px;
  padding: 12px 16px;
  margin: 8px 0;
  max-width: 100%;
  font-size: 14px;
}

.bxdcbot-run-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-weight: 500;
}

.bxdcbot-run-icon {
  font-size: 18px;
}

.bxdcbot-run-label {
  color: var(--td-text-color-primary, #333);
}

.bxdcbot-status--success {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  background: #d4edda;
  color: #155724;
}

.bxdcbot-status--failed {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  background: #f8d7da;
  color: #721c24;
}

.bxdcbot-status--warning {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  background: #fff3cd;
  color: #856404;
}

.bxdcbot-status--unknown {
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 500;
  background: #e9ecef;
  color: #6c757d;
}

.bxdcbot-run-summary-line {
  color: var(--td-text-color-secondary, #666);
  font-size: 13px;
  margin-bottom: 8px;
}

/* === 主 Skills 运行状态 === */
.bxdcbot-tools {
  margin: 6px 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.bxdcbot-tool-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 4px;
  font-size: 13px;
  background: var(--td-bg-color-container, #fafafa);
  border: 1px solid var(--td-component-stroke, #e5e7eb);
}

.bxdcbot-tool--completed .bxdcbot-tool-status {
  color: #155724;
  font-weight: 500;
}

.bxdcbot-tool--running .bxdcbot-tool-status {
  color: #856404;
  font-weight: 500;
}

.bxdcbot-tool--failed .bxdcbot-tool-status {
  color: #721c24;
  font-weight: 500;
}

.bxdcbot-tool-name {
  color: var(--td-text-color-primary, #333);
  font-weight: 500;
}

.bxdcbot-run-subtasks {
  margin: 8px 0;
  font-size: 13px;
  color: var(--td-text-color-secondary, #555);
}

.bxdcbot-run-subtasks summary {
  cursor: pointer;
  user-select: none;
  padding: 4px 0;
}

.bxdcbot-run-subtasks-content {
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

/* === 工具调用链（折叠区） === */
.bxdcbot-tool-log {
  margin-top: 8px;
  border: 1px solid var(--td-component-stroke, #e5e7eb);
  border-radius: 6px;
  background: var(--td-bg-color-container, #fff);
  overflow: hidden;
}

.bxdcbot-tool-log-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  cursor: pointer;
  font-size: 13px;
  color: var(--td-text-color-primary, #333);
  background: var(--td-bg-color-container-hover, #f7f8fa);
  user-select: none;
}

.bxdcbot-tool-log-toggle:hover {
  background: var(--td-bg-color-container-active, #eef0f4);
}

.bxdcbot-tool-log-arrow {
  color: var(--td-text-color-secondary, #666);
  font-size: 12px;
}

.bxdcbot-tool-log-label {
  font-weight: 500;
}

.bxdcbot-tool-log-count {
  color: var(--td-text-color-secondary, #888);
  font-size: 12px;
}

.bxdcbot-tool-log-list {
  padding: 4px 0;
}

.bxdcbot-tool-log-row {
  padding: 6px 12px;
  border-left: 3px solid transparent;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.bxdcbot-tool-log-row--main {
  border-left-color: #b3d8ff;
  background: #f0f7ff;
}

.bxdcbot-tool-log-row--sub {
  border-left-color: #ffd591;
  background: #fffbe6;
  margin-left: 16px;
}

.bxdcbot-tool-log-row--running {
  border-left-color: #ffe58f;
}

.bxdcbot-tool-log-row--failed {
  border-left-color: #ffa39e;
  background: #fff1f0;
}

.bxdcbot-tool-log-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--td-text-color-primary, #333);
  display: flex;
  align-items: center;
  gap: 4px;
}

.bxdcbot-tool-log-prefix {
  color: var(--td-text-color-secondary, #888);
  font-weight: 400;
  font-size: 12px;
}

.bxdcbot-tool-log-status {
  font-size: 12px;
  color: var(--td-text-color-secondary, #666);
}

.bxdcbot-tool-log-row--completed .bxdcbot-tool-log-status {
  color: #389e0d;
}

.bxdcbot-tool-log-row--failed .bxdcbot-tool-log-status {
  color: #cf1322;
}

.bxdcbot-tool-log-row--running .bxdcbot-tool-log-status {
  color: #d48806;
}

.bxdcbot-tool-log-result {
  background: var(--td-bg-color-container-hover, #fafafa);
  border-radius: 4px;
  padding: 6px 8px;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  max-height: 200px;
  overflow: auto;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

/* === 查看原始消息按钮 === */
.bxdcbot-run-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}

.bxdcbot-run-log-btn {
  background: var(--td-bg-color-container, #fff);
  border: 1px solid var(--td-component-stroke, #dcdcdc);
  border-radius: 4px;
  padding: 4px 10px;
  font-size: 12px;
  color: var(--td-text-color-secondary, #666);
  cursor: pointer;
  transition: background 0.15s;
}

.bxdcbot-run-log-btn:hover {
  background: var(--td-bg-color-container-hover, #e8e8e8);
}

.bxdcbot-run-raw-log {
  background: var(--td-bg-color-container, #fafafa);
  border-radius: 4px;
  padding: 8px 12px;
  margin-top: 6px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.5;
  max-height: 300px;
  overflow: auto;
}

.bxdcbot-run-raw {
  color: var(--td-text-color-secondary, #888);
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
}

/* === 子任务步骤列表 === */
.bxdcbot-sub-steps {
  margin: 8px 0;
}

.bxdcbot-sub-steps-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  user-select: none;
  padding: 2px 0;
}

.bxdcbot-sub-steps-label {
  font-weight: 500;
  font-size: 13px;
  color: var(--td-text-color-primary, #333);
}

.bxdcbot-sub-steps-arrow {
  color: var(--td-text-color-placeholder, #999);
  font-size: 14px;
}

.bxdcbot-sub-step {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px;
  padding: 4px 8px;
  margin-bottom: 4px;
  background: var(--td-bg-color-container, #fafafa);
  border-radius: 4px;
  font-size: 13px;
}

.bxdcbot-sub-step-idx {
  color: var(--td-text-color-placeholder, #999);
  min-width: 20px;
}

.bxdcbot-sub-step-name {
  font-weight: 500;
  color: var(--td-text-color-primary, #333);
}

.bxdcbot-sub-step-status {
  font-size: 12px;
}

.bxdcbot-sub-step--completed {
  color: #155724;
}

.bxdcbot-sub-step--pending {
  color: #856404;
}

.bxdcbot-sub-step-result {
  width: 100%;
  margin: 4px 0 0 26px;
  padding: 6px 10px;
  background: var(--td-bg-color-container, #fff);
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.4;
  max-height: 120px;
  overflow: auto;
}
</style>
