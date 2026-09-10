import { postData } from '../utils/request'

export type ChunkStrategyName = 'FIXED' | 'HEADING' | 'RECURSIVE'

export interface ChunkPreviewItem {
  index: number
  length: number
  content: string
}

export interface ChunkPreviewResult {
  strategy: ChunkStrategyName
  size: number
  overlap: number
  separators: string[]
  chunkCount: number
  items: ChunkPreviewItem[]
  hint: string
}

export interface ChunkPreviewPayload {
  text: string
  strategy?: ChunkStrategyName
  size?: number
  overlap?: number
  separators?: string[]
}

export function previewChunk(payload: ChunkPreviewPayload) {
  return postData<ChunkPreviewResult>('/api/admin/v1/chunk/preview', payload)
}
