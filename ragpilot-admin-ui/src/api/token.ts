import { getData, putData } from '../utils/request'

export function fetchTokenSummary(days = 14) {
  return getData<{
    promptPricePer1k: number
    completionPricePer1k: number
    days: number
    totalTokens: number
    estimatedCostUsd: number
    byDay: Array<{ day: string; messages: number; tokens: number }>
    bySession: Array<{ session_id: string; title: string; messages: number; tokens: number }>
    note: string
  }>('/api/admin/v1/token/summary', { days })
}

export function saveTokenPrices(promptPricePer1k: number, completionPricePer1k: number) {
  return putData('/api/admin/v1/token/prices', { promptPricePer1k, completionPricePer1k })
}
