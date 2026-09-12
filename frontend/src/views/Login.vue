<script setup>
import { ref } from 'vue'
import { api } from '../api'

const emit = defineEmits(['done'])
const username = ref('admin')
const password = ref('')
const loading = ref(false)
const err = ref('')

async function login() {
  err.value = ''
  loading.value = true
  try {
    const r = await api.login(username.value, password.value)
    localStorage.setItem('erp_token', r.token)
    emit('done', r)
  } catch (e) {
    err.value = e.response?.data?.error || e.response?.data?.message || '登录失败'
  }
  loading.value = false
}
</script>

<template>
  <div class="login-bg">
    <el-card class="login-card">
      <div style="text-align:center;margin:6px 0 20px">
        <div style="width:56px;height:56px;border-radius:14px;margin:0 auto 12px;background:linear-gradient(135deg,#2563eb,#4f8ef7);display:flex;align-items:center;justify-content:center;color:#fff;font-size:26px;font-weight:bold;box-shadow:0 8px 20px rgba(37,99,235,.35)">卫</div>
        <h2 style="margin:0;font-size:22px">账管卫士</h2>
        <p style="color:#667085;font-size:13px;margin:8px 0 0">AI 原生商家经营系统 · 进销存 / 财务 / 智能助手</p>
      </div>
      <el-form @submit.prevent="login">
        <el-form-item><el-input v-model="username" placeholder="用户名" size="large" /></el-form-item>
        <el-form-item><el-input v-model="password" type="password" placeholder="密码" size="large" show-password @keyup.enter="login" /></el-form-item>
        <el-alert v-if="err" :title="err" type="error" :closable="false" style="margin-bottom:12px" />
        <el-button type="primary" size="large" style="width:100%" :loading="loading" @click="login">登 录</el-button>
      </el-form>
      <p style="color:#98a2b3;font-size:11px;margin-top:14px;text-align:center">
        首次使用：内置管理员 admin / admin123（登录后请及时修改密码）
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.login-bg {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(800px 400px at 20% 20%, rgba(37, 99, 235, .25), transparent),
    radial-gradient(600px 400px at 80% 80%, rgba(79, 142, 247, .18), transparent),
    linear-gradient(135deg, #101828 0%, #1d2939 60%, #33415c 100%);
}
.login-card {
  width: 400px;
  padding: 16px 24px;
  border-radius: 16px;
  border: none;
  box-shadow: 0 20px 50px rgba(0, 0, 0, .4);
}
.login-card .el-button { width: 100%; margin-top: 4px; height: 40px; font-size: 15px; }
</style>
