<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exportExcel } from '../utils/export'
import { printHtml, receiptHtml } from '../utils/print'
import PaperSelect from '../components/PaperSelect.vue'
import CategoryProductPicker from '../components/CategoryProductPicker.vue'

const products = ref([])
const partners = ref([])
const warehouses = ref([])
const bills = ref([])

const form = ref({ type: 'SALE', partnerId: null, warehouseId: null, toWarehouseId: null, paid: 1, discount: 100, remark: '' })
const lines = ref([])   // 自有行: { productId, name, unit, quantity, price, batchNo, productionDate }
                        // 同行行: { peer: true, peerStockId, name, sku, unit, quantity, price(售价), peerPrice(调货价) }
const pick = ref({ productId: null, quantity: 1, price: null, useBigUnit: false, batchNo: '', productionDate: '' })
const peerPartnerId = ref(null)   // 调货供应商（可为字符串=新名称自动建档）
const peerPaid = ref(0)           // 同行结算：默认挂账
const peerPicker = ref(false)     // 同行商品选择弹层
const peerList = ref([])
const peerKeyword = ref('')
const peerCustom = ref({ visible: false, saving: false, form: { productName: '', lastPrice: null, category: '', unit: '个' } })
const peerPage = ref(1)
const pagedPeerList = computed(() => peerList.value.slice((peerPage.value - 1) * 5, peerPage.value * 5))
const qc = ref({ visible: false, saving: false, form: { name: '', barcode: '', salePrice: null, costPrice: null, unit: '个' } })
const scanCode = ref('')
const scanRef = ref(null)
const detailDialog = ref(false)
const detail = ref(null)
const shop = ref({ shopName: '' })

const TYPE_NAMES = {
  PURCHASE: '采购入库', SALE: '销售出库', PURCHASE_RETURN: '采购退货',
  SALE_RETURN: '销售退货', LOSS: '报损', GAIN: '盘盈', TRANSFER: '调拨'
}
const REMARK_PRESETS = ['送货单（未收款）', '已收款', '月结', '货到付款', '补货', '样品', '定金已付']
const PRICE_MODES = [
  { value: 'retail', label: '零售价', field: 'salePrice' },
  { value: 'wholesale', label: '批发价', field: 'wholesalePrice' },
  { value: 'member', label: '会员价', field: 'memberPrice' }
]
const priceMode = ref('retail')

const productMap = computed(() => Object.fromEntries(products.value.map(p => [p.id, p])))
const warehouseMap = computed(() => Object.fromEntries(warehouses.value.map(w => [w.id, w.name])))
const partnerMap = computed(() => Object.fromEntries(partners.value.map(p => [p.id, p])))
const partnerOptions = computed(() =>
  partners.value.filter(p => p.type === (form.value.type.startsWith('PURCHASE') ? 'SUPPLIER' : 'CUSTOMER')))
const supplierOptions = computed(() => partners.value.filter(p => p.type === 'SUPPLIER'))
const hasPeer = computed(() => lines.value.some(l => l.peer))
const peerLines = computed(() => lines.value.filter(l => l.peer))
const grossTotal = computed(() => lines.value.reduce((s, l) => s + l.quantity * l.price, 0))
const discount = computed(() => form.value.discount == null ? 100 : form.value.discount)
const total = computed(() => grossTotal.value * discount.value / 100)
const isOut = computed(() => ['SALE', 'PURCHASE_RETURN', 'LOSS'].includes(form.value.type))
const isPurchase = computed(() => form.value.type === 'PURCHASE')

function defaultPrice(p) {
  if (isPurchase.value || form.value.type === 'PURCHASE_RETURN') return p.costPrice
  const mode = PRICE_MODES.find(m => m.value === priceMode.value)
  const v = mode ? p[mode.field] : null
  return v != null ? v : p.salePrice
}
function onProductChange(id) {
  const p = productMap.value[id]
  if (p && pick.value.price == null) pick.value.price = defaultPrice(p)
}
function onTypeChange() {
  form.value.partnerId = null
  lines.value = []
  pick.value = { productId: null, quantity: 1, price: null, useBigUnit: false, batchNo: '', productionDate: '' }
}
function onPriceModeChange() {
  lines.value.forEach(l => {
    const p = productMap.value[l.productId]
    if (p && !l.priceLocked) l.price = defaultPrice(p)
  })
  pick.value.price = null
}

/** 扫码枪：扫码后自动回车 → 按条码找商品 → 按默认价加一行 */
async function onScan() {
  const code = scanCode.value.trim()
  if (!code) return
  const p = products.value.find(x => x.barcode === code) || products.value.find(x => x.sku === code)
  if (!p) {
    ElMessageBox.confirm(`条码 ${code} 没有登记过商品，是否快速新增并加入明细？`, '未找到商品',
      { confirmButtonText: '快速新增', cancelButtonText: '取消', type: 'warning' })
      .then(() => openQuickCreate(code))
      .catch(() => {})
  } else {
    addLineFor(p, 1, defaultPrice(p))
  }
  scanCode.value = ''
  nextTick(() => scanRef.value?.focus())
}

// ---------- 快速建档（调货商品不在商品列表时，开单页直接建） ----------
function openQuickCreate(barcode) {
  qc.value.form = { name: '', barcode: barcode || '', salePrice: null, costPrice: null, unit: '个' }
  qc.value.visible = true
}
async function saveQuickCreate() {
  const f = qc.value.form
  if (!f.name?.trim()) { ElMessage.warning('商品名称必填'); return }
  qc.value.saving = true
  try {
    const sku = (await api.nextSku('', '', '')).sku   // 无分类 → SP- 前缀自动编号
    const p = await api.saveProduct({
      name: f.name.trim(), sku, barcode: f.barcode || null,
      salePrice: f.salePrice ?? 0, costPrice: f.costPrice ?? 0,
      unit: f.unit || '个', safeStock: 10
    })
    products.value.push(p)
    qc.value.visible = false
    ElMessage.success(`已建档「${p.name}」（SKU ${p.sku}），已加入明细；同行调货请在该行勾选「同行」并填调货价`)
    addLineFor(p, 1, defaultPrice(p))
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '建档失败')
  }
  qc.value.saving = false
}

function addLineFor(p, qty, price) {
  const exist = lines.value.find(l => l.productId === p.id && !l.priceLocked)
  if (exist) exist.quantity += qty
  else lines.value.push({ productId: p.id, name: p.name, unit: p.unit || '个',
    quantity: qty, price, priceLocked: false, batchNo: '', productionDate: '' })
}
function addLine() {
  if (!pick.value.productId) { ElMessage.warning('请选择商品'); return }
  if (!pick.value.quantity || pick.value.quantity <= 0) { ElMessage.warning('数量必须大于0'); return }
  const p = productMap.value[pick.value.productId]
  let qty = pick.value.quantity
  // 大单位换算：1箱=rate 个基本单位
  if (pick.value.useBigUnit && p.bigUnitRate && p.bigUnitRate > 1) qty = qty * p.bigUnitRate
  addLineFor(p, qty, pick.value.price ?? defaultPrice(p))
  pick.value = { productId: null, quantity: 1, price: null, useBigUnit: false, batchNo: '', productionDate: '' }
}
// ---------- 同行商品选择（数据源 = 同行库存表） ----------
async function openPeerPicker() {
  peerPicker.value = true
  await loadPeerStock()
}
async function loadPeerStock() {
  const pid = typeof peerPartnerId.value === 'number' ? peerPartnerId.value : null
  peerList.value = await api.peerStock(pid, peerKeyword.value.trim())
  peerPage.value = 1
}
function addPeerLine(ps) {
  lines.value.push({
    peer: true, peerStockId: ps.id, peerPartnerName: ps.partnerName,
    name: ps.productName, sku: ps.sku,
    unit: ps.unit || '个', quantity: 1, price: ps.lastPrice ?? 0, peerPrice: ps.lastPrice ?? 0
  })
  ElMessage.success(`已加入同行行「${ps.productName}」（调货价 ￥${ps.lastPrice}，售价可在行内修改）`)
}
async function addCustomPeer() {
  const f = peerCustom.value.form
  if (!f.productName?.trim()) { ElMessage.warning('商品名称必填'); return }
  if (f.lastPrice == null || f.lastPrice < 0) { ElMessage.warning('请填写调货价'); return }
  peerCustom.value.saving = true
  try {
    const partnerName = typeof peerPartnerId.value === 'string' ? peerPartnerId.value.trim()
      : (supplierOptions.value.find(p => p.id === peerPartnerId.value)?.name
        || (peerLines.value.find(l => l.peer)?.peerPartnerName ?? ''))
    const ps = await api.addPeerStock({ partnerName, productName: f.productName.trim(), lastPrice: f.lastPrice, category: f.category || '', unit: f.unit || '个' })
    addPeerLine(ps)
    peerCustom.value.visible = false
    peerCustom.value.form = { productName: '', lastPrice: null, category: '', unit: '个' }
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '自定义同行商品失败')
  }
  peerCustom.value.saving = false
}

function removeLine(idx) { lines.value.splice(idx, 1) }

function validateStock() {
  for (const l of lines.value) {
    if (l.peer) continue   // 同行调货行：过账时同一事务先自动采购入库，库存充足
    const p = productMap.value[l.productId]
    if (isOut.value && l.quantity > p.stock) {
      ElMessage.warning(`「${p.name}」库存不足：当前仅 ${p.stock}（如为同行调货，请在该行勾选「同行」）`)
      return false
    }
  }
  return true
}
/** 输入了不存在的往来单位（字符串）→ 按指定类型自动建档 */
async function ensurePartnerByName(value, type) {
  if (value == null || value === '') return null
  if (typeof value === 'number') return value
  try {
    const created = await api.savePartner({ name: String(value).trim(), type })
    partners.value = await api.partners()
    ElMessage.success(`已自动创建${type === 'SUPPLIER' ? '供应商' : '客户'}「${created.name}」`)
    return created.id
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '自动建档失败')
    return false
  }
}
function dto(autoPost) {
  return {
    type: form.value.type, partnerId: form.value.partnerId,
    warehouseId: form.value.warehouseId, toWarehouseId: form.value.toWarehouseId,
    paid: form.value.paid, discount: discount.value, remark: form.value.remark, autoPost,
    items: lines.value.filter(l => !l.peer).map(l => ({ productId: l.productId, quantity: l.quantity, price: l.price,
      batchNo: l.batchNo || undefined, productionDate: isPurchase.value && l.productionDate ? l.productionDate : undefined }))
  }
}
async function save(asPost) {
  if (!lines.value.length) { ElMessage.warning('请先添加商品明细'); return }
  const peers = lines.value.filter(l => l.peer)
  if (peers.length) {
    if (peers.some(l => l.peerPrice == null || l.peerPrice < 0)) { ElMessage.warning('同行行请填写调货价'); return }
    if (peers.some(l => l.price == null || l.price < 0)) { ElMessage.warning('同行行请填写售价'); return }
    if (!asPost) { ElMessage.warning('同行调货行需直接保存并过账（暂不支持存草稿）'); return }
    if (peerPartnerId.value == null || peerPartnerId.value === '') { ElMessage.warning('请选择或输入调货供应商'); return }
    if (form.value.type !== 'SALE') { ElMessage.warning('同行调货仅支持销售出库单'); return }
  }
  if (asPost && !validateStock()) return
  try {
    if (peers.length && asPost) {
      // 同行调货：同一事务 = 同行采购入库（同行行，调货价）+ 销售出库（自有行 + 同行行）
      const peerId = await ensurePartnerByName(peerPartnerId.value, 'SUPPLIER')
      if (peerId === false || peerId == null) return
      // 往来单位（客户）：新输入的名称也自动建档并挂到销售单，避免丢单客户
      const custId = await ensurePartnerByName(form.value.partnerId, 'CUSTOMER')
      if (custId === false) return
      if (custId != null) form.value.partnerId = custId
      const saleDto = dto(false)
      const purchaseDto = {
        type: 'PURCHASE', partnerId: peerId, warehouseId: form.value.warehouseId,
        paid: peerPaid.value, remark: '同行调货' + (form.value.remark ? '：' + form.value.remark : '')
      }
      const peerLines = peers.map(l => ({
        stockId: l.peerStockId, quantity: l.quantity, salePrice: l.price, peerPrice: l.peerPrice
      }))
      const r = await api.peerSale({ sale: saleDto, purchase: purchaseDto, peerLines })
      ElMessage.success(r.message)
      lines.value = []
      form.value.remark = ''
      load()
      return
    }
    const resolved = await ensurePartnerByName(form.value.partnerId, form.value.type.startsWith('PURCHASE') ? 'SUPPLIER' : 'CUSTOMER')
    if (resolved === false) return
    if (resolved != null) form.value.partnerId = resolved
    const r = await api.createBill(dto(asPost))
    ElMessage.success(`单据 ${r.billNo} ${asPost ? '已过账' : '已存草稿'}`)
    lines.value = []
    form.value.remark = ''
    load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.response?.data?.message || '保存失败')
  }
}
async function post(row) {
  try {
    // 同行调货销售草稿：先过账关联的同行采购草稿，再过账销售（同一事务）
    const r = row.peerBillId ? await api.postWithPeer(row.id) : await api.postBill(row.id)
    ElMessage.success(r.message); load()
  }
  catch (e) { ElMessage.error(e.response?.data?.error || '过账失败') }
}
async function reverse(row) {
  await ElMessageBox.confirm(`冲正单据 ${row.billNo}？将生成反向单据还原库存，原单保留。`, '冲正确认', { type: 'warning' })
  try { ElMessage.success((await api.reverseBill(row.id)).message); load() }
  catch (e) { ElMessage.error(e.response?.data?.error || '冲正失败') }
}
async function delDraft(row) {
  await ElMessageBox.confirm(`删除草稿 ${row.billNo}？`, '确认', { type: 'warning' })
  ElMessage.success((await api.deleteBill(row.id)).message)
  load()
}
async function showDetail(row) {
  detail.value = await api.billDetail(row.id)
  detailDialog.value = true
}
function printBill() {
  const d = detail.value
  const disc = d.bill.discount == null ? 100 : d.bill.discount
  printHtml(receiptHtml(
    TYPE_NAMES[d.bill.type] + '单',
    [['单号', d.bill.billNo], ['时间', d.bill.postedAt || d.bill.createdAt],
     ['仓库', d.warehouseName + (d.toWarehouseName ? ' → ' + d.toWarehouseName : '')],
     ['往来单位', d.partnerName || '无'], ['结算', d.bill.paid === 0 ? '挂账' : '已结清'],
     ['折扣', disc + '%'], ['经手人', d.bill.createdBy || '-']],
    d.items.map(i => ({ 商品: i.productName, 数量: i.quantity, 单价: i.effPrice, 金额: i.amount.toFixed(2) })),
    `合计：￥${d.bill.totalAmount.toFixed(2)}`,
    { shopName: shop.value.shopName, showSign: false }
  ))
  printDialogAfter()
}
function printDialogAfter() { detailDialog.value = false }
function doExport() {
  exportExcel(`单据列表_${new Date().toLocaleDateString('zh-CN')}.xlsx`, '单据',
    bills.value.map(b => ({
      单号: b.billNo, 类型: TYPE_NAMES[b.type],
      仓库: warehouseMap.value[b.warehouseId] || '-',
      折扣: (b.discount == null ? 100 : b.discount) + '%',
      金额: b.totalAmount, 状态: b.status === 'POSTED' ? '已过账' : '草稿',
      结算: b.paid === 0 ? '挂账' : '已结清', 时间: b.createdAt, 备注: b.remark || ''
    })))
}
onMounted(() => {
  load()
  api.getShopInfo().then(s => shop.value = s).catch(() => {})
})
async function load() {
  ;[products.value, partners.value, warehouses.value, bills.value] = await Promise.all([
    api.products(), api.partners(), api.warehouses(), api.bills()
  ])
  if (!form.value.warehouseId && warehouses.value.length) form.value.warehouseId = warehouses.value[0].id
  nextTick(() => scanRef.value?.focus())
}
</script>

<template>
  <el-row :gutter="16">
    <el-col :span="10">
      <el-card shadow="hover">
        <template #header><b>开单</b></template>
        <el-form :model="form" label-width="80px" size="default">
          <el-form-item label="扫码录入">
            <el-input ref="scanRef" v-model="scanCode" placeholder="扫码枪扫码 / 手输条码后回车，自动加行" clearable @keyup.enter="onScan">
              <template #prefix><el-icon><FullScreen /></el-icon></template>
            </el-input>
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="form.type" style="width:100%" @change="onTypeChange">
              <el-option v-for="(n, t) in TYPE_NAMES" :key="t" :value="t" :label="n" />
            </el-select>
          </el-form-item>
          <el-form-item label="出/入仓">
            <el-select v-model="form.warehouseId" style="width:100%">
              <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="form.type === 'TRANSFER'" label="调入仓">
            <el-select v-model="form.toWarehouseId" style="width:100%" placeholder="选择调入仓库">
              <el-option v-for="w in warehouses.filter(x => x.id !== form.warehouseId)" :key="w.id" :value="w.id" :label="w.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="往来单位">
            <el-select v-model="form.partnerId" filterable allow-create default-first-option clearable
              placeholder="可选；输入新名称回车自动建档" style="width:100%">
              <el-option v-for="p in partnerOptions" :key="p.id" :value="p.id" :label="p.name" />
            </el-select>
          </el-form-item>

          <el-divider style="margin:8px 0">添加商品明细</el-divider>
          <el-form-item v-if="!isPurchase" label="价格方式">
            <el-radio-group v-model="priceMode" @change="onPriceModeChange">
              <el-radio-button v-for="m in PRICE_MODES" :key="m.value" :value="m.value">{{ m.label }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-space wrap>
            <CategoryProductPicker v-model="pick.productId" :products="products" @select="onProductChange" />
            <el-button v-if="form.type === 'SALE'" @click="openPeerPicker">同行商品</el-button>
            <el-input-number v-model="pick.quantity" :min="1" style="width:100px" />
            <el-checkbox v-if="pick.productId && productMap[pick.productId]?.bigUnitRate > 1"
              v-model="pick.useBigUnit">
              按{{ productMap[pick.productId].bigUnit }}({{ productMap[pick.productId].bigUnitRate }}{{ productMap[pick.productId].unit || '个' }})计
            </el-checkbox>
            <el-input-number v-model="pick.price" :min="0" :precision="2" placeholder="单价" style="width:120px" />
            <el-button type="primary" plain @click="addLine">添加</el-button>
          </el-space>
          <el-space v-if="isPurchase" wrap style="margin-top:8px">
            <el-input v-model="pick.batchNo" placeholder="批次号（选填）" style="width:150px" size="small" />
            <el-date-picker v-model="pick.productionDate" type="date" value-format="YYYY-MM-DD"
              placeholder="生产日期（填了可临期提醒）" style="width:200px" size="small" />
          </el-space>

          <el-form-item v-if="form.type === 'SALE' && hasPeer" label="同行调货">
            <div style="width:100%">
              <el-space wrap>
                <el-select v-model="peerPartnerId" filterable allow-create default-first-option
                  placeholder="调货供应商（可输入新名称自动建档）" style="width:250px">
                  <el-option v-for="p in supplierOptions" :key="p.id" :value="p.id" :label="p.name" />
                </el-select>
                <el-radio-group v-model="peerPaid">
                  <el-radio :value="0">挂账</el-radio>
                  <el-radio :value="1">现结</el-radio>
                </el-radio-group>
                <span style="font-size:12px;color:#909399">同行结算默认挂账，毛利 = 售价 − 调货价；多个同行请分开开单</span>
              </el-space>
            </div>
          </el-form-item>

          <el-table :data="lines" size="small" border style="margin-top:10px" empty-text="还没有明细行（可用扫码枪快速录入，或点「同行商品」加调货行）">
            <el-table-column label="商品" min-width="130">
              <template #default="{ row }">
                {{ row.name }}
                <el-tag v-if="row.peer" size="small" type="warning" style="margin-left:4px">
                  同行·{{ row.peerPartnerName || '未知' }}</el-tag>
                <span v-if="row.sku" style="font-size:11px;color:#c0c4cc">{{ row.sku }}</span>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="105">
              <template #default="{ row }"><el-input-number v-model="row.quantity" :min="1" size="small" style="width:90px" /></template>
            </el-table-column>
            <el-table-column prop="unit" label="单位" width="50" />
            <el-table-column label="单价" width="115">
              <template #default="{ row }"><el-input-number v-model="row.price" :min="0" :precision="2" size="small" style="width:100px" /></template>
            </el-table-column>
            <el-table-column v-if="hasPeer" label="调货价" width="112">
              <template #default="{ row }">
                <el-input-number v-if="row.peer" v-model="row.peerPrice" :min="0" :precision="2" size="small" style="width:100px" />
                <span v-else style="color:#c0c4cc">-</span>
              </template>
            </el-table-column>
            <el-table-column label="金额" width="75">
              <template #default="{ row }">{{ (row.quantity * row.price).toFixed(2) }}</template>
            </el-table-column>
            <el-table-column label=" " width="50">
              <template #default="{ $index }">
                <el-button size="small" type="danger" plain circle @click="removeLine($index)"><el-icon><Delete /></el-icon></el-button>
              </template>
            </el-table-column>
          </el-table>

          <div style="display:flex;justify-content:space-between;align-items:center;margin-top:12px">
            <el-radio-group v-model="form.paid">
              <el-radio :value="1">现结</el-radio>
              <el-radio :value="0">挂账</el-radio>
            </el-radio-group>
            <div style="text-align:right">
              <div style="font-size:12px;color:#909399">
                合计￥{{ grossTotal.toFixed(2) }} ×
                <el-input-number v-model="form.discount" :min="1" :max="100" size="small" style="width:90px" controls-position="right" />%
              </div>
              <b style="font-size:16px">折后：￥{{ total.toFixed(2) }}</b>
            </div>
          </div>
          <el-select v-model="form.remark" filterable allow-create default-first-option clearable
            placeholder="备注：可选常用语或自行输入" style="margin-top:8px;width:100%">
            <el-option v-for="r in REMARK_PRESETS" :key="r" :label="r" :value="r" />
          </el-select>
          <el-space style="margin-top:12px">
            <el-button type="primary" size="large" @click="save(true)">保存并过账</el-button>
            <el-tooltip v-if="hasPeer" content="同行调货行需直接过账（自动生成同行采购入库），不支持存草稿">
              <el-button size="large" disabled>存草稿</el-button>
            </el-tooltip>
            <el-button v-else size="large" @click="save(false)">存草稿</el-button>
          </el-space>
        </el-form>
      </el-card>
    </el-col>

    <el-col :span="14">
      <el-card shadow="hover">
        <template #header>
          <div style="display:flex;justify-content:space-between;align-items:center">
            <b>单据列表</b>
            <el-button size="small" @click="doExport">导出 Excel</el-button>
          </div>
        </template>
        <el-table :data="bills" border stripe max-height="640">
          <el-table-column label="单号" width="140"><template #default="{ row }">{{ row.billNo }}</template></el-table-column>
          <el-table-column label="类型" width="95">
            <template #default="{ row }">
              <el-tag size="small" :type="{ PURCHASE: 'success', SALE: 'warning', LOSS: 'danger', TRANSFER: 'info' }[row.type] || 'info'">
                {{ TYPE_NAMES[row.type] }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="仓库" width="85">
            <template #default="{ row }">{{ warehouseMap[row.warehouseId] || '-' }}</template>
          </el-table-column>
          <el-table-column label="金额" width="95">
            <template #default="{ row }">￥{{ row.totalAmount?.toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'POSTED' ? 'success' : 'warning'" size="small">
                {{ row.status === 'POSTED' ? '已过账' : '草稿' }}
              </el-tag>
              <el-tag v-if="row.paid === 0" type="danger" size="small" style="margin-left:4px">挂账</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createdBy" label="经手人" width="75" />
          <el-table-column prop="createdAt" label="时间" width="145" />
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="showDetail(row)">明细</el-button>
              <el-button v-if="row.status === 'DRAFT'" size="small" type="primary" @click="post(row)">过账</el-button>
              <el-button v-if="row.status === 'POSTED'" size="small" type="warning" plain @click="reverse(row)">冲正</el-button>
              <el-button v-if="row.status === 'DRAFT'" size="small" type="danger" plain @click="delDraft(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </el-col>
  </el-row>

  <!-- 同行商品选择：数据源 = 同行库存表，支持自定义登记 -->
  <el-dialog v-model="peerPicker" title="选择同行商品（来自同行库存）" width="560px">
    <el-space wrap style="margin-bottom:8px">
      <el-select v-model="peerPartnerId" clearable filterable allow-create default-first-option
        placeholder="全部同行 / 选某一家" style="width:200px" size="small" @change="loadPeerStock">
        <el-option v-for="p in supplierOptions" :key="p.id" :value="p.id" :label="p.name" />
      </el-select>
      <el-input v-model="peerKeyword" placeholder="搜商品名 / SKU" size="small" clearable style="width:170px" @keyup.enter="loadPeerStock" />
      <el-button size="small" @click="loadPeerStock">查询</el-button>
    </el-space>

    <el-pagination v-model:current-page="peerPage" :page-size="5" :total="peerList.length"
      layout="prev, pager, next" size="small" style="margin-bottom:6px;justify-content:center" />
    <div style="max-height:260px;overflow-y:auto">
      <div v-for="ps in pagedPeerList" :key="ps.id" @click="addPeerLine(ps)"
        style="display:flex;justify-content:space-between;align-items:center;padding:7px 8px;border-radius:6px;cursor:pointer"
        class="pick-item">
        <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">
          {{ ps.productName }} <span style="font-size:11px;color:#c0c4cc">{{ ps.sku }}</span></span>
        <span style="font-size:12px;color:#909399;white-space:nowrap">
          {{ ps.partnerName }} ｜ 调货价 ￥{{ ps.lastPrice?.toFixed(2) }}</span>
      </div>
      <div v-if="!peerList.length" style="text-align:center;color:#909399;font-size:12px;padding:10px 0">
        同行库存里没有匹配的商品，可在下方自定义登记
      </div>
    </div>

    <el-divider style="margin:10px 0">同行库存里没有？自定义登记</el-divider>
    <el-space wrap>
      <el-input v-model="peerCustom.form.productName" placeholder="商品名称（必填）" size="small" style="width:180px" />
      <el-input-number v-model="peerCustom.form.lastPrice" :min="0" :precision="2" size="small" placeholder="调货价" style="width:110px" />
      <el-input v-model="peerCustom.form.category" placeholder="分类（可空）" size="small" style="width:120px" />
      <el-button size="small" type="primary" plain :loading="peerCustom.saving" @click="addCustomPeer">登记并加入明细</el-button>
    </el-space>

    <template #footer>
      <el-button @click="peerPicker = false">完成</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="qc.visible" title="快速新增商品" width="460px">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px"
      title="SKU 自动生成；安全库存默认 0（调货商品卖完不进库存预警）。同行调货保存后在明细行勾选「同行」并填调货价。" />
    <el-form :model="qc.form" label-width="80px" size="default">
      <el-form-item label="商品名称"><el-input v-model="qc.form.name" placeholder="必填" /></el-form-item>
      <el-form-item label="条码"><el-input v-model="qc.form.barcode" placeholder="可留空" /></el-form-item>
      <el-form-item label="零售价"><el-input-number v-model="qc.form.salePrice" :min="0" :precision="2" style="width:100%" /></el-form-item>
      <el-form-item label="成本价"><el-input-number v-model="qc.form.costPrice" :min="0" :precision="2" style="width:100%" placeholder="同行调货默认填调货价" /></el-form-item>
      <el-form-item label="单位"><el-input v-model="qc.form.unit" style="width:120px" placeholder="个/瓶" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="qc.visible = false">取消</el-button>
      <el-button type="primary" :loading="qc.saving" @click="saveQuickCreate">建档并加入明细</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="detailDialog" title="单据明细" width="600px">
    <template v-if="detail">
      <p style="margin:4px 0"><b>{{ detail.bill.billNo }}</b>（{{ TYPE_NAMES[detail.bill.type] }}）
        <el-tag size="small" style="margin-left:6px">{{ detail.bill.status === 'POSTED' ? '已过账' : '草稿' }}</el-tag>
        <el-tag v-if="detail.bill.discount && detail.bill.discount < 100" type="warning" size="small" style="margin-left:4px">
          折扣{{ detail.bill.discount }}%
        </el-tag>
      </p>
      <p style="margin:4px 0;font-size:13px;color:#606266">
        仓库：{{ detail.warehouseName }}{{ detail.toWarehouseName ? ' → ' + detail.toWarehouseName : '' }}
        ｜ 往来单位：{{ detail.partnerName || '无' }}
        ｜ {{ detail.bill.paid === 0 ? '挂账' : '已结清' }}
      </p>
      <el-table :data="detail.items" size="small" border style="margin-top:8px">
        <el-table-column prop="productName" label="商品" min-width="130" />
        <el-table-column prop="quantity" label="数量" width="65" />
        <el-table-column label="原单价" width="75"><template #default="{ row }">{{ row.price }}</template></el-table-column>
        <el-table-column label="折后价" width="75"><template #default="{ row }">{{ row.effPrice }}</template></el-table-column>
        <el-table-column label="金额" width="85"><template #default="{ row }">￥{{ row.amount.toFixed(2) }}</template></el-table-column>
      </el-table>
      <p style="text-align:right;margin:8px 0 0"><b>合计：￥{{ detail.bill.totalAmount?.toFixed(2) }}</b></p>
    </template>
    <template #footer>
      <PaperSelect style="margin-right:auto" />
      <el-button @click="detailDialog = false">关闭</el-button>
      <el-button type="primary" @click="printBill">打印单据</el-button>
    </template>
  </el-dialog>
</template>
