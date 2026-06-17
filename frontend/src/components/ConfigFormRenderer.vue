<script setup lang="ts">
import { computed } from 'vue';

export interface ConfigSchemaProperty {
  type: string;
  label: string;
  required?: boolean;
  ui: string;
  enum?: string[];
  enumLabels?: string[];
  default?: unknown;
  minimum?: number;
  maximum?: number;
  placeholder?: string;
  readonly?: boolean;
  aiHint?: string;
  aiOptimize?: { fieldId: string };
  visibleWhen?: { field: string; equals: unknown };
}

export interface ConfigSchema {
  type: string;
  properties: Record<string, ConfigSchemaProperty>;
}

const props = defineProps<{
  configSchema: ConfigSchema;
  modelValue: Record<string, unknown>;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, unknown>];
  optimize: [fieldId: string, fieldLabel: string, currentValue: string];
}>();

const properties = computed(() => {
  return Object.entries(props.configSchema.properties ?? {});
});

function isFieldVisible(prop: ConfigSchemaProperty): boolean {
  const rule = prop.visibleWhen;
  if (!rule) return true;
  const currentValue = props.modelValue[rule.field];
  return currentValue === rule.equals || (typeof rule.equals === 'boolean' && !!currentValue === rule.equals);
}

const visibleProperties = computed(() => {
  return properties.value.filter(([_, prop]) => isFieldVisible(prop));
});

function getFieldValue(key: string): unknown {
  return props.modelValue[key] ?? props.configSchema.properties[key]?.default ?? '';
}

function setFieldValue(key: string, value: unknown) {
  emit('update:modelValue', { ...props.modelValue, [key]: value });
}

function formatJsonValue(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') {
    try {
      const parsed = JSON.parse(value);
      if (parsed && typeof parsed === 'object') {
        return JSON.stringify(parsed, null, 2);
      }
    } catch {
      return value;
    }
    return value;
  }
  return JSON.stringify(value, null, 2);
}

function parseJsonValue(text: string): unknown {
  const trimmed = text.trim();
  if (!trimmed) return undefined;
  try {
    return JSON.parse(trimmed);
  } catch {
    return trimmed;
  }
}

function handleJsonChange(key: string, text: string) {
  const parsed = parseJsonValue(text);
  emit('update:modelValue', { ...props.modelValue, [key]: parsed });
}

function handleOptimize(key: string) {
  const prop = props.configSchema.properties[key];
  const fieldId = prop?.aiOptimize?.fieldId ?? key;
  const fieldLabel = prop?.label ?? key;
  const value = props.modelValue[key];
  const currentText = typeof value === 'string'
    ? value
    : formatJsonValue(value ?? '');
  emit('optimize', fieldId, fieldLabel, currentText);
}
</script>

<template>
  <t-form-item
    v-for="[key, prop] in visibleProperties"
    :key="key"
    :name="key"
    :rules="prop.required ? [{ validator: (val: unknown) => { const v = val; return v !== undefined && v !== null && String(v).trim() !== ''; }, message: `${prop.label}不能为空` }] : undefined"
  >
    <template #label>
      <div class="form-label-wrap">
        <span class="form-label-text">
          <span v-if="prop.required" class="form-label-required">*</span>
          {{ prop.label }}
        </span>
        <span v-if="prop.aiHint" class="form-label-hint">{{ prop.aiHint }}</span>
      </div>
    </template>

    <t-checkbox
      v-if="prop.ui === 'checkbox'"
      :checked="!!getFieldValue(key)"
      @change="(checked: boolean) => setFieldValue(key, checked)"
    >
      {{ prop.label }}
    </t-checkbox>

    <t-radio-group
      v-else-if="prop.ui === 'radio'"
      :model-value="String(getFieldValue(key) ?? prop.default ?? '')"
      @change="(val: string) => setFieldValue(key, val)"
    >
      <t-radio
        v-for="(item, idx) in prop.enum ?? []"
        :key="item"
        :value="item"
      >
        {{ prop.enumLabels?.[idx] ?? item }}
      </t-radio>
    </t-radio-group>

    <t-input
      v-else-if="prop.ui === 'input'"
      :model-value="String(getFieldValue(key) ?? '')"
      :placeholder="prop.placeholder"
      :readonly="prop.readonly"
      @change="(val: string) => setFieldValue(key, val)"
    />

    <t-select
      v-else-if="prop.ui === 'select'"
      :model-value="String(getFieldValue(key) ?? '')"
      :options="(prop.enum ?? []).map(v => ({ value: v, label: v }))"
      @change="(val: string) => setFieldValue(key, val)"
    />

    <t-input-number
      v-else-if="prop.ui === 'number'"
      :model-value="Number(getFieldValue(key) ?? prop.default ?? 0)"
      :min="prop.minimum"
      :max="prop.maximum"
      @change="(val: number) => setFieldValue(key, val)"
    />

    <div v-else-if="prop.ui === 'textarea' || prop.ui === 'jsonEditor' || prop.ui === 'keyValue'" class="optimize-textarea-wrap">
      <t-textarea
        :model-value="prop.ui === 'jsonEditor' || prop.ui === 'keyValue' ? formatJsonValue(getFieldValue(key)) : String(getFieldValue(key) ?? '')"
        :placeholder="prop.placeholder"
        :autosize="{ minRows: 3, maxRows: 8 }"
        :maxlength="prop.ui === 'textarea' ? 1000000 : undefined"
        @change="(val: string) => prop.ui === 'jsonEditor' || prop.ui === 'keyValue' ? handleJsonChange(key, val) : setFieldValue(key, val)"
      />
      <t-button
        v-if="prop.aiOptimize"
        size="small"
        variant="text"
        class="optimize-btn"
        @click="handleOptimize(key)"
      >
        ✨ AI 优化
      </t-button>
    </div>

    <t-input
      v-else
      :model-value="String(getFieldValue(key) ?? '')"
      :placeholder="prop.placeholder"
      @change="(val: string) => setFieldValue(key, val)"
    />
  </t-form-item>
</template>

<style scoped>
.optimize-textarea-wrap {
  position: relative;
  width: 100%;
}

.optimize-btn {
  position: absolute;
  bottom: 4px;
  right: 4px;
  z-index: 1;
}

.form-label-wrap {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.form-label-text {
  font-size: 14px;
  line-height: 22px;
  color: var(--td-text-color-primary);
}

.form-label-required {
  color: var(--td-error-color, #e34d59);
  margin-right: 2px;
}

.form-label-hint {
  font-size: 12px;
  line-height: 1.5;
  color: var(--td-text-color-placeholder);
  font-weight: 400;
}
</style>
