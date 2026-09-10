import axios, { type AxiosError } from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一 HTTP 客户端（ADM-0.3）。
 * 约定响应：{ ok: true, data } | { ok: false, error: { code, message } }
 */
export interface ApiErrorBody {
  code?: string
  message?: string
  details?: unknown
}

export interface ApiResponse<T> {
  ok: boolean
  data?: T
  error?: ApiErrorBody
}

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  timeout: 60_000,
})

client.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResponse<unknown> | unknown
    if (body && typeof body === 'object' && 'ok' in body) {
      const api = body as ApiResponse<unknown>
      if (!api.ok) {
        const msg = api.error?.message ?? '请求失败'
        ElMessage.error(msg)
        return Promise.reject(new Error(msg))
      }
    }
    return resp
  },
  (err: AxiosError<ApiResponse<unknown>>) => {
    const msg =
      err.response?.data?.error?.message
      ?? err.message
      ?? '网络错误'
    ElMessage.error(msg)
    return Promise.reject(err)
  },
)

export async function getData<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const resp = await client.get<ApiResponse<T>>(url, { params })
  return resp.data.data as T
}

export async function postData<T>(url: string, body?: unknown): Promise<T> {
  const resp = await client.post<ApiResponse<T>>(url, body)
  return resp.data.data as T
}

export async function putData<T>(url: string, body?: unknown): Promise<T> {
  const resp = await client.put<ApiResponse<T>>(url, body)
  return resp.data.data as T
}

export async function patchData<T>(url: string, body?: unknown): Promise<T> {
  const resp = await client.patch<ApiResponse<T>>(url, body)
  return resp.data.data as T
}

export async function deleteData<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const resp = await client.delete<ApiResponse<T>>(url, { params })
  return resp.data.data as T
}

/** 兼容尚未包成 Admin 信封的旧接口（如 /api/v1/gateway） */
export async function getRaw<T>(url: string): Promise<T> {
  const resp = await client.get<T>(url)
  return resp.data
}

export default client
