<script setup lang="ts">
import { ref } from 'vue';
import { useUser } from '../composables/useUser';
import Button from '../components/ui/Button.vue';
import Input from '../components/ui/Input.vue';
import Card from '../components/ui/Card.vue';

const userId = ref('');
const error = ref('');
const loading = ref(false);
const { login } = useUser();

async function handleLogin() {
  error.value = '';
  loading.value = true;
  try {
    await login(userId.value);
  } catch (e: any) {
    error.value = e.message || '登录失败';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center bg-slate-50 p-4">
    <Card class="w-full max-w-md shadow-lg border-0" title="欢迎回来" description="输入您的 ID 以访问账户">
      <form @submit.prevent="handleLogin" class="space-y-6" data-ref="login-form">
        <Input
          v-model="userId"
          label="用户 ID"
          placeholder="请输入 6 位数字 ID"
          :error="error"
          :disabled="loading"
          data-ref="login-user-id"
        />

        <div class="pt-2">
          <Button type="submit" class="w-full" :loading="loading" data-ref="login-submit-btn">
            登录
          </Button>
        </div>

        <div class="text-center text-sm text-slate-500">
          还没有账户？
          <router-link to="/register" class="font-medium text-blue-600 hover:text-blue-500 hover:underline">
            注册
          </router-link>
        </div>
      </form>
    </Card>
  </div>
</template>
