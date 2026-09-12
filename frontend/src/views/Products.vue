<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exportExcel } from '../utils/export'
import * as XLSX from 'xlsx'

const all = ref([])                 // 全部商品（一次拉取，前端按视图过滤）
const keyword = ref('')
const viewMode = ref('cat')         // cat=分类浏览（默认）/ all=显示所有商品
const sel = ref({ l1: '', l2: '', l3: '' })   // 分类浏览当前路径
const catDict = ref({ categories: [], subOf: {}, sub2Of: {} })
const dialog = ref(false)
const form = ref({})
const byWarehouse = ref([])

async function load() {
  all.value = await api.products()
  byWarehouse.value = await api.stockByWarehouse()
}
async function loadCats() { catDict.value = await api.productCategories() }

const l2Options = computed(() => catDict.value.subOf[sel.value.l1] || [])
const l3Options = computed(() => catDict.value.sub2Of[`${sel.value.l1}/${sel.value.l2}`] || [])

function inScope(p) {
  const s = sel.value
  // 与小程序一致：逐层只显示"直属"商品，子分类里的商品通过磁贴下钻查看
  if (!s.l1) return !p.category                       // 根层级：只平铺未分类商品
  if (p.category !== s.l1) return false
  if (!s.l2) return !(p.subCategory || '')            // 一级层：直属商品
  if ((p.subCategory || '') !== s.l2) return false
  if (!s.l3) return !(p.sub2Category || '')           // 二级层：直属商品
  return (p.sub2Category || '') === s.l3
}
function matchKeyword(p) {
  const k = keyword.value.trim()
  if (!k) return true
  return (p.name || '').includes(k) || (p.sku || '').includes(k) || (p.barcode || '').includes(k)
    || (p.spec || '').includes(k)
    || (p.subCategory || '').includes(k) || (p.sub2Category || '').includes(k)
}
// 搜索时忽略分类层级（全局找），避免在根层级搜不到已分类商品
const searching = computed(() => keyword.value.trim().length > 0)
const filtered = computed(() =>
  all.value.filter(p => (viewMode.value === 'all' || searching.value || inScope(p)) && matchKeyword(p)))
// 分页渲染：上千行 el-table 会卡，每页只渲染 100 行
const page = ref(1)
const pageSize = 100
watch([filtered, viewMode, sel, keyword], () => { page.value = 1 })
const list = computed(() => filtered.value.slice((page.value - 1) * pageSize, page.value * pageSize))

// 当前层级应显示的子分类磁贴（含商品数）
// 注意：统计数量时不能用 inScope —— inScope 只保留"直属商品"（没有下一级分类的），
// 与"统计某个子分类下有多少商品"互相矛盾，会导致二/三级磁贴恒为 0。
// 统计口径应为"属于该分类（含其下所有更细分类）的商品数"，与一级磁贴保持一致。
const tiles = computed(() => {
  const s = sel.value
  const matchCount = (level, name) => all.value.filter(p => {
    if (level === 1) return p.category === name
    if (level === 2) return p.category === s.l1 && (p.subCategory || '') === name
    return p.category === s.l1 && (p.subCategory || '') === s.l2 && (p.sub2Category || '') === name
  }).length

  if (!s.l1) return catDict.value.categories.map(c => ({ name: c, count: matchCount(1, c) }))
  if (!s.l2) return l2Options.value.map(c => ({ name: c, count: matchCount(2, c) }))
  if (!s.l3) return l3Options.value.map(c => ({ name: c, count: matchCount(3, c) }))
  return []
})
function openTile(t) {
  if (!sel.value.l1) sel.value.l1 = t.name
  else if (!sel.value.l2) sel.value.l2 = t.name
  else sel.value.l3 = t.name
}
function crumbTo(i) {
  if (i === 0) sel.value = { l1: '', l2: '', l3: '' }
  else if (i === 1) sel.value = { l1: sel.value.l1, l2: '', l3: '' }
  else if (i === 2) sel.value = { l1: sel.value.l1, l2: sel.value.l2, l3: '' }
}

function stockDetail(row) {
  const parts = byWarehouse.value
    .filter(r => r.productId === row.id && r.quantity !== 0)
    .map(r => `${r.warehouseName}:${r.quantity}`)
  return parts.length ? parts.join(' / ') : '-'
}
function add() {
  form.value = { name: '', sku: '', barcode: '', spec: '', category: sel.value.l1 || '', subCategory: sel.value.l2 || '', sub2Category: sel.value.l3 || '',
    costPrice: 0, salePrice: 0, wholesalePrice: null, memberPrice: null,
    unit: '个', bigUnit: '', bigUnitRate: null, shelfLifeDays: null, safeStock: 10, taxRate: null, noAlert: 0 }
  skuAuto.value = true
  dialog.value = true
  regenSku()
}
function edit(row) { form.value = { ...row, noAlert: row.noAlert ? 1 : 0 }; skuAuto.value = false; dialog.value = true }

// SKU 自动编号：新增时按 一级缩写-二级编号+三级编号+序号 自动预填；用户手改过就不再覆盖
const skuAuto = ref(true)
async function regenSku() {
  if (form.value.id || !form.value.category) return
  try {
    const r = await api.nextSku(form.value.category, form.value.subCategory || '', form.value.sub2Category || '')
    if (r.sku && skuAuto.value) form.value.sku = r.sku
  } catch {}
}
watch(() => [form.value.category, form.value.subCategory, form.value.sub2Category],
  () => { if (skuAuto.value && dialog.value && !form.value.id && form.value.category) regenSku() })
async function save() {
  if (!form.value.name) { ElMessage.warning('商品名称必填'); return }
  try {
    // noAlert 统一以 0/1 提交（后端为整型；以前传布尔会导致保存静默失败）
    await api.saveProduct({ ...form.value, noAlert: form.value.noAlert ? 1 : 0 })
    dialog.value = false
    ElMessage.success('已保存')
    load(); loadCats()
  } catch (e) { ElMessage.error(e.response?.data?.error || '保存失败') }
}
function del(row) {
  ElMessageBox.confirm(`删除商品「${row.name}」？`, '确认', { type: 'warning' })
    .then(() => api.deleteProduct(row.id))
    .then(() => { ElMessage.success('已删除'); load() })
    .catch(() => {})
}
function imgUrl(row) { return row.hasImage ? `/api/images/${row.id}?t=${Date.now()}` : '' }

async function onImageChange(row, uploadFile) {
  // el-upload 的 change 回调给的是 UploadFile 对象，真实文件在 .raw（兼容旧的原生事件形态）
  const file = uploadFile?.raw || uploadFile?.target?.files?.[0]
  if (!file) return
  const fd = new FormData()
  fd.append('file', file)
  try {
    const r = await api.uploadImage(row.id, fd)
    ElMessage.success(r.message)
    load()
  } catch (err) { ElMessage.error(err.response?.data?.error || '上传失败') }
}
function doExport() {
  exportExcel(`商品档案_${new Date().toLocaleDateString('zh-CN')}.xlsx`, '商品档案',
    filtered.value.map(p => ({
      商品名称: p.name, SKU: p.sku, 条码: p.barcode || '', 规格: p.spec || '',
      分类: [p.category, p.subCategory, p.sub2Category].filter(Boolean).join(' / '),
      基本单位: p.unit || '个',
      大件单位: p.bigUnit || '', 换算: p.bigUnitRate ? `1${p.bigUnit}=${p.bigUnitRate}${p.unit || '个'}` : '',
      成本价: p.costPrice, 加权均价: p.avgCost, 零售价: p.salePrice,
      批发价: p.wholesalePrice ?? '', 会员价: p.memberPrice ?? '',
      总库存: p.stock, 分仓库明细: stockDetail(p), 安全库存: p.safeStock,
      保质期天数: p.shelfLifeDays ?? ''
    })))
}
const sub2Options = computed(() => catDict.value.sub2Of[`${form.value.category}/${form.value.subCategory}`] || [])

// ---------- 分类管理（重命名/删除，删除=清空引用该分类的商品的对应字段） ----------
const catMgr = ref(false)
const catTree = ref([])
function openCatMgr() {
  const counts1 = {}, counts2 = {}, counts3 = {}
  all.value.forEach(p => {
    if (p.category) counts1[p.category] = (counts1[p.category] || 0) + 1
    if (p.category && p.subCategory) {
      const k2 = p.category
      counts2[k2 + '/' + p.subCategory] = (counts2[k2 + '/' + p.subCategory] || 0) + 1
    }
    if (p.category && p.subCategory && p.sub2Category)
      counts3[p.category + '/' + p.subCategory + '/' + p.sub2Category] =
        (counts3[p.category + '/' + p.subCategory + '/' + p.sub2Category] || 0) + 1
  })
  // ⚠️ subOf[l1] / sub2Of["一级/二级"] 后端返回的是「字符串数组」，本身就是分类名列表，
  // 直接遍历即可。之前误用 Object.keys(数组) 取到的是下标 "0","1","2"…，
  // 所以二级/三级分类名显示成了数字、商品数恒为 0，重命名/删除也会传错名字。
  catTree.value = Object.keys(counts1).map(l1 => ({
    name: l1, level: 1, count: counts1[l1], path: {},
    children: (catDict.value.subOf[l1] || []).map(l2 => ({
      name: l2, level: 2, count: counts2[l1 + '/' + l2] || 0, path: { l1 },
      children: (catDict.value.sub2Of[l1 + '/' + l2] || []).map(l3 => ({
        name: l3, level: 3, count: counts3[l1 + '/' + l2 + '/' + l3] || 0, path: { l1, l2 }
      }))
    }))
  }))
  catMgr.value = true
}
async function renameCat(node) {
  const { value } = await ElMessageBox.prompt(`将「${node.name}」重命名为：`, '重命名分类',
    { inputValue: node.name, inputPattern: /\S/, inputErrorMessage: '名称不能为空' })
  const r = await api.applyCategory({ level: node.level, ...node.path, oldName: node.name, newName: value.trim() })
  ElMessage.success(r.message)
  load(); loadCats(); openCatMgr()
}
async function deleteCat(node) {
  await ElMessageBox.confirm(
    `删除分类「${node.name}」？该分类下 ${node.count} 个商品的此分类字段将被清空（商品本身不受影响）。`,
    '删除分类', { type: 'warning' })
  const r = await api.applyCategory({ level: node.level, ...node.path, oldName: node.name, newName: '' })
  ElMessage.success(r.message)
  load(); loadCats(); openCatMgr()
}

// ---------- 批量导入（SheetJS 解析任意 ERP 导出表 → 列映射 → 导入） ----------
const importDialog = ref(false)
const importFileRef = ref(null)
const importing = ref(false)
const rawHeaders = ref([])
const rawRows = ref([])
const mapping = ref({})
const batchCat = ref({ l1: '', l2: '', l3: '' })
const skuRegen = ref(false)
const dedup = ref('skip')
const importResult = ref(null)
const TARGET_FIELDS = [
  { key: 'name', label: '商品名称（必填）', required: true, guess: ['名称', '品名', '商品名', '商品'] },
  { key: 'sku', label: 'SKU 编码', guess: ['sku', '编码', '货号', '商品编码'] },
  { key: 'barcode', label: '条码', guess: ['条码', '条形码'] },
  { key: 'spec', label: '规格', guess: ['规格', '型号'] },
  { key: 'category', label: '一级分类（或 a/b/c 整串）', guess: ['一级分类', '分类', '类别', '品类'] },
  { key: 'subCategory', label: '二级分类', guess: ['二级分类', '子分类'] },
  { key: 'sub2Category', label: '三级分类', guess: ['三级分类'] },
  { key: 'unit', label: '基本单位', guess: ['单位', '基本单位', '计量单位'] },
  { key: 'costPrice', label: '成本价', guess: ['成本', '成本价', '进价', '采购价'] },
  { key: 'salePrice', label: '零售价', guess: ['零售价', '售价', '单价', '零售'] },
  { key: 'wholesalePrice', label: '批发价', guess: ['批发价', '批发'] },
  { key: 'memberPrice', label: '会员价', guess: ['会员价', '会员'] },
  { key: 'safeStock', label: '安全库存', guess: ['安全库存'] },
  { key: 'stock', label: '期初库存', guess: ['库存', '期初库存', '数量', '现存'] },
  { key: 'bigUnit', label: '大件单位', guess: ['大件单位', '大单位'] },
  { key: 'bigUnitRate', label: '换算率', guess: ['换算', '换算率'] }
]
function openImport() { importDialog.value = true }
async function onImportFile(e) {
  const file = e.target.files[0]
  if (!file) return
  try {
    const buf = await file.arrayBuffer()
    const wb = XLSX.read(buf)
    const ws = wb.Sheets[wb.SheetNames[0]]
    const json = XLSX.utils.sheet_to_json(ws, { defval: '' })
    if (!json.length) { ElMessage.warning('表格里没有数据行'); return }
    rawHeaders.value = Object.keys(json[0])
    rawRows.value = json
    // 自动猜映射
    const auto = {}
    TARGET_FIELDS.forEach(f => {
      const hit = rawHeaders.value.find(h => f.guess.some(g => String(h).toLowerCase().includes(g.toLowerCase())))
      if (hit) auto[f.key] = hit
    })
    mapping.value = auto
    importResult.value = null
    ElMessage.success(`解析到 ${json.length} 行数据，请检查列映射`)
  } catch (err) { ElMessage.error('解析失败：' + err.message) }
  e.target.value = ''
}
const mappedRows = computed(() => rawRows.value.map(r => {
  const g = f => (mapping.value[f] ? String(r[mapping.value[f]] ?? '').trim() : '')
  // 二三级分类可来自独立列，也可由一级列的 a/b/c 整串拆出（独立列优先）
  let category = g('category'), sub = g('subCategory'), sub2 = g('sub2Category')
  if (category.includes('/')) {
    const parts = category.split('/').map(x => x.trim())
    category = parts[0] || ''; sub = sub || parts[1] || ''; sub2 = sub2 || parts[2] || ''
  }
  const numOrNull = s => { const v = parseFloat(s); return isNaN(v) ? null : v }
  return {
    name: g('name'), sku: g('sku'), barcode: g('barcode'), spec: g('spec'),
    category: category || batchCat.value.l1 || '',
    subCategory: sub || batchCat.value.l2 || '',
    sub2Category: sub2 || batchCat.value.l3 || '',
    unit: g('unit'), costPrice: numOrNull(g('costPrice')), salePrice: numOrNull(g('salePrice')),
    wholesalePrice: numOrNull(g('wholesalePrice')), memberPrice: numOrNull(g('memberPrice')),
    safeStock: numOrNull(g('safeStock')), bigUnit: g('bigUnit'), bigUnitRate: numOrNull(g('bigUnitRate')),
    stock: g('stock')
  }
}).filter(r => r.name))
const missingName = computed(() => !mapping.value.name)
async function doImport() {
  if (missingName.value) { ElMessage.warning('请先映射「商品名称」列'); return }
  if (!mappedRows.value.length) { ElMessage.warning('没有有效的数据行'); return }
  importing.value = true
  try {
    const r = await api.importProducts({ rows: mappedRows.value, skuRegen: skuRegen.value, dedup: dedup.value })
    importResult.value = r
    ElMessage.success(r.message)
    load(); loadCats()
  } catch (e) { ElMessage.error(e.response?.data?.error || '导入失败') }
  importing.value = false
}
onMounted(() => { load(); loadCats() })
</script>

<template>
  <el-space wrap style="margin-bottom:14px">
    <el-input v-model="keyword" placeholder="搜索 名称/SKU/条码/分类" clearable style="width:220px" />
    <el-switch v-model="viewMode" active-value="all" inactive-value="cat"
      active-text="显示所有商品" inactive-text="分类浏览" style="--el-switch-on-color:#409eff" />
    <el-button @click="load(); loadCats()">刷新</el-button>
    <el-button @click="openCatMgr">分类管理</el-button>
    <el-button type="warning" plain @click="openImport">批量导入</el-button>
    <el-button type="primary" @click="add">新增商品</el-button>
    <el-button type="success" plain @click="doExport">导出 Excel</el-button>
  </el-space>

  <!-- 分类浏览：面包屑 + 子分类磁贴 + 当前范围商品 -->
  <template v-if="viewMode === 'cat'">
    <div style="margin-bottom:10px;display:flex;align-items:center;gap:6px;flex-wrap:wrap">
      <el-breadcrumb separator=">">
        <el-breadcrumb-item><el-link :underline="false" @click="crumbTo(0)">全部分类</el-link></el-breadcrumb-item>
        <el-breadcrumb-item v-if="sel.l1"><el-link :underline="false" @click="crumbTo(1)">{{ sel.l1 }}</el-link></el-breadcrumb-item>
        <el-breadcrumb-item v-if="sel.l2"><el-link :underline="false" @click="crumbTo(2)">{{ sel.l2 }}</el-link></el-breadcrumb-item>
        <el-breadcrumb-item v-if="sel.l3">{{ sel.l3 }}</el-breadcrumb-item>
      </el-breadcrumb>
      <span style="font-size:12px;color:#909399;margin-left:8px">当前 {{ list.length }} 个商品；点分类磁贴继续下钻</span>
    </div>
    <div v-if="!searching && tiles.length" style="display:flex;flex-wrap:wrap;gap:10px;margin-bottom:14px">
      <div v-for="t in tiles" :key="t.name" @click="openTile(t)"
        style="cursor:pointer;border:1px solid #dcdfe6;border-radius:8px;padding:14px 22px;text-align:center;min-width:110px;background:#f5f7fa"
        class="cat-tile">
        <div style="font-size:15px;font-weight:bold">{{ t.name }}</div>
        <div style="font-size:12px;color:#909399;margin-top:4px">{{ t.count }} 个商品</div>
      </div>
    </div>
  </template>

    <el-table :data="list" border stripe>
    <el-table-column label="图" width="60">
      <template #default="{ row }">
        <el-image v-if="row.hasImage" :src="imgUrl(row)" fit="cover"
          style="width:40px;height:40px;border-radius:4px" :preview-src-list="[imgUrl(row)]" preview-teleported />
        <el-upload v-else :show-file-list="false" accept=".jpg,.jpeg,.png" :auto-upload="false"
          @change="(e) => onImageChange(row, e)">
          <el-button size="small" circle><el-icon><Plus /></el-icon></el-button>
        </el-upload>
      </template>
    </el-table-column>
    <el-table-column label="商品名称" min-width="140">
      <template #default="{ row }">
        {{ row.name }}
        <el-tag v-if="(row.sku || '').startsWith('PS-')" size="small" type="warning">同行</el-tag>
      </template>
    </el-table-column>
    <el-table-column prop="sku" label="SKU" width="100" />
    <el-table-column label="规格" width="95">
      <template #default="{ row }">{{ row.spec || '-' }}</template>
    </el-table-column>
    <el-table-column prop="barcode" label="条码" width="120" />
    <el-table-column label="分类" width="150">
      <template #default="{ row }">{{ [row.category, row.subCategory, row.sub2Category].filter(Boolean).join(' / ') || '-' }}</template>
    </el-table-column>
    <el-table-column label="单位" width="80">
      <template #default="{ row }">{{ row.unit || '个' }}{{ row.bigUnit ? `(${row.bigUnitRate}${row.unit || '个'}/${row.bigUnit})` : '' }}</template>
    </el-table-column>
    <el-table-column prop="costPrice" label="成本" width="70" />
    <el-table-column label="加权均价" width="85">
      <template #default="{ row }">{{ row.avgCost }}</template>
    </el-table-column>
    <el-table-column prop="salePrice" label="零售" width="70" />
    <el-table-column label="批发" width="70">
      <template #default="{ row }">{{ row.wholesalePrice ?? '-' }}</template>
    </el-table-column>
    <el-table-column label="会员" width="70">
      <template #default="{ row }">{{ row.memberPrice ?? '-' }}</template>
    </el-table-column>
    <el-table-column prop="stock" label="库存" width="75">
      <template #default="{ row }">
        <span :style="{ color: row.stock <= row.safeStock ? '#f56c6c' : '', fontWeight: row.stock <= row.safeStock ? 'bold' : '' }">{{ row.stock }}</span>
      </template>
    </el-table-column>
    <el-table-column label="分仓库明细" min-width="130">
      <template #default="{ row }"><span style="font-size:12px;color:#909399">{{ stockDetail(row) }}</span></template>
    </el-table-column>
    <el-table-column label="操作" width="130" fixed="right">
      <template #default="{ row }">
        <el-button size="small" @click="edit(row)">编辑</el-button>
        <el-button size="small" type="danger" plain @click="del(row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>
  <el-pagination v-model:current-page="page" :page-size="pageSize" :total="filtered.length"
    layout="total, prev, pager, next" style="margin-top:10px;justify-content:flex-end" />

  <el-dialog v-model="dialog" :title="form.id ? '编辑商品' : '新增商品'" width="640px">
    <el-form :model="form" label-width="100px" size="default">
      <el-row>
        <el-col :span="12"><el-form-item label="商品名称"><el-input v-model="form.name" /></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="SKU">
          <el-input v-model="form.sku" :disabled="!!form.id" @input="skuAuto = false" />
          <div style="font-size:12px;color:#909399">
            新增时按「一级缩写-二级编号+三级编号+序号」自动生成（如 XF-010001），可手动修改
          </div>
        </el-form-item></el-col>
        <el-col :span="12"><el-form-item label="条码"><el-input v-model="form.barcode" placeholder="扫码枪可扫" /></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="规格"><el-input v-model="form.spec" placeholder="如 500ml / 4kg干粉 / 24瓶×箱" /></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="保质期(天)"><el-input-number v-model="form.shelfLifeDays" :min="0" style="width:100%" placeholder="按生产日期+天数算到期" /></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="增值税率">
          <el-select v-model="form.taxRate" style="width:100%" clearable placeholder="跟随全局计税设置">
            <el-option :value="13" label="13%（一般货物）" />
            <el-option :value="9" label="9%（农产品/交通等）" />
            <el-option :value="6" label="6%（现代服务）" />
            <el-option :value="0" label="0%（免税/出口）" />
          </el-select>
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="一级分类">
          <el-select v-model="form.category" filterable allow-create default-first-option clearable style="width:100%"
            @change="form.subCategory = ''; form.sub2Category = ''">
            <el-option v-for="c in catDict.categories" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="二级分类">
          <el-select v-model="form.subCategory" filterable allow-create default-first-option clearable style="width:100%"
            :disabled="!form.category" @change="form.sub2Category = ''">
            <el-option v-for="c in (catDict.subOf[form.category] || [])" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="三级分类">
          <el-select v-model="form.sub2Category" filterable allow-create default-first-option clearable style="width:100%"
            :disabled="!form.subCategory">
            <el-option v-for="c in sub2Options" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item></el-col>
        <el-col :span="8"><el-form-item label="基本单位"><el-input v-model="form.unit" placeholder="个/瓶" /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="大件单位"><el-input v-model="form.bigUnit" placeholder="箱" /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="换算率"><el-input-number v-model="form.bigUnitRate" :min="0" style="width:100%" placeholder="1箱=24瓶填24" /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="成本价"><el-input-number v-model="form.costPrice" :min="0" :precision="2" style="width:100%" /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="零售价"><el-input-number v-model="form.salePrice" :min="0" :precision="2" style="width:100%" /></el-form-item></el-col>
        <el-col :span="8">
          <el-form-item label="安全库存">
            <el-input-number v-model="form.safeStock" :min="0" style="width:100%" />
            <div style="font-size:12px;color:#909399">低于此数量预警；设 0 = 仅缺货时提醒</div>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="库存预警">
            <el-switch v-model="form.noAlert" active-text="不监控" :active-value="1" :inactive-value="0" />
            <div style="font-size:12px;color:#909399">开启后此商品不再进库存预警（同行调货等卖完即止的商品用）</div>
          </el-form-item>
        </el-col>
        <el-col :span="8"><el-form-item label="批发价"><el-input-number v-model="form.wholesalePrice" :min="0" :precision="2" style="width:100%" /></el-form-item></el-col>
        <el-col :span="8"><el-form-item label="会员价"><el-input-number v-model="form.memberPrice" :min="0" :precision="2" style="width:100%" /></el-form-item></el-col>
        <el-col v-if="form.id" :span="8">
          <el-form-item label="商品图片">
            <el-upload :show-file-list="false" accept=".jpg,.jpeg,.png" :auto-upload="false"
              @change="(e) => onImageChange(form, e)">
              <el-button size="small">{{ form.hasImage ? '更换图片' : '上传图片' }}</el-button>
            </el-upload>
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
  <!-- 分类管理：重命名 / 删除（删除=清空引用该分类的商品的对应字段） -->
  <el-dialog v-model="catMgr" title="分类管理" width="680px">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px"
      title="分类来自商品档案。误输入的分类在这里删除或重命名，会同步更新所有引用它的商品。" />
    <el-table :data="catTree" row-key="name" border default-expand-all
      :tree-props="{ children: 'children' }" max-height="420">
      <el-table-column label="分类" min-width="200">
        <template #default="{ row }">
          <el-tag size="small" style="margin-right:6px">{{ ['一级','二级','三级'][row.level - 1] }}</el-tag>{{ row.name }}
        </template>
      </el-table-column>
      <el-table-column label="商品数" width="90">
        <template #default="{ row }">{{ row.count }}</template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button size="small" @click="renameCat(row)">重命名</el-button>
          <el-button size="small" type="danger" plain @click="deleteCat(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-dialog>

  <!-- 批量导入：解析任意 ERP 导出表 → 列映射 → 导入 -->
  <el-dialog v-model="importDialog" title="批量导入商品" width="780px" top="6vh">
    <el-steps :active="rawRows.length ? 2 : 1" simple style="margin-bottom:14px">
      <el-step title="选文件" /><el-step title="列映射" /><el-step title="导入" />
    </el-steps>

    <el-space wrap style="margin-bottom:10px">
      <el-button type="primary" plain @click="importFileRef?.click()">选择 Excel / CSV 文件</el-button>
      <input ref="importFileRef" type="file" accept=".xlsx,.xls,.csv" style="display:none" @change="onImportFile" />
      <span style="font-size:12px;color:#909399">
        支持任意 ERP 导出的表格——选完文件后把列对应到本系统字段即可。没有分类列也没关系，可整批指定或留空进「未分类」。
      </span>
    </el-space>

    <template v-if="rawRows.length">
      <el-divider style="margin:8px 0">列映射（{{ rawRows.length }} 行数据，已识别 {{ Object.keys(mapping).length }} 列）</el-divider>
      <el-form size="small" label-width="150px">
        <el-row>
          <el-col v-for="f in TARGET_FIELDS" :key="f.key" :span="8">
            <el-form-item :label="f.label">
              <el-select v-model="mapping[f.key]" clearable placeholder="不导入此列" style="width:95%"
                :class="{ 'miss-req': f.required && !mapping[f.key] }">
                <el-option v-for="h in rawHeaders" :key="h" :label="h" :value="h" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="本批统一指定分类">
          <el-space wrap>
            <el-select v-model="batchCat.l1" clearable filterable allow-create placeholder="一级（可留空）" style="width:130px" />
            <el-select v-model="batchCat.l2" clearable filterable allow-create placeholder="二级（可留空）" style="width:130px" />
            <el-select v-model="batchCat.l3" clearable filterable allow-create placeholder="三级（可留空）" style="width:130px" />
            <span style="font-size:12px;color:#909399">原表没有分类列时用；全空则进「未分类」</span>
          </el-space>
        </el-form-item>
        <el-form-item label="SKU 策略">
          <el-radio-group v-model="skuRegen">
            <el-radio :value="false">保留原 SKU（为空自动编号）</el-radio>
            <el-radio :value="true">全部按本系统规则重新生成</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="重复商品处理">
          <el-radio-group v-model="dedup">
            <el-radio value="skip">跳过（按 SKU/条码 匹配）</el-radio>
            <el-radio value="overwrite">覆盖更新非空字段</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>

      <el-divider style="margin:8px 0">预览（前 10 行映射结果，有效 {{ mappedRows.length }} 行）</el-divider>
      <el-table :data="mappedRows.slice(0, 10)" size="small" border max-height="200">
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column prop="sku" label="SKU" width="110" />
        <el-table-column prop="spec" label="规格" width="80" />
        <el-table-column label="分类" width="150">
          <template #default="{ row }">{{ [row.category, row.subCategory, row.sub2Category].filter(Boolean).join(' / ') || '未分类' }}</template>
        </el-table-column>
        <el-table-column prop="unit" label="单位" width="55" />
        <el-table-column prop="costPrice" label="成本" width="65" />
        <el-table-column prop="salePrice" label="零售" width="65" />
        <el-table-column prop="stock" label="期初库存" width="75" />
      </el-table>

      <el-alert v-if="importResult" :type="importResult.failed ? 'warning' : 'success'" :closable="false" show-icon
        style="margin-top:10px" :title="importResult.message">
        <div v-for="(er, i) in importResult.errors" :key="i" style="font-size:12px">{{ er }}</div>
      </el-alert>
    </template>

    <template #footer>
      <el-button @click="importDialog = false">关闭</el-button>
      <el-button type="primary" :loading="importing" :disabled="!rawRows.length" @click="doImport">
        {{ importing ? '导入中…' : `确认导入 ${mappedRows.length} 个商品` }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.cat-tile:hover { border-color: #409eff; background: #ecf5ff; }
.miss-req :deep(.el-input__wrapper) { box-shadow: 0 0 0 1px #f56c6c inset; }
.file-btn { cursor: pointer; }
</style>
