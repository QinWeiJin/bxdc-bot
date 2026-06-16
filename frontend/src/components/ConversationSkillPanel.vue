<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useUser } from '../composables/useUser'
import { apiUrl } from '../services/config'
import { updateConversation } from '../services/api'
import { MessagePlugin } from 'tdesign-vue-next'
import type { Skill } from '../composables/useSkillHub'
import UserAvatar from './UserAvatar.vue'
import { extendedSkillEmoji } from '../composables/useSkillHub'

const props = defineProps<{
  visible: boolean
  conversationId: string
  enabledSkillIds: number[]
  /** open spec: conversation-file-isolation — 对话已启用的文件 ID 列表 */
  enabledFileIds?: number[]
}>()

const emit = defineEmits<{
  close: []
  saved: [enabledSkillIds: number[], enabledFileIds: number[]]
}>()

const { currentUser } = useUser()

// ============= Tab state =============
const activeTab = ref<'skill' | 'file'>('skill')

// ============= Skill Tab =============
interface ExtendedSkill extends Skill {
  _checked: boolean
}

const skills = ref<ExtendedSkill[]>([])
const isLoading = ref(false)
const error = ref<string | null>(null)
const isSaving = ref(false)
const skillSearchQuery = ref('')

const filteredSkills = computed(() => {
  if (!skillSearchQuery.value.trim()) return skills.value
  const q = skillSearchQuery.value.trim().toLowerCase()
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

const allSkillsChecked = computed(() =>
  skills.value.length > 0 && skills.value.every((s) => s._checked)
)

function toggleAllSkills() {
  const newVal = !allSkillsChecked.value
  skills.value.forEach((s) => (s._checked = newVal))
}

// ============= File Tab =============
interface UserFileRow {
  id: number
  fileName: string
  fileType?: string
  fileSize?: number
  uploadTime?: string
  _checked: boolean
  _deleting: boolean
}

const files = ref<UserFileRow[]>([])
const fileSearchQuery = ref('')
const isFilesLoading = ref(false)
const fileError = ref<string | null>(null)

const filteredFiles = computed(() => {
  if (!fileSearchQuery.value.trim()) return files.value
  const q = fileSearchQuery.value.trim().toLowerCase()
  return files.value.filter((f) => f.fileName.toLowerCase().includes(q))
})

async function fetchFiles() {
  isFilesLoading.value = true
  fileError.value = null
  try {
    // open spec: conversation-file-isolation — 调 GET /api/files 取用户全部文件
    // （不经过 system-skills/execute，不经过会话过滤；配置面板需要全量文件才能跨会话勾选）
    const headers: Record<string, string> = {}
    if (currentUser.value?.id) headers['X-User-Id'] = String(currentUser.value.id)

    // 从 API 实时获取当前会话的 enabled_files（而非依赖父组件传入的可能过时的 prop）
    let initialChecked: Set<number>
    try {
      const convRes = await fetch(apiUrl(`/api/conversations/${encodeURIComponent(props.conversationId)}`), { headers })
      if (convRes.ok) {
        const convData = await convRes.json()
        const raw: string | null = convData?.conversation?.enabled_files ?? null
        if (raw && raw !== 'null') {
          initialChecked = new Set(JSON.parse(raw) as number[])
        } else {
          initialChecked = new Set()
        }
      } else {
        initialChecked = new Set(props.enabledFileIds ?? [])
      }
    } catch {
      initialChecked = new Set(props.enabledFileIds ?? [])
    }

    const res = await fetch(apiUrl('/api/files'), { headers })
    if (!res.ok) throw new Error('Failed to fetch files')
    const data = await res.json()
    const rawFiles: Array<Record<string, unknown>> = Array.isArray(data?.files) ? data.files : []
    files.value = rawFiles.map((f) => ({
      id: Number(f.id),
      fileName: String(f.fileName ?? f.originalFileName ?? `file-${f.id}`),
      fileType: typeof f.fileType === 'string' ? f.fileType : undefined,
      fileSize: typeof f.fileSize === 'number' ? f.fileSize : undefined,
      uploadTime: typeof f.uploadTime === 'string' ? f.uploadTime : undefined,
      _checked: initialChecked.has(Number(f.id)),
      _deleting: false,
    }))
  } catch (e) {
    fileError.value = e instanceof Error ? e.message : 'Unknown error'
  } finally {
    isFilesLoading.value = false
  }
}

const allFilesChecked = computed(() =>
  files.value.length > 0 && files.value.every((f) => f._checked),
)

function toggleAllFiles() {
  const newVal = !allFilesChecked.value
  files.value.forEach((f) => (f._checked = newVal))
}

function formatSize(bytes: number | undefined): string {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function deleteFileItem(file: UserFileRow) {
  if (!currentUser.value) return
  file._deleting = true
  try {
    const res = await fetch(apiUrl(`/api/files/${file.id}`), {
      method: 'DELETE',
      headers: { 'X-User-Id': String(currentUser.value.id) },
    })
    if (!res.ok) throw new Error('删除失败')
    files.value = files.value.filter((f) => f.id !== file.id)
    MessagePlugin.success(`已删除 #${file.id} ${file.fileName}`)
  } catch (e) {
    MessagePlugin.error(e instanceof Error ? e.message : '删除失败')
  } finally {
    file._deleting = false
  }
}

// ============= Save =============
async function save() {
  if (!currentUser.value) return
  isSaving.value = true
  const selectedSkillIds = skills.value.filter((s) => s._checked).map((s) => s.id)
  const selectedFileIds = files.value.filter((f) => f._checked).map((f) => f.id)
  try {
    await updateConversation(
      currentUser.value.id,
      props.conversationId,
      { enabled_skills: selectedSkillIds, enabled_files: selectedFileIds },
    )
    emit('saved', selectedSkillIds, selectedFileIds)
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
    if (v) {
      fetchSkills()
      fetchFiles()
    }
  },
  { immediate: true },
)
</script>

<template>
  <t-dialog
    :visible="visible"
    header="对话配置"
    width="560px"
    :footer="true"
    :confirm-btn="{ content: '保存', loading: isSaving, theme: 'primary' }"
    :cancel-btn="{ content: '取消' }"
    @confirm="save"
    @cancel="cancel"
    @close="cancel"
  >
    <div class="config-panel">
      <t-tabs v-model="activeTab">
        <!-- ============ Skill Tab ============ -->
        <t-tab-panel value="skill" label="Skill">
          <div class="tab-toolbar">
            <t-input
              v-model="skillSearchQuery"
              placeholder="搜索 Skill..."
              clearable
              size="small"
              class="tab-search"
            />
            <t-button size="small" variant="outline" @click="toggleAllSkills">
              {{ allSkillsChecked ? '取消全选' : '全选' }}
            </t-button>
          </div>
          <div v-if="isLoading" class="panel-state">
            <t-loading text="加载 Skill 列表..." />
          </div>
          <div v-else-if="error" class="panel-state">
            <t-alert theme="error" :message="error" />
          </div>
          <div v-else-if="skills.length === 0" class="panel-state">
            <p>暂无可用 Extension Skill</p>
          </div>
          <t-list v-else :split="true">
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
        </t-tab-panel>

        <!-- ============ File Tab ============ -->
        <t-tab-panel value="file" label="文件">
          <div class="tab-toolbar">
            <t-input
              v-model="fileSearchQuery"
              placeholder="搜索文件名..."
              clearable
              size="small"
              class="tab-search"
            />
            <t-button size="small" variant="outline" @click="toggleAllFiles">
              {{ allFilesChecked ? '取消全选' : '全选' }}
            </t-button>
          </div>
          <div v-if="isFilesLoading" class="panel-state">
            <t-loading text="加载文件列表..." />
          </div>
          <div v-else-if="fileError" class="panel-state">
            <t-alert theme="error" :message="fileError" />
          </div>
          <div v-else-if="files.length === 0" class="panel-state">
            <p>暂无可用文件，请先上传文件</p>
          </div>
          <t-list v-else :split="true">
            <t-list-item v-for="file in filteredFiles" :key="file.id">
              <template #action>
                <t-space :size="8" align="center">
                  <t-checkbox v-model="file._checked" />
                  <t-button
                    size="small"
                    variant="text"
                    theme="danger"
                    :loading="file._deleting"
                    @click.stop="deleteFileItem(file)"
                  >
                    删除
                  </t-button>
                </t-space>
              </template>
              <t-list-item-meta
                :title="`#${file.id} · ${file.fileName}`"
                :description="`${file.fileType ?? '?'} · ${formatSize(file.fileSize)}${file.uploadTime ? ' · ' + file.uploadTime : ''}`"
              />
            </t-list-item>
          </t-list>
        </t-tab-panel>
      </t-tabs>
    </div>
  </t-dialog>
</template>

<style scoped>
.config-panel {
  min-height: 280px;
  max-height: 480px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.config-panel :deep(.t-tabs) {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.config-panel :deep(.t-tabs__content) {
  flex: 1;
  overflow-y: auto;
  padding-top: 12px;
}

.config-panel :deep(.t-list-item__meta) {
  align-items: center;
}

.config-panel :deep(.t-list-item__meta-avatar) {
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

.tab-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.tab-search {
  flex: 1;
  min-width: 0;
}

.panel-state {
  padding: 32px 0;
  text-align: center;
}
</style>
