<script setup lang="ts">
interface Props {
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost'
  size?: 'sm' | 'md' | 'lg'
  disabled?: boolean
  loading?: boolean
  dataRef?: string
}

withDefaults(defineProps<Props>(), {
  variant: 'primary',
  size: 'md',
  disabled: false,
  loading: false,
})

const emit = defineEmits<{
  (e: 'click', event: MouseEvent): void
}>()
</script>

<template>
  <button
    :data-ref="dataRef"
    class="inline-flex items-center justify-center rounded-lg font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 disabled:pointer-events-none disabled:opacity-50"
    :class="[
      // Variants
      variant === 'primary' && 'bg-blue-600 text-white hover:bg-blue-700 active:bg-blue-800',
      variant === 'secondary' && 'bg-slate-100 text-slate-900 hover:bg-slate-200 active:bg-slate-300',
      variant === 'outline' && 'border border-slate-200 bg-transparent hover:bg-slate-100 text-slate-900',
      variant === 'ghost' && 'hover:bg-slate-100 text-slate-900',
      // Sizes
      size === 'sm' && 'h-9 px-3 text-sm',
      size === 'md' && 'h-10 px-4 py-2',
      size === 'lg' && 'h-11 px-8 text-lg',
    ]"
    :disabled="disabled || loading"
    @click="emit('click', $event)"
  >
    <svg
      v-if="loading"
      class="animate-spin -ml-1 mr-2 h-4 w-4"
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
    >
      <circle
        class="opacity-25"
        cx="12"
        cy="12"
        r="10"
        stroke="currentColor"
        stroke-width="4"
      ></circle>
      <path
        class="opacity-75"
        fill="currentColor"
        d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
      ></path>
    </svg>
    <slot />
  </button>
</template>
