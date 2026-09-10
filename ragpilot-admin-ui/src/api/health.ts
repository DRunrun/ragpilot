import { getData, getRaw } from '../utils/request'

export interface AdminHealth {
  status: string
  service: string
}

export interface GatewayInfo {
  baseUrl?: string
  chatModel?: string
  embeddingModel?: string
  embeddingDim?: number
}

/** ADM-0.7：管理端健康检查 */
export function fetchAdminHealth() {
  return getData<AdminHealth>('/api/admin/v1/health')
}

/** 复用现有网关自检（未包 Admin 信封） */
export function fetchGateway() {
  return getRaw<GatewayInfo>('/api/v1/gateway')
}
