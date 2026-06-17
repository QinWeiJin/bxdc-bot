<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { useRouter } from 'vue-router';
import { useUser } from '../composables/useUser';
import { useSkillHub } from '../composables/useSkillHub';
import { useServerLedger } from '../composables/useServerLedger';
import { useAsyncTaskNotifications } from '../composables/useAsyncTaskNotifications';
import UserAvatar from './UserAvatar.vue';
import ProfileEditModal from './ProfileEditModal.vue';
import SkillHub from './SkillHub.vue';
import ServerLedger from './ServerLedger.vue';
import TaskNotificationBell from './TaskNotificationBell.vue';
import ConversationSidebar from './ConversationSidebar.vue';
import { AppIcon, FolderOpenIcon, ServerIcon } from 'tdesign-icons-vue-next';

const router = useRouter();
const { currentUser, logout } = useUser();
const profileEditVisible = ref(false);
const { toggleSkillHub } = useSkillHub();
const { toggleServerLedger } = useServerLedger();
const { startPolling, stopPolling } = useAsyncTaskNotifications();

const sidebarCollapsed = ref(false)

onMounted(() => {
  // 启动异步任务通知 30s 轮询
  startPolling(30_000);
});

onBeforeUnmount(() => {
  stopPolling();
});
</script>

<template>
  <t-layout class="app-layout">
    <div class="layout-body">
      <div class="sidebar-area">
        <div class="sidebar-brand" :class="{ 'sidebar-brand--collapsed': sidebarCollapsed }">
          <UserAvatar avatar="🤖" :size="28" rounded variant="skillBuiltin" class="brand-avatar" />
          <div v-show="!sidebarCollapsed" class="brand-text">
            <div class="layout-title">BXDC.bot</div>
            <div class="layout-subtitle">AI Chat</div>
          </div>
        </div>
        <ConversationSidebar v-model:collapsed="sidebarCollapsed" @published="$emit('conversation-published')" />
      </div>
      <div class="main-area">
        <div class="layout-header">
          <div class="layout-spacer"></div>
          <div class="layout-actions">
            <t-button theme="default" variant="text" @click="router.push('/file-manager')">
              <template #icon><FolderOpenIcon /></template>
              文件管理
            </t-button>
            <t-button theme="default" variant="text" @click="toggleServerLedger" v-if="currentUser">
              <template #icon><ServerIcon /></template>
              Servers
            </t-button>
            <t-button theme="default" variant="text" @click="toggleSkillHub">
              <template #icon><AppIcon /></template>
              SkillHub
            </t-button>
            <t-button v-if="currentUser" theme="default" variant="text" @click="router.push('/settings')">
              大模型设置
            </t-button>
            <t-button v-if="currentUser" theme="default" variant="text" @click="profileEditVisible = true">
              编辑资料
            </t-button>

            <TaskNotificationBell v-if="currentUser" />

            <div class="user-info" v-if="currentUser">
              <UserAvatar :avatar="currentUser.avatar" :size="32" />
              <span class="user-name">{{ currentUser.nickname }}</span>
            </div>
            <t-button v-if="currentUser" theme="default" variant="text" @click="logout">Switch User</t-button>
          </div>
        </div>
        <t-content class="layout-content">
          <slot />
        </t-content>
      </div>
    </div>
    <SkillHub />
    <ServerLedger />
    <ProfileEditModal v-model:visible="profileEditVisible" />
  </t-layout>
</template>

<style scoped>
.app-layout {
  height: 100vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background-color: var(--td-bg-color-page);
}

.layout-body {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
}

/* ── Sidebar Area (branding + conversation list) ── */
.sidebar-area {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  background: var(--td-bg-color-container);
  border-right: 1px solid var(--td-component-stroke);
}

.sidebar-brand {
  flex-shrink: 0;
  box-sizing: border-box;
  height: 49px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--td-component-stroke);
}

.sidebar-brand--collapsed {
  padding: 0;
  justify-content: center;
  width: 48px;
}

.brand-avatar {
  flex-shrink: 0;
}

.brand-text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  line-height: 1.2;
  overflow: hidden;
}

.sidebar-area :deep(.sidebar) {
  border-right: none;
}

@media (min-width: 768px) {
  .sidebar-brand {
    padding: 0 20px;
  }
  .sidebar-brand--collapsed {
    padding: 0;
  }
}

/* ── Main Area (header + content) ── */
.main-area {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.layout-header {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 8px 16px;
  border-bottom: 1px solid var(--td-component-stroke);
  background: var(--td-bg-color-container);
}

@media (min-width: 768px) {
  .layout-header {
    padding: 8px 24px;
  }
}

.layout-spacer {
  flex: 1;
}

.layout-content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  padding: 12px;
}

@media (min-width: 768px) {
  .layout-content {
    padding: 16px;
  }
}

/* ── Branding ── */
.layout-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--td-text-color-primary);
}

.layout-subtitle {
  font-size: 12px;
  color: var(--td-text-color-secondary);
}

/* ── Actions ── */
.layout-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-name {
  font-size: 14px;
  font-weight: 500;
}
</style>
