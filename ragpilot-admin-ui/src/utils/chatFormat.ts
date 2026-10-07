/**
 * 聊天展示用轻量格式化：转义 HTML，渲染常见 Markdown 标记与引用角标。
 * 不引入 marked 依赖；内容来自模型，必须先 escape 再替换。
 */

function escapeHtml(raw: string): string {
  return raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * @param text 模型或用户原文
 * @returns 可安全放入 v-html 的 HTML 片段
 */
export function formatChatHtml(text: string): string {
  if (!text) return ''
  let s = escapeHtml(text)

  // 行内代码优先，避免内部 ** 被二次处理
  s = s.replace(/`([^`]+)`/g, '<code class="chat-code">$1</code>')
  // 粗体 / 斜体（成对）
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  // 单星斜体：前后不是 *，避免吃掉粗体残留
  s = s.replace(/(^|[^*])\*([^*]+)\*([^*]|$)/g, '$1<em>$2</em>$3')
  // 引用编号 [1] → 角标
  s = s.replace(/\[(\d+)\]/g, '<sup class="cite-ref">[$1]</sup>')
  // 换行
  s = s.replace(/\n/g, '<br>')
  return s
}

/** 引用片段过长时截断，避免整屏刷墙 */
export function truncateSnippet(text: string, max = 120): string {
  const t = (text || '').replace(/\s+/g, ' ').trim()
  if (t.length <= max) return t
  return `${t.slice(0, max)}…`
}
