<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exportSheets } from '../utils/export'
import * as XLSX from 'xlsx'

// ==================== 平台卡片 + 通道配置 ====================
const platforms = ref([])
const cfgDialog = ref(false)
const cfgForm = ref({ platform: '', shopName: '', appKey: '', appSecret: '', callback: '' })
const cfgChannelId = ref(null)

async function loadPlatforms() {
  platforms.value = await api.integrationPlatforms()
}
function openCfg(p) {
  const ch = p.channel || {}
  let cfg = {}
  try { cfg = JSON.parse(ch.configJson || '{}') } catch {}
  cfgForm.value = {
    platform: p.platform, name: p.name,
    shopName: ch.shopName || '', appKey: cfg.appKey || '',
    appSecret: cfg.appSecret || '', callback: cfg.callback || ''
  }
  cfgChannelId.value = ch.id || null
  cfgDialog.value = true
}
async function saveCfg() {
  try {
    await api.saveIntegrationChannel(cfgForm.value)
    ElMessage.success('通道配置已保存在本机')
    cfgDialog.value = false
    loadPlatforms()
  } catch (e) { ElMessage.error(e.response?.data?.error || '保存失败') }
}
async function testCfg(p) {
  if (!p.channel?.id) { ElMessage.warning('请先保存配置'); return }
  const r = await api.testIntegrationChannel(p.channel.id)
  r.ok ? ElMessage.success(r.message) : ElMessageBox.alert(r.message, '测试结果', { type: 'info' }).catch(() => {})
}

// ==================== 订单 Excel 导入 ====================
const PLATFORM_PREFIX = { TAOBAO: 'TB', PDD: 'PDD', DOUYIN: 'DY', MEITUAN: 'MT' }
const importPlatform = ref('TAOBAO')
const parsed = ref([])          // 解析出的订单 [{orderNo, orderTime, buyer, remark, items:[]}]
const parsedCount = ref(0)
const importResult = ref(null)
const orders = ref([])

function downloadTemplate() {
  exportSheets('平台订单导入模板.xlsx', [
    { name: '订单明细', rows: [
      { 平台单号: 'TB100001', 下单时间: '2026-09-01 10:30:00', 商品名称: '农夫山泉550ml', 数量: 2, 单价: 2, 买家: '张三', 备注: '' },
      { 平台单号: 'TB100001', 下单时间: '2026-09-01 10:30:00', 商品名称: '乐事薯片原味', 数量: 1, 单价: 5, 买家: '张三', 备注: '' },
      { 平台单号: 'TB100002', 下单时间: '2026-09-01 14:00:00', 商品名称: '可口可乐330ml', 数量: 12, 单价: 3, 买家: '李四', 备注: '' }
    ] }
  ])
  ElMessage.success('模板已下载：同一单号的多个商品行会合并为一张销售单')
}

async function onFileChange(e) {
  const file = e.target.files[0]
  if (!file) return
  try {
    const buf = await file.arrayBuffer()
    const wb = XLSX.read(buf)
    const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]])
    // 按 平台单号 合并商品行 → 订单
    const map = new Map()
    let bad = 0
    for (const r of rows) {
      const orderNo = String(r['平台单号'] ?? '').trim()
      const name = String(r['商品名称'] ?? '').trim()
      const qty = Number(r['数量']) || 0
      const price = Number(r['单价']) || 0
      if (!orderNo || !name || qty <= 0) { bad++; continue }
      if (!map.has(orderNo)) map.set(orderNo, {
        orderNo, orderTime: String(r['下单时间'] ?? ''), buyer: String(r['买家'] ?? ''),
        remark: String(r['备注'] ?? ''), items: []
      })
      map.get(orderNo).items.push({ productName: name, quantity: qty, price })
    }
    parsed.value = [...map.values()]
    parsedCount.value = bad
    importResult.value = null
    if (!parsed.value.length) { ElMessage.warning('没有解析出有效订单，请检查模板列名'); parsed.value = []; return }
    ElMessage.success(`解析出 ${parsed.value.length} 张订单${bad ? `，跳过 ${bad} 行无效数据` : ''}，确认后开始导入`)
  } catch (err) {
    ElMessage.error('Excel 解析失败：' + err.message)
  }
  e.target.value = ''
}

async function doImport() {
  if (!parsed.value.length) { ElMessage.warning('请先选择并解析订单 Excel'); return }
  try {
    const r = await api.importPlatformOrders({ platform: importPlatform.value, orders: parsed.value })
    importResult.value = r
    ElMessage.success(r.message)
    loadOrders(); loadPlatforms()
  } catch (e) { ElMessage.error(e.response?.data?.error || '导入失败') }
}
async function clearParsed() {
  await ElMessageBox.confirm('清空已解析未导入的订单？', '确认', { type: 'warning' })
  parsed.value = []; importResult.value = null
}

async function loadOrders() { orders.value = await api.platformOrders() }

onMounted(() => { loadPlatforms(); loadOrders() })
</script>

<template>
  <!-- ==================== 平台接入卡片 ==================== -->
  <el-card shadow="hover" style="margin-bottom:16px">
    <template #header><b>平台对接</b>
      <span style="color:#909399;font-size:12px;margin-left:8px">API 自动拉单入口已预留（配置密钥后待平台开通）；现阶段可用下方订单 Excel 导入</span>
    </template>
    <el-row :gutter="12">
      <el-col v-for="p in platforms" :key="p.platform" :span="6">
        <el-card shadow="never" style="margin-bottom:12px">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">
            <b>{{ p.name }}</b>
            <el-tag size="small" :type="p.channel?.status === 'ENABLED' ? 'success' : 'info'">
              {{ p.channel?.status === 'ENABLED' ? '已配置' : '未接入' }}
            </el-tag>
          </div>
          <div style="color:#909399;font-size:12px;margin-bottom:10px">
            {{ p.channel?.shopName || '尚未配置店铺' }}
          </div>
          <el-space>
            <el-button size="small" type="primary" plain @click="openCfg(p)">配置密钥</el-button>
            <el-button size="small" :disabled="!p.channel?.id" @click="testCfg(p)">测试</el-button>
          </el-space>
        </el-card>
      </el-col>
    </el-row>
  </el-card>

  <!-- ==================== 订单 Excel 导入 ==================== -->
  <el-card shadow="hover" style="margin-bottom:16px">
    <template #header><b>平台订单导入</b>
      <span style="color:#909399;font-size:12px;margin-left:8px">把平台后台导出的订单整理成模板格式，导入即自动建商品（缺的）、开销售单并生成财务凭证</span>
    </template>
    <el-space wrap style="margin-bottom:12px">
      <el-select v-model="importPlatform" style="width:140px">
        <el-option v-for="p in platforms" :key="p.platform" :value="p.platform" :label="p.name" />
      </el-select>
      <el-button @click="downloadTemplate">下载模板</el-button>
      <el-upload :show-file-list="false" accept=".xlsx,.xls" :auto-upload="false" @change="onFileChange">
        <el-button type="primary">选择订单 Excel</el-button>
      </el-upload>
      <el-button type="success" :disabled="!parsed.length" @click="doImport">确认导入（{{ parsed.length }} 单）</el-button>
      <el-button v-if="parsed.length" @click="clearParsed">清空</el-button>
    </el-space>

    <el-alert v-if="parsedCount" type="warning" :closable="false"
      :title="`有 ${parsedCount} 行数据因缺少平台单号/商品名/数量被跳过`" style="margin-bottom:10px" />

    <el-table v-if="parsed.length" :data="parsed" size="small" border max-height="220" style="margin-bottom:10px">
      <el-table-column prop="orderNo" label="平台单号" width="150" />
      <el-table-column prop="orderTime" label="下单时间" width="160" />
      <el-table-column label="商品" min-width="200">
        <template #default="{ row }">{{ row.items.map(i => `${i.productName}×${i.quantity}`).join('、') }}</template>
      </el-table-column>
      <el-table-column label="行数" width="60">
        <template #default="{ row }">{{ row.items.length }}</template>
      </el-table-column>
      <el-table-column prop="buyer" label="买家" width="120" />
    </el-table>

    <el-descriptions v-if="importResult" :title="importResult.message" :column="4" border size="small" style="margin-bottom:8px">
      <el-descriptions-item label="成功">{{ importResult.imported }} 单</el-descriptions-item>
      <el-descriptions-item label="重复跳过">{{ importResult.duplicated }} 单</el-descriptions-item>
      <el-descriptions-item label="失败">{{ importResult.failed }} 单</el-descriptions-item>
      <el-descriptions-item label="新建商品">{{ importResult.newProducts }} 个</el-descriptions-item>
    </el-descriptions>
    <el-table v-if="importResult?.errors?.length" :data="importResult.errors" size="small" border>
      <el-table-column prop="orderNo" label="失败单号" width="160" />
      <el-table-column prop="error" label="原因" min-width="240" />
    </el-table>
    <el-alert type="info" :closable="false" title="自动建档的商品成本价默认等于售价（毛利按 0 计，不虚报），请到商品管理修正成本价以准确核算毛利"
      style="margin-top:8px" />
  </el-card>

  <!-- ==================== 已导入订单 ==================== -->
  <el-card shadow="hover">
    <template #header><b>已导入订单</b>（最近 200 条）</template>
    <el-table :data="orders" size="small" border stripe max-height="320" empty-text="还没有导入过平台订单">
      <el-table-column prop="platformOrderNo" label="平台单号" width="160" />
      <el-table-column label="平台" width="90">
        <template #default="{ row }">{{ platforms.find(p => p.platform === row.platform)?.name || row.platform }}</template>
      </el-table-column>
      <el-table-column prop="buyer" label="买家" width="120" />
      <el-table-column prop="orderTime" label="下单时间" width="160" />
      <el-table-column label="本地销售单" width="170">
        <template #default="{ row }">{{ row.billNo || '-' }}</template>
      </el-table-column>
      <el-table-column label="金额" width="100">
        <template #default="{ row }">{{ row.billAmount != null ? '￥' + Number(row.billAmount).toFixed(2) : '-' }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="导入时间" min-width="160" />
    </el-table>
  </el-card>

  <el-dialog v-model="cfgDialog" :title="`配置 ${cfgForm.name} 通道`" width="480px">
    <el-form :model="cfgForm" label-width="90px">
      <el-form-item label="店铺名称"><el-input v-model="cfgForm.shopName" placeholder="如：某某旗舰店" /></el-form-item>
      <el-form-item label="AppKey"><el-input v-model="cfgForm.appKey" placeholder="平台开放平台申请" /></el-form-item>
      <el-form-item label="AppSecret"><el-input v-model="cfgForm.appSecret" show-password placeholder="仅保存在本机" /></el-form-item>
      <el-form-item label="回调地址"><el-input v-model="cfgForm.callback" placeholder="选填" /></el-form-item>
    </el-form>
    <el-alert type="info" :closable="false" title="密钥只保存在本机数据库，不会上传到任何服务器" style="margin-bottom:10px" />
    <template #footer>
      <el-button @click="cfgDialog = false">取消</el-button>
      <el-button type="primary" @click="saveCfg">保存</el-button>
    </template>
  </el-dialog>
</template>
