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
      <div v-if="visible" class="chat-panel">
        <!-- 头部 -->
        <div class="chat-header">
          <div class="chat-header-left">
            <svg class="header-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
            </svg>
            <span>智能客服</span>
          </div>
          <div class="chat-header-actions">
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
            <div class="welcome-icon">🤖</div>
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
              {{ msg.role === 'user' ? '👤' : '🤖' }}
            </div>
            <div class="message-bubble">
              <div v-if="msg.loading" class="message-loading">
                <span class="dot-pulse"></span>
              </div>
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

<script setup>
import { ref, nextTick, watch, onUnmounted } from 'vue'
import { postAsk, clearConversation } from '@/api/chat'

// 生成会话ID（持久化到 SessionStorage）
const conversationId = 'chat-' + (sessionStorage.getItem('chat_conv_id') || (() => {
  const id = Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
  sessionStorage.setItem('chat_conv_id', id)
  return id
})())

const visible = ref(false)
const messages = ref([])
const inputText = ref('')
const loading = ref(false)
const unreadCount = ref(0)
const messagesRef = ref(null)
const inputRef = ref(null)

const quickQuestions = [
  '推荐热门商品',
  '帮我找手机',
  '有什么优惠活动吗'
]

function openChat() {
  visible.value = true
  unreadCount.value = 0
  nextTick(() => {
    scrollToBottom()
    inputRef.value?.focus()
  })
}

function closeChat() {
  visible.value = false
  // 有未读新消息才计数
  const lastMsg = messages.value[messages.value.length - 1]
  if (lastMsg && lastMsg.role === 'assistant' && !lastMsg.isRead) {
    unreadCount.value += 1
    lastMsg.isRead = false
  }
}

function renderContent(text) {
  if (!text) return ''
  // 简单处理：换行转 <br>
  return text.replace(/\n/g, '<br>')
}

function scrollToBottom() {
  nextTick(() => {
    const el = messagesRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

function addMessage(role, content, loading = false) {
  messages.value.push({ role, content, loading, isRead: true })
  scrollToBottom()
}

function sendMessage(text) {
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
  background: var(--ds-coral, #e94560);
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 20px rgba(233, 69, 96, 0.35);
  transition: transform 0.3s cubic-bezier(0.34, 1.56, 0.64, 1), box-shadow 0.3s;
  position: relative;
  color: #fff;
}
.chat-fab:hover {
  transform: scale(1.08);
  box-shadow: 0 6px 28px rgba(233, 69, 96, 0.45);
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
}

.chat-slide-enter-active,
.chat-slide-leave-active {
  transition: all 0.3s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.chat-slide-enter-from,
.chat-slide-leave-to {
  opacity: 0;
  transform: scale(0.6) translateY(40px);
}

/* ====== 头部 ====== */
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  background: var(--ds-navy, #1a1a2e);
  color: #fff;
  flex-shrink: 0;
}
.chat-header-left {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
}
.header-icon { width: 20px; height: 20px; }
.chat-header-actions { display: flex; gap: 6px; }
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
  background: #f8f7f4;
  scroll-behavior: smooth;
}
.chat-messages::-webkit-scrollbar { width: 4px; }
.chat-messages::-webkit-scrollbar-thumb { background: #ddd; border-radius: 2px; }

/* 欢迎区 */
.chat-welcome {
  text-align: center;
  padding: 32px 16px;
}
.welcome-icon { font-size: 48px; margin-bottom: 12px; }
.welcome-title { font-size: 16px; font-weight: 600; color: #1a1a2e; margin-bottom: 4px; }
.welcome-desc { font-size: 13px; color: #999; margin-bottom: 20px; }
.quick-questions { display: flex; flex-direction: column; gap: 8px; max-width: 220px; margin: 0 auto; }
.quick-btn {
  padding: 8px 16px;
  border: 1.5px solid var(--ds-border-light, #e0ddd5);
  border-radius: 20px;
  background: #fff;
  font-size: 13px;
  color: var(--ds-navy, #1a1a2e);
  cursor: pointer;
  transition: all 0.2s;
}
.quick-btn:hover {
  border-color: var(--ds-coral, #e94560);
  color: var(--ds-coral, #e94560);
  background: rgba(233, 69, 96, 0.04);
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
.message-avatar { font-size: 24px; flex-shrink: 0; }
.message-bubble {
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.6;
  word-break: break-word;
}
.message-user .message-bubble {
  background: var(--ds-coral, #e94560);
  color: #fff;
  border-bottom-right-radius: 4px;
}
.message-ai .message-bubble {
  background: #fff;
  color: #1a1a2e;
  border-bottom-left-radius: 4px;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.06);
}

.message-content { white-space: pre-wrap; }

/* loading 动画 */
.message-loading { padding: 4px 0; }
.dot-pulse {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #999;
  animation: pulse 1.2s infinite ease-in-out;
}
@keyframes pulse {
  0%, 80%, 100% { opacity: 0.3; transform: scale(0.8); }
  40% { opacity: 1; transform: scale(1); }
}

/* ====== 输入区 ====== */
.chat-input-area {
  padding: 12px 14px;
  border-top: 1px solid #f0eee9;
  background: #fff;
  flex-shrink: 0;
}
.chat-input-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #f5f3ef;
  border-radius: 24px;
  padding: 4px 4px 4px 16px;
}
.chat-input {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  font-size: 14px;
  color: #1a1a2e;
  height: 36px;
}
.chat-input::placeholder { color: #bbb; }
.send-btn {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--ds-coral, #e94560);
  border: none;
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  flex-shrink: 0;
}
.send-btn:hover { background: #d63a52; }
.send-btn:disabled { opacity: 0.4; cursor: not-allowed; }

/* ====== 响应式 ====== */
@media (max-width: 480px) {
  .chat-panel {
    position: fixed;
    inset: 0;
    width: 100%;
    height: 100%;
    border-radius: 0;
  }
  .floating-chat { bottom: 16px; right: 16px; }
}
</style>
