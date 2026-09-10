<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import { fetchAgentSettings, mcpPing, saveAgentSettings } from '../api/agent'

const router = useRouter()
const enabled = ref(false)
const endpoint = ref('')
const result = ref<Record<string, unknown> | null>(null)

async function load() {
  const data = await fetchAgentSettings()
  enabled.value = data.mcpEnabled
  endpoint.value = data.mcpEndpoint
}

async function save() {
  await saveAgentSettings({ mcpEnabled: enabled.value, mcpEndpoint: endpoint.value })
  await ping()
}

async function ping() {
  result.value = await mcpPing()
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <PageHeader title="MCP 管理" description="启用状态与 endpoint Overlay；Bean 需重启后装配。">
      <template #extra>
        <el-button @click="router.push('/agent')">Agent 完整配置</el-button>
        <el-button @click="ping">Ping</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </PageHeader>
    <el-card shadow="never" class="mb">
      <el-form label-width="120px" size="small">
        <el-form-item label="enabled">
          <el-switch v-model="enabled" />
        </el-form-item>
        <el-form-item label="endpoint">
          <el-input v-model="endpoint" />
        </el-form-item>
      </el-form>
    </el-card>
    <el-card v-if="result" shadow="never">
      <pre>{{ JSON.stringify(result, null, 2) }}</pre>
    </el-card>
  </div>
</template>

<style scoped>
.mb { margin-bottom: 12px; }
pre { margin: 0; font-size: 12px; white-space: pre-wrap; }
</style>
