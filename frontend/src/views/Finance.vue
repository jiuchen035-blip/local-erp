<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exportSheets } from '../utils/export'
import { printHtml } from '../utils/print'
import PaperSelect from '../components/PaperSelect.vue'

const tab = ref('vouchers')
function onTabChange(t) {
  if (t === 'vouchers' && !vouchers.value.length) loadVouchers()
  if (t === 'trial' && !trial.value) loadTrial()
  if (t === 'statements' && !fs.value) loadStatements()
}

// ==================== 日期工具 ====================
function fmtDate(d) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function today() { return fmtDate(new Date()) }
function monthStart() { const d = new Date(); return fmtDate(new Date(d.getFullYear(), d.getMonth(), 1)) }

// ==================== 科目缓存 ====================
const accounts = ref([])
const accMap = computed(() => Object.fromEntries(accounts.value.map(a => [a.id, a])))
async function loadAccounts() { accounts.value = await api.financeAccounts() }

// ==================== 凭证管理 ====================
const vouchers = ref([])
const vStart = ref(monthStart())
const vEnd = ref(today())
const vKeyword = ref('')
const SOURCE_NAMES = { BILL: '单据自动', MANUAL: '手工录入', OPENING: '期初建账', CLOSE: '期末结转' }

async function loadVouchers() {
  vouchers.value = await api.financeVouchers({ start: vStart.value, end: vEnd.value, keyword: vKeyword.value })
}

const vDialog = ref(false)
const vForm = ref({})
const vLines = ref([])
function addVoucher() {
  vForm.value = { voucherDate: today(), summary: '' }
  vLines.value = [
    { accountId: null, direction: 'DEBIT', amount: null, summary: '' },
    { accountId: null, direction: 'CREDIT', amount: null, summary: '' }
  ]
  vDialog.value = true
}
function addLine() { vLines.value.push({ accountId: null, direction: 'DEBIT', amount: null, summary: '' }) }
function removeLine(idx) { vLines.value.splice(idx, 1) }
function bal(col) {
  return vLines.value.filter(l => l.direction === col)
    .reduce((s, l) => s + (Number(l.amount) || 0), 0).toFixed(2)
}
const vBalanced = computed(() => bal('DEBIT') === bal('CREDIT') && Number(bal('DEBIT')) > 0)

const detailDialog = ref(false)
const vDetail = ref(null)
async function showDetail(row) {
  vDetail.value = await api.financeVoucherDetail(row.id)
  detailDialog.value = true
}
async function saveVoucher() {
  if (!vForm.value.voucherDate) { ElMessage.warning('请选凭证日期'); return }
  const items = vLines.value.filter(l => l.accountId && Number(l.amount) > 0)
    .map(l => ({ accountId: l.accountId, direction: l.direction, amount: Number(l.amount), summary: l.summary }))
  try {
    const v = await api.createVoucher({ voucherDate: vForm.value.voucherDate, summary: vForm.value.summary, items })
    ElMessage.success(`凭证 ${v.voucherNo} 已保存`)
    vDialog.value = false
    loadVouchers()
  } catch (e) { ElMessage.error(e.response?.data?.error || '保存失败') }
}
async function delVoucher(row) {
  await ElMessageBox.confirm(`删除凭证 ${row.voucherNo}？`, '确认', { type: 'warning' })
  ElMessage.success((await api.deleteVoucher(row.id)).message)
  loadVouchers()
}

// ==================== 科目余额表 / 明细账 ====================
const tbStart = ref(monthStart())
const tbEnd = ref(today())
const trial = ref(null)
async function loadTrial() {
  trial.value = await api.trialBalance(tbStart.value, tbEnd.value)
}

const ldAccountId = ref(null)
const ldStart = ref(monthStart())
const ldEnd = ref(today())
const ledger = ref(null)
async function loadLedger() {
  if (!ldAccountId.value) { ElMessage.warning('请选择科目'); return }
  ledger.value = await api.ledgerDetail(ldAccountId.value, ldStart.value, ldEnd.value)
  // 数量金额式：滚动结存数量（借方入库 +，贷方出库 -）
  let qtyBal = 0
  ledger.value.rows.forEach(r => {
    if (r.quantity != null) qtyBal += (r.debit > 0 ? r.quantity : -r.quantity)
    r.qtyBalance = r.quantity != null ? qtyBal : null
  })
}

// ==================== 财务报表 ====================
const fsStart = ref(monthStart())
const fsEnd = ref(today())
const fs = ref(null)
const taxSettings = ref(null)
const vat = ref(null)
const cashFlow = ref(null)
const invoiceDialog = ref(false)
const invoiceRows = ref([])

async function loadTaxSettings() { taxSettings.value = await api.financeSettings() }
async function saveTaxSettings() {
  try {
    const r = await api.saveFinanceSettings(taxSettings.value)
    ElMessage.success(r.message)
    loadStatements()
  } catch (e) { ElMessage.error(e.response?.data?.error || '保存失败') }
}
async function loadStatements() {
  fs.value = await api.statements(fsStart.value, fsEnd.value)
  vat.value = taxSettings.value?.taxEnabled ? await api.vatReport(fsStart.value, fsEnd.value) : null
  cashFlow.value = await api.cashFlow(fsStart.value, fsEnd.value)
}
async function doCloseProfit() {
  await ElMessageBox.confirm(
    `把 ${fsEnd.value} 之前的损益类科目净额结转入“本年利润”？\n（同月只能结转一次）`, '期末结转损益', { type: 'warning' })
  try {
    const r = await api.closeProfit(fsEnd.value)
    ElMessage.success(`${r.message}，净利润 ${Number(r.profit).toFixed(2)} 元`)
    loadStatements(); loadVouchers()
  } catch (e) { ElMessage.error(e.response?.data?.error || '结转失败') }
}
async function doYearClose() {
  const y = fsEnd.value.substring(0, 4)
  await ElMessageBox.confirm(
    `年度结转：把 ${y} 年 12 月 31 日前的全部损益结转入“本年利润”？\n（若 12 月已结转过会提示，无需重复）`, '年度结转', { type: 'warning' })
  try {
    const r = await api.closeProfit(`${y}-12-31`)
    ElMessage.success(`${r.message}，本年净利润 ${Number(r.profit).toFixed(2)} 元`)
    loadStatements(); loadVouchers()
  } catch (e) { ElMessage.error(e.response?.data?.error || '年度结转失败') }
}
async function showInvoiceList() {
  invoiceRows.value = await api.invoiceList(fsStart.value, fsEnd.value)
  invoiceDialog.value = true
}
function exportInvoice() {
  exportSheets(`开票清单_${fsStart.value}_${fsEnd.value}.xlsx`, '开票清单',
    invoiceRows.value.map(r => ({
      日期: r.date, 单号: r.billNo, 客户: r.partner, 金额: r.amount,
      不含税额: r.net ?? '', 税额: r.tax ?? '', 备注: r.remark || ''
    })))
}

// ==================== 凭证套打 ====================
function toUpperAmount(n) {
  const D = '零壹贰叁肆伍陆柒捌玖', U = ['', '拾', '佰', '仟'], BIG = ['', '万', '亿']
  const [int, dec] = Number(n).toFixed(2).split('.')
  const clean = int.replace(/^0+/, '') || '0'
  const groups = []
  for (let i = clean.length; i > 0; i -= 4) groups.unshift(clean.slice(Math.max(0, i - 4), i))
  let out = ''
  groups.forEach((g, gi) => {
    let s = '', zero = false
    for (let i = 0; i < g.length; i++) {
      const d = Number(g[i])
      if (d === 0) { zero = true; continue }
      if (zero && s) s += '零'
      zero = false
      s += D[d] + U[g.length - 1 - i]
    }
    if (s) out += s + BIG[groups.length - 1 - gi]
  })
  out = (out || '零') + '元'
  if (dec === '00') return out + '整'
  if (dec[0] !== '0') out += D[Number(dec[0])] + '角'
  else if (dec[1] !== '0') out += '零'
  if (dec[1] !== '0') out += D[Number(dec[1])] + '分'
  else out += '整'
  return '人民币' + out
}
function printVoucher() {
  const d = vDetail.value
  if (!d) return
  const rows = d.items.map(i => `<tr>
    <td>${i.summary || ''}</td>
    <td>${i.accountCode} ${i.accountName}</td>
    <td>${i.direction === 'DEBIT' ? fmt(i.amount) : ''}</td>
    <td>${i.direction === 'CREDIT' ? fmt(i.amount) : ''}</td></tr>`).join('')
  const emptyRows = Array.from({ length: Math.max(0, 5 - d.items.length) },
    () => '<tr><td>&nbsp;</td><td></td><td></td><td></td></tr>').join('')
  printHtml(`<div class="receipt voucher-print">
    <h3>记 账 凭 证</h3>
    <div class="meta"><span>日期：${d.voucher.voucherDate}</span><span>凭证号：${d.voucher.voucherNo}</span><span>附单据 ___ 张</span></div>
    <table><thead><tr><th style="width:35%">摘要</th><th style="width:30%">会计科目</th><th style="width:17.5%">借方金额</th><th style="width:17.5%">贷方金额</th></tr></thead>
    <tbody>${rows}${emptyRows}
      <tr class="total"><td colSpan="2">合计（${toUpperAmount(d.debitTotal)}）</td><td>￥${fmt(d.debitTotal)}</td><td>￥${fmt(d.creditTotal)}</td></tr>
    </tbody></table>
    <div class="sign-row"><span>制单人：${d.voucher.createdBy || '____'}</span><span>审核：______</span><span>记账：______</span><span>出纳：______</span></div>
  </div>`)
}

// ==================== 科目设置 ====================
const aDialog = ref(false)
const aForm = ref({})
const CATEGORY_NAMES = { ASSET: '资产', LIABILITY: '负债', EQUITY: '权益', COST: '成本', PROFIT: '损益' }
function addAccount() {
  aForm.value = { code: '', name: '', category: 'ASSET', direction: 'DEBIT' }
  aDialog.value = true
}
function onCategoryChange(c) { aForm.value.direction = (c === 'LIABILITY' || c === 'EQUITY' || c === 'PROFIT') ? 'CREDIT' : 'DEBIT' }
async function saveAccount() {
  if (!aForm.value.code || !aForm.value.name) { ElMessage.warning('编码和名称必填'); return }
  try {
    await api.saveFinanceAccount(aForm.value)
    aDialog.value = false
    ElMessage.success('已保存')
    loadAccounts()
  } catch (e) { ElMessage.error(e.response?.data?.error || '保存失败') }
}
async function delAccount(row) {
  await ElMessageBox.confirm(`删除科目「${row.name}」？`, '确认', { type: 'warning' })
  try {
    ElMessage.success((await api.deleteFinanceAccount(row.id)).message)
    loadAccounts()
  } catch (e) { ElMessage.error(e.response?.data?.error || '删除失败') }
}

// ==================== 导出 ====================
function exportTrial() {
  if (!trial.value) return
  exportSheets(`科目余额表_${tbStart.value}_${tbEnd.value}.xlsx`, '科目余额表',
    trial.value.rows.map(r => ({
      科目编码: r.code, 科目名称: r.name, 方向: r.direction === 'DEBIT' ? '借' : '贷',
      期初余额: r.opening, 本期借方: r.periodDebit, 本期贷方: r.periodCredit, 期末余额: r.ending
    })))
}
function exportStatements() {
  if (!fs.value) return
  const b = fs.value.balanceSheet
  const rows = [
    ...b.assets.map(r => ({ 类别: '资产', 科目编码: r.code, 科目名称: r.name, 余额: r.balance })),
    ...b.liabilities.map(r => ({ 类别: '负债', 科目编码: r.code, 科目名称: r.name, 余额: r.balance })),
    ...b.equity.map(r => ({ 类别: '权益', 科目编码: r.code, 科目名称: r.name, 余额: r.balance })),
    [{ 类别: '权益', 科目名称: '未结转损益（累计净利润）', 余额: b.unclosedProfit }],
    [{ 类别: '合计', 科目名称: '资产总计', 余额: b.assetsTotal },
     { 类别: '合计', 科目名称: '负债和权益总计', 余额: b.liabEquityTotal }]
  ].flat()
  exportSheets(`财务报表_${fsStart.value}_${fsEnd.value}.xlsx`, [
    { name: '资产负债表', rows },
    { name: '利润表', rows: fs.value.income.rows.map(r => ({
        科目编码: r.code, 科目名称: r.name, 本期借方: r.debit, 本期贷方: r.credit, 净额: r.net })) }
  ])
}

function fmt(n) { return Number(n || 0).toFixed(2) }
onMounted(() => { loadAccounts(); loadTaxSettings() })
</script>

<template>
  <el-tabs v-model="tab" @tab-change="onTabChange">

    <!-- ============ 凭证管理 ============ -->
    <el-tab-pane label="凭证管理" name="vouchers">
      <el-space wrap style="margin-bottom:12px">
        <el-date-picker v-model="vStart" type="date" value-format="YYYY-MM-DD" style="width:130px" @change="loadVouchers" />
        <el-date-picker v-model="vEnd" type="date" value-format="YYYY-MM-DD" style="width:130px" @change="loadVouchers" />
        <el-input v-model="vKeyword" placeholder="摘要/凭证号" clearable style="width:160px" @keyup.enter="loadVouchers" @clear="loadVouchers" />
        <el-button @click="loadVouchers">查询</el-button>
        <el-button type="primary" @click="addVoucher">新增凭证</el-button>
      </el-space>

      <el-table :data="vouchers" border stripe max-height="560" empty-text="暂无凭证：单据过账会自动生成，也可手工新增">
        <el-table-column prop="voucherNo" label="凭证号" width="95" />
        <el-table-column prop="voucherDate" label="日期" width="100" />
        <el-table-column prop="summary" label="摘要" min-width="200" show-overflow-tooltip />
        <el-table-column label="金额" width="105">
          <template #default="{ row }">￥{{ fmt(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="95">
          <template #default="{ row }">
            <el-tag size="small" :type="{ BILL: 'success', MANUAL: 'primary', OPENING: 'info', CLOSE: 'warning' }[row.sourceType] || 'info'">
              {{ row.sourceTypeName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdBy" label="制单人" width="80" />
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="showDetail(row)">详情</el-button>
            <el-button v-if="row.sourceType !== 'BILL'" size="small" type="danger" plain @click="delVoucher(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-dialog v-model="vDialog" title="新增记账凭证" width="760px">
        <el-space style="margin-bottom:10px">
          <el-date-picker v-model="vForm.voucherDate" type="date" value-format="YYYY-MM-DD" placeholder="凭证日期" style="width:140px" />
          <el-input v-model="vForm.summary" placeholder="凭证摘要（如：本月房租）" style="width:300px" />
        </el-space>
        <el-table :data="vLines" size="small" border>
          <el-table-column label="科目" min-width="180">
            <template #default="{ row }">
              <el-select v-model="row.accountId" filterable style="width:100%">
                <el-option v-for="a in accounts.filter(x => x.enabled)" :key="a.id" :value="a.id"
                  :label="`${a.code} ${a.name}`" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="借/贷" width="90">
            <template #default="{ row }">
              <el-select v-model="row.direction" style="width:100%">
                <el-option value="DEBIT" label="借" /><el-option value="CREDIT" label="贷" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="金额" width="140">
            <template #default="{ row }"><el-input-number v-model="row.amount" :min="0" :precision="2" :controls="false" style="width:120px" /></template>
          </el-table-column>
          <el-table-column label="分录摘要" min-width="150">
            <template #default="{ row }"><el-input v-model="row.summary" /></template>
          </el-table-column>
          <el-table-column label=" " width="55">
            <template #default="{ $index }">
              <el-button size="small" type="danger" plain circle @click="removeLine($index)"><el-icon><Delete /></el-icon></el-button>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top:10px;display:flex;justify-content:space-between;align-items:center">
          <el-button size="small" @click="addLine">加一行</el-button>
          <div>
            借方合计 <b>{{ bal('DEBIT') }}</b>　贷方合计 <b>{{ bal('CREDIT') }}</b>
            <el-tag :type="vBalanced ? 'success' : 'danger'" size="small" style="margin-left:8px">
              {{ vBalanced ? '借贷平衡' : '不平衡' }}
            </el-tag>
          </div>
        </div>
        <template #footer>
          <el-button @click="vDialog = false">取消</el-button>
          <el-button type="primary" :disabled="!vBalanced" @click="saveVoucher">保存凭证</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="detailDialog" :title="vDetail ? `凭证 ${vDetail.voucher.voucherNo}（${vDetail.voucher.voucherDate}）` : ''" width="640px">
        <template v-if="vDetail">
          <p style="margin:4px 0 8px"><b>{{ vDetail.voucher.summary }}</b>
            <el-tag size="small" style="margin-left:6px">{{ SOURCE_NAMES[vDetail.voucher.sourceType] }}</el-tag>
          </p>
          <el-table :data="vDetail.items" size="small" border>
            <el-table-column label="科目" min-width="160">
              <template #default="{ row }">{{ row.accountCode }} {{ row.accountName }}</template>
            </el-table-column>
            <el-table-column label="借方" width="100">
              <template #default="{ row }">{{ row.direction === 'DEBIT' ? fmt(row.amount) : '' }}</template>
            </el-table-column>
            <el-table-column label="贷方" width="100">
              <template #default="{ row }">{{ row.direction === 'CREDIT' ? fmt(row.amount) : '' }}</template>
            </el-table-column>
            <el-table-column prop="summary" label="分录摘要" min-width="140" />
          </el-table>
          <p style="text-align:right;margin:8px 0 0">
            借方合计 ￥{{ fmt(vDetail.debitTotal) }}　贷方合计 ￥{{ fmt(vDetail.creditTotal) }}
          </p>
        </template>
        <template #footer>
          <PaperSelect style="margin-right:auto" />
          <el-button @click="detailDialog = false">关闭</el-button>
          <el-button type="primary" @click="printVoucher">打印凭证</el-button>
        </template>
      </el-dialog>
    </el-tab-pane>

    <!-- ============ 科目余额表 ============ -->
    <el-tab-pane label="科目余额表" name="trial">
      <el-space wrap style="margin-bottom:12px">
        <el-date-picker v-model="tbStart" type="date" value-format="YYYY-MM-DD" style="width:130px" />
        <el-date-picker v-model="tbEnd" type="date" value-format="YYYY-MM-DD" style="width:130px" />
        <el-button type="primary" @click="loadTrial">查询</el-button>
        <el-button @click="exportTrial">导出 Excel</el-button>
        <el-tag v-if="trial" :type="trial.balanced ? 'success' : 'danger'" size="small">
          {{ trial.balanced ? '✓ 本期借贷平衡' : '✗ 借贷不平衡，请检查凭证' }}
        </el-tag>
      </el-space>
      <el-table v-if="trial" :data="trial.rows" border stripe max-height="560" show-summary
        :summary-method="() => ['合计', '', '', fmt(trial.totals.openingDebitCreditDiff), fmt(trial.totals.periodDebit), fmt(trial.totals.periodCredit), fmt(trial.totals.endingDebitCreditDiff)]">
        <el-table-column prop="code" label="科目编码" width="100" />
        <el-table-column prop="name" label="科目名称" min-width="140" />
        <el-table-column label="方向" width="60">
          <template #default="{ row }">{{ row.direction === 'DEBIT' ? '借' : '贷' }}</template>
        </el-table-column>
        <el-table-column label="期初余额" width="110">
          <template #default="{ row }">{{ fmt(row.opening) }}</template>
        </el-table-column>
        <el-table-column label="本期借方" width="110">
          <template #default="{ row }">{{ fmt(row.periodDebit) }}</template>
        </el-table-column>
        <el-table-column label="本期贷方" width="110">
          <template #default="{ row }">{{ fmt(row.periodCredit) }}</template>
        </el-table-column>
        <el-table-column label="期末余额" width="110">
          <template #default="{ row }">{{ fmt(row.ending) }}</template>
        </el-table-column>
      </el-table>
    </el-tab-pane>

    <!-- ============ 明细账 ============ -->
    <el-tab-pane label="明细账" name="ledger">
      <el-space wrap style="margin-bottom:12px">
        <el-select v-model="ldAccountId" filterable placeholder="选择科目" style="width:220px">
          <el-option v-for="a in accounts" :key="a.id" :value="a.id" :label="`${a.code} ${a.name}`" />
        </el-select>
        <el-date-picker v-model="ldStart" type="date" value-format="YYYY-MM-DD" style="width:130px" />
        <el-date-picker v-model="ldEnd" type="date" value-format="YYYY-MM-DD" style="width:130px" />
        <el-button type="primary" @click="loadLedger">查询</el-button>
      </el-space>
      <template v-if="ledger">
        <p style="margin:4px 0 8px;color:#606266">
          {{ ledger.account.code }} {{ ledger.account.name }}　期初余额 <b>{{ fmt(ledger.opening) }}</b>　期末余额 <b>{{ fmt(ledger.ending) }}</b>
        </p>
        <el-table :data="ledger.rows" border stripe max-height="520" empty-text="该期间没有此科目的分录">
          <el-table-column prop="date" label="日期" width="110" />
          <el-table-column prop="voucherNo" label="凭证号" width="100" />
          <el-table-column prop="summary" label="摘要" min-width="160" />
          <template v-if="ledger.account.quantityEnabled">
            <el-table-column label="收入数量" width="90">
              <template #default="{ row }">{{ row.debit > 0 && row.quantity != null ? row.quantity : '' }}</template>
            </el-table-column>
            <el-table-column label="发出数量" width="90">
              <template #default="{ row }">{{ row.credit > 0 && row.quantity != null ? row.quantity : '' }}</template>
            </el-table-column>
            <el-table-column label="结存数量" width="90">
              <template #default="{ row }">{{ row.qtyBalance != null ? row.qtyBalance : '' }}</template>
            </el-table-column>
            <el-table-column label="单价" width="80">
              <template #default="{ row }">{{ row.unitPrice != null ? fmt(row.unitPrice) : '' }}</template>
            </el-table-column>
          </template>
          <el-table-column label="借方" width="110">
            <template #default="{ row }">{{ fmt(row.debit) }}</template>
          </el-table-column>
          <el-table-column label="贷方" width="110">
            <template #default="{ row }">{{ fmt(row.credit) }}</template>
          </el-table-column>
          <el-table-column label="余额" width="110">
            <template #default="{ row }">{{ fmt(row.balance) }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-tab-pane>

    <!-- ============ 财务报表 ============ -->
    <el-tab-pane label="财务报表" name="statements">
      <el-space wrap style="margin-bottom:8px">
        <el-date-picker v-model="fsStart" type="date" value-format="YYYY-MM-DD" style="width:130px" />
        <el-date-picker v-model="fsEnd" type="date" value-format="YYYY-MM-DD" style="width:130px" />
        <el-button type="primary" @click="loadStatements">生成报表</el-button>
        <el-button @click="exportStatements">导出 Excel</el-button>
        <el-button type="warning" plain @click="doCloseProfit">期末结转损益</el-button>
        <el-button type="warning" plain @click="doYearClose">年度结转</el-button>
        <el-button @click="showInvoiceList">开票清单</el-button>
      </el-space>
      <el-card v-if="taxSettings" shadow="never" style="margin-bottom:12px">
        <el-space wrap>
          <b>计税方式：</b>
          <el-switch v-model="taxSettings.taxEnabled" active-text="价税分离（含税开单自动拆分税额）"
            inactive-text="不计税（小规模/个体户，全额入账）" />
          <template v-if="taxSettings.taxEnabled">
            默认税率
            <el-select v-model="taxSettings.defaultTaxRate" style="width:90px">
              <el-option :value="13" label="13%" /><el-option :value="9" label="9%" />
              <el-option :value="6" label="6%" /><el-option :value="0" label="0%" />
            </el-select>
          </template>
          <el-button size="small" type="primary" plain @click="saveTaxSettings">保存计税设置</el-button>
          <span style="color:#909399;font-size:12px">商品可单独设税率（空=用默认）；设置即时生效，只影响之后过账的单据</span>
        </el-space>
      </el-card>
      <template v-if="fs">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-card shadow="hover">
              <template #header><b>资产负债表</b>（截至 {{ fs.end }}）
                <el-tag :type="fs.balanceSheet.balanced ? 'success' : 'danger'" size="small" style="float:right">
                  {{ fs.balanceSheet.balanced ? '✓ 账平' : '✗ 不平' }}
                </el-tag>
              </template>
              <el-table :data="[...fs.balanceSheet.assets, ...fs.balanceSheet.liabilities, ...fs.balanceSheet.equity,
                { code: '', name: '未结转损益（累计净利润）', balance: fs.balanceSheet.unclosedProfit }]"
                size="small" border max-height="420">
                <el-table-column prop="code" label="编码" width="70" />
                <el-table-column prop="name" label="科目" min-width="150" />
                <el-table-column label="余额" width="110">
                  <template #default="{ row }">{{ fmt(row.balance) }}</template>
                </el-table-column>
              </el-table>
              <p style="text-align:right;margin:8px 0 0;font-size:13px">
                资产总计 <b>￥{{ fmt(fs.balanceSheet.assetsTotal) }}</b>
                　负债和权益总计 <b>￥{{ fmt(fs.balanceSheet.liabEquityTotal) }}</b>
              </p>
            </el-card>
          </el-col>
          <el-col :span="12">
            <el-card shadow="hover">
              <template #header><b>利润表</b>（{{ fs.start }} ~ {{ fs.end }}）</template>
              <el-table :data="fs.income.rows" size="small" border max-height="420" empty-text="该期间没有损益发生额">
                <el-table-column prop="code" label="编码" width="70" />
                <el-table-column prop="name" label="科目" min-width="150" />
                <el-table-column label="借方" width="90">
                  <template #default="{ row }">{{ fmt(row.debit) }}</template>
                </el-table-column>
                <el-table-column label="贷方" width="90">
                  <template #default="{ row }">{{ fmt(row.credit) }}</template>
                </el-table-column>
                <el-table-column label="净额" width="90">
                  <template #default="{ row }">{{ fmt(row.net) }}</template>
                </el-table-column>
              </el-table>
              <p style="text-align:right;margin:8px 0 0">
                本期净利润 <b style="color:#67c23a">￥{{ fmt(fs.income.netProfit) }}</b>
              </p>
            </el-card>
          </el-col>
        </el-row>

        <!-- 增值税辅助表（计税开启时显示） -->
        <el-card v-if="taxSettings?.taxEnabled && vat" shadow="hover" style="margin-top:16px">
          <template #header><b>增值税辅助表</b>（{{ fsStart }} ~ {{ fsEnd }}）
            <span style="color:#909399;font-size:12px;margin-left:8px">应纳税额 = 销项 − 进项（负数为留抵），仅供申报参考</span>
          </template>
          <el-table :data="vat.rows" size="small" border empty-text="该期间没有带税率的销项/进项分录">
            <el-table-column label="税率" width="90">
              <template #default="{ row }">{{ row.rate }}%</template>
            </el-table-column>
            <el-table-column label="销售额（不含税）" width="150">
              <template #default="{ row }">{{ fmt(row.salesNet) }}</template>
            </el-table-column>
            <el-table-column label="销项税额" width="120">
              <template #default="{ row }">{{ fmt(row.outputTax) }}</template>
            </el-table-column>
            <el-table-column label="采购额（不含税）" width="150">
              <template #default="{ row }">{{ fmt(row.purchaseNet) }}</template>
            </el-table-column>
            <el-table-column label="进项税额" width="120">
              <template #default="{ row }">{{ fmt(row.inputTax) }}</template>
            </el-table-column>
            <el-table-column label="应纳税额" width="120">
              <template #default="{ row }"><b>{{ fmt(row.payable) }}</b></template>
            </el-table-column>
          </el-table>
          <p style="text-align:right;margin:8px 0 0;font-size:13px">
            销项合计 <b>{{ fmt(vat.outputTaxTotal) }}</b>
            　进项合计 <b>{{ fmt(vat.inputTaxTotal) }}</b>
            　应纳税额合计 <b style="color:#e6a23c">{{ fmt(vat.payableTotal) }}</b>
          </p>
        </el-card>

        <!-- 现金流量表（简化直接法，期末与资产负债表货币资金勾稽） -->
        <el-card v-if="cashFlow" shadow="hover" style="margin-top:16px">
          <template #header><b>现金流量表</b>（{{ cashFlow.start }} ~ {{ cashFlow.end }}，简化直接法）
            <span style="float:right;font-size:13px">
              期初现金 {{ fmt(cashFlow.opening) }}
              　流入 {{ fmt(cashFlow.inflowTotal) }}
              　流出 {{ fmt(cashFlow.outflowTotal) }}
              　净额 {{ fmt(cashFlow.netTotal) }}
              　期末现金 <b>{{ fmt(cashFlow.ending) }}</b>
            </span>
          </template>
          <el-table :data="cashFlow.rows" size="small" border empty-text="该期间没有现金收付">
            <el-table-column prop="category" label="类别" width="80" />
            <el-table-column prop="item" label="项目" min-width="240" />
            <el-table-column label="流入" width="120">
              <template #default="{ row }">{{ fmt(row.inflow) }}</template>
            </el-table-column>
            <el-table-column label="流出" width="120">
              <template #default="{ row }">{{ fmt(row.outflow) }}</template>
            </el-table-column>
            <el-table-column label="净额" width="120">
              <template #default="{ row }">{{ fmt(row.net) }}</template>
            </el-table-column>
          </el-table>
        </el-card>

        <!-- 开票清单 -->
        <el-dialog v-model="invoiceDialog" title="开票清单（已过账销售单）" width="760px">
          <el-table :data="invoiceRows" size="small" border max-height="420" empty-text="该期间没有已过账销售单">
            <el-table-column prop="date" label="日期" width="100" />
            <el-table-column prop="billNo" label="单号" width="140" />
            <el-table-column prop="partner" label="客户" min-width="130" />
            <el-table-column label="金额" width="100">
              <template #default="{ row }">{{ fmt(row.amount) }}</template>
            </el-table-column>
            <template v-if="taxSettings?.taxEnabled">
              <el-table-column label="不含税" width="100">
                <template #default="{ row }">{{ row.net != null ? fmt(row.net) : '' }}</template>
              </el-table-column>
              <el-table-column label="税额" width="90">
                <template #default="{ row }">{{ row.tax != null ? fmt(row.tax) : '' }}</template>
              </el-table-column>
            </template>
            <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
          </el-table>
          <el-alert type="info" :closable="false" style="margin-top:10px"
            title="开票需在电子税务局或税控设备中操作，本清单供录入参考" />
          <template #footer>
            <el-button @click="exportInvoice">导出 Excel</el-button>
            <el-button type="primary" @click="invoiceDialog = false">关闭</el-button>
          </template>
        </el-dialog>
      </template>
    </el-tab-pane>

    <!-- ============ 科目设置 ============ -->
    <el-tab-pane label="科目设置" name="accounts">
      <el-space style="margin-bottom:12px">
        <el-button type="primary" @click="addAccount">新增科目</el-button>
        <span style="color:#909399;font-size:12px">系统预置 17 个常用科目（不可删除），可按需新增自定义科目</span>
      </el-space>
      <el-table :data="accounts" border stripe max-height="560">
        <el-table-column prop="code" label="科目编码" width="110" />
        <el-table-column prop="name" label="科目名称" min-width="160" />
        <el-table-column label="类别" width="90">
          <template #default="{ row }">{{ CATEGORY_NAMES[row.category] || row.category }}</template>
        </el-table-column>
        <el-table-column label="余额方向" width="90">
          <template #default="{ row }">{{ row.direction === 'DEBIT' ? '借' : '贷' }}</template>
        </el-table-column>
        <el-table-column label="预置" width="70">
          <template #default="{ row }"><el-tag v-if="row.builtin" size="small" type="info">预置</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="aForm = { ...row }; aDialog = true">编辑</el-button>
            <el-button size="small" type="danger" plain :disabled="!!row.builtin" @click="delAccount(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-dialog v-model="aDialog" :title="aForm.id ? '编辑科目' : '新增科目'" width="460px">
        <el-form :model="aForm" label-width="90px">
          <el-form-item label="科目编码"><el-input v-model="aForm.code" :disabled="!!aForm.id" placeholder="如 6603" /></el-form-item>
          <el-form-item label="科目名称"><el-input v-model="aForm.name" placeholder="如 差旅费" /></el-form-item>
          <el-form-item label="类别">
            <el-select v-model="aForm.category" style="width:100%" @change="onCategoryChange">
              <el-option v-for="(n, c) in CATEGORY_NAMES" :key="c" :value="c" :label="n" />
            </el-select>
          </el-form-item>
          <el-form-item label="余额方向">
            <el-select v-model="aForm.direction" style="width:100%">
              <el-option value="DEBIT" label="借" /><el-option value="CREDIT" label="贷" />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="aDialog = false">取消</el-button>
          <el-button type="primary" @click="saveAccount">保存</el-button>
        </template>
      </el-dialog>
    </el-tab-pane>
  </el-tabs>
</template>
