import { getData, putData } from '../utils/request'

export interface ModelSettings {
  baseUrl: string
  chatModel: string
  temperature: number
  embeddingModel: string
  embeddingDimensions: number
  rerankEnabled: boolean
  rerankModel: string
  rerankBaseUrl: string
  hint?: string
}

export function fetchModelSettings() {
  return getData<ModelSettings>('/api/admin/v1/models')
}

export function saveModelSettings(body: Partial<ModelSettings>) {
  return putData<{ ok: boolean; warnings: string[] }>('/api/admin/v1/models', body)
}

export function probeModels() {
  return getData<{
    baseUrl: string
    modelsEndpointOk: boolean
    modelsError?: string
    embeddingDim: number
    embeddingError?: string
    configuredDim: number
    warnings: string[]
  }>('/api/admin/v1/models/probe')
}
