<script setup>
import { ref, watch, nextTick } from 'vue'
import { api } from '../api'
import { ElMessage } from 'element-plus'

const visible = defineModel('visible', { type: Boolean, default: false })
const emit = defineEmits(['saved'])

const PRESETS_META = {
  auto: { name: '自动（推荐）', keyUrl: '' },
  deepseek: { name: 'DeepSeek', keyUrl: 'https://platform.deepseek.com' },
  zhipu: { name: '智谱GLM', keyUrl: 'https://open.bigmodel.cn' },
  qwen: { name: '通义千问', keyUrl: 'https://bailian.console.aliyun.com' },
  moonshot: { name: 'Kimi月之暗面', keyUrl: 'https://platform.moonshot.cn' },
  siliconflow: { name: '硅基流动', keyUrl: 'https://siliconflow.cn' },
  openai: { name: 'OpenAI', keyUrl: 'https://platform.openai.com' },
  ollama: { name: 'Ollama本地', keyUrl: 'https://ollama.com' },
  webchat: { name: '网页版模型（免API）', keyUrl: '' },
  custom: { name: '自定义', keyUrl: '' }
}

const form = ref({ provider: 'auto', baseUrl: '', model: '', apiKey: '', embeddingEnabled: false, embeddingModel: '' })
const settings = ref({})
const models = ref([])
const modelSource = ref('')
const modelHint = ref('')
const modelsLoading = ref(false)
const testing = ref(false)
const testResult = ref(null)
const saving = ref(false)
// webllm 桥接（网页版模型）
const bridge = ref({})
const bridgeLoading = ref(false)
const loginPending = ref({})

watch(visible, (v) => { if (v) open() })

async function open() {
  testResult.value = null
  try {
    const s = await api.getAiSettings()
    settings.value = s
    form.value = {
      provider: s.provider || 'auto',
      baseUrl: s.baseUrl || '',
      model: s.model || '',
      apiKey: '',
      embeddingEnabled: s.embedding?.enabled || false,
      embeddingModel: s.embedding?.model || ''
    }
    loadModels()
    loadBridge()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '读取AI设置失败')
  }
}

function onProviderChange() {
  const preset = (settings.value.presets || []).find(p => p.key === form.value.provider)
  if (preset && form.value.provider !== 'custom') {
    // baseUrl 用后端预设（表单不显示给标准厂家）
    form.value.model = ''
  }
  models.value = []
  modelHint.value = ''
  loadModels()
  loadBridge()
}

async function loadModels() {
  modelsLoading.value = true
  modelHint.value = ''
  try {
    const r = await api.fetchAiModels(form.value.provider, form.value.apiKey, form.value.provider === 'custom' ? form.value.baseUrl : undefined)
    models.value = r.models || []
    modelSource.value = r.source
    if (r.hint) modelHint.value = r.hint
    else if (form.value.provider === 'webchat') modelHint.value = '可用网页模型（选其一，默认第一个）'
    else if (r.source === 'preset') modelHint.value = '在线拉取失败，以下为内置常见模型清单（也可手动输入其他型号）'
    else if (r.source === 'live' && form.value.provider === 'ollama') modelHint.value = '已从本地 Ollama 拉取模型列表'
  } catch (e) { /* 静默，可用手输 */ }
  modelsLoading.value = false
}

// 统一提取后端/网关错误文本（后端 error 字段、FastAPI detail 字段、HTTP 状态）
function errMsg(e) {
  return e.response?.data?.error || e.response?.data?.detail
    || (e.response ? `HTTP ${e.response.status}` : e.message) || '未知错误'
}

// ---------- webllm 桥接（网页版模型） ----------
async function loadBridge() {
  if (form.value.provider !== 'webchat') return
  bridgeLoading.value = true
  try { bridge.value = await api.webllmStatus() } catch { bridge.value = {} }
  bridgeLoading.value = false
}

async function startBridge() {
  try {
    const r = await api.webllmStart()
    if (r.ok) { ElMessage.success('桥接服务已启动'); loadBridge() }
    else ElMessage.error(r.error || '启动失败')
  } catch (e) {
    ElMessage.error('启动失败：' + errMsg(e))
  }
}

async function openLogin(site) {
  loginPending.value[site] = true
  try {
    await api.webllmLogin(site)
    ElMessage.info('登录窗口已打开，请在 5 分钟内完成登录')
    pollLogin(site, 60)
  } catch (e) {
    loginPending.value[site] = false
    ElMessage.error('打开登录窗口失败：' + errMsg(e))
  }
}

function pollLogin(site, left) {
  if (left <= 0) { loginPending.value[site] = false; return }
  setTimeout(async () => {
    try {
      const r = await api.webllmLoginResult(site)
      if (r.done) {
        loginPending.value[site] = false
        ElMessage({ type: String(r.message).includes('成功') ? 'success' : 'warning', message: r.message, duration: 5000 })
      } else pollLogin(site, left - 5)
    } catch { loginPending.value[site] = false }
  }, 5000)
}

async function testConnection() {
  if (!form.value.model && form.value.provider !== 'auto') { ElMessage.warning('请先选择或输入模型'); return }
  testing.value = true
  testResult.value = null
  try {
    const r = await api.testAi({
      provider: form.value.provider,
      baseUrl: form.value.provider === 'custom' ? form.value.baseUrl : undefined,
      apiKey: form.value.apiKey || undefined,
      model: form.value.model
    })
    testResult.value = r
  } catch (e) {
    testResult.value = { ok: false, error: errMsg(e) }
  }
  testing.value = false
}

async function save() {
  if (form.value.provider !== 'auto' && !form.value.model) { ElMessage.warning('请先选择或输入模型'); return }
  if (form.value.provider === 'custom' && !form.value.baseUrl) { ElMessage.warning('自定义厂家需填写 base-url'); return }
  saving.value = true
  try {
    await api.saveAiSettings({ ...form.value })
    ElMessage.success('AI 设置已保存并立即生效')
    visible.value = false
    emit('saved')
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '保存失败')
  }
  saving.value = false
}
</script>

<template>
  <el-dialog v-model="visible" title="AI 模型设置" width="560px">
    <el-form :model="form" label-width="100px">
      <el-form-item label="模型厂家">
        <el-select v-model="form.provider" style="width:100%" @change="onProviderChange">
          <el-option v-for="(p, k) in PRESETS_META" :key="k" :label="p.name" :value="k" />
        </el-select>
      </el-form-item>

      <el-form-item v-if="form.provider === 'custom'" label="Base URL">
        <el-input v-model="form.baseUrl" placeholder="如 https://xx.com/v1（OpenAI兼容）" />
      </el-form-item>

      <el-form-item v-if="form.provider === 'ollama'" label="本地服务">
        <el-alert type="info" :closable="false" show-icon
          title="Ollama 免 API Key。请确保已启动：ollama serve，并已拉取模型：ollama pull qwen2.5:7b" />
      </el-form-item>

      <el-form-item v-else-if="form.provider === 'webchat'" label="桥接服务">
        <div style="width:100%">
          <el-alert type="info" :closable="false" show-icon style="margin-bottom:8px"
            title="免 API Key：通过本机桥接服务驱动已登录的网页版大模型。首次使用需安装 Python 依赖并登录一次；响应比 API 慢，同一网站同时只处理一个请求。" />
          <el-space wrap style="margin-bottom:8px">
            <el-tag :type="bridge.running ? 'success' : 'danger'">{{ bridge.running ? '桥接服务运行中' : '桥接服务未运行' }}</el-tag>
            <el-button size="small" :loading="bridgeLoading" @click="loadBridge">刷新状态</el-button>
            <el-button v-if="!bridge.running" size="small" type="primary" @click="startBridge">启动服务</el-button>
          </el-space>
          <el-space wrap>
            <span style="font-size:12px;color:#909399">首次使用请登录网页（登录态会记住）：</span>
            <el-button size="small" :loading="loginPending.deepseek" @click="openLogin('deepseek')">DeepSeek 登录</el-button>
            <el-button size="small" :loading="loginPending.miaoxiang" @click="openLogin('miaoxiang')">妙想登录</el-button>
          </el-space>
          <div style="font-size:12px;color:#909399;margin-top:6px">
            环境准备（仅首次）：安装 Python 3.10+，在 webllm 目录执行 pip install -r requirements.txt 后再执行 playwright install chromium
          </div>
        </div>
      </el-form-item>

      <el-form-item v-else-if="form.provider === 'auto'" label="自动模式">
        <el-alert type="info" :closable="false" show-icon style="width:100%"
          title="自动降级：填了有效 API Key 就走云端 API → 否则本地 Ollama 在线时走本地模型 → 都没有时自动使用网页版模型（免 API）。无需选择模型。" />
      </el-form-item>

      <el-form-item v-else label="API Key">
        <el-input v-model="form.apiKey" type="password" show-password
          :placeholder="settings.hasKey ? `已保存（${settings.keyMasked}），留空则不修改` : `输入 ${PRESETS_META[form.provider]?.name || ''} 的 API Key`" />
        <div v-if="PRESETS_META[form.provider]?.keyUrl" style="font-size:12px;color:#909399;margin-top:2px">
          获取地址：<a :href="PRESETS_META[form.provider].keyUrl" target="_blank">{{ PRESETS_META[form.provider].keyUrl }}</a>
        </div>
      </el-form-item>

      <el-form-item v-if="form.provider !== 'auto'" label="对话模型">
        <el-select v-model="form.model" filterable allow-create default-first-option :loading="modelsLoading"
          placeholder="拉取中… 或直接输入模型名" style="width:100%">
          <el-option v-for="m in models" :key="m" :label="m" :value="m" />
        </el-select>
        <div style="font-size:12px;color:#909399;margin-top:2px">
          <span v-if="modelSource === 'live'">✓ 已在线拉取 {{ models.length }} 个可用模型</span>
          <span v-else-if="modelHint">{{ modelHint }}</span>
          <el-button link size="small" @click="loadModels">重新拉取</el-button>
        </div>
      </el-form-item>

      <el-divider style="margin:8px 0">知识库向量模型（可选）</el-divider>
      <el-form-item label="启用语义检索">
        <el-switch v-model="form.embeddingEnabled" />
        <span style="font-size:12px;color:#909399;margin-left:8px">不启用时知识库用关键词检索</span>
      </el-form-item>
      <el-form-item v-if="form.embeddingEnabled" label="向量模型">
        <el-select v-model="form.embeddingModel" filterable allow-create default-first-option style="width:100%"
          placeholder="如 embedding-3（智谱）/ BAAI/bge-m3（硅基流动、Ollama）">
          <el-option v-for="m in models.filter(x => /embed|bge/i.test(x))" :key="m" :label="m" :value="m" />
        </el-select>
      </el-form-item>

      <el-alert v-if="testResult" :type="testResult.ok ? 'success' : 'error'" :closable="false" show-icon
        :title="testResult.ok ? `连接成功！模型回复：${testResult.reply}` : `连接失败：${testResult.error}`" />
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button :loading="testing" @click="testConnection">测试连接</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存并生效</el-button>
    </template>
  </el-dialog>
</template>
