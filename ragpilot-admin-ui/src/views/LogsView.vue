<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { fetchLogTail, getTrace, listTraces } from '../api/trace'

const traces = ref<Array<{
  traceId: string
  kind: string
  startedAtEpochMs: number
  eventCount: number
}>>([])
const detail = ref('')
const logs = ref<string[]>([])
const auto = ref(true)
let timer: number | undefined

async function refreshTraces() {
  const data = await listTraces(50)
  traces.value = data.items
}

async function openTrace(id: string) {
  const data = await getTrace(id)
  detail.value = JSON.stringify(data, null, 2)
}

async function refreshLogs() {
  const data = await fetchLogTail(150)
  logs.value = data.lines
}

async function refreshAll() {
  await Promise.all([refreshTraces(), refreshLogs()])
}

onMounted(() => {
  void refreshAll()
  timer = window.setInterval(() => {
    if (auto.value) void refreshLogs()
  }, 4000)
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="page">
    <PageHeader title="日志与 Trace" description="内存 Trace 列表 + 应用日志环形缓冲 tail。">
      <template #extra>
        <el-checkbox v-model="auto">日志自动刷新</el-checkbox>
        <el-button @click="refreshAll">刷新</el-button>
      </template>
    </PageHeader>

    <el-row :gutter="12">
      <el-col :md="10" :xs="24">
        <el-card shadow="never" class="mb">
          <div class="label">最近 Trace</div>
          <el-table :data="traces" size="small" @row-click="(r: { traceId: string }) => openTrace(r.traceId)">
            <el-table-column prop="traceId" label="traceId" min-width="140" />
            <el-table-column prop="kind" label="kind" width="70" />
            <el-table-column prop="eventCount" label="事件" width="60" />
          </el-table>
        </el-card>
        <el-card shadow="never">
          <div class="label">Trace 详情</div>
          <pre class="box">{{ detail || '点击左侧 trace' }}</pre>
        </el-card>
      </el-col>
      <el-col :md="14" :xs="24">
        <el-card shadow="never">
          <div class="label">日志 tail（内存）</div>
          <pre class="box logs">{{ logs.join('\n') || '暂无日志' }}</pre>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.mb { margin-bottom: 12px; }
.label { margin-bottom: 8px; font-size: 13px; color: #6b7280; }
.box {
  margin: 0;
  padding: 10px;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 6px;
  font-size: 11px;
  white-space: pre-wrap;
  max-height: 40vh;
  overflow: auto;
}
.logs { max-height: 70vh; }
</style>
