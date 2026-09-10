<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import {
  createEvalJob,
  fetchGoldenMeta,
  getEvalReport,
  listEvalJobs,
  type EvalJob,
} from '../api/eval'

const jobs = ref<EvalJob[]>([])
const loading = ref(false)
const mode = ref('VECTOR')
const goldenSet = ref('v1.0')
const withJudge = ref(false)
const goldenCount = ref(0)
const reportMd = ref('')
let timer: number | undefined

async function refresh() {
  loading.value = true
  try {
    const [meta, list] = await Promise.all([
      fetchGoldenMeta(goldenSet.value),
      listEvalJobs(),
    ])
    goldenCount.value = meta.count
    jobs.value = list.items
  } finally {
    loading.value = false
  }
}

async function start() {
  const job = await createEvalJob({
    mode: mode.value,
    goldenSet: goldenSet.value,
    withJudge: withJudge.value,
  })
  ElMessage.success(`已创建任务 ${job.id}`)
  await refresh()
}

async function viewReport(job: EvalJob) {
  const data = await getEvalReport(job.id)
  reportMd.value = data.markdown || '(空)'
}

onMounted(() => {
  void refresh()
  timer = window.setInterval(() => {
    if (jobs.value.some((j) => j.status === 'PENDING' || j.status === 'RUNNING')) {
      void refresh()
    }
  }, 3000)
})

onUnmounted(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="page">
    <PageHeader title="评测管理" description="选择黄金集与检索 mode，异步跑 EvalRunner 并查看报告。">
      <template #extra>
        <el-button :loading="loading" @click="refresh">刷新</el-button>
      </template>
    </PageHeader>

    <el-card shadow="never" class="mb">
      <el-form inline size="small">
        <el-form-item label="mode">
          <el-select v-model="mode" style="width: 160px">
            <el-option label="VECTOR" value="VECTOR" />
            <el-option label="HYBRID" value="HYBRID" />
            <el-option label="HYBRID_RERANK" value="HYBRID_RERANK" />
          </el-select>
        </el-form-item>
        <el-form-item label="黄金集">
          <el-select v-model="goldenSet" style="width: 120px" @change="refresh">
            <el-option label="v0.1" value="v0.1" />
            <el-option label="v1.0" value="v1.0" />
          </el-select>
        </el-form-item>
        <el-form-item :label="`题数 ${goldenCount}`">
          <el-checkbox v-model="withJudge">启用 Judge（较慢）</el-checkbox>
        </el-form-item>
        <el-button type="primary" @click="start">开始评测</el-button>
      </el-form>
    </el-card>

    <el-row :gutter="16">
      <el-col :md="14" :xs="24">
        <el-card shadow="never">
          <el-table :data="jobs" size="small" v-loading="loading">
            <el-table-column prop="id" label="jobId" min-width="140" />
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column prop="mode" label="mode" width="120" />
            <el-table-column prop="progress" label="进度" width="80" />
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button
                  link
                  type="primary"
                  :disabled="row.status !== 'DONE'"
                  @click="viewReport(row)"
                >
                  报告
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :md="10" :xs="24">
        <el-card shadow="never">
          <div class="label">报告预览</div>
          <pre class="report">{{ reportMd || '选择 DONE 任务查看 Markdown 报告' }}</pre>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.mb { margin-bottom: 12px; }
.label { margin-bottom: 8px; font-size: 13px; color: #6b7280; }
.report { white-space: pre-wrap; font-size: 12px; max-height: 60vh; overflow: auto; margin: 0; }
</style>
