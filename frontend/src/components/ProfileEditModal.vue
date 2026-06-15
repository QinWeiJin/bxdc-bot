<script setup lang="ts">
/**
 * 资料编辑弹窗
 *
 * 任务 6/7（frontend: ProfileEditModal 集成入口 + 初始化流程串接）产出物。
 *
 * 在原"昵称 + emoji 头像"基础上，于 emoji 网格下方内联一个
 * "记忆管理"子区块：清除 → 危险确认 → 初始化记忆 → 重新提交。
 * 受 MEM0_ENABLED 配置开关控制（spec memory-initialization-flow Decision 7）。
 * 整个流程受 X-User-Id 跨用户守卫（spec memory-initialization-flow 用户隔离）。
 */
import { ref, watch, onMounted } from 'vue';
import { MessagePlugin } from 'tdesign-vue-next';
import { useUser } from '../composables/useUser';
import { useMemory } from '../composables/useMemory';
import { AVATAR_EMOJI_CHOICES } from '../constants/avatarEmojiChoices';
import UserAvatar from './UserAvatar.vue';
import MemoryInitModal from './MemoryInitModal.vue';

const visible = defineModel<boolean>('visible', { default: false });

const { currentUser, updateProfile } = useUser();
const { deleteUserMemory, addUserMemory } = useMemory();

const nickname = ref('');
const avatar = ref('👤');
const saving = ref(false);
const error = ref('');

// —— 记忆管理子区块状态 ——
/** 「清除记忆」危险确认弹窗 */
const showDangerConfirm = ref(false);
/** 危险确认中正在调 delete 接口 */
const clearingMemory = ref(false);
/** 初始化记忆弹窗 */
const showInitModal = ref(false);
/** 初始化提交中（保存按钮 loading） */
const initLoading = ref(false);
/** 当前用户 id 缓存（避免 currentUser 解包时机问题） */
const activeUserId = ref<string | undefined>(undefined);

watch(visible, async (v) => {
  if (v && currentUser.value) {
    nickname.value = currentUser.value.nickname ?? '';
    avatar.value = currentUser.value.avatar || '👤';
    error.value = '';
    activeUserId.value = currentUser.value.id;
  } else if (!v) {
    // 关闭时重置，避免下次打开有残留
    showDangerConfirm.value = false;
    showInitModal.value = false;
    clearingMemory.value = false;
    initLoading.value = false;
  }
});

onMounted(() => {
  if (visible.value && currentUser.value) {
    activeUserId.value = currentUser.value.id;
  }
});

async function handleClearClick() {
  if (!activeUserId.value) return;
  showDangerConfirm.value = true;
}

async function handleSave() {
  const u = currentUser.value;
  if (!u) return;
  saving.value = true;
  error.value = '';
  try {
    await updateProfile(u.id, {
      nickname: nickname.value,
      avatar: avatar.value,
    });
    visible.value = false;
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : '保存失败';
  } finally {
    saving.value = false;
  }
}

// —— 记忆管理子流程 ——

function handleDangerCancel() {
  // 取消 / X / Esc / 遮罩都走这里（前提是没在 loading）
  if (clearingMemory.value) return;
  showDangerConfirm.value = false;
}

async function handleDangerConfirm() {
  const uid = activeUserId.value;
  if (!uid) return;
  clearingMemory.value = true;
  try {
    await deleteUserMemory(uid);
    // 成功：关闭危险弹窗，打开初始化表单
    showDangerConfirm.value = false;
    showInitModal.value = true;
  } catch (e: any) {
    // 失败：弹 toast，危险弹窗保持打开
    const msg = e?.message || '清空失败';
    MessagePlugin.error(`清空失败：${msg}`);
  } finally {
    clearingMemory.value = false;
  }
}

function handleInitSubmit(payload: { who: string; hobbies: string }) {
  const uid = activeUserId.value;
  if (!uid) return;
  // spec memory-initialization-flow Decision 6：frontend 拼接
  const text = payload.hobbies ? `${payload.who}。${payload.hobbies}` : payload.who;
  initLoading.value = true;
  addUserMemory(uid, text)
    .then(() => {
      // 成功 toast + 关闭 init + 关闭整个 ProfileEditModal
      MessagePlugin.success('记忆初始化更新成功');
      showInitModal.value = false;
      visible.value = false;
    })
    .catch((e: any) => {
      // 失败：清空已成功但初始化失败，弹警告 toast，init 弹窗保持打开供重试
      const msg = e?.message || '初始化失败';
      MessagePlugin.warning(`记忆已清空但初始化失败：${msg}，请重试`);
    })
    .finally(() => {
      initLoading.value = false;
    });
}

function handleInitCancel() {
  if (initLoading.value) return;
  // 用户点"取消"（关闭 init 弹窗），按 spec 7.4 行为：关闭 init + 关闭 ProfileEditModal
  // （此时 mem0 已清空，记忆处于"已清空"状态）
  showInitModal.value = false;
  visible.value = false;
}

function handleInitLater() {
  if (initLoading.value) return;
  // "稍后再说"：关闭 init + 关闭 ProfileEditModal（不恢复已清空的记忆）
  showInitModal.value = false;
  visible.value = false;
}
</script>

<template>
  <t-dialog
    v-model:visible="visible"
    header="编辑资料"
    width="420px"
    :confirm-btn="null"
    :cancel-btn="null"
    :close-on-overlay-click="!saving"
    :close-on-esc-keydown="!saving"
  >
    <div class="profile-edit">
      <p v-if="currentUser" class="readonly-id">
        用户 ID：<strong>{{ currentUser.id }}</strong>（不可修改）
      </p>
      <div class="preview-row">
        <span class="label">预览</span>
        <UserAvatar :avatar="avatar" :size="48" />
      </div>

      <t-input v-model="nickname" label="昵称" placeholder="1–10 个字符" maxlength="10" />
      <div class="emoji-section">
        <div class="label">头像</div>
        <div class="emoji-grid">
          <button
            v-for="e in AVATAR_EMOJI_CHOICES"
            :key="e"
            type="button"
            class="emoji-btn"
            :class="{ active: avatar === e }"
            @click="avatar = e"
          >
            <UserAvatar :avatar="e" :size="32" rounded />
          </button>
        </div>
      </div>

      <!-- 记忆管理子区块（spec memory-initialization-flow / user-profile ADDED Requirements） -->
      <div class="memory-section">
        <div class="memory-section-divider" />
        <t-link
          theme="danger"
          hover
          underline
          class="clear-memory-link"
          @click="handleClearClick"
        >
          清除记忆
        </t-link>
      </div>

      <p v-if="error" class="error">{{ error }}</p>
    </div>

    <template #footer>
      <t-button theme="default" :disabled="saving" @click="visible = false">取消</t-button>
      <t-button theme="primary" :loading="saving" @click="handleSave">保存</t-button>
    </template>
  </t-dialog>

  <!-- 危险确认弹窗：清除记忆二次确认（spec tasks.md 6.3） -->
  <t-dialog
    v-model:visible="showDangerConfirm"
    header="清除记忆（不可恢复）"
    theme="danger"
    width="380px"
    :confirm-btn="null"
    :cancel-btn="null"
    :close-on-enter="false"
    :close-on-esc-keydown="false"
    :close-on-overlay-click="false"
  >
    <div class="danger-confirm">
      <p>此操作将<strong>永久删除</strong>你在 mem0 中的全部长期记忆，<strong>不可恢复</strong>。</p>
      <p>确认要继续吗？</p>
    </div>
    <template #footer>
      <div class="modal-footer">
        <t-button variant="outline" :disabled="clearingMemory" @click="handleDangerCancel">取消</t-button>
        <t-button theme="danger" :loading="clearingMemory" :disabled="clearingMemory" @click="handleDangerConfirm">
          确认清除
        </t-button>
      </div>
    </template>
  </t-dialog>

  <!-- 初始化记忆表单弹窗（spec memory-initialization-flow ADDED Requirements） -->
  <MemoryInitModal
    v-if="activeUserId"
    v-model:visible="showInitModal"
    :user-id="activeUserId"
    :loading="initLoading"
    @submit="handleInitSubmit"
    @cancel="handleInitCancel"
    @later="handleInitLater"
  />
</template>

<style scoped>
.profile-edit {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.readonly-id {
  margin: 0;
  font-size: 13px;
  color: var(--td-text-color-secondary);
}

.preview-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.label {
  font-size: 13px;
  color: var(--td-text-color-secondary);
}

.emoji-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.emoji-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px;
  /* 每格 40px 高，约 3.5 行可见（露出半行提示可滚动） */
  max-height: calc(40px * 3.5 + 8px * 3);
  overflow-y: auto;
  overflow-x: hidden;
  -webkit-overflow-scrolling: touch;
}

.emoji-btn {
  width: 40px;
  height: 40px;
  border: 1px solid var(--td-component-border);
  border-radius: 8px;
  background: var(--td-bg-color-container);
  cursor: pointer;
  padding: 2px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}

.emoji-btn:hover {
  border-color: var(--td-brand-color);
}

.emoji-btn.active {
  border-color: var(--td-brand-color);
  box-shadow: 0 0 0 1px var(--td-brand-color);
}

.error {
  margin: 0;
  font-size: 13px;
  color: var(--td-error-color);
}

/* 记忆管理子区块样式（spec user-profile：低视觉权重、不占主色） */
.memory-section {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 4px;
}
.memory-section-divider {
  height: 1px;
  background: var(--td-component-stroke);
  opacity: 0.4;
  margin: 4px 0;
}
.clear-memory-link {
  font-size: 12px;
  align-self: flex-start;
  /* hover/focus 才有 danger 色，未 hover 时低调 */
}

.danger-confirm {
  font-size: 14px;
  line-height: 1.6;
  color: var(--td-text-color-primary);
}
.danger-confirm p {
  margin: 0 0 8px 0;
}
.danger-confirm p:last-child {
  margin-bottom: 0;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  width: 100%;
}
</style>
