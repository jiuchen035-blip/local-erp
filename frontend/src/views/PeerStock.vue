<script setup>
// 同行库存：从同行处可调到的货源目录（与自己商品库存分开）。
// 支持 Excel 批量导入 / 手动新增 / 编辑删除 / 按同行·分类·SKU·商品名筛选。
import { ref, computed, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exportExcel } from '../utils/export'
import * as XLSX from 'xlsx'

const list = ref([])
const partners = ref([])
const filterPartner = ref(null)
const filterCategory = ref(null)
const keyword = ref('')
const loading = ref(false)
const page = ref(1)
const pageSize = 50
const dialog = ref(false)
const form = ref({})

const categories = computed(() => [...new Set(list.value.map(x => x.category).filter(Boolean))])

async function load() {
  loading.value = true
  try {
    list.value = await api.peerStock(filterPartner.value || null, filterCategory.value || null, keyword.value.trim())
    page.value = 1
  } finally { loading.value = false }
}
const paged = computed(() => list.value.slice((page.value - 1) * pageSize, page.value * pageSize))

function add() { form.value = { partnerName: '', productName: '', category: '', lastPrice: null, unit: '个' }; dialog.value = true }
function edit(row) { form.value = { ...row }; dialog.value = true }
async function save() {
  if (!form.value.productName?.trim()) { ElMessage.warning('商品名称必填'); return }
  const data = { ...form.value }
  // allow-create 下拉输新同行名时 v-model 是字符串 → 转为 partnerName 让后端自动建档
  if (typeof data.partnerId === 'string') { data.partnerName = data.partnerId.trim(); delete data.partnerId }
  if (!data.partnerId && !data.partnerName) { ElMessage.warning('请选择或输入同行名称'); return }
  try {
    if (form.value.id) await api.updatePeerStock(form.value.id, data)
    else await api.addPeerStock(data)
    ElMessage.success('已保存')
    dialog.value = false
    load(); partners.value = await api.partners('SUPPLIER')
  } catch (e) { ElMessage.error(e.response?.data?.error || '保存失败') }
}
function del(row) {
  ElMessageBox.confirm(`删除同行库存记录「${row.partnerName} - ${row.productName}」？`, '确认', { type: 'warning' })
    .then(() => api.deletePeerStock(row.id))
    .then(() => { ElMessage.success('已删除'); load() })
    .catch(() => {})
}

// ---------- Excel 批量导入（固定列：同行名称 / 商品名称 / 调货价 / 分类 / 单位） ----------
const importing = ref(false)
const importResult = ref(null)
function downloadTemplate() {
  exportExcel('同行库存导入模板.xlsx', '同行库存', [
    { 同行名称: '示例：老王同行', 商品名称: '示例：百事可乐600ml', 调货价: 2.8, 分类: '饮料/碳酸饮料', 单位: '瓶' }
  ])
}
async function onImportFile(e) {
  const file = e.target.files[0]
  if (!file) return
  try {
    const buf = await file.arrayBuffer()
    const wb = XLSX.read(buf)
    const rows = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { defval: '' })
    if (!rows.length) { ElMessage.warning('表格里没有数据行'); e.target.value = ''; return }
    importing.value = true
    const r = await api.importPeerStock(rows)
    importResult.value = r
    ElMessage.success(r.message)
    load()
  } catch (err) { ElMessage.error('解析失败：' + err.message) }
  importing.value = false
  e.target.value = ''
}

onMounted(async () => {
  load()
  try { partners.value = await api.partners('SUPPLIER') } catch { /* 忽略 */ }
})
</script>

<template>
  <el-space wrap style="margin-bottom:14px">
    <el-select v-model="filterPartner" clearable filterable placeholder="按同行筛选" style="width:180px" @change="load">
      <el-option v-for="p in partners" :key="p.id" :label="p.name" :value="p.id" />
    </el-select>
    <el-select v-model="filterCategory" clearable filterable allow-create placeholder="按分类筛选" style="width:150px" @change="load">
      <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
    </el-select>
    <el-input v-model="keyword" placeholder="搜 SKU / 商品名称" clearable style="width:190px" @keyup.enter="load" @clear="load" />
    <el-button @click="load">查询</el-button>
    <label class="up-btn">
      <input type="file" accept=".xlsx,.xls" style="display:none" @change="onImportFile" />
      <el-button type="warning" plain :loading="importing">Excel 批量导入</el-button>
    </label>
    <el-button @click="downloadTemplate">下载模板</el-button>
    <el-button type="primary" @click="add">新增</el-button>
    <el-button type="success" plain @click="doExport">导出 Excel</el-button>
  </el-space>

  <el-alert v-if="importResult" :type="importResult.failed ? 'warning' : 'success'" :closable="false" show-icon
    style="margin-bottom:12px" :title="importResult.message">
    <div v-for="(er, i) in importResult.errors" :key="i" style="font-size:12px">{{ er }}</div>
  </el-alert>

  <el-table :data="paged" border stripe v-loading="loading">
    <el-table-column prop="partnerName" label="同行名称" min-width="130" />
    <el-table-column prop="productName" label="商品名称" min-width="160" />
    <el-table-column prop="sku" label="SKU（自动生成）" width="130" />
    <el-table-column label="分类" min-width="120">
      <template #default="{ row }">{{ row.category || '-' }}</template>
    </el-table-column>
    <el-table-column label="调货价" width="95">
      <template #default="{ row }">￥{{ row.lastPrice?.toFixed(2) }}</template>
    </el-table-column>
    <el-table-column prop="unit" label="单位" width="70" />
    <el-table-column prop="createdAt" label="录入时间" width="165" />
    <el-table-column label="操作" width="130" fixed="right">
      <template #default="{ row }">
        <el-button size="small" @click="edit(row)">编辑</el-button>
        <el-button size="small" type="danger" plain @click="del(row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
  <el-pagination v-model:current-page="page" :page-size="pageSize" :total="list.length"
    layout="total, prev, pager, next" style="margin-top:10px;justify-content:flex-end" />

  <el-dialog v-model="dialog" :title="form.id ? '编辑同行库存' : '新增同行库存'" width="520px">
    <el-form :model="form" label-width="90px">
      <el-form-item label="同行名称">
        <el-select v-model="form.partnerId" filterable allow-create default-first-option placeholder="选择或输入新同行名称"
          style="width:100%">
          <el-option v-for="p in partners" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <div style="font-size:12px;color:#909399">输入新名称保存时自动创建供应商档案</div>
      </el-form-item>
      <el-form-item label="商品名称"><el-input v-model="form.productName" placeholder="必填" /></el-form-item>
      <el-form-item label="分类"><el-input v-model="form.category" placeholder="可留空；支持 饮料/碳酸饮料" /></el-form-item>
      <el-form-item label="调货价"><el-input-number v-model="form.lastPrice" :min="0" :precision="2" style="width:100%" /></el-form-item>
      <el-form-item label="单位"><el-input v-model="form.unit" style="width:120px" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.up-btn { cursor: pointer; }
</style>
