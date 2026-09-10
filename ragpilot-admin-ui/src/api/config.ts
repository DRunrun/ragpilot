import { deleteData, getData, putData } from '../utils/request'

export interface EffectiveConfig {
  'chunk.strategy': string
  'chunk.size': number
  'chunk.overlap': number
  'chunk.separators': string[]
  'prompt.activeVersion': string | null
  'retrieval.mode': string
  'retrieval.topK': number
  'retrieval.minScore': number
  'retrieval.defaultKnowledgeBaseId': string | null
  overlayKeys: string[]
}

export function fetchEffectiveConfig() {
  return getData<EffectiveConfig>('/api/admin/v1/config')
}

export function putConfig(key: string, value: string | number) {
  return putData<{ key: string; value: string }>(
    `/api/admin/v1/config/${encodeURIComponent(key)}`,
    { value: String(value) },
  )
}

export function deleteConfig(key: string) {
  return deleteData<{ key: string; deleted: boolean }>(
    `/api/admin/v1/config/${encodeURIComponent(key)}`,
  )
}
