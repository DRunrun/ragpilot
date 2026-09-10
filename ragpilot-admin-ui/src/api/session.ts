import { deleteData, getData, postData } from '../utils/request'

export interface ChatSession {
  id: string
  title: string
  mode: string
  knowledgeBaseId: string | null
  createdAt?: string
  updatedAt?: string
}

export interface ChatMessage {
  id: string
  sessionId: string
  role: string
  content: string
  citationsJson: string | null
  traceId: string | null
  promptVersion: string | null
  tokenCount: number | null
  createdAt?: string
}

export function listSessions() {
  return getData<{ items: ChatSession[]; total: number }>('/api/admin/v1/sessions')
}

export function createSession(body: {
  title?: string
  mode?: string
  knowledgeBaseId?: string
}) {
  return postData<ChatSession>('/api/admin/v1/sessions', body)
}

export function deleteSession(id: string) {
  return deleteData(`/api/admin/v1/sessions/${id}`)
}

export function listMessages(sessionId: string) {
  return getData<{ items: ChatMessage[]; total: number }>(
    `/api/admin/v1/sessions/${sessionId}/messages`,
  )
}
