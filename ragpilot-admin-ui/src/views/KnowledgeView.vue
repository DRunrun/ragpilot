<script setup lang="ts">
/**
 * 知识库管理（ADM-3.7）：库列表 + 文档上传/samples/重灌/块预览。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import {
  createKnowledgeBase,
  deleteDocument,
  deleteKnowledgeBase,
  ingestSamples,
  listDocChunks,
  listDocuments,
  listKnowledgeBases,
  patchKnowledgeBase,
  reingestDocument,
  uploadDocument,
  type KnowledgeBase,
  type KnowledgeDocument,
} from '../api/knowledge'

const loading = ref(false)
const bases = ref<KnowledgeBase[]>([])
const activeId = ref('default')
const docs = ref<KnowledgeDocument[]>([])
const docsLoading = ref(false)
const createName = ref('')
const createDesc = ref('')
const chunkDialog = ref(false)
const chunkItems = ref<Array<{ chunkId: string; content: string; length: number }>>([])
const chunkTitle = ref('')

const activeBase = computed(() => bases.value.find((b) => b.id === activeId.value))

async function refreshBases() {
  loading.value = true
  try {
    const data = await listKnowledgeBases()
    bases.value = data.items
    if (!bases.value.some((b) => b.id === activeId.value) && bases.value.length) {
      activeId.value = bases.value[0].id
    }
    await refreshDocs()
  } finally {
    loading.value = false
  }
}

async function refreshDocs() {
  if (!activeId.value) return
  docsLoading.value = true
  try {
    const data = await listDocuments(activeId.value)
    docs.value = data.items
  } finally {
    docsLoading.value = false
  }
}

async function onCreate() {
  if (!createName.value.trim()) {
    ElMessage.warning('请输入知识库名称')
    return
  }
  await createKnowledgeBase(createName.value.trim(), createDesc.value || undefined)
  createName.value = ''
  createDesc.value = ''
  ElMessage.success('已创建')
  await refreshBases()
}

async function toggleEnabled(row: KnowledgeBase) {
  await patchKnowledgeBase(row.id, { enabled: !row.enabled })
  await refreshBases()
}

async function onDeleteKb(row: KnowledgeBase) {
  if (row.id === 'default') {
    ElMessage.warning('默认知识库不可删除')
    return
  }
  await ElMessageBox.confirm(`确认删除知识库「${row.name}」？将级联删除文档与向量。`, '危险操作', {
    type: 'warning',
  })
  await deleteKnowledgeBase(row.id)
  ElMessage.success('已删除')
  activeId.value = 'default'
  await refreshBases()
}

async function onUpload(file: File) {
  await uploadDocument(activeId.value, file)
  ElMessage.success('上传并摄入完成')
  await refreshBases()
  return false
}

async function onSamples() {
  const data = await ingestSamples(activeId.value)
  ElMessage.success(`samples 摄入 ${data.total} 个文件`)
  await refreshBases()
}

async function onReingest(doc: KnowledgeDocument) {
  await reingestDocument(activeId.value, doc.docId)
  ElMessage.success('重灌完成')
  await refreshDocs()
}

async function onDeleteDoc(doc: KnowledgeDocument) {
  await ElMessageBox.confirm(`删除文档 ${doc.docId}？`, '确认', { type: 'warning' })
  await deleteDocument(activeId.value, doc.docId)
  ElMessage.success('已删除')
  await refreshBases()
}

async function onChunks(doc: KnowledgeDocument) {
  chunkTitle.value = doc.docId
  const data = await listDocChunks(activeId.value, doc.docId)
  chunkItems.value = data.items
  chunkDialog.value = true
}

function selectKb(id: string) {
  activeId.value = id
  void refreshDocs()
}

onMounted(() => {
  void refreshBases()
})
</script>

<template>
  <div class="page">
    <PageHeader
      title="知识库管理"
      description="业务主入口：建库、上传文档、启停库。向量写入带 knowledgeBaseId；日常灌文档请走本页。"
    >
      <template #extra>
        <el-button :loading="loading" @click="refreshBases">刷新</el-button>
      </template>
    </PageHeader>

    <el-row :gutter="16">
      <el-col :xs="24" :md="8">
        <el-card shadow="never">
          <div class="card-title">知识库</div>
          <el-table
            :data="bases"
            v-loading="loading"
            highlight-current-row
            size="small"
            @row-click="(row: KnowledgeBase) => selectKb(row.id)"
          >
            <el-table-column prop="name" label="名称" min-width="100" />
            <el-table-column label="启用" width="70">
              <template #default="{ row }">
                <el-switch
                  :model-value="row.enabled"
                  @change="toggleEnabled(row)"
                  @click.stop
                />
              </template>
            </el-table-column>
            <el-table-column prop="documentCount" label="文档" width="60" />
            <el-table-column label="" width="56">
              <template #default="{ row }">
                <el-button
                  v-if="row.id !== 'default'"
                  link
                  type="danger"
                  @click.stop="onDeleteKb(row)"
                >
                  删
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <el-divider />
          <el-form label-position="top" size="small">
            <el-form-item label="新建知识库">
              <el-input v-model="createName" placeholder="名称" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="createDesc" placeholder="可选" />
            </el-form-item>
            <el-button type="primary" @click="onCreate">创建</el-button>
          </el-form>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="16">
        <el-card shadow="never">
          <div class="card-title">
            文档 · {{ activeBase?.name || activeId }}
            <code class="kb-id">{{ activeId }}</code>
          </div>
          <div class="actions">
            <el-upload :show-file-list="false" :before-upload="onUpload" accept=".md,.txt,.markdown">
              <el-button type="primary">上传 md/txt</el-button>
            </el-upload>
            <el-button @click="onSamples">灌入 samples/</el-button>
            <el-button @click="refreshDocs">刷新文档</el-button>
          </div>
          <el-table :data="docs" v-loading="docsLoading" size="small">
            <el-table-column prop="docId" label="docId" min-width="140" />
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column prop="chunkCount" label="块数" width="70" />
            <el-table-column label="操作" width="220">
              <template #default="{ row }">
                <el-button link type="primary" @click="onChunks(row)">块预览</el-button>
                <el-button link @click="onReingest(row)">重灌</el-button>
                <el-button link type="danger" @click="onDeleteDoc(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="chunkDialog" :title="`块预览 · ${chunkTitle}`" width="720px">
      <div v-for="(c, i) in chunkItems" :key="c.chunkId || i" class="chunk">
        <div class="chunk-head">{{ c.chunkId }} · {{ c.length }} 字</div>
        <pre>{{ c.content }}</pre>
      </div>
      <el-empty v-if="!chunkItems.length" description="无块" />
    </el-dialog>
  </div>
</template>

<style scoped>
.card-title {
  margin-bottom: 12px;
  font-weight: 600;
  font-size: 14px;
}

.kb-id {
  margin-left: 8px;
  font-size: 12px;
  color: #9ca3af;
  font-weight: 400;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.chunk {
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  margin-bottom: 8px;
  overflow: hidden;
}

.chunk-head {
  padding: 4px 8px;
  background: #f9fafb;
  font-size: 12px;
  color: #6b7280;
}

.chunk pre {
  margin: 0;
  padding: 8px;
  white-space: pre-wrap;
  font-size: 12px;
}
</style>
