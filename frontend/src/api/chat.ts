import { get, post } from './request'
import type { Result } from './types'

/** 商品卡片（Phase 3：客服推荐商品卡片，点击新标签打开详情） */
export interface ProductCard {
  productId: string
  name: string
  image?: string | null
  minPrice?: number | null
  maxPrice?: number | null
  excerpt?: string | null
}

/** SSE 流式数据帧（后端 /api/chat/ask） */
export interface ChatStreamFrame {
  type: 'token' | 'products' | 'route' | 'end' | 'error'
  content?: string | ProductCard[]
}

/** 流式回调 */
export interface ChatCallbacks {
  onToken?: (content: string) => void
  onProducts?: (products: ProductCard[]) => void
  onRoute?: (route: string) => void
  onEnd?: () => void
  onError?: (msg: string) => void
}

/**
 * 智能客服 SSE 流式问答
 * 使用 fetch 读取 EventStream，非标准 axios（不支持流式响应）
 */
export function postAsk(
  message: string,
  conversationId: string,
  onToken?: (content: string) => void,
  onEnd?: () => void,
  onError?: (msg: string) => void,
  onProducts?: (products: ProductCard[]) => void,
  onRoute?: (route: string) => void
): Promise<void> {
  const token = localStorage.getItem('token')
  const baseURL = import.meta.env.VITE_API_BASE || ''

  return new Promise<void>((resolve, reject) => {
    fetch(baseURL + '/api/chat/ask', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { token: token } : {})
      },
      body: JSON.stringify({ message, conversationId: conversationId || 'default' })
    }).then(async (response) => {
      if (!response.ok) {
        const text = await response.text().catch(() => '')
        onError?.('请求失败: ' + (text || response.statusText))
        reject(new Error(text || response.statusText))
        return
      }

      if (!response.body) {
        onError?.('响应无数据流')
        reject(new Error('响应无数据流'))
        return
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      function read() {
        reader.read().then(({ done, value }) => {
          if (done) {
            onEnd?.()
            resolve()
            return
          }

          buffer += decoder.decode(value, { stream: true })
          const lines = buffer.split('\n')
          buffer = lines.pop() || '' // 保留未完成行

          for (const line of lines) {
            if (line.startsWith('data:')) {
              const data = line.slice(5).trim()
              if (!data) continue
              try {
                const parsed = JSON.parse(data) as ChatStreamFrame
                if (parsed.type === 'token') {
                  onToken?.(typeof parsed.content === 'string' ? parsed.content : '')
                } else if (parsed.type === 'products') {
                  // 后端 products content 是 JSON 数组字符串（如 "[{...}]"），需二次 parse 成数组
                  onProducts?.(normalizeProducts(parsed.content))
                } else if (parsed.type === 'route') {
                  onRoute?.(typeof parsed.content === 'string' ? parsed.content : '')
                } else if (parsed.type === 'error') {
                  onError?.(typeof parsed.content === 'string' ? parsed.content : '')
                } else if (parsed.type === 'end') {
                  // end 已在 done 时触发
                }
              } catch (e) {
                console.warn('非 JSON 数据忽略:', e)
              }
            }
          }

          read()
        }).catch((err: Error) => {
          onError?.('读取中断: ' + err.message)
          reject(err)
        })
      }

      read()
    }).catch((err: Error) => {
      onError?.('网络异常: ' + err.message)
      reject(err)
    })
  })
}

/** 商品卡片 content 归一化：后端传 JSON 数组字符串，可能为数组或字符串，统一转 ProductCard[] */
function normalizeProducts(content: string | ProductCard[] | undefined): ProductCard[] {
  if (Array.isArray(content)) return content
  if (typeof content === 'string' && content.trim()) {
    try {
      const arr = JSON.parse(content)
      return Array.isArray(arr) ? (arr as ProductCard[]) : []
    } catch {
      return []
    }
  }
  return []
}

/** 后端历史消息记录（chat_message 表） */
export interface ChatHistoryMessage {
  id?: string
  role: 'user' | 'assistant'
  content?: string | null
  ragContext?: string | null
  createTime?: string | null
}

/** 拉取会话历史消息（刷新后恢复对话用） */
export function getConversationMessages(conversationId: string): Promise<Result<ChatHistoryMessage[]>> {
  return get<ChatHistoryMessage[]>('/chat/conversations/' + conversationId + '/messages')
}

/** 清除对话历史 */
export function clearConversation(conversationId: string): Promise<Result<void>> {
  return post<void>('/chat/clear', { conversationId: conversationId || 'default' })
}
