import { deleteData, getData, patchData, postData } from '../utils/request'
import client, { type ApiResponse } from '../utils/request'

export interface KnowledgeBase {
  id: string
  name: string
  description: string | null
  enabled: boolean
  documentCount: number
  createdAt?: string
  updatedAt?: string
}

export interface KnowledgeDocument {
  id: string
  knowledgeBaseId: string
  docId: string
  title: string | null
  sourceUri: string | null
  status: string
  chunkCount: number
  errorMessage: string | null
  createdAt?: string
  updatedAt?: string
}

export function listKnowledgeBases() {
  return getData<{ items: KnowledgeBase[]; total: number }>('/api/admin/v1/knowledge-bases')
}

export function createKnowledgeBase(name: string, description?: string) {
  return postData<KnowledgeBase>('/api/admin/v1/knowledge-bases', { name, description })
}

export function patchKnowledgeBase(
  id: string,
  body: { name?: string; description?: string; enabled?: boolean },
) {
  return patchData<KnowledgeBase>(`/api/admin/v1/knowledge-bases/${id}`, body)
}

export function deleteKnowledgeBase(id: string) {
  return deleteData<{ id: string }>(`/api/admin/v1/knowledge-bases/${id}`, {
    confirm: 'DELETE_KB',
  })
}

export function listDocuments(kbId: string) {
  return getData<{ items: KnowledgeDocument[]; total: number }>(
    `/api/admin/v1/knowledge-bases/${kbId}/documents`,
  )
}

export function ingestSamples(kbId: string) {
  return postData<{ items: KnowledgeDocument[]; total: number }>(
    `/api/admin/v1/knowledge-bases/${kbId}/documents/samples`,
  )
}

export async function uploadDocument(kbId: string, file: File) {
  const form = new FormData()
  form.append('file', file)
  const resp = await client.post<ApiResponse<KnowledgeDocument>>(
    `/api/admin/v1/knowledge-bases/${kbId}/documents/upload`,
    form,
  )
  return resp.data.data as KnowledgeDocument
}

export function reingestDocument(kbId: string, docId: string) {
  return postData<KnowledgeDocument>(
    `/api/admin/v1/knowledge-bases/${kbId}/documents/${encodeURIComponent(docId)}/reingest`,
  )
}

export function deleteDocument(kbId: string, docId: string) {
  return deleteData(
    `/api/admin/v1/knowledge-bases/${kbId}/documents/${encodeURIComponent(docId)}`,
  )
}

export function listDocChunks(kbId: string, docId: string) {
  return getData<{ items: Array<{ chunkId: string; content: string; length: number }>; total: number }>(
    `/api/admin/v1/knowledge-bases/${kbId}/documents/${encodeURIComponent(docId)}/chunks`,
  )
}
