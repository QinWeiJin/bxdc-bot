<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { MessagePlugin } from 'tdesign-vue-next'
import { AddIcon, DeleteIcon, EditIcon } from 'tdesign-icons-vue-next'
import { useSkillHub, BUILT_IN_SKILLS, extendedSkillEmoji, getExecutionModeLabel, getConfigSummary, canManageGatewaySkill, type Skill } from '../composables/useSkillHub'
import { useUser } from '../composables/useUser'
import SkillManagementModal from './SkillManagementModal.vue'
import UserAvatar from './UserAvatar.vue'

const {
  isSkillHubVisible,
  skills,
  isLoading,
  error,
  nicknameByUserId,
  closeSkillHub,
  refreshSkills,
  toggleSkillEnabled,
  deleteSkill,
} = useSkillHub()

const { currentUser } = useUser()

const skillMgmtRef = ref<InstanceType<typeof SkillManagementModal> | null>(null)

function canManageRow(skill: Skill): boolean {
  return canManageGatewaySkill(skill, currentUser.value?.id)
}

// Tab
const activeTab = ref('extended')

// Search & filter (Extended only)
const searchQuery = ref('')
const statusFilter = ref<'all' | 'active' | 'inactive'>('all')
const visibilityFilter = ref<'all' | 'private' | 'public'>('all')

const filteredExtendedSkills = computed<Skill[]>(() => {
  let result = skills.value.filter((s) => (s.type || '').toUpperCase() === 'EXTENSION')

  // 搜索：名称、介绍、作者（ID + 昵称）
  if (searchQuery.value.trim()) {
    const q = searchQuery.value.trim().toLowerCase()
    result = result.filter((s) =>
      (s.name || '').toLowerCase().includes(q) ||
      (s.description || '').toLowerCase().includes(q) ||
      (s.createdBy || '').toLowerCase().includes(q) ||
      (nicknameByUserId.value[s.createdBy || ''] || '').toLowerCase().includes(q)
    )
  }

  // 激活状态筛选
  if (statusFilter.value === 'active') result = result.filter((s) => s.enabled)
  else if (statusFilter.value === 'inactive') result = result.filter((s) => !s.enabled)

  // 可见性筛选
  if (visibilityFilter.value === 'private') result = result.filter((s) => s.visibility === 'PRIVATE')
  else if (visibilityFilter.value === 'public') result = result.filter((s) => s.visibility === 'PUBLIC')

  return result
})

const statusFilterOptions = [
  { label: '全量', value: 'all' },
  { label: '已激活', value: 'active' },
  { label: '未激活', value: 'inactive' },
]

const visibilityFilterOptions = [
  { label: '全量', value: 'all' },
  { label: '私人', value: 'private' },
  { label: '公共', value: 'public' },
]

// Toggle skill enabled with optimistic update and error rollback
const toggleStates = ref<Record<number, boolean>>({})

async function handleToggle(skill: Skill, checked: boolean) {
  toggleStates.value[skill.id] = true
  const prevEnabled = skill.enabled
  // Optimistic update
  skill.enabled = checked
  try {
    await toggleSkillEnabled(skill, checked)
  } catch (e) {
    // Rollback
    skill.enabled = prevEnabled
    MessagePlugin.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    toggleStates.value[skill.id] = false
  }
}

async function handleDeleteSkill(skill: Skill) {
  try {
    await deleteSkill(skill.id)
    MessagePlugin.success('Skill 删除成功')
  } catch (e) {
    MessagePlugin.error(e instanceof Error ? e.message : '删除失败')
  }
}

function openCreateForm() {
  skillMgmtRef.value?.openCreateForm()
}

function openEditForm(skill: Skill) {
  skillMgmtRef.value?.openEditForm(skill)
}

// Reset filters when drawer closes
watch(isSkillHubVisible, (v) => {
  if (v) {
    activeTab.value = 'extended'
    searchQuery.value = ''
    statusFilter.value = 'all'
    visibilityFilter.value = 'all'
  }
})
</script>

<template>
  <t-drawer
    v-model:visible="isSkillHubVisible"
    header="Skill Hub"
    size="680px"
    :footer="false"
    @close="closeSkillHub"
  >
    <div class="skill-hub-content">
      <t-tabs v-model="activeTab">
        <template #action>
          <div class="tab-actions">
            <t-button size="small" theme="default" variant="outline" @click="refreshSkills">
              刷新
            </t-button>
            <t-button size="small" theme="primary" @click="openCreateForm">
              <template #icon><AddIcon /></template>
              新增 Skill
            </t-button>
          </div>
        </template>
        <t-tab-panel value="extended" label="Extended Skills">

          <!-- Search & Filters -->
          <div class="filters-row">
            <t-input
              v-model="searchQuery"
              placeholder="搜索名称、介绍、作者..."
              clearable
              size="small"
              class="search-input"
            >
              <template #prefix-icon>
                <span class="search-icon-simple">🔍</span>
              </template>
            </t-input>
            <t-select
              v-model="statusFilter"
              :options="statusFilterOptions"
              size="small"
              class="filter-select"
            />
            <t-select
              v-model="visibilityFilter"
              :options="visibilityFilterOptions"
              size="small"
              class="filter-select"
            />
          </div>

          <div v-if="isLoading" class="loading-state">
            <t-loading text="Loading skills..." />
          </div>
          <div v-else-if="error" class="error-state">
            <t-alert theme="error" :message="error" />
          </div>
          <div v-else-if="filteredExtendedSkills.length === 0" class="empty-state">
            <p>{{ searchQuery || statusFilter !== 'all' || visibilityFilter !== 'all' ? '没有匹配的 Skill' : 'No extended skills found.' }}</p>
          </div>
          <t-list v-else :split="true">
            <div v-for="skill in filteredExtendedSkills" :key="`${skill.id}-${skill.avatar ?? ''}`" class="skill-card">
              <!-- Row 1: Avatar + Title (left) | Edit/Delete/Enable (right) -->
              <div class="skill-row1">
                <div class="skill-title-group">
                  <UserAvatar
                    :avatar="extendedSkillEmoji(skill)"
                    :size="32"
                    rounded
                    variant="skillExtended"
                  />
                  <span class="skill-name" :title="skill.name">{{ skill.name }}</span>
                </div>
                <div class="skill-row1-actions">
                  <t-button
                    v-if="canManageRow(skill)"
                    variant="text"
                    shape="square"
                    size="small"
                    @click.stop="openEditForm(skill)"
                  >
                    <EditIcon />
                  </t-button>
                  <t-popconfirm
                    v-if="canManageRow(skill)"
                    content="确认删除该 Skill 吗？"
                    @confirm="handleDeleteSkill(skill)"
                  >
                    <t-button
                      variant="text"
                      theme="danger"
                      shape="square"
                      size="small"
                      @click.stop
                    >
                      <DeleteIcon />
                    </t-button>
                  </t-popconfirm>
                  <t-switch
                    :value="skill.enabled"
                    :loading="toggleStates[skill.id]"
                    size="small"
                    @change="(checked: boolean) => handleToggle(skill, checked)"
                  />
                </div>
              </div>

              <!-- Row 2: Type tags + visibility (left) -->
              <div class="skill-row2">
                <t-tag size="small" theme="success" variant="light">Extended</t-tag>
                <t-tag size="small" :theme="skill.executionMode === 'OPENCLAW' ? 'warning' : 'primary'" variant="light">
                  {{ getExecutionModeLabel(skill.executionMode) }}
                </t-tag>
                <t-tag
                  v-if="skill.executionMode === 'CONFIG' && getConfigSummary(skill.configuration).kindLabel"
                  size="small"
                  theme="default"
                  variant="light"
                >
                  {{ getConfigSummary(skill.configuration).kindLabel }}
                </t-tag>
                <t-tag size="small" theme="default" variant="light">
                  {{ skill.visibility === 'PUBLIC' ? '公共' : '私人' }}
                </t-tag>
              </div>

              <!-- Row 3: Description (full width, max 3 lines) -->
              <div class="skill-row3" v-if="skill.description">{{ skill.description }}</div>
            </div>
          </t-list>
        </t-tab-panel>

        <t-tab-panel value="builtin" label="Built-in Skills">
          <t-list :split="true">
            <t-list-item v-for="skill in BUILT_IN_SKILLS" :key="skill.id">
              <template #action>
                <t-tag theme="primary" variant="light">Built-in</t-tag>
              </template>
              <t-list-item-meta :title="skill.name" :description="skill.description">
                <template #image>
                  <UserAvatar
                    :avatar="skill.emoji"
                    :size="32"
                    rounded
                    variant="skillBuiltin"
                  />
                </template>
              </t-list-item-meta>
            </t-list-item>
          </t-list>
        </t-tab-panel>
      </t-tabs>
    </div>
  </t-drawer>
  <SkillManagementModal ref="skillMgmtRef" @saved="refreshSkills" />
</template>

<style scoped>
.skill-hub-content {
  display: flex;
  flex-direction: column;
}

.skill-hub-content :deep(.t-tabs__operations) {
  display: flex;
  align-items: center;
}

.skill-hub-content :deep(.t-tabs__content) {
  padding-top: 16px;
}

.tab-actions {
  display: flex;
  gap: 8px;
}

.filters-row {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
  align-items: center;
}

.search-input {
  flex: 1;
  min-width: 0;
}

.filter-select {
  width: 100px;
  flex-shrink: 0;
}

.search-icon-simple {
  font-size: 14px;
}

/* ── Skill Card ── */
.skill-card {
  padding: 12px 16px;
}

.skill-card:not(:last-child) {
  border-bottom: 1px solid var(--td-component-stroke);
}

/* Row 1: Avatar + Title | Edit/Delete/Enable */
.skill-row1 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.skill-title-group {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1 1 auto;
}

.skill-name {
  font-size: 15px;
  font-weight: 500;
  color: var(--td-text-color-primary);
  max-width: 16em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-row1-actions {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}

/* Row 2: Tags */
.skill-row2 {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  align-items: center;
  margin-top: 8px;
  padding-left: 42px; /* align with title (32px avatar + 10px gap) */
}

/* Row 3: Description */
.skill-row3 {
  margin-top: 6px;
  padding-left: 42px;
  font-size: 13px;
  color: var(--td-text-color-secondary);
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  word-break: break-all;
}

.loading-state,
.empty-state {
  padding: 24px;
  text-align: center;
  color: var(--td-text-color-secondary);
}
</style>
