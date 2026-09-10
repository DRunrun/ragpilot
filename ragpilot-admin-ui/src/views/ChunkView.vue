<script setup lang="ts">
/**
 * 分块策略页（ADM-2.5）：切换策略/参数、试切预览；持久化写 DB 覆盖层。
 */
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import { previewChunk, type ChunkPreviewItem, type ChunkStrategyName } from '../api/chunk'
import { fetchEffectiveConfig, putConfig } from '../api/config'

const SAMPLE = `### 什么是 Bean 生命周期？
Spring Bean 从创建到销毁经历多个阶段，包括实例化、属性填充、初始化与销毁回调。

### 什么是 IoC？
控制反转：对象依赖由容器注入，而非自行 new。

### 短答
OK。`

const loading = ref(false)
const saving = ref(false)
const previewing = ref(false)
const strategy = ref<ChunkStrategyName>('FIXED')
const size = ref(512)
const overlap = ref(64)
const separatorsText = ref('\\n\\n,\\n,。, ,')
const text = ref(SAMPLE)
const items = ref<ChunkPreviewItem[]>([])
const hint = ref('')
const chunkCount = ref(0)

function parseSeparators(): string[] {
  // UI 用逗号分隔；字面 \\n 转成换行
  return separatorsText.value
    .split(',')
    .map((s) => s.trim().replace(/\\n/g, '\n'))
}

async function loadConfig() {
  loading.value = true
  try {
    const cfg = await fetchEffectiveConfig()
    strategy.value = (cfg['chunk.strategy'] as ChunkStrategyName) || 'FIXED'
    size.value = cfg['chunk.size'] ?? 512
    overlap.value = cfg['chunk.overlap'] ?? 64
    const seps = cfg['chunk.separators'] ?? []
    separatorsText.value = seps
      .map((s) => s.replace(/\n/g, '\\n'))
      .join(',')
  } finally {
    loading.value = false
  }
}

async function save() {
  if (size.value <= 0 || overlap.value < 0 || overlap.value >= size.value) {
    ElMessage.error('参数非法：须 size>0 且 0≤overlap<size')
    return
  }
  saving.value = true
  try {
    await putConfig('ragpilot.chunk.strategy', strategy.value)
    await putConfig('ragpilot.chunk.size', size.value)
    await putConfig('ragpilot.chunk.overlap', overlap.value)
    if (strategy.value === 'RECURSIVE') {
      await putConfig('ragpilot.chunk.separators', JSON.stringify(parseSeparators()))
    }
    ElMessage.success('已保存（仅影响后续摄入；已入库文档请重灌）')
  } finally {
    saving.value = false
  }
}

async function runPreview() {
  if (!text.value.trim()) {
    ElMessage.warning('请输入试切文本')
    return
  }
  previewing.value = true
  try {
    const result = await previewChunk({
      text: text.value,
      strategy: strategy.value,
      size: size.value,
      overlap: overlap.value,
      separators: strategy.value === 'RECURSIVE' ? parseSeparators() : undefined,
    })
    items.value = result.items
    chunkCount.value = result.chunkCount
    hint.value = result.hint
  } finally {
    previewing.value = false
  }
}

onMounted(() => {
  void loadConfig()
})
</script>

<template>
  <div class="page">
    <PageHeader
      title="分块策略"
      description="切换 FIXED / HEADING / RECURSIVE 并试切预览。保存写入 DB 覆盖层，不修改 application.yml。"
    >
      <template #extra>
        <el-button :loading="loading" @click="loadConfig">重新加载</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
      </template>
    </PageHeader>

    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="策略变更只影响后续摄入；已入库文档需在知识库中重灌才会改变块边界。"
      class="mb"
    />

    <el-row :gutter="16">
      <el-col :xs="24" :lg="10">
        <el-card shadow="never">
          <el-form label-position="top" :disabled="loading">
            <el-form-item label="策略">
              <el-radio-group v-model="strategy">
                <el-radio-button value="FIXED">FIXED</el-radio-button>
                <el-radio-button value="HEADING">HEADING</el-radio-button>
                <el-radio-button value="RECURSIVE">RECURSIVE</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="size（字符）">
              <el-input-number v-model="size" :min="32" :max="8000" />
            </el-form-item>
            <el-form-item label="overlap">
              <el-input-number v-model="overlap" :min="0" :max="size - 1" />
            </el-form-item>
            <el-form-item v-if="strategy === 'RECURSIVE'" label="separators（逗号分隔，\\n 表示换行）">
              <el-input v-model="separatorsText" />
            </el-form-item>
            <el-form-item label="试切文本">
              <el-input v-model="text" type="textarea" :rows="14" />
            </el-form-item>
            <el-button type="primary" :loading="previewing" @click="runPreview">试切预览</el-button>
          </el-form>
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="14">
        <el-card shadow="never">
          <div class="preview-meta">
            共 {{ chunkCount }} 块
            <span v-if="hint" class="hint"> · {{ hint }}</span>
          </div>
          <el-empty v-if="!items.length" description="点击「试切预览」查看块列表" />
          <div v-else class="chunks">
            <div v-for="item in items" :key="item.index" class="chunk">
              <div class="chunk-head">
                #{{ item.index }} · {{ item.length }} 字符
              </div>
              <pre class="chunk-body">{{ item.content }}</pre>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.mb {
  margin-bottom: 12px;
}

.preview-meta {
  margin-bottom: 12px;
  font-size: 13px;
  color: #4b5563;
}

.hint {
  color: #9ca3af;
}

.chunks {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 70vh;
  overflow: auto;
}

.chunk {
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  overflow: hidden;
}

.chunk-head {
  padding: 6px 10px;
  background: #f9fafb;
  font-size: 12px;
  color: #6b7280;
}

.chunk-body {
  margin: 0;
  padding: 10px;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.45;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}
</style>
