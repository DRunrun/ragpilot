<script setup lang="ts">
/**
 * 在线调试（ADM-5.3）：SSE 问答 + citation + 选知识库 + 多轮会话。
 */
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import { listKnowledgeBases, type KnowledgeBase } from '../api/knowledge'
import { createSession, listMessages, type ChatMessage } from '../api/session'

const bases = ref<KnowledgeBase[]>([])
const kbId = ref<string>('')
const sessionId = ref('')
const question = ref('')
const streaming = ref(false)
const answer = ref('')
const citations = ref<Array<{ title?: string; docId?: string; snippet?: string }>>([])
const meta = ref('')
const messages = ref<ChatMessage[]>([])
const box = ref<HTMLElement | null>(null)

async function ensureSession() {
  if (sessionId.value) return
  const s = await createSession({
    mode: 'RAG',
    knowledgeBaseId: kbId.value || undefined,
  })
  sessionId.value = s.id
}

async function reloadMessages() {
  if (!sessionId.value) return
  const data = await listMessages(sessionId.value)
  messages.value = data.items
  await nextTick()
  if (box.value) box.value.scrollTop = box.value.scrollHeight
}

async function newChat() {
  sessionId.value = ''
  messages.value = []
  answer.value = ''
  citations.value = []
  meta.value = ''
  await ensureSession()
  ElMessage.success(`新会话 ${sessionId.value}`)
}

async function ask() {
  if (!question.value.trim()) return
  await ensureSession()
  streaming.value = true
  answer.value = ''
  citations.value = []
  meta.value = ''
  const q = question.value.trim()
  question.value = ''

  const resp = await fetch('/api/v1/ask', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      question: q,
      knowledgeBaseId: kbId.value || null,
      sessionId: sessionId.value,
    }),
  })
  if (!resp.ok || !resp.body) {
    streaming.value = false
    ElMessage.error('ask 失败')
    return
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let eventName = 'message'

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const parts = buffer.split('\n')
    buffer = parts.pop() || ''
    for (const line of parts) {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        const data = line.slice(5).trim()
        handleEvent(eventName, data)
        eventName = 'message'
      }
    }
  }
  streaming.value = false
  await reloadMessages()
}

function handleEvent(event: string, data: string) {
  try {
    const obj = JSON.parse(data) as Record<string, unknown>
    if (event === 'token') {
      answer.value += String(obj.text ?? '')
    } else if (event === 'citation') {
      citations.value.push(obj as { title?: string; docId?: string; snippet?: string })
    } else if (event === 'refused') {
      answer.value = String(obj.message ?? obj.reason ?? '拒答')
      meta.value = `refused: ${String(obj.reason ?? '')}`
    } else if (event === 'done') {
      meta.value = `promptVersion=${obj.promptVersion ?? '-'} · tokens=${obj.promptTokens}/${obj.completionTokens} · trace=${obj.traceId}`
    }
  } catch {
    // ignore non-json
  }
}

onMounted(async () => {
  try {
    const data = await listKnowledgeBases()
    bases.value = data.items
  } catch {
    // 知识库列表失败不阻塞调试页
  }
  try {
    await ensureSession()
    await reloadMessages()
  } catch (e) {
    ElMessage.error(
      e instanceof Error
        ? `创建会话失败：${e.message}（若为 403，请重启 bootstrap 以加载本机 Vite 端口 CORS）`
        : '创建会话失败',
    )
  }
})
</script>

<template>
  <div class="page">
    <PageHeader title="在线调试" description="替代 static/index.html 主路径：SSE、citation、选库、多轮会话。">
      <template #extra>
        <el-button @click="newChat">新会话</el-button>
      </template>
    </PageHeader>

    <el-form inline size="small" class="mb">
      <el-form-item label="知识库">
        <el-select v-model="kbId" clearable placeholder="全部 enabled" style="width: 200px">
          <el-option
            v-for="b in bases"
            :key="b.id"
            :label="b.name"
            :value="b.id"
            :disabled="!b.enabled"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="session">
        <code>{{ sessionId || '-' }}</code>
      </el-form-item>
    </el-form>

    <el-card shadow="never">
      <div ref="box" class="chat">
        <div v-for="m in messages" :key="m.id" class="msg" :class="m.role">
          <div class="role">{{ m.role }}</div>
          <pre>{{ m.content }}</pre>
        </div>
        <div v-if="streaming || answer" class="msg assistant live">
          <div class="role">assistant（流式）</div>
          <pre>{{ answer || '…' }}</pre>
        </div>
      </div>
      <div v-if="citations.length" class="cites">
        <div v-for="(c, i) in citations" :key="i" class="cite">
          [{{ i + 1 }}] {{ c.docId || c.title }} — {{ c.snippet || '' }}
        </div>
      </div>
      <div v-if="meta" class="meta">{{ meta }}</div>
      <div class="input-row">
        <el-input
          v-model="question"
          type="textarea"
          :rows="2"
          placeholder="输入问题，Enter+Ctrl 发送"
          @keydown.ctrl.enter="ask"
        />
        <el-button type="primary" :loading="streaming" @click="ask">发送</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.mb { margin-bottom: 12px; }
.chat { min-height: 280px; max-height: 50vh; overflow: auto; padding: 8px; background: #fafafa; border-radius: 6px; }
.msg { margin-bottom: 10px; }
.msg .role { font-size: 11px; color: #9ca3af; margin-bottom: 2px; }
.msg pre { margin: 0; white-space: pre-wrap; font-size: 13px; font-family: inherit; }
.msg.user pre { color: #111827; }
.input-row { display: flex; gap: 8px; margin-top: 12px; align-items: flex-end; }
.cites { margin-top: 8px; font-size: 12px; color: #4b5563; }
.cite { margin-bottom: 4px; }
.meta { margin-top: 6px; font-size: 12px; color: #9ca3af; }
</style>
