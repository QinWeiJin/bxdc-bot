<script setup lang="ts">
import { ref, computed } from 'vue'
import {
  ChatIcon,
  AddIcon,
  EditIcon,
  DeleteIcon,
  ViewListIcon,
  ChevronLeftIcon,
  SettingIcon,
  ShareIcon,
} from 'tdesign-icons-vue-next'
import { useConversations } from '../composables/useConversations'
import { useUser } from '../composables/useUser'
import { MessagePlugin } from 'tdesign-vue-next'
import type { Conversation } from '../types/conversation'
import ConversationSkillPanel from './ConversationSkillPanel.vue'
import PublishApiModal from './PublishApiModal.vue'

const { currentUser } = useUser()
const {
  conversations,
  currentConversationId,
  switchConversation,
  newConversation,
  renameConversation,
  deleteConversation,
  isProcessing,
} = useConversations()

const collapsed = defineModel<boolean>('collapsed', { default: false })
const editingId = ref<string | null>(null)

function toggleCollapsed() {
  collapsed.value = !collapsed.value
}
const editName = ref('')
const editInputRef = ref<HTMLInputElement | null>(null)

// Skill configuration panel state
const skillPanelVisible = ref(false)
const skillPanelConvId = ref('')
const skillPanelConvEnabledIds = ref<number[]>([])

const emit = defineEmits<{
  (e: 'select', conversationId: string): void
  (e: 'published', conversationId: string): void
}>()

const sortedConversations = computed<Conversation[]>(() => {
  const list = conversations.value || []
  return [...list].sort((a, b) => {
    return new Date(b.updated_at).getTime() - new Date(a.updated_at).getTime()
  })
})

function formatTime(dateStr: string): string {
  const now = Date.now()
  const date = new Date(dateStr).getTime()
  const diff = now - date
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} 天前`
  return new Date(dateStr).toLocaleDateString('zh-CN')
}

function displayName(conv: Conversation): string {
  return conv.name || '新对话'
}

async function handleSelect(conv: Conversation) {
  if (isProcessing.value) {
    MessagePlugin.warning('当前对话正在进行中，请等待完成后切换')
    return
  }
  if (!currentUser.value) return
  await switchConversation(conv.conversation_id, currentUser.value.id)
  emit('select', conv.conversation_id)
}

async function handleNew() {
  if (isProcessing.value) {
    MessagePlugin.warning('当前对话正在进行中，请等待完成后再新建')
    return
  }
  if (!currentUser.value) return
  const id = await newConversation(currentUser.value.id)
  emit('select', id)
}

function startEdit(conv: Conversation, event: Event) {
  event.stopPropagation()
  editingId.value = conv.conversation_id
  editName.value = conv.name
  // Focus input after Vue renders it
  setTimeout(() => {
    editInputRef.value?.focus()
    editInputRef.value?.select()
  }, 0)
}

async function commitEdit() {
  if (!editingId.value || !currentUser.value) return
  const trimmed = editName.value.trim()
  if (trimmed) {
    await renameConversation(editingId.value, trimmed, currentUser.value.id)
  }
  editingId.value = null
}

function cancelEdit() {
  editingId.value = null
}

async function handleDelete(conv: Conversation) {
  if (!currentUser.value) return
  await deleteConversation(conv.conversation_id, currentUser.value.id)
  if (currentConversationId.value !== conv.conversation_id) return
  if ((conversations.value || []).length > 0) {
    emit('select', (conversations.value || [])[0]?.conversation_id ?? '')
  }
}

function openSkillPanel(conv: Conversation) {
  skillPanelConvId.value = conv.conversation_id
  skillPanelConvEnabledIds.value = parseEnabledSkills(conv.enabled_skills)
  skillPanelVisible.value = true
}

// Publish modal state
const publishModalVisible = ref(false)
const publishTargetConvId = ref('')

function openPublishModal(conv: Conversation) {
  publishTargetConvId.value = conv.conversation_id
  publishModalVisible.value = true
}

function onPublished(apiKey: string) {
  publishModalVisible.value = false
  emit('published', publishTargetConvId.value)
}

function onSkillPanelSaved(ids: number[]) {
  // Update local conversations list
  const conv = (conversations.value || []).find(
    (c) => c.conversation_id === skillPanelConvId.value,
  )
  if (conv) {
    conv.enabled_skills = JSON.stringify(ids)
  }
}

function parseEnabledSkills(raw: string): number[] {
  if (!raw) return []
  try {
    return JSON.parse(raw) as number[]
  } catch {
    return []
  }
}

function handleEditKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter') {
    event.preventDefault()
    commitEdit()
  } else if (event.key === 'Escape') {
    cancelEdit()
  }
}
</script>

<template>
  <div class="sidebar" :class="{ collapsed }">
    <!-- Collapse toggle button (always visible) -->
    <div class="sidebar-toggle" @click="toggleCollapsed">
      <ChevronLeftIcon v-if="!collapsed" />
      <ViewListIcon v-else />
    </div>

    <template v-if="!collapsed">
      <!-- New conversation button -->
      <div class="sidebar-header">
        <t-button
          block
          theme="primary"
          variant="outline"
          @click="handleNew"
        >
          <template #icon><AddIcon /></template>
          新建对话
        </t-button>
      </div>

      <!-- Conversation list -->
      <div class="sidebar-list">
        <div v-if="conversations.length === 0" class="sidebar-empty">
          <ChatIcon class="empty-icon" />
          <span>暂无对话</span>
        </div>

        <div
          v-for="conv in sortedConversations"
          :key="conv.conversation_id"
          class="sidebar-item"
          :class="{ active: currentConversationId === conv.conversation_id }"
          @click="handleSelect(conv)"
        >
          <!-- Title row — full width -->
          <div class="item-title-row">
            <input
              v-if="editingId === conv.conversation_id"
              ref="editInputRef"
              v-model="editName"
              class="item-name-input"
              @blur="commitEdit"
              @keydown="handleEditKeydown"
              @click.stop
            />
            <span
              v-else
              class="item-name"
              @dblclick="startEdit(conv, $event)"
            >{{ displayName(conv) }}</span>
            <t-tag
              v-if="conv.is_published"
              theme="success"
              variant="light"
              size="small"
              class="api-badge"
            >API</t-tag>
            <t-button
              v-if="editingId !== conv.conversation_id"
              class="item-action"
              theme="default"
              variant="text"
              size="small"
              shape="square"
              @click.stop="startEdit(conv, $event)"
            >
              <EditIcon />
            </t-button>
          </div>

          <!-- Meta row — time + actions -->
          <div class="item-meta-row">
            <span class="item-time">{{ formatTime(conv.updated_at) }}</span>
            <div class="item-actions">
              <t-tooltip v-if="!conv.is_published" content="发布为API">
                <t-button
                  class="item-icon-btn item-publish"
                  theme="primary"
                  variant="text"
                  size="small"
                  shape="square"
                  @click.stop="openPublishModal(conv)"
                >
                  <template #icon><ShareIcon /></template>
                </t-button>
              </t-tooltip>
              <t-popconfirm
                content="确定删除？"
                @confirm="handleDelete(conv)"
              >
                <t-button
                  class="item-icon-btn item-delete"
                  theme="danger"
                  variant="text"
                  size="small"
                  shape="square"
                  @click.stop
                >
                  <template #icon><DeleteIcon /></template>
                </t-button>
              </t-popconfirm>
              <t-button
                class="item-meta-btn item-settings"
                theme="default"
                variant="outline"
                size="small"
                @click.stop="openSkillPanel(conv)"
              >
                <template #icon><SettingIcon /></template>
                Skill配置
              </t-button>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>

  <!-- Skill configuration panel -->
  <ConversationSkillPanel
    :visible="skillPanelVisible"
    :conversation-id="skillPanelConvId"
    :enabled-skill-ids="skillPanelConvEnabledIds"
    @close="skillPanelVisible = false"
    @saved="onSkillPanelSaved"
  />

  <!-- Publish API modal -->
  <PublishApiModal
    :visible="publishModalVisible"
    :conversation-id="publishTargetConvId"
    @close="publishModalVisible = false"
    @published="onPublished"
  />
</template>

<style scoped>
.sidebar {
  width: 260px;
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  transition: width 0.25s ease;
  overflow: hidden;
  position: relative;
}

.sidebar.collapsed {
  width: 48px;
  min-width: 48px;
}

.sidebar-toggle {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  cursor: pointer;
  color: var(--td-text-color-secondary);
  z-index: 1;
  transition: background 0.15s;
}
.sidebar-toggle:hover {
  background: var(--td-bg-color-component-hover);
}

.sidebar-header {
  padding: 48px 12px 8px;
}

.sidebar-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.sidebar-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 32px 0;
  color: var(--td-text-color-placeholder);
  font-size: 14px;
}
.empty-icon {
  font-size: 32px;
  opacity: 0.4;
}

.sidebar-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  border-radius: 0;
  cursor: pointer;
  transition: background 0.15s;
  position: relative;
}
.sidebar-item::after {
  content: '';
  position: absolute;
  bottom: 0;
  left: 10%;
  width: 80%;
  height: 1px;
  background: var(--td-component-stroke);
}
.sidebar-item:last-child::after {
  display: none;
}
.sidebar-item:hover {
  background: var(--td-bg-color-component-hover);
}
.sidebar-item.active {
  background: var(--td-brand-color-light);
}
.sidebar-item.active .item-name {
  color: var(--td-brand-color);
  font-weight: 500;
}

/* Title row — full width */
.item-title-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.item-name {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  color: var(--td-text-color-primary);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-name-input {
  flex: 1;
  font-size: 14px;
  border: 1px solid var(--td-brand-color);
  border-radius: 4px;
  padding: 2px 6px;
  outline: none;
  background: var(--td-bg-color-container);
  color: var(--td-text-color-primary);
}

.item-action {
  flex-shrink: 0;
}

/* Meta row — time + actions below title */
.item-meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 3px;
}

.item-time {
  font-size: 12px;
  color: var(--td-text-color-placeholder);
}

.item-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.item-icon-btn {
  flex-shrink: 0;
  color: var(--td-text-color-secondary) !important;
}

.item-icon-btn.item-publish {
  color: var(--td-brand-color) !important;
}

.item-icon-btn.item-delete:hover {
  color: var(--td-error-color) !important;
}

.item-meta-btn.item-settings {
  flex-shrink: 0;
  padding: 0 8px !important;
  height: 24px !important;
  font-size: 11px !important;
  border-radius: 4px !important;
  color: var(--td-text-color-secondary) !important;
  border-color: var(--td-component-stroke) !important;
  background: var(--td-bg-color-container) !important;
}

.api-badge {
  flex-shrink: 0;
  font-size: 10px !important;
  padding: 0 4px !important;
  height: 18px !important;
  line-height: 18px !important;
}
</style>
