import { deleteData, getData } from '../utils/request'

export function fetchVectorStats() {
  return getData<{
    totalChunks: number
    byKnowledgeBase: Array<{ knowledge_base_id: string; chunk_count: number }>
  }>('/api/admin/v1/vector/stats')
}

export function inspectByDocId(docId: string, limit = 50) {
  return getData<{
    docId: string
    items: Array<{
      id: string
      knowledgeBaseId: string
      chunkId: string
      preview: string
      length: number
    }>
    total: number
  }>('/api/admin/v1/vector/inspect', { docId, limit })
}

export function clearAllVectors() {
  return deleteData<{ deleted: number }>('/api/admin/v1/vector/all', {
    confirm: 'DELETE_ALL',
  })
}
