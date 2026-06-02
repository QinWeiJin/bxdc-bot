<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { provideChat } from '../composables/useChat'
import Layout from '../components/Layout.vue'
import MessageList from '../components/MessageList.vue'
import MessageInput from '../components/MessageInput.vue'

const { error, fetchGreeting } = provideChat()
const route = useRoute()

onMounted(() => {
  fetchGreeting()
  // 如果是从任务通知跳过来的（?taskId=xxx），把任务 ID 暂存到 sessionStorage，
  // MessageList 会读取并展示"已从通知进入"的提示横幅。
  const taskId = route.query.taskId
  if (typeof taskId === 'string' && taskId) {
    try {
      sessionStorage.setItem('pendingTaskId', taskId)
    } catch {
      // 忽略：sessionStorage 可能不可用
    }
  }
})
</script>

<template>
  <Layout>
    <div class="chat-wrapper">
      <t-card class="chat-shell" :bordered="false">
        <div class="chat-container">
          <div class="chat-main">
            <MessageList />
          </div>
          <t-alert
            v-if="error"
            class="chat-error"
            theme="error"
            :message="error"
          />
        </div>
      </t-card>

      <div class="input-box">
        <MessageInput />
      </div>
    </div>
  </Layout>
</template>

<style scoped>
.chat-wrapper {
  display: flex;
  flex-direction: column;
  gap: 16px;
  flex: 1;
  min-height: 0;
  padding: 12px;
}

@media (min-width: 768px) {
  .chat-wrapper {
    gap: 20px;
    padding: 16px;
  }
}

.chat-shell {
  flex: 1;
  padding: 0;
  border-radius: 16px;
  background: var(--td-bg-color-container);
  min-height: 0;
}

.chat-shell :deep(.t-card__body) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.input-box {
  flex-shrink: 0;
  padding: 12px 16px 16px;
  border-radius: 16px;
  background: var(--td-bg-color-container);
}

@media (min-width: 768px) {
  .input-box {
    padding: 16px 20px 20px;
  }
}

.chat-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  min-height: 0;
  overflow: hidden;
  gap: 12px;
}

.chat-main {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.chat-error {
  flex-shrink: 0;
  margin: 0 12px;
}

@media (min-width: 768px) {
  .chat-error {
    margin: 0 24px;
  }
}
</style>
