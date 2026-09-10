import { getData, postData, putData } from '../utils/request'

export function fetchAgentSettings() {
  return getData<{
    maxSteps: number
    stepTimeoutMs: number
    httpAllowlist: string
    httpTimeoutMs: number
    mcpEnabled: boolean
    mcpEndpoint: string
    mcpTimeoutMs: number
    mcpBeanPresent: boolean
    runtimeAllowlist: string[]
  }>('/api/admin/v1/agent/settings')
}

export function saveAgentSettings(body: Record<string, unknown>) {
  return putData<{ warnings: string[] }>('/api/admin/v1/agent/settings', body)
}

export function listTools() {
  return getData<{
    items: Array<{ name: string; description: string; inputSchema: string }>
    total: number
  }>('/api/admin/v1/agent/tools')
}

export function tryTool(tool: string, input: string) {
  return postData<{ tool: string; output: string }>('/api/admin/v1/agent/tools/try', {
    tool,
    input,
  })
}

export function mcpPing() {
  return getData<{
    configuredEnabled: boolean
    endpoint: string
    beanPresent: boolean
    ok: boolean
    message?: string
    toolCount?: number
  }>('/api/admin/v1/agent/mcp/ping')
}
