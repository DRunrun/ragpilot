<script setup lang="ts">
/**
 * 管理端主布局：顶栏 + 完整侧栏 IA（ADM-0.2）。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Monitor } from '@element-plus/icons-vue'
import { MENU_GROUPS } from '../config/menu'

const route = useRoute()
const router = useRouter()

const active = computed(() => {
  const p = route.path
  if (p === '/' || p === '') return '/'
  return p.endsWith('/') ? p.slice(0, -1) : p
})

const headerTitle = computed(() => String(route.meta.title ?? '可运维控制台'))

function onSelect(path: string) {
  if (path !== route.path) {
    void router.push(path)
  }
}
</script>

<template>
  <el-container class="admin-shell">
    <el-aside width="220px" class="admin-aside">
      <div class="brand">
        <el-icon :size="20"><Monitor /></el-icon>
        <span>RagPilot Admin</span>
      </div>
      <el-scrollbar class="menu-scroll">
        <el-menu
          :default-active="active"
          background-color="#1f2a37"
          text-color="#d1d5db"
          active-text-color="#ffffff"
          :router="false"
          @select="onSelect"
        >
          <template v-for="group in MENU_GROUPS" :key="group.title">
            <div class="group-label">{{ group.title }}</div>
            <el-menu-item
              v-for="item in group.children"
              :key="item.path"
              :index="item.path"
            >
              <span>{{ item.title }}</span>
              <el-tag
                v-if="item.status === 'planned'"
                size="small"
                type="info"
                effect="plain"
                class="planned-tag"
              >
                规划中
              </el-tag>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>
    </el-aside>
    <el-container>
      <el-header class="admin-header" height="56px">
        <span class="header-title">{{ headerTitle }}</span>
        <el-tag size="small" type="info">无鉴权 · 本机/内网</el-tag>
      </el-header>
      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-shell {
  min-height: 100vh;
  background: var(--rp-bg);
}

.admin-aside {
  background: #1f2a37;
  color: #e5e7eb;
  display: flex;
  flex-direction: column;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 18px 16px;
  font-weight: 600;
  font-size: 15px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
}

.menu-scroll {
  flex: 1;
}

.group-label {
  padding: 14px 20px 6px;
  font-size: 11px;
  letter-spacing: 0.04em;
  color: #6b7280;
  text-transform: none;
}

.admin-aside :deep(.el-menu) {
  border-right: none;
}

.admin-aside :deep(.el-menu-item) {
  height: 40px;
  line-height: 40px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 12px !important;
}

.admin-aside :deep(.el-menu-item.is-active) {
  background: rgba(59, 130, 246, 0.25) !important;
}

.planned-tag {
  margin-left: 8px;
  border-color: #4b5563;
  color: #9ca3af;
  background: transparent;
  height: 18px;
  line-height: 16px;
  padding: 0 4px;
  font-size: 10px;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
}

.header-title {
  font-size: 15px;
  font-weight: 500;
  color: #111827;
}

.admin-main {
  background: var(--rp-bg);
}
</style>
