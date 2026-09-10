<script setup lang="ts">
/**
 * 向量库运维页（ADM-3.11）：统计 / 块排查 / 清空；无主上传入口。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import { clearAllVectors, fetchVectorStats, inspectByDocId } from '../api/vector'

const router = useRouter()
const loading = ref(false)
const total = ref(0)
const byKb = ref<Array<{ knowledge_base_id: string; chunk_count: number }>>([])
const docId = ref('')
const inspectItems = ref<Array<{
  id: string
  knowledgeBaseId: string
  chunkId: string
  preview: string
  length: number
}>>([])

async function refresh() {
  loading.value = true
  try {
    const data = await fetchVectorStats()
    total.value = data.totalChunks
    byKb.value = data.byKnowledgeBase
  } finally {
    loading.value = false
  }
}

async function inspect() {
  if (!docId.value.trim()) {
    ElMessage.warning('请输入 docId')
    return
  }
  const data = await inspectByDocId(docId.value.trim())
  inspectItems.value = data.items
}

async function clearAll() {
  await ElMessageBox.confirm(
    '将删除 vector_store 全部向量，且不可恢复。确认继续？',
    '危险操作',
    { type: 'error', confirmButtonText: '确认清空' },
  )
  const data = await clearAllVectors()
  ElMessage.success(`已删除 ${data.deleted} 条`)
  inspectItems.value = []
  await refresh()
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <div class="page">
    <PageHeader
      title="向量库管理"
      description="存储运维视角：统计、按 docId 排查、危险清空。日常上传请使用知识库管理。"
    >
      <template #extra>
        <el-button @click="router.push('/knowledge')">去知识库上传</el-button>
        <el-button :loading="loading" @click="refresh">刷新统计</el-button>
      </template>
    </PageHeader>

    <el-row :gutter="12" class="mb">
      <el-col :span="8">
        <el-card shadow="never">
          <div class="label">总块数</div>
          <div class="num">{{ total }}</div>
        </el-card>
      </el-col>
      <el-col :span="16">
        <el-card shadow="never">
          <div class="label">按知识库</div>
          <el-table :data="byKb" size="small" v-loading="loading">
            <el-table-column prop="knowledge_base_id" label="knowledgeBaseId" />
            <el-table-column prop="chunk_count" label="块数" width="100" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="mb">
      <div class="label">块排查（docId）</div>
      <div class="row">
        <el-input v-model="docId" placeholder="例如 spring-bean-lifecycle" style="max-width: 320px" />
        <el-button type="primary" @click="inspect">查询</el-button>
      </div>
      <el-table :data="inspectItems" size="small" class="mt">
        <el-table-column prop="chunkId" label="chunkId" width="180" />
        <el-table-column prop="knowledgeBaseId" label="KB" width="120" />
        <el-table-column prop="length" label="长度" width="70" />
        <el-table-column prop="preview" label="预览" />
      </el-table>
    </el-card>

    <el-card shadow="never">
      <el-button type="danger" @click="clearAll">清空全部向量…</el-button>
      <span class="hint">须二次确认；confirm=DELETE_ALL</span>
    </el-card>
  </div>
</template>

<style scoped>
.mb { margin-bottom: 12px; }
.mt { margin-top: 12px; }
.label { font-size: 13px; color: #6b7280; margin-bottom: 8px; }
.num { font-size: 28px; font-weight: 600; }
.row { display: flex; gap: 8px; align-items: center; }
.hint { margin-left: 12px; font-size: 12px; color: #9ca3af; }
</style>
