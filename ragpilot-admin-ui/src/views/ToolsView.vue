<script setup lang="ts">
import { onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { listTools, tryTool } from '../api/agent'

const tools = ref<Array<{ name: string; description: string; inputSchema: string }>>([])
const selected = ref('knowledge_search')
const input = ref('{"query":"Bean 生命周期"}')
const output = ref('')
const loading = ref(false)

async function load() {
  const data = await listTools()
  tools.value = data.items
  if (data.items.length && !data.items.some((t) => t.name === selected.value)) {
    selected.value = data.items[0].name
  }
}

async function run() {
  loading.value = true
  try {
    const data = await tryTool(selected.value, input.value)
    output.value = data.output
  } catch (e) {
    output.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function pick(name: string) {
  selected.value = name
  if (name === 'http_get') {
    input.value = '{"url":"https://docs.spring.io/spring-framework/reference/"}'
  } else if (name === 'knowledge_search') {
    input.value = '{"query":"Bean 生命周期"}'
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <PageHeader title="工具管理" description="查看已注册工具并试跑（knowledge_search / http_get / mcp_proxy）。">
      <template #extra>
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" :loading="loading" @click="run">试跑</el-button>
      </template>
    </PageHeader>

    <el-row :gutter="16">
      <el-col :md="8" :xs="24">
        <el-card shadow="never">
          <div
            v-for="t in tools"
            :key="t.name"
            class="tool"
            :class="{ active: t.name === selected }"
            @click="pick(t.name)"
          >
            <b>{{ t.name }}</b>
            <p>{{ t.description }}</p>
          </div>
          <el-empty v-if="!tools.length" description="无工具" />
        </el-card>
      </el-col>
      <el-col :md="16" :xs="24">
        <el-card shadow="never">
          <el-form label-position="top" size="small">
            <el-form-item :label="`输入 · ${selected}`">
              <el-input v-model="input" type="textarea" :rows="6" />
            </el-form-item>
          </el-form>
          <div class="label">输出</div>
          <pre class="out">{{ output || '点击试跑' }}</pre>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.tool {
  padding: 10px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  margin-bottom: 8px;
  cursor: pointer;
}
.tool.active { border-color: #0f7a62; background: #f0faf6; }
.tool p { margin: 4px 0 0; font-size: 12px; color: #6b7280; }
.label { font-size: 13px; color: #6b7280; margin-bottom: 6px; }
.out {
  margin: 0;
  padding: 10px;
  background: #f9fafb;
  border-radius: 6px;
  white-space: pre-wrap;
  font-size: 12px;
  max-height: 50vh;
  overflow: auto;
}
</style>
