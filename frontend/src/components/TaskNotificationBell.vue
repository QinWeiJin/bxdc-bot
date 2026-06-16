<script setup lang="ts">
import { ref, computed, reactive, watch } from 'vue'
import { useAsyncTaskNotifications, type AsyncTaskNotification } from '../composables/useAsyncTaskNotifications'
import { fmtShortTime, fmtFullTime } from '../utils/datetime'

const {
  unreadCount,
  tasks,
  loading,
  drawerVisible,
  loadTasks,
  acknowledge,
  deleteTask,
  batchDelete,
  openDrawer,
  closeDrawer,
} = useAsyncTaskNotifications()

function statusColor(status: string): 'primary' | 'success' | 'warning' | 'danger' | 'default' {
  switch (status) {
    case 'PENDING': return 'default'
    case 'POLLING': return 'primary'
    case 'SINGLE_CALLED': return 'primary'
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
    case 'SINGLE_CALLED': return '单次调用中'
    case 'COMPLETED': return '已完成'
    case 'FAILED': return '失败'
    case 'TIMEOUT': return '超时'
    default: return status
  }
}

function pollStrategyLabel(ps: string | null | undefined): string {
  switch (ps) {
    case 'PERIODIC': return '周期轮询'
    case 'SINGLE_CALL': return '单次长调用'
    default: return '周期轮询'
  }
}

function fmtTime(s: string | null): string {
  return fmtShortTime(s)
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
  if (t.status === 'POLLING' || t.status === 'SINGLE_CALLED') {
    // 基于任务真实 maxWaitSeconds（优先）或 fallback 默认值
    const max = (t.maxWaitSeconds && t.maxWaitSeconds > 0) ? t.maxWaitSeconds : 1800
    return Math.min(99, Math.floor(((t.elapsedSeconds || 0) / max) * 100))
  }
  if (t.status === 'PENDING') return 5
  return 0
}

/**
 * 进度条 tooltip 文案。当任务运行时间已接近 maxWaitSeconds 但状态仍未变时，
 * 给用户"任务仍在运行中，可能即将完成/可能已卡住"的提示，避免 99% 被误认为 bug。
 * open spec: async-task-polling-completion（前端 UX 部分）
 */
function progressTitle(t: AsyncTaskNotification): string {
  const elapsed = t.elapsedSeconds || 0
  if (t.status === 'POLLING' || t.status === 'SINGLE_CALLED') {
    const max = (t.maxWaitSeconds && t.maxWaitSeconds > 0) ? t.maxWaitSeconds : 1800
    if (elapsed >= max) {
      return `任务已运行 ${elapsed}s（超过最大等待 ${max}s），仍在等待异步接口返回终态`
    }
    if (elapsed >= max * 0.95) {
      return `任务已运行 ${elapsed}s，即将完成`
    }
    return `任务运行中（已 ${elapsed}s / 上限 ${max}s）`
  }
  if (t.status === 'COMPLETED') return '任务已完成'
  if (t.status === 'FAILED') return '任务失败'
  if (t.status === 'TIMEOUT') return '任务超时'
  if (t.status === 'PENDING') return '任务等待中'
  return ''
}

function progressStatus(t: AsyncTaskNotification): 'success' | 'error' | 'active' | 'undefined' {
  if (t.status === 'COMPLETED') return 'success'
  if (t.status === 'FAILED' || t.status === 'TIMEOUT') return 'error'
  return 'active'
}

function fmtDetailTime(s: string | null): string {
  return fmtFullTime(s)
}

// 批量选择模式 + 选中集合
// 用 reactive(Set) 而非 ref(Set)：ref 内部 Set 不会被 Proxy 包装，add/delete 不触发响应式
const batchMode = ref(false)
const selectedIds = reactive(new Set<number>())

// 详情弹窗
const detailVisible = ref(false)
const detailTask = ref<AsyncTaskNotification | null>(null)
/** Bxdcbot run 同一 parentToolId 下的所有子任务（detail 弹窗展示用） */
const siblingTasks = ref<AsyncTaskNotification[]>([])

/** 列表里按 parentToolId 分组后，每个 parent 的子任务数（含自身） */
const childCountByParent = computed(() => {
  const m = new Map<string, number>()
  for (const t of tasks.value) {
    if (t.parentToolId) {
      m.set(t.parentToolId, (m.get(t.parentToolId) ?? 0) + 1)
    }
  }
  return m
})

/**
 * 分组后的列表：把同一 parentToolId 的所有子任务合并为一条「Bxdcbot run 通知」。
 * 无 parentToolId 的普通 async 任务保持原样。
 */
interface GroupedNotification {
  /** 组内所有原始任务 */
  members: AsyncTaskNotification[]
  /** 用于「代表任务」展示（取 createdAt 最新的一条） */
  rep: AsyncTaskNotification
  /** 是否有 Bxdcbot 父上下文 */
  isBxdcbotGroup: boolean
  /** 父 run id */
  parentToolId: string | null
}

const groupedTasks = computed<GroupedNotification[]>(() => {
  const out: GroupedNotification[] = []
  const seenParents = new Set<string>()
  for (const t of tasks.value) {
    if (t.parentToolId) {
      if (seenParents.has(t.parentToolId)) continue
      seenParents.add(t.parentToolId)
      const members = tasks.value.filter(x => x.parentToolId === t.parentToolId)
      // 按 createdAt 倒序取第一个作为 rep
      const rep = members.slice().sort((a, b) =>
        new Date(b.createdAt || 0).getTime() - new Date(a.createdAt || 0).getTime()
      )[0] || t
      out.push({ members, rep, isBxdcbotGroup: true, parentToolId: t.parentToolId })
    } else {
      out.push({ members: [t], rep: t, isBxdcbotGroup: false, parentToolId: null })
    }
  }
  return out
})

/** 同一 parent 下，当前任务之前/之后的兄弟 */
const detailSiblingTasks = computed(() => {
  if (!detailTask.value?.parentToolId) return []
  // siblingTasks 已经在 watch 里赋值
  return siblingTasks.value.filter(t => t.parentToolId === detailTask.value!.parentToolId)
})

/** 详情页用：如果当前任务是 Bxdcbot 组的成员，返回对应的组；否则 null */
const detailGroup = computed<GroupedNotification | null>(() => {
  if (!detailTask.value?.parentToolId) return null
  return groupedTasks.value.find(g => g.parentToolId === detailTask.value!.parentToolId) ?? null
})

/** 详情页用：组内所有成员（含自身），若 detailGroup 为 null 则退化为 [detailTask] */
const detailGroupMembers = computed<AsyncTaskNotification[]>(() => {
  if (detailGroup.value) return detailGroup.value.members
  return detailTask.value ? [detailTask.value] : []
})

/** 详情页用：与消息卡片同一时间口径（最早 start → 最晚 completed） */
const detailGroupLabel = computed(() => {
  if (!detailGroup.value) return ''
  return groupDurationLabel(detailGroup.value)
})

/**
 * 可批量选择的项数：每组算一项（Bxdcbot 整组算一项，普通任务也算一项）。
 * 与 toggleSelectAll / 全选 checkbox 视觉状态共享同一口径。
 */
const selectableCount = computed(() => {
  return groupedTasks.value.length
})

/**
 * 红点徽章显示的未读数：与当前已加载的列表保持一致
 * - 列表加载后：用 groupedTasks 中 rep.unread === true 的数量
 * - 列表未加载（空 / loading 中）：fallback 用后端权威 unreadCount
 *
 * 这样无论 Bxdcbot 组折叠多少个任务，徽章数字 = 用户实际可见的卡片数。
 */
const displayUnreadCount = computed(() => {
  if (groupedTasks.value.length === 0) {
    return unreadCount.value
  }
  let count = 0
  for (const g of groupedTasks.value) {
    if (g.rep.unread) count += 1
  }
  return count
})

watch(detailVisible, async (visible) => {
  if (!visible || !detailTask.value) {
    siblingTasks.value = []
    return
  }
  // 有 parentToolId 的 Bxdcbot 任务：拉所有同 parentToolId 的兄弟任务
  if (detailTask.value.parentToolId) {
    const items = await loadTasks(false, 50, detailTask.value.parentToolId)
    // loadTasks 现在只把 sibling 合入到 tasks，siblingTasks 直接用返回的 items（不依赖全局 tasks）
    if (items.length > 0) {
      siblingTasks.value = items
    } else {
      // 兜底：从已有 tasks 里过滤
      siblingTasks.value = tasks.value.filter(t => t.parentToolId === detailTask.value!.parentToolId)
    }
  }
})

function toggleSelect(taskId: number) {
  if (selectedIds.has(taskId)) {
    selectedIds.delete(taskId)
  } else {
    selectedIds.add(taskId)
  }
}

/**
 * 列表行点击：根据模式分支
 * - batch 模式 → 切换整组选中（Bxdcbot 整组也支持整组勾选，按"组"为单位删除）
 * - 否则 → 打开详情
 *
 * 配合 t-checkbox 的 @click.stop 防止双重 toggle 漏洞
 * （checkbox 自身 click 不会冒泡到 row click）。
 */
function onRowClick(group: GroupedNotification) {
  if (batchMode.value) {
    toggleSelect(group.rep.id)
  } else {
    handleViewDetail(group.rep)
  }
}

function toggleSelectAll() {
  // 批量管理现在也支持 Bxdcbot 整组：每组只放 rep id 进去，
  // 删除时再按 group 展开成全部成员 id
  const selectable = groupedTasks.value.map(g => g.rep.id)
  if (selectedIds.size === selectable.length && selectable.length > 0) {
    selectedIds.clear()
  } else {
    selectedIds.clear()
    for (const id of selectable) selectedIds.add(id)
  }
}

function enterBatchMode() {
  batchMode.value = true
  selectedIds.clear()
}

function exitBatchMode() {
  batchMode.value = false
  selectedIds.clear()
}

/**
 * 把当前选中的「组」展开成需要传给后端的 task id 列表。
 * - 普通任务：1 个 id（rep.id 本身就是任务 id）
 * - Bxdcbot 整组：组内所有成员 id（这样点"删除整组"才不会有 sibling 残留在通知中心）
 */
function expandSelectedGroupsToTaskIds(): number[] {
  const ids: number[] = []
  for (const g of groupedTasks.value) {
    if (!selectedIds.has(g.rep.id)) continue
    if (g.isBxdcbotGroup) {
      for (const m of g.members) ids.push(m.id)
    } else {
      ids.push(g.rep.id)
    }
  }
  return ids
}

function findGroupByTaskId(taskId: number): GroupedNotification | null {
  return groupedTasks.value.find(g => g.rep.id === taskId) ?? null
}

async function handleBatchDelete() {
  if (selectedIds.size === 0) return
  const ids = expandSelectedGroupsToTaskIds()
  if (ids.length === 0) return
  const ok = confirm(`确定删除选中的 ${ids.length} 条任务通知？此操作不可恢复。`)
  if (!ok) return
  const affected = await batchDelete(ids)
  // 同步刷新弹窗里的 siblingTasks（dialog 还开着时不能继续显示已删内容）
  if (siblingTasks.value.length > 0) {
    const idSet = new Set(ids)
    siblingTasks.value = siblingTasks.value.filter(t => !idSet.has(t.id))
  }
  selectedIds.clear()
  // 无论后端删了几个，都退出批量模式（避免卡死）
  batchMode.value = false
  if (affected > 0) {
    void affected
  }
}

async function handleSingleDelete(t: AsyncTaskNotification) {
  // 找到 t 所在的 group：可能是普通任务也可能是 Bxdcbot 整组
  const group = findGroupByTaskId(t.id)
  const isWholeBxdcbotGroup = !!group && group.isBxdcbotGroup && group.members.length > 1
  const ids = isWholeBxdcbotGroup ? group!.members.map(m => m.id) : [t.id]
  const confirmText = isWholeBxdcbotGroup
    ? `确定删除该 Bxdcbot 整组（共 ${group!.members.length} 个子任务）？此操作不可恢复。`
    : `确定删除任务 #${t.id}（${t.skillName || '异步任务'}）？此操作不可恢复。`
  const ok = confirm(confirmText)
  if (!ok) return
  await batchDelete(ids)
  // 同步刷新弹窗里的 siblingTasks
  if (siblingTasks.value.length > 0) {
    const idSet = new Set(ids)
    siblingTasks.value = siblingTasks.value.filter(s => !idSet.has(s.id))
  }
  // 如果弹窗里正在看的就是被删的那个，关掉弹窗避免看到"残留的初始内容"
  if (detailTask.value && ids.includes(detailTask.value.id)) {
    handleCloseDetail()
  }
  selectedIds.delete(t.id)
}

function handleViewDetail(t: AsyncTaskNotification) {
  // 标记已读
  if (t.unread) {
    void acknowledge(t.id)
  }
  detailTask.value = t
  detailVisible.value = true
}

function handleCloseDetail() {
  detailVisible.value = false
  detailTask.value = null
}

/** 分组耗时：从最早 startedAt/createdAt 到最晚 completedAt（或 now） */
function groupDurationLabel(g: GroupedNotification): string {
  if (!g.isBxdcbotGroup) return durationLabel(g.rep)
  const members = g.members
  const starts = members.map(m => m.startedAt || m.createdAt).filter(Boolean) as string[]
  const ends = members.map(m => m.completedAt).filter(Boolean) as string[]
  if (starts.length === 0) return ''
  const earliestMs = Math.min(...starts.map(s => new Date(s).getTime()))
  const latestMs = ends.length > 0
    ? Math.max(...ends.map(s => new Date(s).getTime()))
    : Date.now()
  const sec = Math.max(0, Math.floor((latestMs - earliestMs) / 1000))
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m${sec % 60}s`
  return `${Math.floor(sec / 3600)}h${Math.floor((sec % 3600) / 60)}m`
}

/** 分组时间范围：创建时间 ~ 完成时间 */
function groupTimeRange(g: GroupedNotification): string {
  if (!g.isBxdcbotGroup) return g.rep.startedAt || g.rep.createdAt || ''
  const members = g.members
  const starts = members.map(m => m.startedAt || m.createdAt).filter(Boolean) as string[]
  const ends = members.map(m => m.completedAt).filter(Boolean) as string[]
  if (starts.length === 0) return ''
  const earliest = starts.reduce((a, b) => new Date(a).getTime() < new Date(b).getTime() ? a : b)
  const startStr = fmtShortTime(earliest)
  if (ends.length === 0) return `创建 ${startStr}`
  const latest = ends.reduce((a, b) => new Date(a).getTime() > new Date(b).getTime() ? a : b)
  const endStr = fmtShortTime(latest)
  return `${startStr} → ${endStr}`
}

/** 分组进度：按完成/总子任务比例 */
function groupProgressPercent(g: GroupedNotification): number {
  if (!g.isBxdcbotGroup) return progressPercent(g.rep)
  const total = g.members.length
  if (total === 0) return 0
  const done = g.members.filter(m => m.status === 'COMPLETED' || m.status === 'FAILED' || m.status === 'TIMEOUT').length
  return Math.floor((done / total) * 100)
}

/** 显示用技能名：有 parentSkillName 时优先显示父技能名 */
function displaySkillName(t: AsyncTaskNotification): string {
  if (t.parentSkillName) return t.parentSkillName
  return t.skillName || '异步任务'
}

/** 是否有子任务上下文 */
function hasParentContext(t: AsyncTaskNotification): boolean {
  return !!t.parentToolId
}

async function handleOpen() {
  openDrawer()
  // 打开抽屉时退出批量模式
  batchMode.value = false
  selectedIds.clear()
  // 立即拉一次
  await loadTasks(false, 50)
}

function handleClose() {
  closeDrawer()
  batchMode.value = false
  selectedIds.clear()
}
</script>

<template>
  <div class="task-notification-bell">
    <t-badge :count="displayUnreadCount" :max-count="99" :show-zero="false" :offset="[-4, 4]">
      <t-button
        theme="default"
        variant="text"
        aria-label="任务通知"
        @click="handleOpen"
      >
        消息
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
        <t-loading v-if="loading && groupedTasks.length === 0" text="加载中..." />
        <t-empty v-else-if="groupedTasks.length === 0" description="暂无任务通知" />
        <div v-if="batchMode" class="batch-toolbar">
          <t-checkbox
            :checked="selectedIds.size === selectableCount && selectableCount > 0"
            :indeterminate="selectedIds.size > 0 && selectedIds.size < selectableCount"
            @change="toggleSelectAll"
          >
            全选 ({{ selectedIds.size }}/{{ selectableCount }})
          </t-checkbox>
          <div class="batch-actions">
            <t-button
              theme="danger"
              size="small"
              variant="text"
              :disabled="selectedIds.size === 0"
              @click="handleBatchDelete"
            >
              批量删除
            </t-button>
            <t-button theme="default" size="small" variant="text" @click="exitBatchMode">
              取消
            </t-button>
          </div>
        </div>
        <div v-else class="list-toolbar">
          <t-button theme="default" size="small" variant="text" @click="enterBatchMode">
            批量管理
          </t-button>
        </div>

        <ul v-if="groupedTasks.length > 0" class="task-list">
          <li
            v-for="group in groupedTasks"
            :key="group.rep.id + '|' + (group.parentToolId || '')"
            class="task-item"
            :class="{ 'is-unread': group.rep.unread, 'is-selected': selectedIds.has(group.rep.id) }"
          >
            <div class="task-item-main">
              <t-checkbox
                v-if="batchMode"
                :checked="selectedIds.has(group.rep.id)"
                @change="toggleSelect(group.rep.id)"
                @click.stop
              />
              <div class="task-item-content" @click.stop="onRowClick(group)">
                <div class="task-item-header">
                  <div class="task-item-title">
                    <span class="skill-name">{{ displaySkillName(group.rep) }}</span>
                    <span v-if="group.isBxdcbotGroup" class="parent-badge">自主规划</span>
                    <t-tag v-if="group.isBxdcbotGroup" theme="primary" size="small" variant="light">
                      {{ group.members.length }} 个子任务
                    </t-tag>
                    <t-tag :theme="statusColor(group.rep.status)" size="small">{{ statusLabel(group.rep.status) }}</t-tag>
                    <span v-if="group.rep.unread" class="unread-dot" aria-label="未读">●</span>
                  </div>
                  <div v-if="group.isBxdcbotGroup" class="task-item-subtitle">
                    <span class="sub-skill-list">
                      {{ Array.from(new Set(group.members.map(m => m.skillName).filter(Boolean))).join(' / ') }}
                    </span>
                    <span class="meta-time">{{ groupTimeRange(group) }}</span>
                    <span class="meta-duration">耗时 {{ groupDurationLabel(group) }}</span>
                  </div>
                  <div v-else class="task-item-meta">
                    <span class="meta-time">{{ fmtTime(group.rep.startedAt || group.rep.createdAt) }}</span>
                    <span class="meta-duration">耗时 {{ durationLabel(group.rep) }}</span>
                  </div>
                </div>

                <t-progress
                  v-if="group.rep.status === 'POLLING' || group.rep.status === 'PENDING' || group.rep.status === 'SINGLE_CALLED' || group.rep.status === 'COMPLETED' || group.rep.status === 'FAILED' || group.rep.status === 'TIMEOUT'"
                  :percentage="group.isBxdcbotGroup ? groupProgressPercent(group) : progressPercent(group.rep)"
                  :status="progressStatus(group.rep)"
                  :stroke-width="3"
                  class="task-progress"
                />

                <div v-if="group.rep.status === 'FAILED' && group.rep.errorMessage" class="task-error">
                  <span class="error-label">错误：</span>{{ group.rep.errorMessage }}
                </div>

                <div v-else-if="group.rep.status === 'COMPLETED' && group.rep.previewResult" class="task-preview">
                  {{ group.rep.previewResult }}
                </div>

                <div v-else-if="group.rep.externalTaskId" class="task-external">
                  外部任务 ID: {{ group.rep.externalTaskId }}
                </div>
              </div>
            </div>

            <div v-if="!batchMode" class="task-item-actions">
              <t-button theme="primary" size="small" variant="text" @click="handleViewDetail(group.rep)">
                查看
              </t-button>
              <t-button theme="danger" size="small" variant="text" @click="handleSingleDelete(group.rep)">
                删除
              </t-button>
            </div>
          </li>
        </ul>
      </div>
    </t-drawer>

    <t-dialog
      v-model:visible="detailVisible"
      :header="detailTask ? `任务详情 #${detailTask.id}` : '任务详情'"
      :footer="false"
      width="640px"
      @close="handleCloseDetail"
    >
      <div v-if="detailTask" class="task-detail">
        <div class="detail-row">
          <span class="detail-label">任务 ID：</span>
          <span class="detail-value">#{{ detailTask.id }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">技能：</span>
          <span class="detail-value">{{ detailTask.skillName || '异步任务' }} <span v-if="detailTask.skillId" class="muted">(id: {{ detailTask.skillId }})</span></span>
        </div>
        <div class="detail-row">
          <span class="detail-label">状态：</span>
          <t-tag :theme="statusColor(detailTask.status)" size="small">{{ statusLabel(detailTask.status) }}</t-tag>
          <span v-if="detailTask.unread" class="unread-dot" aria-label="未读">● 未读</span>
        </div>
        <div v-if="detailTask.externalTaskId" class="detail-row">
          <span class="detail-label">外部任务 ID：</span>
          <span class="detail-value mono">{{ detailTask.externalTaskId }}</span>
        </div>
        <div v-if="detailTask.parentToolId" class="detail-row">
          <span class="detail-label">Bxdcbot Run ID：</span>
          <span class="detail-value mono">{{ detailTask.parentToolId }}</span>
        </div>
        <div v-if="detailTask.sessionId" class="detail-row">
          <span class="detail-label">会话 ID：</span>
          <span class="detail-value mono">{{ detailTask.sessionId }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">轮询策略：</span>
          <span class="detail-value">{{ pollStrategyLabel(detailTask.pollStrategy) }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">重试次数：</span>
          <span class="detail-value">{{ detailTask.retryCount }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">已耗时：</span>
          <span class="detail-value">
            <span v-if="detailGroup">{{ detailGroupLabel }}（共 {{ detailGroupMembers.length }} 个子任务）</span>
            <span v-else>{{ durationLabel(detailTask) }}</span>
          </span>
        </div>
        <div class="detail-row">
          <span class="detail-label">开始时间：</span>
          <span class="detail-value mono">{{ fmtDetailTime(detailTask.startedAt) }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">创建时间：</span>
          <span class="detail-value mono">{{ fmtDetailTime(detailTask.createdAt) }}</span>
        </div>
        <div v-if="detailTask.completedAt" class="detail-row">
          <span class="detail-label">完成时间：</span>
          <span class="detail-value mono">{{ fmtDetailTime(detailTask.completedAt) }}</span>
        </div>

        <t-progress
          v-if="detailTask.status === 'POLLING' || detailTask.status === 'PENDING' || detailTask.status === 'SINGLE_CALLED'"
          :percentage="progressPercent(detailTask)"
          :status="progressStatus(detailTask)"
          :title="progressTitle(detailTask)"
          :stroke-width="4"
          class="detail-progress"
        />

        <!--
          整合后的执行结果区：
          - 单条任务：直接展示该任务的 previewResult / errorMessage
          - 多条任务（parentToolId 相同的 Bxdcbot run 兄弟）：把 main task 和所有 sibling
            合并到同一份「执行结果」卡片里，每条一个 sibling-result-item，
            避免出现「main task 块 + sibling 块」两份结果摘要造成的"最新覆盖原始"错觉
        -->
        <div class="detail-block">
          <div class="detail-block-title">
            <template v-if="detailSiblingTasks.length > 1">
              执行结果（Skills × {{ detailSiblingTasks.length }}）
            </template>
            <template v-else>执行结果</template>
          </div>
          <!-- 多条 sibling 情况：每条独立一行 -->
          <template v-if="detailSiblingTasks.length > 1">
            <div
              v-for="sib in detailSiblingTasks"
              :key="sib.id"
              class="sibling-result-item"
            >
              <div class="sibling-result-header">
                <span class="sibling-skill-name">{{ sib.skillName || `任务 #${sib.id}` }}</span>
                <t-tag :theme="statusColor(sib.status)" size="small">{{ statusLabel(sib.status) }}</t-tag>
                <span class="sibling-meta">耗时 {{ durationLabel(sib) }}</span>
              </div>
              <pre
                v-if="sib.previewResult"
                class="detail-block-content sibling-result-body"
              >{{ sib.previewResult }}</pre>
              <pre
                v-else-if="sib.errorMessage"
                class="detail-block-content detail-block-content--error"
              >{{ sib.errorMessage }}</pre>
              <div v-else class="sibling-result-empty">（暂无结果）</div>
            </div>
          </template>
          <!-- 单条任务：只展示这一条的内容（包含 main task 自身的 previewResult / errorMessage）-->
          <template v-else>
            <pre
              v-if="detailTask.previewResult"
              class="detail-block-content"
            >{{ detailTask.previewResult }}</pre>
            <pre
              v-else-if="detailTask.errorMessage"
              class="detail-block-content detail-block-content--error"
            >{{ detailTask.errorMessage }}</pre>
            <div v-else class="sibling-result-empty">（暂无结果）</div>
          </template>
        </div>
      </div>
    </t-dialog>
  </div>
</template>

<style scoped>
.task-notification-bell {
  display: inline-flex;
  align-items: center;
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

.parent-badge {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 500;
  background: #e8f0fe;
  color: #1a73e8;
  white-space: nowrap;
}

.task-item-subtitle {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--td-text-color-secondary);
}

.sub-skill-name {
  color: var(--td-text-color-placeholder);
}

.sub-task-count {
  color: var(--td-text-color-secondary);
  font-size: 12px;
}

.sub-skill-list {
  color: var(--td-text-color-secondary);
  font-size: 12px;
  word-break: break-all;
  line-height: 1.5;
  flex: 1 1 auto;
  min-width: 0;
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

.list-toolbar {
  display: flex;
  justify-content: flex-end;
  padding: 0 4px 8px 4px;
}

.batch-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  margin-bottom: 10px;
  background: var(--td-brand-color-light);
  border: 1px solid var(--td-brand-color);
  border-radius: 6px;
}

.batch-actions {
  display: flex;
  gap: 4px;
}

.task-item-main {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.task-item-content {
  flex: 1;
  min-width: 0;
  cursor: pointer;
}

.task-item-actions {
  display: flex;
  gap: 4px;
  margin-left: 8px;
}

.task-detail {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.detail-row {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
}

.detail-label {
  color: var(--td-text-color-secondary);
  min-width: 80px;
}

.detail-value {
  color: var(--td-text-color-primary);
}

.detail-value.mono {
  font-family: var(--td-font-family-mono);
  word-break: break-all;
}

.muted {
  color: var(--td-text-color-placeholder);
  font-size: 12px;
}

.detail-progress {
  margin: 12px 0;
}

.detail-block {
  margin-top: 12px;
  padding: 12px;
  background: var(--td-bg-color-secondarycontainer);
  border-radius: 6px;
}

.detail-block-error {
  background: var(--td-error-color-light);
}

.detail-block-title {
  font-size: 12px;
  font-weight: 500;
  color: var(--td-text-color-secondary);
  margin-bottom: 6px;
}

.detail-block-content {
  font-size: 12px;
  color: var(--td-text-color-primary);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow: auto;
  margin: 0;
  font-family: var(--td-font-family-mono);
}

.sibling-result-item {
  margin-top: 8px;
  padding: 8px 10px;
  background: var(--td-bg-color-container);
  border: 1px solid var(--td-component-stroke);
  border-radius: 4px;
}

.sibling-result-item:first-of-type {
  margin-top: 0;
}

.sibling-result-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.sibling-skill-name {
  font-weight: 500;
  color: var(--td-text-color-primary);
  font-size: 13px;
}

.sibling-meta {
  margin-left: auto;
  font-size: 11px;
  color: var(--td-text-color-placeholder);
}

.sibling-result-body {
  max-height: 200px;
}

.sibling-result-empty {
  font-size: 12px;
  color: var(--td-text-color-placeholder);
  font-style: italic;
}

.detail-block-content--error {
  color: var(--td-error-color);
  background: var(--td-error-color-light);
  padding: 6px 8px;
  border-radius: 4px;
}
</style>
