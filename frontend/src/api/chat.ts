import { post } from './request'
import type { Result } from './types'

/** SSE 流式数据帧（后端 /api/chat/ask） */
export interface ChatStreamFrame {
  type: 'token' | 'end' | 'error'
  content?: string
}

/** 流式回调 */
export interface ChatCallbacks {
  onToken?: (content: string) => void
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
  onError?: (msg: string) => void
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
                  onToken?.(parsed.content || '')
                } else if (parsed.type === 'error') {
                  onError?.(parsed.content || '')
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

/** 清除对话历史 */
export function clearConversation(conversationId: string): Promise<Result<void>> {
  return post<void>('/chat/clear', { conversationId: conversationId || 'default' })
}
