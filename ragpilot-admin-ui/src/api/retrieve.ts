import { getData } from '../utils/request'

export interface RetrieveHit {
  rank: number
  score: number
  channel: string
  docId: string
  chunkId: string
  snippet: string
  knowledgeBaseId?: string
}

export function compareRetrieveModes(q: string, topK?: number, knowledgeBaseId?: string) {
  return getData<{
    query: string
    topK: number
    knowledgeBaseIds: string[]
    filterExpression: string | null
    defaultMode: string
    modes: Array<{ mode: string; hitCount: number; hits: RetrieveHit[] }>
  }>('/api/admin/v1/retrieve/modes', {
    q,
    topK,
    knowledgeBaseId: knowledgeBaseId || undefined,
  })
}
