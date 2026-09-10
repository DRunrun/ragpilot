import { getData, postData } from '../utils/request'

export interface EvalJob {
  id: string
  status: string
  mode: string
  goldenSet: string
  topK: number
  withJudge: boolean
  progress: number
  resultPath: string | null
  errorMessage: string | null
  createdAt?: string
  finishedAt?: string
}

export function fetchGoldenMeta(goldenSet = 'v1.0') {
  return getData<{
    goldenSet: string
    count: number
    samples: Array<{ id: string; question: string; expectedDocIds: string[] }>
  }>('/api/admin/v1/eval/golden', { goldenSet })
}

export function listEvalJobs() {
  return getData<{ items: EvalJob[]; total: number }>('/api/admin/v1/eval/jobs')
}

export function createEvalJob(body: {
  mode: string
  goldenSet: string
  topK?: number
  withJudge?: boolean
}) {
  return postData<EvalJob>('/api/admin/v1/eval/jobs', body)
}

export function getEvalJob(id: string) {
  return getData<EvalJob>(`/api/admin/v1/eval/jobs/${id}`)
}

export function getEvalReport(id: string) {
  return getData<{ id: string; markdown: string }>(`/api/admin/v1/eval/jobs/${id}/report`)
}
