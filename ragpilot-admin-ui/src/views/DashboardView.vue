<script setup lang="ts">
/**
 * 总览页：模块入口 + 网关/Admin 健康状态（ADM-0.7）。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import { MENU_GROUPS } from '../config/menu'
import { fetchAdminHealth, fetchGateway, type GatewayInfo } from '../api/health'

const router = useRouter()
const loading = ref(false)
const adminStatus = ref<string>('未知')
const gateway = ref<GatewayInfo | null>(null)
const gatewayError = ref('')

function go(path: string) {
  void router.push(path)
}

async function refresh() {
  loading.value = true
  gatewayError.value = ''
  try {
    const health = await fetchAdminHealth()
    adminStatus.value = health.status ?? 'UP'
  } catch {
    adminStatus.value = 'DOWN'
  }
  try {
    gateway.value = await fetchGateway()
  } catch (e) {
    gateway.value = null
    gatewayError.value = e instanceof Error ? e.message : '网关不可用'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <div class="page">
    <PageHeader
      title="总览"
      description="侧栏已覆盖全部管理模块。标「规划中」的页面将按 ADM 任务逐步替换为真实功能。"
    >
      <template #extra>
        <el-button :loading="loading" @click="refresh">刷新状态</el-button>
      </template>
    </PageHeader>

    <el-row :gutter="12" class="status-row">
      <el-col :xs="24" :md="8">
        <el-card shadow="never">
          <div class="status-label">Admin API</div>
          <el-tag :type="adminStatus === 'UP' ? 'success' : 'danger'">
            {{ adminStatus }}
          </el-tag>
          <div class="status-hint">GET /api/admin/v1/health</div>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="16">
        <el-card shadow="never">
          <div class="status-label">模型网关</div>
          <template v-if="gateway">
            <div class="gw-line"><b>baseUrl</b> {{ gateway.baseUrl || '-' }}</div>
            <div class="gw-line"><b>chat</b> {{ gateway.chatModel || '-' }}</div>
            <div class="gw-line">
              <b>embed</b> {{ gateway.embeddingModel || '-' }}
              <span v-if="gateway.embeddingDim"> · dim={{ gateway.embeddingDim }}</span>
            </div>
          </template>
          <el-alert
            v-else
            type="warning"
            :closable="false"
            :title="gatewayError || '未拉取到网关信息（请启动 bootstrap + LM Studio）'"
          />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="12">
      <el-col
        v-for="group in MENU_GROUPS"
        :key="group.title"
        :xs="24"
        :sm="12"
        :md="8"
        class="col"
      >
        <el-card shadow="never" class="group-card">
          <template #header>
            <span class="card-title">{{ group.title }}</span>
          </template>
          <div
            v-for="item in group.children"
            :key="item.path"
            class="entry"
            @click="go(item.path)"
          >
            <span>{{ item.title }}</span>
            <el-tag
              size="small"
              :type="item.status === 'ready' ? 'success' : 'info'"
              effect="plain"
            >
              {{ item.status === 'ready' ? '可用' : '规划中' }}
            </el-tag>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.status-row {
  margin-bottom: 12px;
}

.status-label {
  font-size: 12px;
  color: #6b7280;
  margin-bottom: 8px;
}

.status-hint {
  margin-top: 8px;
  font-size: 11px;
  color: #9ca3af;
}

.gw-line {
  font-size: 13px;
  margin-bottom: 4px;
  word-break: break-all;
}

.col {
  margin-bottom: 12px;
}

.card-title {
  font-weight: 600;
  font-size: 14px;
}

.entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #f3f4f6;
  cursor: pointer;
  font-size: 13px;
  color: #374151;
}

.entry:last-child {
  border-bottom: none;
}

.entry:hover {
  color: #2563eb;
}
</style>
