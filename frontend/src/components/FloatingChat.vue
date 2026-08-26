<template>
  <div class="floating-chat">
    <!-- 浮动按钮 -->
    <button v-if="!visible" class="chat-fab" @click="openChat" :title="unreadCount > 0 ? `未读: ${unreadCount}` : '智能客服'">
      <svg class="fab-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
      </svg>
      <span v-if="unreadCount > 0" class="fab-badge">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
    </button>

    <!-- 聊天面板 -->
    <transition name="chat-slide">
      <div
        v-if="visible"
        class="chat-panel"
        :class="{
          'is-zoomed': isZoomed,
          'is-dragging': isDragging
        }"
        :style="panelStyle"
      >
        <!-- 头部（拖拽手柄） -->
        <div class="chat-header" @mousedown.prevent="startDrag">
          <div class="chat-header-left">
            <span class="drag-indicator">⠿</span>
            <svg class="header-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
            </svg>
            <span>智能客服</span>
          </div>
          <div class="chat-header-actions">
            <button class="header-btn" :title="isZoomed ? '还原大小' : '放大'" @click="toggleZoom">
              <svg v-if="!isZoomed" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                <polyline points="15 3 21 3 21 9"/>
                <polyline points="9 21 3 21 3 15"/>
                <line x1="21" y1="3" x2="14" y2="10"/>
                <line x1="3" y1="21" x2="10" y2="14"/>
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                <polyline points="4 14 10 14 10 20"/>
                <polyline points="20 10 14 10 14 4"/>
                <line x1="14" y1="10" x2="21" y2="3"/>
                <line x1="3" y1="21" x2="10" y2="14"/>
              </svg>
            </button>
            <button class="header-btn" title="清空对话" @click="clearChat">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                <polyline points="3 6 5 6 21 6"/>
                <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>
              </svg>
            </button>
            <button class="header-btn" title="关闭" @click="closeChat">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
                <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
              </svg>
            </button>
          </div>
        </div>

        <!-- 消息列表 -->
        <div class="chat-messages" ref="messagesRef">
          <div v-if="messages.length === 0" class="chat-welcome">
            <div class="welcome-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <rect x="4" y="7" width="16" height="12" rx="2"/>
                <circle cx="12" cy="3.5" r="1.5"/>
                <circle cx="9" cy="12" r="1.1" fill="currentColor" stroke="none"/>
                <circle cx="15" cy="12" r="1.1" fill="currentColor" stroke="none"/>
                <path d="M9 16h6"/>
              </svg>
            </div>
            <div class="welcome-title">你好！我是智能客服助手</div>
            <div class="welcome-desc">我可以帮你推荐商品、解答问题</div>
            <div class="quick-questions">
              <button
                v-for="(q, i) in quickQuestions"
                :key="i"
                class="quick-btn"
                @click="sendMessage(q)"
                :disabled="loading"
              >{{ q }}</button>
            </div>
          </div>

          <div
            v-for="(msg, i) in messages"
            :key="i"
            class="message"
            :class="[msg.role === 'user' ? 'message-user' : 'message-ai']"
          >
            <div class="message-avatar">
              <svg v-if="msg.role === 'user'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <circle cx="12" cy="8" r="4"/>
                <path d="M4 20c1.5-3.5 4.5-5 8-5s6.5 1.5 8 5"/>
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                <rect x="4" y="7" width="16" height="12" rx="2"/>
                <circle cx="12" cy="3.5" r="1.5"/>
                <circle cx="9" cy="12" r="1.1" fill="currentColor" stroke="none"/>
                <circle cx="15" cy="12" r="1.1" fill="currentColor" stroke="none"/>
                <path d="M9 16h6"/>
              </svg>
            </div>
            <div class="message-bubble">
              <div v-if="msg.loading && !msg.content" class="message-loading">
                <span class="dot-pulse"></span>
                <span class="dot-pulse"></span>
                <span class="dot-pulse"></span>
              </div>
              <div v-else-if="msg.loading && msg.content" class="message-content streaming" v-html="renderContent(msg.content)"></div>
              <div v-else class="message-content" v-html="renderContent(msg.content)"></div>
            </div>
          </div>
        </div>

        <!-- 输入区 -->
        <div class="chat-input-area">
          <div class="chat-input-wrap">
            <input
              ref="inputRef"
              v-model="inputText"
              class="chat-input"
              placeholder="输入消息..."
              @keyup.enter="sendCurrentMessage"
              :disabled="loading"
            />
            <button
              class="send-btn"
              @click="sendCurrentMessage"
              :disabled="loading || !inputText.trim()"
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
                <line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/>
              </svg>
            </button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, watch, onMounted, onUnmounted } from 'vue'
import { postAsk, clearConversation } from '@/api/chat'

/** 聊天消息 */
interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  loading: boolean
  isRead: boolean
}

// 生成会话ID（持久化到 SessionStorage）。
// 注意：后端 chat_conversation.id 为 Long，前端必须传数字字符串（历史 'chat-' 前缀会 NumberFormatException）
const conversationId = sessionStorage.getItem('chat_conv_id') || (() => {
  const id = String(Date.now()) + String(Math.floor(Math.random() * 1000))
  sessionStorage.setItem('chat_conv_id', id)
  return id
})()

const visible = ref(false)
const messages = ref<ChatMessage[]>([])
const inputText = ref('')
const loading = ref(false)
const unreadCount = ref(0)
const messagesRef = ref<HTMLElement | null>(null)
const inputRef = ref<HTMLInputElement | null>(null)

// ====== 拖拽状态 ======
const dragOffset = ref({ x: 0, y: 0 })
const isDragging = ref(false)
const dragStart = ref({ x: 0, y: 0 })
const initialOffset = ref({ x: 0, y: 0 })

// ====== 放大状态 ======
const isZoomed = ref(false)

const panelStyle = computed(() => {
  if (dragOffset.value.x !== 0 || dragOffset.value.y !== 0) {
    return { transform: `translate(${dragOffset.value.x}px, ${dragOffset.value.y}px)` }
  }
  return {}
})

function startDrag(e: MouseEvent) {
  // 只响应左键
  if (e.button !== 0) return
  isDragging.value = true
  dragStart.value = { x: e.clientX, y: e.clientY }
  initialOffset.value = { ...dragOffset.value }
}

function onDragMove(e: MouseEvent) {
  if (!isDragging.value) return
  const dx = e.clientX - dragStart.value.x
  const dy = e.clientY - dragStart.value.y
  dragOffset.value = {
    x: initialOffset.value.x + dx,
    y: initialOffset.value.y + dy
  }
}

function stopDrag() {
  isDragging.value = false
}

function toggleZoom() {
  isZoomed.value = !isZoomed.value
}

const quickQuestions = [
  '推荐热门商品',
  '帮我找手机',
  '有什么优惠活动吗'
]

function openChat() {
  // 智能客服为登录后功能：未登录引导登录
  if (!localStorage.getItem('token')) {
    window.location.href = '/login'
    return
  }
  visible.value = true
  unreadCount.value = 0
  nextTick(() => {
    scrollToBottom()
    inputRef.value?.focus()
  })
}

function closeChat() {
  visible.value = false
  // 还原拖拽位置
  dragOffset.value = { x: 0, y: 0 }
  // 有未读新消息才计数
  const lastMsg = messages.value[messages.value.length - 1]
  if (lastMsg && lastMsg.role === 'assistant' && !lastMsg.isRead) {
    unreadCount.value += 1
    lastMsg.isRead = false
  }
}

/**
 * 将 AI 返回文本渲染为带基本 Markdown 样式的 HTML
 */
function renderContent(text: string): string {
  if (!text) return ''

  // 1) 转义 HTML 防 XSS
  let html = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')

  // 2) 行内样式：粗体、斜体
  html = html
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/__(.+?)__/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')

  // 3) 按行解析列表
  const lines = html.split('\n')
  const result = []
  let inUl = false
  let inOl = false

  for (const line of lines) {
    const ulMatch = line.match(/^[-*]\s+(.+)/)
    const olMatch = line.match(/^\d+[.、]\s+(.+)/)

    if (ulMatch) {
      if (!inUl) { result.push('<ul>'); inUl = true }
      if (inOl) { result.push('</ol>'); inOl = false }
      result.push(`<li>${ulMatch[1]}</li>`)
    } else if (olMatch) {
      if (!inOl) { result.push('<ol>'); inOl = true }
      if (inUl) { result.push('</ul>'); inUl = false }
      result.push(`<li>${olMatch[1]}</li>`)
    } else {
      if (inUl) { result.push('</ul>'); inUl = false }
      if (inOl) { result.push('</ol>'); inOl = false }
      if (line.trim()) {
        result.push(`<p>${line}</p>`)
      } else {
        result.push('<br>')
      }
    }
  }
  if (inUl) result.push('</ul>')
  if (inOl) result.push('</ol>')

  return result.join('')
}

function scrollToBottom() {
  nextTick(() => {
    const el = messagesRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function addMessage(role: ChatMessage['role'], content: string, isLoading = false) {
  messages.value.push({ role, content, loading: isLoading, isRead: true })
  scrollToBottom()
}

function sendMessage(text: string) {
  if (!text.trim() || loading.value) return
  inputText.value = ''

  // 添加用户消息
  addMessage('user', text)

  // 添加 AI 占位（loading 态）
  addMessage('assistant', '', true)
  loading.value = true

  let fullContent = ''

  postAsk(
    text,
    conversationId,
    // onToken
    (token) => {
      fullContent += token
      const last = messages.value[messages.value.length - 1]
      if (last && last.loading) {
        last.content = fullContent
        scrollToBottom()
      }
    },
    // onEnd
    () => {
      const last = messages.value[messages.value.length - 1]
      if (last) {
        last.loading = false
        last.isRead = false
      }
      loading.value = false
      scrollToBottom()
    },
    // onError
    (err) => {
      const last = messages.value[messages.value.length - 1]
      if (last && last.loading) {
        last.content = err || '抱歉，服务出错了，请稍后重试'
        last.loading = false
      }
      loading.value = false
      scrollToBottom()
    }
  )
}

function sendCurrentMessage() {
  if (!inputText.value.trim() || loading.value) return
  sendMessage(inputText.value.trim())
}

function clearChat() {
  messages.value = []
  loading.value = false
  clearConversation(conversationId).catch(() => {})
}

// 全局拖拽监听
onMounted(() => {
  document.addEventListener('mousemove', onDragMove)
  document.addEventListener('mouseup', stopDrag)
})

onUnmounted(() => {
  document.removeEventListener('mousemove', onDragMove)
  document.removeEventListener('mouseup', stopDrag)
})

// 监听新消息自动滚动
watch(messages, () => { scrollToBottom() }, { deep: true })
</script>

<style scoped>
/* ====== 浮动按钮 ====== */
.floating-chat {
  position: fixed;
  bottom: 28px;
  right: 28px;
  z-index: 9999;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}

.chat-fab {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: var(--ds-primary, #2d7ff9);
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 20px rgba(45, 127, 249, 0.35);
  transition: transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.3s;
  position: relative;
  color: #fff;
}
.chat-fab:hover {
  transform: scale(1.08);
  box-shadow: 0 6px 28px rgba(45, 127, 249, 0.45);
}
.fab-icon { width: 26px; height: 26px; }
.fab-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  background: #ff4757;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  min-width: 20px;
  height: 20px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 4px;
  box-shadow: 0 2px 6px rgba(255, 71, 87, 0.4);
}

/* ====== 聊天面板 ====== */
.chat-panel {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 380px;
  height: 540px;
  background: #fff;
  border-radius: 18px;
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  transform-origin: bottom right;
  /* 放大/缩小时过渡宽度和高度 */
  transition: width 0.3s cubic-bezier(0.34, 1.56, 0.64, 1),
              height 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.chat-panel.is-zoomed {
  width: 540px;
  height: 680px;
}
.chat-panel.is-dragging {
  transition: none; /* 拖拽时禁用所有过渡，避免卡顿 */
  box-shadow: 0 16px 60px rgba(0, 0, 0, 0.22);
}

.chat-slide-enter-active,
.chat-slide-leave-active {
  transition: opacity 0.3s cubic-bezier(0.34, 1.56, 0.64, 1),
              transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.chat-slide-enter-from,
.chat-slide-leave-to {
  opacity: 0;
  transform: scale(0.6) translateY(40px);
}

/* ====== 头部（拖拽手柄） ====== */
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: var(--ds-text, #1e2632);
  color: #fff;
  flex-shrink: 0;
  cursor: grab;
  user-select: none;
}
.chat-header:active { cursor: grabbing; }
.chat-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  min-width: 0;
}
.drag-indicator {
  font-size: 14px;
  opacity: 0.5;
  margin-right: 2px;
  letter-spacing: 2px;
}
.header-icon { width: 18px; height: 18px; flex-shrink: 0; }
.chat-header-actions { display: flex; gap: 4px; }
.header-btn {
  background: rgba(255, 255, 255, 0.1);
  border: none;
  color: rgba(255, 255, 255, 0.7);
  cursor: pointer;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}
.header-btn:hover { background: rgba(255, 255, 255, 0.2); color: #fff; }

/* ====== 消息列表 ====== */
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: var(--ds-bg, #f6f7fa);
  scroll-behavior: smooth;
}
.chat-messages::-webkit-scrollbar { width: 4px; }
.chat-messages::-webkit-scrollbar-thumb { background: #ddd; border-radius: 2px; }

/* 欢迎区 */
.chat-welcome {
  text-align: center;
  padding: 32px 16px;
}
.welcome-icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 12px;
  border-radius: 50%;
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.1));
  color: var(--ds-primary, #2d7ff9);
  display: flex;
  align-items: center;
  justify-content: center;
}
.welcome-icon svg { width: 30px; height: 30px; }
.welcome-title { font-size: 16px; font-weight: 600; color: var(--ds-text, #1e2632); margin-bottom: 4px; }
.welcome-desc { font-size: 13px; color: #999; margin-bottom: 20px; }
.quick-questions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  max-width: 280px;
  margin: 0 auto;
}
.quick-btn {
  padding: 7px 16px;
  border: 1.5px solid var(--ds-line-2, #d8dce4);
  border-radius: 20px;
  background: #fff;
  font-size: 13px;
  color: var(--ds-text, #1e2632);
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}
.quick-btn:hover {
  border-color: var(--ds-primary, #2d7ff9);
  color: var(--ds-primary, #2d7ff9);
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.08));
}
.quick-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 消息气泡 */
.message {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
  align-items: flex-start;
}
.message-user { flex-direction: row-reverse; }
.message-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-top: 2px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.message-avatar svg { width: 18px; height: 18px; }
.message-ai .message-avatar {
  background: var(--ds-primary-tint, rgba(45, 127, 249, 0.12));
  color: var(--ds-primary, #2d7ff9);
}
.message-user .message-avatar {
  background: rgba(30, 38, 50, 0.08);
  color: var(--ds-text, #1e2632);
}
.message-bubble {
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.65;
  word-break: break-word;
}
.message-user .message-bubble {
  background: var(--ds-primary, #2d7ff9);
  color: #fff;
  border-bottom-right-radius: 4px;
}
.message-ai .message-bubble {
  background: #fff;
  color: var(--ds-text, #1e2632);
  border-bottom-left-radius: 4px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

/* 消息内容增强样式（针对渲染的 Markdown） */
.message-content { line-height: 1.7; }
.message-content :deep(p) {
  margin: 4px 0;
}
.message-content :deep(p:first-child) {
  margin-top: 0;
}
.message-content :deep(p:last-child) {
  margin-bottom: 0;
}
.message-content :deep(ul),
.message-content :deep(ol) {
  margin: 6px 0;
  padding-left: 20px;
}
.message-content :deep(li) {
  margin: 3px 0;
}
.message-content :deep(strong) {
  font-weight: 600;
}
.message-user .message-content :deep(strong) {
  color: rgba(255, 255, 255, 0.95);
}
.message-ai .message-content :deep(strong) {
  color: var(--ds-primary, #2d7ff9);
}

/* 流式输出光标 */
.message-content.streaming::after {
  content: '▊';
  display: inline;
  animation: blink-cursor 0.8s step-end infinite;
  color: var(--ds-primary, #2d7ff9);
  font-size: 14px;
  line-height: 1;
  margin-left: 1px;
  vertical-align: text-top;
}
.message-user .message-content.streaming::after {
  color: rgba(255, 255, 255, 0.8);
}

/* loading 动画 */
.message-loading { padding: 4px 0; display: flex; gap: 4px; align-items: center; }
.dot-pulse {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ds-primary, #2d7ff9);
  animation: pulse 1.4s infinite ease-in-out both;
}
.dot-pulse:nth-child(1) { animation-delay: -0.32s; }
.dot-pulse:nth-child(2) { animation-delay: -0.16s; }
.dot-pulse:nth-child(3) { animation-delay: 0s; }

/* ====== 输入区 ====== */
.chat-input-area {
  padding: 12px 14px;
  border-top: 1px solid var(--ds-line, #e7e9ee);
  background: #fff;
  flex-shrink: 0;
}
.chat-input-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--ds-bg-2, #eef0f4);
  border-radius: 24px;
  padding: 4px 4px 4px 16px;
}
.chat-input {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  font-size: 14px;
  color: var(--ds-text, #1e2632);
  height: 36px;
}
.chat-input::placeholder { color: #bbb; }
.send-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--ds-primary, #2d7ff9);
  border: none;
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  flex-shrink: 0;
}
.send-btn:hover { background: var(--ds-primary-strong, #1e63d6); }
.send-btn:disabled { opacity: 0.4; cursor: not-allowed; }

/* ====== 动画关键帧 ====== */
@keyframes pulse {
  0%, 80%, 100% { opacity: 0; transform: scale(0.6); }
  40% { opacity: 1; transform: scale(1); }
}
@keyframes blink-cursor {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

/* ====== 响应式 ====== */
@media (max-width: 480px) {
  .chat-panel {
    position: fixed;
    inset: 0;
    width: 100%;
    height: 100%;
    border-radius: 0;
  }
  .chat-panel.is-zoomed {
    width: 100%;
    height: 100%;
  }
  .floating-chat { bottom: 16px; right: 16px; }
}
</style>
