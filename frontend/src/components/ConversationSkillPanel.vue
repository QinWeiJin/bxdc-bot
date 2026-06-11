<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useUser } from '../composables/useUser'
import { apiUrl } from '../services/config'
import { updateConversation } from '../services/api'
import type { Skill } from '../composables/useSkillHub'
import UserAvatar from './UserAvatar.vue'
import { extendedSkillEmoji } from '../composables/useSkillHub'

const props = defineProps<{
  visible: boolean
  conversationId: string
  enabledSkillIds: number[]
}>()

const emit = defineEmits<{
  close: []
  saved: [enabledSkillIds: number[]]
}>()

const { currentUser } = useUser()

interface ExtendedSkill extends Skill {
  _checked: boolean
}

const skills = ref<ExtendedSkill[]>([])
const isLoading = ref(false)
const error = ref<string | null>(null)
const isSaving = ref(false)
const searchQuery = ref('')

const filteredSkills = computed(() => {
  if (!searchQuery.value.trim()) return skills.value
  const q = searchQuery.value.trim().toLowerCase()
  return skills.value.filter((s) => s.name.toLowerCase().includes(q))
})

async function fetchSkills() {
  isLoading.value = true
  error.value = null
  try {
    const res = await fetch(apiUrl('/api/skills'), {
      cache: 'no-store',
      headers: currentUser.value?.id ? { 'X-User-Id': String(currentUser.value.id) } : {},
    })
    if (!res.ok) throw new Error('Failed to fetch skills')
    const allSkills = await res.json() as Skill[]
    const extensionSkills = allSkills.filter(
      (s) => s.enabled && (s.type || '').toUpperCase() === 'EXTENSION'
    )
    skills.value = extensionSkills.map((s) => ({
      ...s,
      _checked: props.enabledSkillIds.includes(s.id),
    }))
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Unknown error'
  } finally {
    isLoading.value = false
  }
}

const allChecked = computed(() =>
  skills.value.length > 0 && skills.value.every((s) => s._checked)
)

function toggleAll() {
  const newVal = !allChecked.value
  skills.value.forEach((s) => (s._checked = newVal))
}



async function save() {
  if (!currentUser.value) return
  isSaving.value = true
  const selectedIds = skills.value.filter((s) => s._checked).map((s) => s.id)
  try {
    await updateConversation(
      currentUser.value.id,
      props.conversationId,
      { enabled_skills: selectedIds },
    )
    emit('saved', selectedIds)
    emit('close')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    isSaving.value = false
  }
}

function cancel() {
  emit('close')
}

watch(
  () => props.visible,
  (v) => {
    if (v) fetchSkills()
  },
  { immediate: true },
)
</script>

<template>
  <t-dialog
    :visible="visible"
    header="对话 Skill 配置"
    width="520px"
    :footer="true"
    :confirm-btn="{ content: '保存', loading: isSaving, theme: 'primary' }"
    :cancel-btn="{ content: '取消' }"
    @confirm="save"
    @cancel="cancel"
    @close="cancel"
  >
    <div class="skill-panel">
      <div v-if="isLoading" class="panel-state">
        <t-loading text="加载 Skill 列表..." />
      </div>
      <div v-else-if="error" class="panel-state">
        <t-alert theme="error" :message="error" />
      </div>
      <div v-else-if="skills.length === 0" class="panel-state">
        <p>暂无可用 Extension Skill</p>
      </div>
      <template v-else>
        <div class="panel-toolbar">
          <t-input
            v-model="searchQuery"
            placeholder="搜索 Skill..."
            clearable
            size="small"
            class="panel-search"
          />
          <t-space>
            <t-button
              size="small"
              variant="outline"
              @click="toggleAll"
            >
              {{ allChecked ? '取消全选' : '全选' }}
            </t-button>
          </t-space>
        </div>
        <t-list :split="true">
          <t-list-item v-for="skill in filteredSkills" :key="skill.id">
            <template #action>
              <t-checkbox v-model="skill._checked" />
            </template>
            <t-list-item-meta :title="skill.name" :description="skill.description || ''">
              <template #image>
                <UserAvatar
                  :avatar="extendedSkillEmoji(skill)"
                  :size="32"
                  rounded
                  variant="skillExtended"
                />
              </template>
            </t-list-item-meta>
          </t-list-item>
        </t-list>
      </template>
    </div>
  </t-dialog>
</template>

<style scoped>
.skill-panel {
  min-height: 200px;
  max-height: 400px;
  overflow-y: auto;
}

.skill-panel :deep(.t-list-item__meta) {
  align-items: center;
}

.skill-panel :deep(.t-list-item__meta-avatar) {
  width: 32px !important;
  height: 32px !important;
  min-width: 32px;
  min-height: 32px;
  padding: 0 !important;
  margin: 0 12px 0 0 !important;
  border-radius: 6px !important;
  overflow: visible !important;
  background: transparent !important;
  border: none !important;
}

.panel-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.panel-search {
  flex: 1;
  min-width: 0;
}

.panel-state {
  padding: 32px 0;
  text-align: center;
}
</style>
