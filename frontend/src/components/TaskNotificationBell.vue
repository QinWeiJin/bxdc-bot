<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAsyncTaskNotifications, type AsyncTaskNotification } from '../composables/useAsyncTaskNotifications'

const router = useRouter()
const {
  unreadCount,
  tasks,
  loading,
  drawerVisible,
  loadTasks,
  acknowledge,
  openDrawer,
  closeDrawer,
} = useAsyncTaskNotifications()

function statusColor(status: string): 'primary' | 'success' | 'warning' | 'danger' | 'default' {
  switch (status) {
    case 'PENDING': return 'default'
    case 'POLLING': return 'primary'
    case 'COMPLETED': return 'success'
    case 'FAILED': return 'danger'
    case 'TIMEOUT': return 'warning'
    default: return 'default'
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'PENDING': return '等待中'
    case 'POLLING': return '轮询中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    case 'TIMEOUT': return '超时'
    default: return status
  }
}

function fmtTime(s: string | null): string {
  if (!s) return ''
  try {
    const d = new Date(s)
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  } catch {
    return ''
  }
}

function durationLabel(t: AsyncTaskNotification): string {
  if (t.status === 'COMPLETED' || t.status === 'FAILED' || t.status === 'TIMEOUT') {
    if (t.startedAt && t.completedAt) {
      const ms = new Date(t.completedAt).getTime() - new Date(t.startedAt).getTime()
      const sec = Math.max(0, Math.floor(ms / 1000))
      if (sec < 60) return `${sec}s`
      if (sec < 3600) return `${Math.floor(sec / 60)}m${sec % 60}s`
      return `${Math.floor(sec / 3600)}h${Math.floor((sec % 3600) / 60)}m`
    }
    return ''
  }
  // 进行中：基于 elapsedSeconds
  const sec = Math.max(0, t.elapsedSeconds || 0)
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m${sec % 60}s`
  return `${Math.floor(sec / 3600)}h${Math.floor((sec % 3600) / 60)}m`
}

function progressPercent(t: AsyncTaskNotification): number {
  if (t.status === 'COMPLETED') return 100
  if (t.status === 'FAILED' || t.status === 'TIMEOUT') return 100
  if (t.status === 'POLLING') {
    // 粗略估算：基于 elapsed vs maxWait，超出则 99%
    const max = 1800 // 默认最长 30 分钟
    return Math.min(99, Math.floor(((t.elapsedSeconds || 0) / max) * 100))
  }
  if (t.status === 'PENDING') return 5
  return 0
}

function progressStatus(t: AsyncTaskNotification): 'success' | 'error' | 'active' | 'undefined' {
  if (t.status === 'COMPLETED') return 'success'
  if (t.status === 'FAILED' || t.status === 'TIMEOUT') return 'error'
  return 'active'
}

async function handleItemClick(t: AsyncTaskNotification) {
  // 1. 先标记已读
  if (t.unread) {
    await acknowledge(t.id)
  }
  // 2. 关闭抽屉
  closeDrawer()
  // 3. 跳转到对应 sessionId 聊天页（带 taskId query 用于后续处理）
  if (t.sessionId) {
    router.push({ path: `/chat/${t.sessionId}`, query: { taskId: String(t.id) } })
  }
}

async function handleOpen() {
  openDrawer()
  // 立即拉一次
  await loadTasks(false, 50)
}

function handleClose() {
  closeDrawer()
}
</script>

<template>
  <div class="task-notification-bell">
    <t-badge :count="unreadCount" :max-count="99" :show-zero="false" :offset="[-4, 4]">
      <t-button
        theme="default"
        variant="text"
        class="bell-button"
        aria-label="任务通知"
        @click="handleOpen"
      >
        <template #icon>
          <t-icon name="notification" />
        </template>
      </t-button>
    </t-badge>

    <t-drawer
      v-model:visible="drawerVisible"
      header="异步任务通知"
      size="medium"
      :footer="false"
      @close="handleClose"
    >
      <div class="task-notification-content">
        <div class="header-hint">
          <span class="hint-text">仅显示长时间执行的异步任务（asyncPoll 配置）。同步秒回任务不会出现在这里。</span>
        </div>
        <t-loading v-if="loading && tasks.length === 0" text="加载中..." />
        <t-empty v-else-if="tasks.length === 0" description="暂无任务通知" />
        <ul v-else class="task-list">
          <li
            v-for="t in tasks"
            :key="t.id"
            class="task-item"
            :class="{ 'is-unread': t.unread, 'is-clickable': !!t.sessionId }"
            @click="handleItemClick(t)"
          >
            <div class="task-item-header">
              <div class="task-item-title">
                <span class="skill-name">{{ t.skillName || '异步任务' }}</span>
                <t-tag :theme="statusColor(t.status)" size="small">{{ statusLabel(t.status) }}</t-tag>
                <span v-if="t.unread" class="unread-dot" aria-label="未读">●</span>
              </div>
              <div class="task-item-meta">
                <span class="meta-time">{{ fmtTime(t.startedAt || t.createdAt) }}</span>
                <span class="meta-duration">耗时 {{ durationLabel(t) }}</span>
              </div>
            </div>

            <t-progress
              v-if="t.status === 'POLLING' || t.status === 'PENDING' || t.status === 'COMPLETED' || t.status === 'FAILED' || t.status === 'TIMEOUT'"
              :percentage="progressPercent(t)"
              :status="progressStatus(t)"
              :stroke-width="3"
              class="task-progress"
            />

            <div v-if="t.status === 'FAILED' && t.errorMessage" class="task-error">
              <span class="error-label">错误：</span>{{ t.errorMessage }}
            </div>

            <div v-else-if="t.status === 'COMPLETED' && t.previewResult" class="task-preview">
              {{ t.previewResult }}
            </div>

            <div v-else-if="t.externalTaskId" class="task-external">
              外部任务 ID: {{ t.externalTaskId }}
            </div>

            <div v-if="!t.sessionId" class="task-no-session-hint">
              (无关联会话，无法跳转)
            </div>
          </li>
        </ul>
      </div>
    </t-drawer>
  </div>
</template>

<style scoped>
.task-notification-bell {
  display: inline-flex;
  align-items: center;
}

.bell-button {
  font-size: 18px;
}

.task-notification-content {
  padding: 0 4px;
}

.header-hint {
  padding: 0 0 12px 0;
  border-bottom: 1px solid var(--td-component-stroke);
  margin-bottom: 12px;
}

.hint-text {
  font-size: 12px;
  color: var(--td-text-color-placeholder);
  line-height: 1.5;
}

.task-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.task-item {
  padding: 12px;
  border: 1px solid var(--td-component-stroke);
  border-radius: 8px;
  background: var(--td-bg-color-container);
  transition: all 0.2s ease;
}

.task-item.is-clickable {
  cursor: pointer;
}

.task-item.is-clickable:hover {
  border-color: var(--td-brand-color);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.task-item.is-unread {
  border-left: 3px solid var(--td-brand-color);
  background: var(--td-brand-color-light);
}

.task-item-header {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 8px;
}

.task-item-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.skill-name {
  font-weight: 500;
  color: var(--td-text-color-primary);
}

.unread-dot {
  color: var(--td-error-color);
  font-size: 12px;
  margin-left: auto;
}

.task-item-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: var(--td-text-color-placeholder);
}

.task-progress {
  margin: 6px 0 8px 0;
}

.task-error,
.task-preview,
.task-external {
  font-size: 12px;
  color: var(--td-text-color-secondary);
  margin-top: 6px;
  word-break: break-word;
}

.task-error {
  color: var(--td-error-color);
}

.task-no-session-hint {
  font-size: 11px;
  color: var(--td-text-color-disabled);
  margin-top: 6px;
  font-style: italic;
}
</style>
