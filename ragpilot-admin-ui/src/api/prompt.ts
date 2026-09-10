import { getData, postData, putData } from '../utils/request'

export interface PromptListItem {
  version: string
  active: boolean
  length: number
}

export function listPrompts() {
  return getData<{
    active: string
    historySize: number
    items: PromptListItem[]
    total: number
  }>('/api/admin/v1/prompts')
}

export function getPrompt(version: string) {
  return getData<{ version: string; content: string; active: boolean }>(
    `/api/admin/v1/prompts/versions/${encodeURIComponent(version)}`,
  )
}

export function upsertPrompt(version: string, content: string) {
  return putData<{ version: string; content: string }>(
    '/api/admin/v1/prompts',
    { version, content },
  )
}

export function activatePrompt(version: string) {
  return postData('/api/admin/v1/prompts/activate', { version })
}

export function rollbackPrompt() {
  return postData('/api/admin/v1/prompts/rollback', {})
}

export function diffPrompts(left: string, right: string) {
  return getData<{
    left: string
    right: string
    leftContent: string
    rightContent: string
  }>('/api/admin/v1/prompts/diff', { left, right })
}
