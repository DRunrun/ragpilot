<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import { fetchAgentSettings, saveAgentSettings } from '../api/agent'

const form = ref({
  maxSteps: 5,
  stepTimeoutMs: 15000,
  httpAllowlist: '',
  httpTimeoutMs: 5000,
  mcpEnabled: false,
  mcpEndpoint: '',
  mcpTimeoutMs: 5000,
})
const warnings = ref<string[]>([])
const runtimeAllowlist = ref<string[]>([])

async function load() {
  const data = await fetchAgentSettings()
  form.value = {
    maxSteps: data.maxSteps,
    stepTimeoutMs: data.stepTimeoutMs,
    httpAllowlist: data.httpAllowlist,
    httpTimeoutMs: data.httpTimeoutMs,
    mcpEnabled: data.mcpEnabled,
    mcpEndpoint: data.mcpEndpoint,
    mcpTimeoutMs: data.mcpTimeoutMs,
  }
  runtimeAllowlist.value = data.runtimeAllowlist || []
}

async function save() {
  const res = await saveAgentSettings(form.value)
  warnings.value = res.warnings || []
  ElMessage.success('已保存')
  await load()
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <PageHeader title="Agent 管理" description="maxSteps / 超时 / HttpGet allowlist / MCP 开关（部分需重启）。">
      <template #extra>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </PageHeader>
    <el-alert
      v-for="(w, i) in warnings"
      :key="i"
      type="warning"
      :title="w"
      :closable="false"
      show-icon
      class="mb"
    />
    <el-card shadow="never">
      <el-form label-width="140px" size="small">
        <el-form-item label="maxSteps">
          <el-input-number v-model="form.maxSteps" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="stepTimeoutMs">
          <el-input-number v-model="form.stepTimeoutMs" :min="1000" :step="1000" />
        </el-form-item>
        <el-form-item label="http allowlist">
          <el-input v-model="form.httpAllowlist" placeholder="host1,host2" />
          <div class="hint">运行时：{{ runtimeAllowlist.join(', ') || '-' }}（allowlist 热更新）</div>
        </el-form-item>
        <el-form-item label="httpTimeoutMs">
          <el-input-number v-model="form.httpTimeoutMs" :min="500" :step="500" />
        </el-form-item>
        <el-form-item label="MCP enabled">
          <el-switch v-model="form.mcpEnabled" />
        </el-form-item>
        <el-form-item label="MCP endpoint">
          <el-input v-model="form.mcpEndpoint" />
        </el-form-item>
        <el-form-item label="MCP timeoutMs">
          <el-input-number v-model="form.mcpTimeoutMs" :min="500" :step="500" />
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.mb { margin-bottom: 12px; }
.hint { font-size: 12px; color: #9ca3af; margin-top: 4px; }
</style>
