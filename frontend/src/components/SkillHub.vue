<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { MessagePlugin } from 'tdesign-vue-next'
import { useSkillHub, BUILT_IN_SKILLS, extendedSkillEmoji, getExecutionModeLabel, getConfigSummary, type Skill } from '../composables/useSkillHub'
import SkillManagementModal from './SkillManagementModal.vue'
import UserAvatar from './UserAvatar.vue'

const {
  isSkillHubVisible,
  skills,
  isLoading,
  error,
  closeSkillHub,
  openSkillManagement,
  refreshSkills,
  toggleSkillEnabled,
} = useSkillHub()

// Tab
const activeTab = ref('extended')

// Search & filter (Extended only)
const searchQuery = ref('')
const statusFilter = ref<'all' | 'active' | 'inactive'>('all')
const visibilityFilter = ref<'all' | 'private' | 'public'>('all')

const filteredExtendedSkills = computed<Skill[]>(() => {
  let result = skills.value.filter((s) => (s.type || '').toUpperCase() === 'EXTENSION')

  // 搜索
  if (searchQuery.value.trim()) {
    const q = searchQuery.value.trim().toLowerCase()
    result = result.filter((s) => s.name.toLowerCase().includes(q))
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
    size="medium"
    :footer="false"
    @close="closeSkillHub"
  >
    <div class="skill-hub-content">
      <t-tabs v-model="activeTab">
        <t-tab-panel value="extended" label="Extended Skills">
          <div class="tab-header">
            <div class="tab-actions">
              <t-button size="small" theme="default" variant="outline" @click="refreshSkills">
                刷新
              </t-button>
              <t-button size="small" theme="default" variant="outline" @click="openSkillManagement">
                管理
              </t-button>
            </div>
          </div>

          <!-- Search & Filters -->
          <div class="filters-row">
            <t-input
              v-model="searchQuery"
              placeholder="搜索 Skill 名称..."
              clearable
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
            <t-list-item v-for="skill in filteredExtendedSkills" :key="`${skill.id}-${skill.avatar ?? ''}`">
              <template #action>
                <div class="skill-tags">
                  <t-tag theme="success" variant="light">Extended</t-tag>
                  <t-tag :theme="skill.executionMode === 'OPENCLAW' ? 'warning' : 'primary'" variant="light">
                    {{ getExecutionModeLabel(skill.executionMode) }}
                  </t-tag>
                  <t-tag
                    v-if="skill.executionMode === 'CONFIG' && getConfigSummary(skill.configuration).kindLabel"
                    theme="default"
                    variant="light"
                  >
                    {{ getConfigSummary(skill.configuration).kindLabel }}
                  </t-tag>
                  <t-tag
                    v-if="skill.visibility === 'PUBLIC'"
                    theme="default"
                    variant="light"
                  >
                    公共
                  </t-tag>
                </div>
                <t-switch
                  :value="skill.enabled"
                  :loading="toggleStates[skill.id]"
                  size="small"
                  class="skill-toggle"
                  @change="(checked: boolean) => handleToggle(skill, checked)"
                />
              </template>
              <t-list-item-meta :title="skill.name" :description="skill.description || 'No description provided.'">
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

        <t-tab-panel value="builtin" label="Built-in Skills">
          <div class="tab-header">
            <div />
          </div>
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
  <SkillManagementModal />
</template>

<style scoped>
.skill-hub-content :deep(.t-list-item__meta) {
  align-items: flex-start;
}

.skill-hub-content :deep(.t-list-item__meta-avatar) {
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
  box-shadow: none !important;
  flex-shrink: 0;
}

.skill-hub-content {
  display: flex;
  flex-direction: column;
}

.tab-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
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

.skill-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  align-items: center;
}

.skill-toggle {
  margin-left: 8px;
  flex-shrink: 0;
}

.loading-state,
.empty-state {
  padding: 24px;
  text-align: center;
  color: var(--td-text-color-secondary);
}
</style>
