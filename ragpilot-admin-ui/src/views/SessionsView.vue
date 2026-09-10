<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import {
  deleteSession,
  listMessages,
  listSessions,
  type ChatMessage,
  type ChatSession,
} from '../api/session'

const sessions = ref<ChatSession[]>([])
const messages = ref<ChatMessage[]>([])
const loading = ref(false)
const activeId = ref('')

async function refresh() {
  loading.value = true
  try {
    const data = await listSessions()
    sessions.value = data.items
  } finally {
    loading.value = false
  }
}

async function open(row: ChatSession) {
  activeId.value = row.id
  const data = await listMessages(row.id)
  messages.value = data.items
}

async function remove(row: ChatSession) {
  await ElMessageBox.confirm(`删除会话 ${row.title || row.id}？`, '确认', { type: 'warning' })
  await deleteSession(row.id)
  ElMessage.success('已删除')
  if (activeId.value === row.id) {
    activeId.value = ''
    messages.value = []
  }
  await refresh()
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <div class="page">
    <PageHeader title="会话管理" description="查看与删除多轮对话历史。">
      <template #extra>
        <el-button :loading="loading" @click="refresh">刷新</el-button>
      </template>
    </PageHeader>
    <el-row :gutter="16">
      <el-col :md="10" :xs="24">
        <el-card shadow="never">
          <el-table :data="sessions" size="small" v-loading="loading" @row-click="open">
            <el-table-column prop="title" label="标题" min-width="120" />
            <el-table-column prop="mode" label="模式" width="70" />
            <el-table-column label="" width="60">
              <template #default="{ row }">
                <el-button link type="danger" @click.stop="remove(row)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :md="14" :xs="24">
        <el-card shadow="never">
          <div class="label">消息 · {{ activeId || '未选择' }}</div>
          <div v-for="m in messages" :key="m.id" class="msg">
            <b>{{ m.role }}</b>
            <pre>{{ m.content }}</pre>
          </div>
          <el-empty v-if="!messages.length" description="选择左侧会话" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.label { margin-bottom: 10px; font-size: 13px; color: #6b7280; }
.msg { margin-bottom: 10px; border-bottom: 1px solid #f3f4f6; padding-bottom: 8px; }
.msg pre { margin: 4px 0 0; white-space: pre-wrap; font-size: 13px; font-family: inherit; }
</style>
