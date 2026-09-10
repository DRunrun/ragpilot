/**
 * 管理端侧栏信息架构（与 docs/admin-console-plan.md §3 对齐）。
 * status: ready=可点进真实页；planned=显示「规划中」占位。
 */
export type MenuStatus = 'ready' | 'planned'

export interface MenuItem {
  path: string
  title: string
  /** 对应计划编号，占位页展示用 */
  adm?: string
  status: MenuStatus
}

export interface MenuGroup {
  title: string
  children: MenuItem[]
}

export const MENU_GROUPS: MenuGroup[] = [
  {
    title: '总览',
    children: [{ path: '/', title: '总览', status: 'ready' }],
  },
  {
    title: '对话',
    children: [
      { path: '/chat/debug', title: '在线调试', adm: 'ADM-5.3', status: 'ready' },
      { path: '/chat/sessions', title: '会话管理', adm: 'ADM-5.4', status: 'ready' },
    ],
  },
  {
    title: '知识与检索',
    children: [
      { path: '/knowledge', title: '知识库管理', adm: 'ADM-3.7', status: 'ready' },
      { path: '/chunk', title: '分块策略', adm: 'ADM-2.5', status: 'ready' },
      { path: '/vector', title: '向量库管理', adm: 'ADM-3.11', status: 'ready' },
      { path: '/retrieve', title: '检索调试', adm: 'ADM-8.x', status: 'ready' },
    ],
  },
  {
    title: '生成与模型',
    children: [
      { path: '/prompt', title: 'Prompt 管理', adm: 'ADM-1.3', status: 'ready' },
      { path: '/models', title: '模型管理', adm: 'ADM-6.x', status: 'ready' },
      { path: '/token', title: 'Token 统计', adm: 'ADM-7.x', status: 'ready' },
    ],
  },
  {
    title: '评测',
    children: [
      { path: '/eval', title: '评测管理', adm: 'ADM-4.5', status: 'ready' },
    ],
  },
  {
    title: '智能体',
    children: [
      { path: '/agent', title: 'Agent 管理', adm: 'ADM-9.x', status: 'ready' },
      { path: '/tools', title: '工具管理', adm: 'ADM-9.x', status: 'ready' },
      { path: '/mcp', title: 'MCP 管理', adm: 'ADM-9.x', status: 'ready' },
    ],
  },
  {
    title: '系统',
    children: [
      { path: '/logs', title: '日志与 Trace', adm: 'ADM-10.x', status: 'ready' },
    ],
  },
]

/** 扁平化全部叶子，供路由生成 */
export function flattenMenuItems(): MenuItem[] {
  return MENU_GROUPS.flatMap((g) => g.children)
}
