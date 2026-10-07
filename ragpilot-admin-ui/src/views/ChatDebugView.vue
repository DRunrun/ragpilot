<script setup lang="ts">
/**
 * 在线调试（ADM-5.3）：SSE 问答 + citation + 选知识库 + 多轮会话。
 *
 * <p>并发：同一时刻只允许一路 SSE（AbortController + streaming 闸门），避免多路 token 拼进同一 answer。
 * <p>展示：轻量 Markdown/引用角标渲染；引用区折叠截断；调试元信息收进折叠面板。
 */
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PageHeader from '../components/PageHeader.vue'
import { listKnowledgeBases, type KnowledgeBase } from '../api/knowledge'
import { createSession, listMessages, type ChatMessage } from '../api/session'
import { formatChatHtml, truncateSnippet } from '../utils/chatFormat'

const bases = ref<KnowledgeBase[]>([])
const kbId = ref<string>('')
const sessionId = ref('')
const question = ref('')
const streaming = ref(false)
const answer = ref('')
const pendingUser = ref('')
const citations = ref<Array<{ title?: string; docId?: string; snippet?: string }>>([])
const meta = ref('')
const messages = ref<ChatMessage[]>([])
const box = ref<HTMLElement | null>(null)

/** 取消上一路未完成的 SSE，防止并发写 answer */
let abortCtrl: AbortController | null = null
/** 流世代号：过期回调忽略，避免 abort 后旧流仍追加 */
let streamGen = 0

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
  abortCtrl?.abort()
  abortCtrl = null
  streamGen++
  streaming.value = false
  sessionId.value = ''
  messages.value = []
  answer.value = ''
  pendingUser.value = ''
  citations.value = []
  meta.value = ''
  await ensureSession()
  ElMessage.success(`新会话 ${sessionId.value}`)
}

async function ask() {
  const q = question.value.trim()
  if (!q) return
  // 进行中再点发送：取消旧流，只保留最新一问（避免两路 token 交织）
  if (streaming.value) {
    abortCtrl?.abort()
  }

  await ensureSession()
  const gen = ++streamGen
  abortCtrl = new AbortController()
  streaming.value = true
  answer.value = ''
  citations.value = []
  meta.value = ''
  pendingUser.value = q
  question.value = ''
  await nextTick()
  if (box.value) box.value.scrollTop = box.value.scrollHeight

  try {
    const resp = await fetch('/api/v1/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      signal: abortCtrl.signal,
      body: JSON.stringify({
        question: q,
        knowledgeBaseId: kbId.value || null,
        sessionId: sessionId.value,
      }),
    })
    if (!resp.ok || !resp.body) {
      if (gen === streamGen) {
        streaming.value = false
        pendingUser.value = ''
        ElMessage.error('ask 失败')
      }
      return
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let eventName = 'message'

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      if (gen !== streamGen) break
      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split('\n')
      buffer = parts.pop() || ''
      for (const line of parts) {
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (gen === streamGen) {
            handleEvent(eventName, data)
          }
          eventName = 'message'
        }
      }
    }

    if (gen !== streamGen) return
    streaming.value = false
    pendingUser.value = ''
    // 会话落库后以 messages 为准；清空流式缓冲，避免与历史 assistant 重复
    await reloadMessages()
    if (gen === streamGen) {
      answer.value = ''
    }
  } catch (e) {
    if ((e as Error)?.name === 'AbortError') {
      return
    }
    if (gen === streamGen) {
      streaming.value = false
      pendingUser.value = ''
      ElMessage.error(e instanceof Error ? e.message : 'ask 异常')
    }
  }
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
      meta.value = `拒答原因：${String(obj.reason ?? '')}`
    } else if (event === 'done') {
      const parts = [
        obj.promptVersion ? `Prompt ${obj.promptVersion}` : '',
        obj.promptTokens != null ? `tokens ${obj.promptTokens}/${obj.completionTokens}` : '',
        obj.traceId ? `trace ${obj.traceId}` : '',
      ].filter(Boolean)
      meta.value = parts.join(' · ')
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

onUnmounted(() => {
  abortCtrl?.abort()
  streamGen++
})
</script>

<template>
  <div class="page">
    <PageHeader title="在线调试" description="SSE 问答、citation、选库、多轮会话。发送中再次发送会取消上一问。">
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
        <code class="sid">{{ sessionId || '-' }}</code>
      </el-form-item>
    </el-form>

    <el-card shadow="never">
      <div ref="box" class="chat">
        <div v-for="m in messages" :key="m.id" class="msg" :class="m.role">
          <div class="role">{{ m.role === 'user' ? '你' : '助手' }}</div>
          <div class="bubble" v-html="formatChatHtml(m.content)" />
        </div>
        <template v-if="streaming || answer || pendingUser">
          <div v-if="pendingUser" class="msg user">
            <div class="role">你</div>
            <div class="bubble" v-html="formatChatHtml(pendingUser)" />
          </div>
          <div class="msg assistant live">
            <div class="role">助手{{ streaming ? ' · 生成中' : '' }}</div>
            <div class="bubble" v-html="formatChatHtml(answer || (streaming ? '…' : ''))" />
          </div>
        </template>
      </div>

      <el-collapse v-if="citations.length" class="cites-panel">
        <el-collapse-item :title="`参考来源（${citations.length}）`" name="cites">
          <div v-for="(c, i) in citations" :key="i" class="cite">
            <span class="cite-idx">[{{ i + 1 }}]</span>
            <span class="cite-doc">{{ c.docId || c.title || '未命名' }}</span>
            <p class="cite-snip">{{ truncateSnippet(c.snippet || '') }}</p>
          </div>
        </el-collapse-item>
      </el-collapse>

      <el-collapse v-if="meta" class="meta-panel">
        <el-collapse-item title="调试信息" name="meta">
          <div class="meta">{{ meta }}</div>
        </el-collapse-item>
      </el-collapse>

      <div class="input-row">
        <el-input
          v-model="question"
          type="textarea"
          :rows="2"
          placeholder="输入问题，Ctrl+Enter 发送；生成中再发送会取消上一问"
          @keydown.ctrl.enter="ask"
        />
        <el-button type="primary" :loading="streaming" @click="ask">
          {{ streaming ? '生成中' : '发送' }}
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.mb {
  margin-bottom: 12px;
}
.sid {
  font-size: 12px;
  color: #6b7280;
}
.chat {
  min-height: 280px;
  max-height: 50vh;
  overflow: auto;
  padding: 12px;
  background: #f8fafc;
  border-radius: 8px;
}
.msg {
  margin-bottom: 14px;
  max-width: 92%;
}
.msg.user {
  margin-left: auto;
}
.msg .role {
  font-size: 11px;
  color: #9ca3af;
  margin-bottom: 4px;
}
.msg.user .role {
  text-align: right;
}
.bubble {
  font-size: 14px;
  line-height: 1.65;
  color: #1f2937;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #e5e7eb;
  word-break: break-word;
}
.msg.user .bubble {
  background: #eff6ff;
  border-color: #bfdbfe;
}
.msg.assistant .bubble {
  background: #fff;
}
.bubble :deep(strong) {
  font-weight: 600;
  color: #111827;
}
.bubble :deep(.chat-code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  background: #f3f4f6;
  padding: 1px 5px;
  border-radius: 4px;
}
.bubble :deep(.cite-ref) {
  color: #2563eb;
  font-weight: 600;
  margin: 0 1px;
}
.cites-panel,
.meta-panel {
  margin-top: 8px;
  border: none;
}
.cite {
  padding: 8px 0;
  border-bottom: 1px solid #f3f4f6;
  font-size: 13px;
}
.cite:last-child {
  border-bottom: none;
}
.cite-idx {
  color: #2563eb;
  font-weight: 600;
  margin-right: 6px;
}
.cite-doc {
  color: #374151;
  font-weight: 500;
}
.cite-snip {
  margin: 4px 0 0;
  color: #6b7280;
  font-size: 12px;
  line-height: 1.5;
}
.meta {
  font-size: 12px;
  color: #9ca3af;
  word-break: break-all;
}
.input-row {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  align-items: flex-end;
}
</style>
