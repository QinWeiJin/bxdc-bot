<script setup lang="ts">
/**
 * MemoryInitModal — 记忆初始化表单弹窗
 *
 * 任务 5（frontend: 新增 MemoryInitModal 组件）产出物。
 *
 * 用途：资料编辑页"清除 → 重新初始化"两步流程中，删除成功后的初始化表单弹窗。
 * 让用户填写"我是谁"和"我的兴趣/性格特征"两段文本，作为新一轮长期记忆的种子。
 *
 * 拼接规则（spec memory-initialization-flow Decision 6）：
 *   text = hobbies.trim() ? `${who}。${hobbies}` : who
 *   但本组件**不**做拼接，只通过 submit 事件把 { who, hobbies } 原值抛出，
 *   由父组件（ProfileEditModal）按 spec 拼接后再调 addUserMemory。
 *
 * Props/Emits（tasks.md 5.2 / 5.3）：
 *   - props: visible (v-model), userId, loading
 *   - emit: submit ({ who, hobbies }), cancel, later
 *
 * 校验（tasks.md 5.4 / 5.5）：
 *   - "我是谁"必填（>= 1 字符）
 *   - 错误显示在字段下方
 *   - 三个按钮：取消 / 稍后再说 / 保存初始化（primary）
 *   - loading 期间所有按钮禁用
 */
import { ref, watch, computed } from 'vue';

const visible = defineModel<boolean>('visible', { default: false });

defineProps<{
  /** 当前用户 id（仅用于展示，实际调用由父组件发） */
  userId?: string;
  /** 父组件控制：调 addUserMemory 时设为 true，禁用所有按钮 */
  loading?: boolean;
}>();

const emit = defineEmits<{
  /** 用户点"保存初始化"且通过校验时触发，payload 已通过 trim 处理 */
  (e: 'submit', payload: { who: string; hobbies: string }): void;
  /** 用户点"取消"或遮罩 / X 关闭弹窗时触发 */
  (e: 'cancel'): void;
  /** 用户点"稍后再说"时触发（与 cancel 不同：明确表达"我先不清空"或"先看看"） */
  (e: 'later'): void;
}>();

const who = ref('');
const hobbies = ref('');
const whoError = ref('');

// 每次打开时重置表单状态
watch(visible, (v) => {
  if (v) {
    who.value = '';
    hobbies.value = '';
    whoError.value = '';
  }
});

const whoTrimmed = computed(() => who.value.trim());
const hobbiesTrimmed = computed(() => hobbies.value.trim());
const canSubmit = computed(
  () => whoTrimmed.value.length > 0 && whoTrimmed.value.length <= 500 && hobbies.value.length <= 500
);

function handleSubmit() {
  if (!canSubmit.value) {
    whoError.value = whoTrimmed.value.length === 0 ? '请填写「我是谁」' : '';
    return;
  }
  whoError.value = '';
  emit('submit', { who: whoTrimmed.value, hobbies: hobbiesTrimmed.value });
}

function handleCancel() {
  emit('cancel');
}

function handleLater() {
  emit('later');
}
</script>

<template>
  <t-dialog
    v-model:visible="visible"
    header="初始化记忆"
    width="480px"
    :confirm-btn="null"
    :cancel-btn="null"
    :close-on-overlay-click="!loading"
    :close-on-esc-keydown="!loading"
    @close="handleCancel"
  >
    <div class="memory-init-form">
      <p class="hint">
        清空成功。请填写以下信息，我们会把它作为新一轮长期记忆的种子，
        后续对话中 AI 会参考这些内容给你更贴切的回答。
      </p>

      <div class="field">
        <label class="field-label">
          我是谁
          <span class="required">*</span>
        </label>
        <t-textarea
          v-model="who"
          placeholder="例如：我是 bot，资深 AI 工程师，家住北京海淀，养了一只英短"
          :maxlength="500"
          :autosize="{ minRows: 3, maxRows: 6 }"
          :disabled="loading"
          status="default"
        />
        <div v-if="whoError" class="field-error">{{ whoError }}</div>
        <div class="field-counter">{{ whoTrimmed.length }} / 500</div>
      </div>

      <div class="field">
        <label class="field-label">我的兴趣爱好 / 性格特征</label>
        <t-textarea
          v-model="hobbies"
          placeholder="例如：喜欢看科幻电影、跑步、收集黑胶唱片；性格偏内向、注重细节（可选）"
          :maxlength="500"
          :autosize="{ minRows: 2, maxRows: 5 }"
          :disabled="loading"
        />
        <div class="field-counter">{{ hobbiesTrimmed.length }} / 500</div>
      </div>
    </div>

    <template #footer>
      <div class="modal-footer">
        <t-button variant="outline" :disabled="loading" @click="handleCancel">取消</t-button>
        <t-button variant="outline" :disabled="loading" @click="handleLater">稍后再说</t-button>
        <t-button theme="primary" :loading="loading" :disabled="loading || !canSubmit" @click="handleSubmit">
          保存初始化
        </t-button>
      </div>
    </template>
  </t-dialog>
</template>

<style scoped>
.memory-init-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 0;
}
.hint {
  margin: 0;
  color: var(--td-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--td-text-color-primary);
}
.required {
  color: var(--td-error-color);
  margin-left: 2px;
}
.field-error {
  color: var(--td-error-color);
  font-size: 12px;
  margin-top: 2px;
}
.field-counter {
  text-align: right;
  font-size: 11px;
  color: var(--td-text-color-placeholder);
  margin-top: 2px;
}
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  width: 100%;
}
</style>
