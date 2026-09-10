<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import { fetchModelSettings, probeModels, saveModelSettings } from '../api/models'

const form = ref({
  baseUrl: '',
  chatModel: '',
  temperature: 0,
  embeddingModel: '',
  embeddingDimensions: 768,
  rerankEnabled: false,
  rerankModel: '',
  rerankBaseUrl: '',
})
const probe = ref<Record<string, unknown> | null>(null)
const warnings = ref<string[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const data = await fetchModelSettings()
    form.value = { ...form.value, ...data }
  } finally {
    loading.value = false
  }
}

async function save() {
  const res = await saveModelSettings(form.value)
  warnings.value = res.warnings || []
  ElMessage.success('已保存 Overlay')
}

async function probeNow() {
  probe.value = await probeModels()
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <PageHeader title="模型管理" description="Overlay 覆盖 baseUrl / chat / embedding / rerank；探测网关与维度。">
      <template #extra>
        <el-button @click="probeNow">探测</el-button>
        <el-button type="primary" :loading="loading" @click="save">保存</el-button>
      </template>
    </PageHeader>

    <el-alert
      v-for="(w, i) in warnings"
      :key="i"
      type="warning"
      :title="w"
      show-icon
      :closable="false"
      class="mb"
    />

    <el-card shadow="never" class="mb">
      <el-form label-width="140px" size="small">
        <el-form-item label="baseUrl">
          <el-input v-model="form.baseUrl" />
        </el-form-item>
        <el-form-item label="chat model">
          <el-input v-model="form.chatModel" />
        </el-form-item>
        <el-form-item label="temperature">
          <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" />
        </el-form-item>
        <el-form-item label="embedding model">
          <el-input v-model="form.embeddingModel" />
        </el-form-item>
        <el-form-item label="dimensions">
          <el-input-number v-model="form.embeddingDimensions" :min="1" :max="4096" />
        </el-form-item>
        <el-form-item label="rerank">
          <el-switch v-model="form.rerankEnabled" />
        </el-form-item>
        <el-form-item label="rerank model">
          <el-input v-model="form.rerankModel" />
        </el-form-item>
        <el-form-item label="rerank baseUrl">
          <el-input v-model="form.rerankBaseUrl" />
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="probe" shadow="never">
      <pre class="probe">{{ JSON.stringify(probe, null, 2) }}</pre>
    </el-card>
  </div>
</template>

<style scoped>
.mb { margin-bottom: 12px; }
.probe { margin: 0; font-size: 12px; white-space: pre-wrap; }
</style>
