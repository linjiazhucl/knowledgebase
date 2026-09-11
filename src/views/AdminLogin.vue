<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowUpRight, Eye, EyeOff, LockKeyhole, UserRound } from '../icons.js'
import { ElMessage } from 'element-plus'
import { api } from '../api.js'

const router = useRouter()
const account = ref('admin')
const password = ref('admin123')
const showPassword = ref(false)
const loading = ref(false)

const login = async () => {
  if (!account.value.trim() || !password.value.trim()) {
    ElMessage.error('请输入管理员账号和密码')
    return
  }
  loading.value = true
  try {
    const user = await api.login(account.value, password.value)
    if (user?.token) {
      localStorage.setItem('kb_admin_token', user.token)
      localStorage.setItem('kb_admin_session', 'active')
      localStorage.setItem('kb_admin_user', JSON.stringify(user))
      ElMessage.success('欢迎回来，管理员')
      router.push('/admin/dashboard')
    } else {
      ElMessage.error('账号或密码错误，请重试')
    }
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '后端服务暂时不可用')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <div class="login-orbit orbit-one"></div>
    <div class="login-orbit orbit-two"></div>
    <div class="login-aside">
      <div class="brand-lockup"><span class="brand-symbol">✳</span><span>澄明知识库</span></div>
      <div class="login-manifesto">
        <div class="eyebrow pale">KNOWLEDGE, WITH EVIDENCE</div>
        <h1>从散落的文档，<br /><strong>到可靠的答案。</strong></h1>
        <p>企业知识的统一入口。解析、检索、追溯，每一步都清晰可见。</p>
      </div>
      <div class="login-aside-foot"><span>RAG OPERATIONS CONSOLE</span><span>V 1.1 · 2026</span></div>
    </div>
    <section class="login-card-wrap">
      <div class="login-card">
        <div class="login-card-head">
          <div>
            <div class="eyebrow">ADMIN ACCESS</div>
            <h2>进入管理端</h2>
          </div>
          <ArrowUpRight :size="22" stroke-width="1.5" />
        </div>
        <p class="login-intro">管理知识资产与问答质量，保持每个结论可被验证。</p>
        <form @submit.prevent="login">
          <label class="field-label">管理员账号</label>
          <div class="input-shell">
            <UserRound :size="18" />
            <input v-model="account" autocomplete="username" placeholder="输入账号" />
          </div>
          <label class="field-label">登录密码</label>
          <div class="input-shell">
            <LockKeyhole :size="18" />
            <input v-model="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" placeholder="输入密码" />
            <button type="button" class="input-action" @click="showPassword = !showPassword">
              <EyeOff v-if="showPassword" :size="17" /><Eye v-else :size="17" />
            </button>
          </div>
          <button class="primary-button login-button" :disabled="loading">
            <span>{{ loading ? '验证中…' : '登录管理端' }}</span><ArrowUpRight :size="18" />
          </button>
        </form>
        <div class="login-hint"><span class="status-dot"></span> 演示账号：admin / admin123</div>
      </div>
      <RouterLink to="/" class="back-home">← 返回用户端</RouterLink>
    </section>
  </main>
</template>
