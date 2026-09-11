import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import './styles.css'

const routes = [
  { path: '/', component: () => import('./views/PublicPlaceholder.vue') },
  { path: '/admin', component: () => import('./views/AdminLogin.vue') },
  { path: '/admin/:section', component: () => import('./views/AdminShell.vue'), meta: { requiresAuth: true }, props: true },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && localStorage.getItem('kb_admin_session') !== 'active') {
    return '/admin'
  }
  if (to.path === '/admin' && localStorage.getItem('kb_admin_session') === 'active') {
    return '/admin/dashboard'
  }
})

createApp(App).use(router).use(ElementPlus).mount('#app')
