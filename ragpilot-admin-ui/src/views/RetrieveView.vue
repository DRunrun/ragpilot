<script setup lang="ts">
import { onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { listKnowledgeBases, type KnowledgeBase } from '../api/knowledge'
import { compareRetrieveModes, type RetrieveHit } from '../api/retrieve'

const q = ref('Bean 生命周期')
const topK = ref(5)
const kbId = ref('')
const bases = ref<KnowledgeBase[]>([])
const loading = ref(false)
const modes = ref<Array<{ mode: string; hitCount: number; hits: RetrieveHit[] }>>([])
const meta = ref('')

async function run() {
  loading.value = true
  try {
    const data = await compareRetrieveModes(q.value, topK.value, kbId.value || undefined)
    modes.value = data.modes
    meta.value = `filter=${data.filterExpression || '无'} · kb=${(data.knowledgeBaseIds || []).join(',') || '全部'}`
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  const data = await listKnowledgeBases()
  bases.value = data.items
})
</script>

<template>
  <div class="page">
    <PageHeader title="检索调试" description="同一问题对比 VECTOR / HYBRID / HYBRID_RERANK，可按知识库过滤。">
      <template #extra>
        <el-button type="primary" :loading="loading" @click="run">对比检索</el-button>
      </template>
    </PageHeader>

    <el-card shadow="never" class="mb">
      <el-form inline size="small">
        <el-form-item label="问题">
          <el-input v-model="q" style="width: 280px" />
        </el-form-item>
        <el-form-item label="topK">
          <el-input-number v-model="topK" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="知识库">
          <el-select v-model="kbId" clearable placeholder="全部 enabled" style="width: 180px">
            <el-option v-for="b in bases" :key="b.id" :label="b.name" :value="b.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <div class="meta">{{ meta }}</div>
    </el-card>

    <el-row :gutter="12">
      <el-col v-for="m in modes" :key="m.mode" :md="8" :xs="24">
        <el-card shadow="never" class="mb">
          <div class="mode-title">{{ m.mode }} · {{ m.hitCount }} hits</div>
          <div v-for="h in m.hits" :key="h.chunkId" class="hit">
            <div class="hit-head">#{{ h.rank }} {{ h.score.toFixed?.(4) ?? h.score }} · {{ h.docId }}</div>
            <div class="hit-body">{{ h.snippet }}</div>
          </div>
          <el-empty v-if="!m.hits.length" description="无命中" :image-size="48" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.mb { margin-bottom: 12px; }
.meta { font-size: 12px; color: #9ca3af; }
.mode-title { font-weight: 600; margin-bottom: 8px; font-size: 13px; }
.hit { border: 1px solid #e5e7eb; border-radius: 6px; margin-bottom: 8px; overflow: hidden; }
.hit-head { padding: 4px 8px; background: #f9fafb; font-size: 11px; color: #6b7280; }
.hit-body { padding: 8px; font-size: 12px; white-space: pre-wrap; }
</style>
