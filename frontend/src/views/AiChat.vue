<script setup>
import { ref, watch, nextTick } from 'vue'
import { api } from '../api'
import { ElMessage } from 'element-plus'
import AiSettings from '../components/AiSettings.vue'

const settingsVisible = ref(false)
const isAdmin = localStorage.getItem('erp_role') === 'ADMIN'

const AGENT_TYPE_NAMES = {
  PURCHASE: '采购入库', SALE: '销售出库', PURCHASE_RETURN: '采购退货',
  SALE_RETURN: '销售退货', LOSS: '报损', GAIN: '盘盈', TRANSFER: '调拨'
}

// 历史对话按操作员分开存 localStorage，切页面/重启浏览器都不丢
const userKey = localStorage.getItem('erp_user') || 'default'
const role = localStorage.getItem('erp_role') || 'OPERATOR'
const HISTORY_KEY = 'ai_chat_history_' + userKey
const MODE_KEY = 'ai_chat_mode_' + userKey

function restore() {
  try {
    const arr = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]')
    return Array.isArray(arr) ? arr : []
  } catch { return [] }
}

async function postDraft(m) {
  try {
    const r = m.draft.peerPurchaseId
      ? await api.postWithPeer(m.draft.id)
      : await api.postBill(m.draft.id)
    ElMessage.success(r.message)
    m.text = (m.text || '') + '\n✅ 草稿 ' + m.draft.billNo + ' 已过账'
    m.draft = null
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '过账失败')
  }
}

const input = ref('')
const loading = ref(false)
const mode = ref(localStorage.getItem(MODE_KEY) || 'kb') // agent=智能开单 / kb=知识库问答 / query=查经营数据 / chat=自由问答
const msgs = ref(restore())
const msgsEl = ref(null)
// 当前实际生效的模型来源（API / 本地 / 网页版）
const source = ref(null)

async function loadSource() {
  try { source.value = await api.aiSource() } catch { source.value = null }
}
loadSource()

watch(msgs, () => {
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(msgs.value.slice(-200))) } catch {}
}, { deep: true })
watch(mode, m => localStorage.setItem(MODE_KEY, m))

function clearHistory() {
  msgs.value = []
  localStorage.removeItem(HISTORY_KEY)
}
function scrollBottom() {
  nextTick(() => msgsEl.value?.scrollTo({ top: msgsEl.value.scrollHeight }))
}

async function send() {
  const q = input.value.trim()
  if (!q) return
  msgs.value.push({ role: 'user', text: q })
  input.value = ''
  loading.value = true
  scrollBottom()
  try {
    if (mode.value === 'agent') {
      const r = await api.agent(q)
      if (r.error) { msgs.value.push({ role: 'ai', text: r.error }) }
      else {
        msgs.value.push({ role: 'ai', text: r.reply, draft: r.draft })
      }
    } else if (mode.value === 'kb') {
      const r = await api.kbAsk(q)
      msgs.value.push({
        role: 'ai',
        text: r.answer || '(无结果)',
        sources: r.sources && r.sources.length ? r.sources : null
      })
    } else if (mode.value === 'query') {
      const r = await api.aiQuery(q)
      msgs.value.push({
        role: 'ai',
        text: r.error || r.summary || '(无结果)',
        sql: r.sql,
        rows: r.rows
      })
    } else {
      const r = await api.aiChat(q)
      // 智能助手：开单时后端会带 draft，聊天页内直接出确认过账卡片
      msgs.value.push({ role: 'ai', text: r.answer, draft: r.draft })
    }
  } catch (e) {
    msgs.value.push({ role: 'ai', text: '调用失败：' + (e.response?.data?.error || e.message) })
  }
  loading.value = false
  scrollBottom()
}
</script>

<template>
  <div class="chat-wrap">
    <div class="identity-bar">
      <span>👤 {{ userKey }}（{{ role === 'ADMIN' ? '管理员' : '操作员' }}）</span>
      <span class="sep">｜</span>
      <span>AI 以<b>你的身份与权限</b>执行操作：可查经营/库存/应收应付/年检/同行货源，可开草稿单（过账需你确认）；无权的事会明确告知</span>
    </div>
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px">
      <el-radio-group v-model="mode">
        <el-radio-button value="agent">智能开单（Agent）</el-radio-button>
        <el-radio-button value="kb">知识库问答</el-radio-button>
        <el-radio-button value="query">查经营数据（自动生成SQL）</el-radio-button>
        <el-radio-button value="chat">智能助手（能查数据/能办单）</el-radio-button>
      </el-radio-group>
      <el-space>
        <el-tooltip v-if="source?.label"
          :content="source.fallbackReason ? `当前来源：${source.label}（${source.fallbackReason}）` : `当前来源：${source.label}（${source.model}）`"
          placement="left">
          <el-tag size="small" :type="source.provider === 'webchat' ? 'warning' : source.provider === 'none' ? 'danger' : 'success'">
            {{ source.label }}
          </el-tag>
        </el-tooltip>
        <el-button size="small" @click="clearHistory">清空记录</el-button>
        <el-tooltip content="选择模型厂家 / 填 API Key / 切本地 Ollama / 网页版模型" placement="left">
          <el-button v-if="isAdmin" circle @click="settingsVisible = true"><el-icon><Setting /></el-icon></el-button>
        </el-tooltip>
      </el-space>
    </div>
    <AiSettings v-model:visible="settingsVisible" @saved="loadSource" />

    <div class="msgs" ref="msgsEl">
      <el-empty v-if="!msgs.length" description="智能开单试试：把低于安全库存的商品生成一张补货采购单，挂账给光明乳业 / 帮我把10个灭火器调到二号仓" />
      <div v-for="(m, i) in msgs" :key="i" :class="['bubble', m.role]">
        <div class="text" style="white-space: pre-wrap">{{ m.text }}</div>
        <div v-if="m.sources" class="sql">来源：{{ m.sources.join('、') }}</div>
        <el-card v-if="m.draft" shadow="never" style="margin-top:10px">
          <div style="font-weight:bold;margin-bottom:6px">
            📋 草稿单 {{ m.draft.billNo }}（{{ AGENT_TYPE_NAMES[m.draft.type] || m.draft.type }}）
            <span v-if="m.draft.partnerName">｜{{ m.draft.partnerName }}</span>
            <el-tag v-if="m.draft.paid === 0" type="danger" size="small" style="margin:0 4px">挂账</el-tag>
            <el-tag v-if="m.draft.peerPurchaseNo" type="warning" size="small" style="margin:0 4px">
              同行调货·采购草稿 {{ m.draft.peerPurchaseNo }}</el-tag>
            待人工确认
          </div>
          <el-table :data="m.draft.items" size="small" border>
            <el-table-column prop="productId" label="商品ID" width="80" />
            <el-table-column prop="quantity" label="数量" width="80" />
            <el-table-column prop="price" label="单价" width="90" />
          </el-table>
          <div style="display:flex;justify-content:space-between;align-items:center;margin-top:8px">
            <span style="color:#909399;font-size:12px">确认过账前不会影响库存</span>
            <el-button type="primary" size="small" @click="postDraft(m)">确认过账</el-button>
          </div>
        </el-card>
        <div v-if="m.sql" class="sql">SQL: {{ m.sql }}</div>
        <el-table v-if="m.rows && m.rows.length" :data="m.rows" size="small" border max-height="200" style="margin-top:8px">
          <el-table-column v-for="col in Object.keys(m.rows[0])" :key="col" :prop="col" :label="col" />
        </el-table>
      </div>
      <div v-if="loading" class="bubble ai">
        {{ source?.provider === 'webchat' ? '思考中（网页版模型响应较慢，首次可能需要浏览器登录，请耐心等待）...' : '思考中...' }}
      </div>
    </div>

    <div class="input-bar">
      <el-input v-model="input" placeholder="用自然语言提问，回车发送" @keyup.enter="send" clearable />
      <el-button type="primary" :loading="loading" @click="send">发送</el-button>
    </div>
  </div>
</template>

<style scoped>
.chat-wrap { max-width: 900px; }
.msgs { height: 55vh; overflow-y: auto; padding: 4px; }
.bubble { max-width: 85%; padding: 10px 14px; border-radius: 10px; margin-bottom: 10px; font-size: 14px; }
.bubble.user { background: #409eff; color: #fff; margin-left: auto; }
.bubble.ai { background: #fff; border: 1px solid #e4e7ed; }
.sql { margin-top: 6px; font-size: 12px; color: #909399; word-break: break-all; }
.input-bar { display: flex; gap: 10px; margin-top: 10px; }
.identity-bar {
  background: linear-gradient(90deg, #eff6ff, #f0fdf4);
  border: 1px solid var(--erp-border);
  border-radius: 8px;
  padding: 8px 14px;
  font-size: 12px;
  color: #475467;
  margin-bottom: 10px;
}
.identity-bar .sep { color: #d0d5dd; margin: 0 6px; }
.identity-bar b { color: #2563eb; }
</style>
