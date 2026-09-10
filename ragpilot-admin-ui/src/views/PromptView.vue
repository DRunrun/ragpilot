<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import {
  activatePrompt,
  diffPrompts,
  getPrompt,
  listPrompts,
  rollbackPrompt,
  upsertPrompt,
  type PromptListItem,
} from '../api/prompt'

const loading = ref(false)
const items = ref<PromptListItem[]>([])
const active = ref('')
const historySize = ref(0)
const selected = ref('')
const content = ref('')
const newVersion = ref('rag-custom-1')
const diffLeft = ref('rag-v1')
const diffRight = ref('rag-v2')
const diffText = ref('')

async function refresh() {
  loading.value = true
  try {
    const data = await listPrompts()
    items.value = data.items
    active.value = data.active
    historySize.value = data.historySize
    if (!selected.value && data.items.length) {
      selected.value = data.active || data.items[0].version
      await loadSelected()
    }
  } finally {
    loading.value = false
  }
}

async function loadSelected() {
  if (!selected.value) return
  const data = await getPrompt(selected.value)
  content.value = data.content
}

async function onActivate(version: string) {
  await activatePrompt(version)
  ElMessage.success(`已激活 ${version}`)
  await refresh()
}

async function onRollback() {
  await rollbackPrompt()
  ElMessage.success('已回滚')
  await refresh()
  await loadSelected()
}

async function onSave() {
  const version = newVersion.value.trim() || selected.value
  await upsertPrompt(version, content.value)
  selected.value = version
  ElMessage.success('已保存（写入 DB，重启仍在）')
  await refresh()
}

async function onDiff() {
  const data = await diffPrompts(diffLeft.value, diffRight.value)
  diffText.value = `=== ${data.left} ===\n${data.leftContent}\n\n=== ${data.right} ===\n${data.rightContent}`
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <div class="page">
    <PageHeader title="Prompt 管理" description="列表 / 编辑 / 激活 / 回滚；自定义版本持久化到 DB。">
      <template #extra>
        <el-button :disabled="historySize <= 0" @click="onRollback">回滚</el-button>
        <el-button :loading="loading" @click="refresh">刷新</el-button>
      </template>
    </PageHeader>

    <el-row :gutter="16">
      <el-col :md="8" :xs="24">
        <el-card shadow="never">
          <div class="meta">当前激活：<b>{{ active }}</b> · 历史栈 {{ historySize }}</div>
          <el-table :data="items" size="small" v-loading="loading" @row-click="(r: PromptListItem) => { selected = r.version; loadSelected() }">
            <el-table-column prop="version" label="版本" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.active" type="success" size="small">激活</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="" width="70">
              <template #default="{ row }">
                <el-button link type="primary" @click.stop="onActivate(row.version)">激活</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :md="16" :xs="24">
        <el-card shadow="never">
          <el-form label-position="top" size="small">
            <el-form-item label="保存为版本号">
              <el-input v-model="newVersion" />
            </el-form-item>
            <el-form-item :label="`模板正文（${selected}）`">
              <el-input v-model="content" type="textarea" :rows="16" />
            </el-form-item>
            <el-button type="primary" @click="onSave">保存</el-button>
            <el-button @click="loadSelected">重新加载</el-button>
          </el-form>
          <el-divider />
          <div class="diff-row">
            <el-input v-model="diffLeft" style="width: 140px" />
            <span>vs</span>
            <el-input v-model="diffRight" style="width: 140px" />
            <el-button @click="onDiff">对比</el-button>
          </div>
          <pre v-if="diffText" class="diff">{{ diffText }}</pre>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.meta { margin-bottom: 10px; font-size: 13px; color: #4b5563; }
.diff-row { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
.diff { white-space: pre-wrap; font-size: 12px; background: #f9fafb; padding: 10px; border-radius: 6px; max-height: 320px; overflow: auto; }
</style>
