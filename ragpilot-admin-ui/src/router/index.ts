import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import AdminLayout from '../layouts/AdminLayout.vue'
import { flattenMenuItems } from '../config/menu'

/** 已实现页面：覆盖菜单默认 Placeholder */
const READY_VIEWS: Record<string, () => Promise<unknown>> = {
  '/chunk': () => import('../views/ChunkView.vue'),
  '/knowledge': () => import('../views/KnowledgeView.vue'),
  '/vector': () => import('../views/VectorView.vue'),
  '/prompt': () => import('../views/PromptView.vue'),
  '/eval': () => import('../views/EvalView.vue'),
  '/chat/debug': () => import('../views/ChatDebugView.vue'),
  '/chat/sessions': () => import('../views/SessionsView.vue'),
  '/models': () => import('../views/ModelsView.vue'),
  '/token': () => import('../views/TokenView.vue'),
  '/retrieve': () => import('../views/RetrieveView.vue'),
  '/agent': () => import('../views/AgentView.vue'),
  '/tools': () => import('../views/ToolsView.vue'),
  '/mcp': () => import('../views/McpView.vue'),
  '/logs': () => import('../views/LogsView.vue'),
}

/**
 * 路由：总览 + 已实现页为真实组件；其余 planned 走 PlaceholderView。
 */
const children: RouteRecordRaw[] = [
  {
    path: '',
    name: 'dashboard',
    component: () => import('../views/DashboardView.vue'),
    meta: { title: '总览', status: 'ready' },
  },
  ...flattenMenuItems()
    .filter((item) => item.path !== '/')
    .map((item) => ({
      path: item.path.replace(/^\//, ''),
      name: item.path,
      component: READY_VIEWS[item.path]
        ?? (() => import('../views/PlaceholderView.vue')),
      meta: {
        title: item.title,
        adm: item.adm,
        status: READY_VIEWS[item.path] ? 'ready' : item.status,
      },
    })),
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      component: AdminLayout,
      children,
    },
  ],
})

export default router
