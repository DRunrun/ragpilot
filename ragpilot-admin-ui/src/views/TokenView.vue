<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import { fetchTokenSummary, saveTokenPrices } from '../api/token'

const days = ref(14)
const promptPrice = ref(0)
const completionPrice = ref(0)
const totalTokens = ref(0)
const estimatedCost = ref(0)
const byDay = ref<Array<{ day: string; messages: number; tokens: number }>>([])
const bySession = ref<Array<{ session_id: string; title: string; messages: number; tokens: number }>>([])
const note = ref('')

async function refresh() {
  const data = await fetchTokenSummary(days.value)
  promptPrice.value = data.promptPricePer1k
  completionPrice.value = data.completionPricePer1k
  totalTokens.value = data.totalTokens
  estimatedCost.value = data.estimatedCostUsd
  byDay.value = data.byDay
  bySession.value = data.bySession
  note.value = data.note
}

async function savePrices() {
  await saveTokenPrices(promptPrice.value, completionPrice.value)
  ElMessage.success('单价已更新（即时生效）')
  await refresh()
}

onMounted(() => {
  void refresh()
})
</script>

<template>
  <div class="page">
    <PageHeader title="Token 统计" description="按日 / 会话聚合 chat_message 用量；单价走 Overlay。">
      <template #extra>
        <el-button @click="refresh">刷新</el-button>
      </template>
    </PageHeader>

    <el-card shadow="never" class="mb">
      <el-form inline size="small">
        <el-form-item label="天数">
          <el-input-number v-model="days" :min="1" :max="90" @change="refresh" />
        </el-form-item>
        <el-form-item label="prompt $/1k">
          <el-input-number v-model="promptPrice" :min="0" :step="0.001" />
        </el-form-item>
        <el-form-item label="completion $/1k">
          <el-input-number v-model="completionPrice" :min="0" :step="0.001" />
        </el-form-item>
        <el-button type="primary" @click="savePrices">保存单价</el-button>
      </el-form>
      <div class="stats">
        总 tokens：<b>{{ totalTokens }}</b>
        · 粗估成本：<b>${{ estimatedCost.toFixed(6) }}</b>
      </div>
      <p class="note">{{ note }}</p>
    </el-card>

    <el-row :gutter="12">
      <el-col :md="12" :xs="24">
        <el-card shadow="never">
          <div class="label">按日</div>
          <el-table :data="byDay" size="small">
            <el-table-column prop="day" label="日期" />
            <el-table-column prop="messages" label="消息" width="80" />
            <el-table-column prop="tokens" label="tokens" width="100" />
          </el-table>
        </el-card>
      </el-col>
      <el-col :md="12" :xs="24">
        <el-card shadow="never">
          <div class="label">按会话 Top</div>
          <el-table :data="bySession" size="small">
            <el-table-column prop="title" label="标题" min-width="120" />
            <el-table-column prop="tokens" label="tokens" width="100" />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.mb { margin-bottom: 12px; }
.stats { margin-top: 8px; font-size: 14px; }
.note { font-size: 12px; color: #9ca3af; }
.label { margin-bottom: 8px; font-size: 13px; color: #6b7280; }
</style>
