import { ref, computed } from 'vue'
import { useUser } from './useUser'
import { apiUrl } from '../services/config'

/**
 * 异步任务通知 composable。
 * 单例：跨组件共享 unreadCount / tasks / polling state。
 *
 * 行为：
 * - 启动后每 30s 轮询 /api/async-tasks/my/unread-count
 * - 拉取列表时调用 /api/async-tasks/my
 * - markRead 调用 POST /api/async-tasks/{id}/ack
 */
export interface AsyncTaskNotification {
  id: number
  skillId: number | null
  skillName: string | null
  externalTaskId: string | null
  sessionId: string | null
  status: 'PENDING' | 'POLLING' | 'SINGLE_CALLED' | 'COMPLETED' | 'FAILED' | 'TIMEOUT' | string
  pollStrategy: 'PERIODIC' | 'SINGLE_CALL' | null
  retryCount: number
  elapsedSeconds: number
  pollResponseCount: number
  errorMessage: string | null
  startedAt: string | null
  completedAt: string | null
  createdAt: string | null
  notifiedAt: string | null
  unread: boolean
  previewResult: string | null
  /** 异步任务最大等待秒数（前端进度条用） */
  maxWaitSeconds?: number | null
  /** Bxdcbot 自主规划调子 skill 时的 runId（parent_tool_id） */
  parentToolId?: string | null
  /** Bxdcbot 父技能 ID */
  parentSkillId?: number | null
  /** Bxdcbot 父技能名称（通知中心用） */
  parentSkillName?: string | null
}

let _instance: ReturnType<typeof createInstance> | null = null

function createInstance() {
  const { currentUser } = useUser()

  const unreadCount = ref(0)
  const tasks = ref<AsyncTaskNotification[]>([])
  const loading = ref(false)
  const drawerVisible = ref(false)

  let pollTimer: number | null = null

  function authHeaders(): Record<string, string> {
    const h: Record<string, string> = { 'Content-Type': 'application/json' }
    if (currentUser.value?.id) h['X-User-Id'] = currentUser.value.id
    return h
  }

  async function fetchUnreadCount(): Promise<void> {
    if (!currentUser.value?.id) {
      unreadCount.value = 0
      return
    }
    try {
      const res = await fetch(apiUrl('/api/async-tasks/my/unread-count'), {
        method: 'GET',
        headers: authHeaders(),
        credentials: 'include',
      })
      if (!res.ok) return
      const data = await res.json()
      unreadCount.value = Number(data.count) || 0
    } catch {
      // 静默失败，不影响其他功能
    }
  }

  async function loadTasks(unreadOnly = false, limit = 20, parentToolId?: string): Promise<AsyncTaskNotification[]> {
    if (!currentUser.value?.id) {
      tasks.value = []
      return []
    }
    loading.value = true
    try {
      const params = new URLSearchParams({
        unreadOnly: String(unreadOnly),
        limit: String(limit),
      })
      if (parentToolId) params.append('parentToolId', parentToolId)
      const res = await fetch(`${apiUrl('/api/async-tasks/my')}?${params.toString()}`, {
        method: 'GET',
        headers: authHeaders(),
        credentials: 'include',
      })
      if (!res.ok) {
        if (!parentToolId) tasks.value = []
        return []
      }
      const data = await res.json()
      const items: AsyncTaskNotification[] = Array.isArray(data.items) ? data.items : []
      if (parentToolId) {
        // 只把 Bxdcbot 兄弟任务合入到已有列表（不覆盖主列表）
        const map = new Map(tasks.value.map(t => [t.id, t]))
        for (const it of items) map.set(it.id, it)
        tasks.value = Array.from(map.values())
        // 拉兄弟任务后，同步未读数（兜底用权威接口）
        void fetchUnreadCount()
      } else {
        tasks.value = items
        // 列表加载后用后端权威 count 同步未读数（避免只展示 20-50 条导致不准）
        void fetchUnreadCount()
      }
      return items
    } catch {
      if (!parentToolId) tasks.value = []
      return []
    } finally {
      loading.value = false
    }
  }

  async function acknowledge(taskId: number): Promise<boolean> {
    if (!currentUser.value?.id) return false
    try {
      const res = await fetch(apiUrl(`/api/async-tasks/${taskId}/ack`), {
        method: 'POST',
        headers: authHeaders(),
        credentials: 'include',
      })
      if (!res.ok) return false
      const data = await res.json()
      const ok = !!data.ok
      if (ok) {
        // 本地更新 unread 状态
        const idx = tasks.value.findIndex(t => t.id === taskId)
        if (idx >= 0) {
          const t = tasks.value[idx]!
          t.unread = false
          t.notifiedAt = new Date().toISOString()
        }
        // 重新拉后端权威 count
        void fetchUnreadCount()
      }
      return ok
    } catch {
      return false
    }
  }

  /**
   * 删除单条任务。后端仅允许删除属于自己的任务。
   * 成功后从本地列表中移除。
   */
  async function deleteTask(taskId: number): Promise<boolean> {
    if (!currentUser.value?.id) return false
    try {
      const res = await fetch(apiUrl(`/api/async-tasks/${taskId}`), {
        method: 'DELETE',
        headers: authHeaders(),
        credentials: 'include',
      })
      if (!res.ok) return false
      const data = await res.json()
      const ok = !!data.ok
      if (ok) {
        tasks.value = tasks.value.filter(t => t.id !== taskId)
        // 重新拉后端权威 count
        void fetchUnreadCount()
      }
      return ok
    } catch {
      return false
    }
  }

  /**
   * 批量删除任务。
   * 返回后端实际删除的条数。
   */
  async function batchDelete(taskIds: number[]): Promise<number> {
    if (!currentUser.value?.id || taskIds.length === 0) return 0
    try {
      const res = await fetch(apiUrl('/api/async-tasks/batch-delete'), {
        method: 'POST',
        headers: authHeaders(),
        credentials: 'include',
        body: JSON.stringify({ ids: taskIds }),
      })
      if (!res.ok) return 0
      const data = await res.json()
      const affected = Number(data.affected) || 0
      tasks.value = tasks.value.filter(t => !taskIds.includes(t.id))
      // 重新拉后端权威 count
      void fetchUnreadCount()
      return affected
    } catch {
      return 0
    }
  }

  function openDrawer(): void {
    drawerVisible.value = true
    loadTasks(false, 50)
  }

  function closeDrawer(): void {
    drawerVisible.value = false
  }

  function startPolling(intervalMs = 30_000): void {
    stopPolling()
    // 立即拉一次
    fetchUnreadCount()
    pollTimer = window.setInterval(fetchUnreadCount, intervalMs)
  }

  function stopPolling(): void {
    if (pollTimer !== null) {
      window.clearInterval(pollTimer)
      pollTimer = null
    }
  }

  return {
    unreadCount: computed(() => unreadCount.value),
    tasks: computed(() => tasks.value),
    loading: computed(() => loading.value),
    drawerVisible: computed(() => drawerVisible.value),
    fetchUnreadCount,
    loadTasks,
    acknowledge,
    deleteTask,
    batchDelete,
    openDrawer,
    closeDrawer,
    startPolling,
    stopPolling,
  }
}

export function useAsyncTaskNotifications() {
  if (!_instance) {
    _instance = createInstance()
  }
  return _instance
}
