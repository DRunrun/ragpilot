import { getData } from '../utils/request'

export function listTraces(limit = 50) {
  return getData<{
    items: Array<{
      traceId: string
      kind: string
      startedAtEpochMs: number
      eventCount: number
    }>
    total: number
  }>('/api/admin/v1/traces', { limit })
}

export function getTrace(traceId: string) {
  return getData<{
    traceId: string
    kind: string
    startedAtEpochMs: number
    eventCount: number
    events: unknown[]
  }>(`/api/admin/v1/traces/${encodeURIComponent(traceId)}`)
}

export function fetchLogTail(limit = 100) {
  return getData<{ lines: string[]; total: number; buffered: number }>(
    '/api/admin/v1/logs/tail',
    { limit },
  )
}
