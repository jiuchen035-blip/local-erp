<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exportSheets } from '../utils/export'
import { printHtml, shopHeaderHtml } from '../utils/print'
import PaperSelect from '../components/PaperSelect.vue'

const tab = ref('receivable')
const summary = ref({ receivables: [], payables: [], receivableTotal: 0, payableTotal: 0 })
const expanded = ref({})
const unpaidMap = ref({})
const shop = ref({ shopName: '', phone: '', address: '' })

/** 打印设置弹窗：先选日期范围再出对账单 */
const printDialog = ref(false)
const printJob = ref(null)   // { partnerId, name, phone, opening }
const printRange = ref({ start: '', end: '' })
const printRecords = ref([])
const printing = ref(false)

async function load() {
  summary.value = await api.ledgerSummary()
}
const currentList = () => tab.value === 'receivable' ? summary.value.receivables : summary.value.payables
const totalOf = () => tab.value === 'receivable' ? summary.value.receivableTotal : summary.value.payableTotal
const typeLabel = () => tab.value === 'receivable' ? '应收' : '应付'

async function toggleExpand(row) {
  expanded.value[row.partnerId] = !expanded.value[row.partnerId]
  if (expanded.value[row.partnerId]) {
    unpaidMap.value[row.partnerId] = await api.unpaidRecords(row.partnerId)
  }
}
async function settleOne(recordId, partnerId) {
  const r = await api.settle(recordId)
  ElMessage.success(r.message)
  unpaidMap.value[partnerId] = await api.unpaidRecords(partnerId)
  load()
}
function settleAll(row) {
  ElMessageBox.confirm(`确认结清「${row.name}」的全部${typeLabel()}款？合计 ￥${row.amount}`, '结清确认', { type: 'warning' })
    .then(() => api.settlePartner(row.partnerId, tab.value === 'receivable' ? 'RECEIVABLE' : 'PAYABLE'))
    .then(r => { ElMessage.success(r.message); load() })
    .catch(() => {})
}

/** 打印对账单：先弹日期范围（留空=全部），确认后渲染打印 */
async function doPrint(row) {
  printJob.value = { partnerId: row.partnerId, name: row.name, phone: row.phone || '', opening: row.opening || 0 }
  printRange.value = { start: '', end: '' }
  printRecords.value = await api.unpaidRecords(row.partnerId)
  printDialog.value = true
}
function inRange(rec) {
  const day = (rec.createdAt || '').slice(0, 10)
  if (printRange.value.start && day < printRange.value.start) return false
  if (printRange.value.end && day > printRange.value.end) return false
  return true
}
function rangeCount() { return printRecords.value.filter(inRange).length }
function rangeAmount() { return printRecords.value.filter(inRange).reduce((s, r) => s + r.amount, 0) }
function confirmPrint() {
  const records = printRecords.value.filter(inRange)
  if (!records.length) { ElMessage.warning('所选日期范围内没有挂账记录'); return }
  const job = printJob.value
  const opening = job.opening || 0
  const creditTotal = records.reduce((s, r) => s + r.amount, 0)
  const filtered = printRange.value.start || printRange.value.end
  const rowsHtml = records.map(r => `<tr>
    <td>${r.billNo || '#' + r.id}</td>
    <td>${(r.createdAt || '-').slice(0, 10)}</td>
    <td>${r.productName}</td>
    <td>${r.remark || '-'}</td>
    <td style="text-align:right">${r.quantity}</td>
    <td style="text-align:right">${r.price}</td>
    <td style="text-align:right">${r.amount.toFixed(2)}</td>
  </tr>`).join('')

  const html = `
  <div class="receipt">
    ${shopHeaderHtml(shop.value.shopName, typeLabel() + '对账单')}
    <div class="meta"><span>往来单位</span><span>${job.name}</span></div>
    <div class="meta"><span>电话</span><span>${job.phone || '-'}</span></div>
    <div class="meta"><span>对账日期</span><span>${new Date().toLocaleDateString('zh-CN')}</span></div>
    ${filtered ? `<div class="meta"><span>统计范围</span><span>${printRange.value.start || '不限'} ~ ${printRange.value.end || '不限'}</span></div>` : ''}
    <table>
      <thead><tr><th>单据号</th><th>日期</th><th>商品名称</th><th>备注</th><th style="text-align:right">数量</th><th style="text-align:right">单价</th><th style="text-align:right">金额</th></tr></thead>
      <tbody>${rowsHtml}</tbody>
    </table>
    <div style="margin-top:8px;line-height:1.8">
      <div style="display:flex;justify-content:space-between"><span>期初余额</span><span>￥${opening.toFixed(2)}</span></div>
      <div style="display:flex;justify-content:space-between"><span>本期${typeLabel()}（${records.length}笔）</span><span>￥${creditTotal.toFixed(2)}</span></div>
      <div style="display:flex;justify-content:space-between;font-weight:bold;font-size:14px;border-top:1px solid #333;padding-top:4px">
        <span>合计应付${typeLabel()}款</span><span>￥${(opening + creditTotal).toFixed(2)}</span>
      </div>
    </div>
    <div class="sign-row">
      <span>客户签字：______________</span><span>日期：______________</span><span>店家盖章：______________</span>
    </div>
    <div class="sign">打印时间：${new Date().toLocaleString('zh-CN')}<br/>如有疑问请及时与本公司核对</div>
  </div>`
  printHtml(html)
  printDialog.value = false
}

/** 修改备注（作用于整张单据） */
async function editRemark(r, partnerId) {
  const { value } = await ElMessageBox.prompt('修改备注（同一单据的所有行共用此备注）', `单据 ${r.billNo || '#' + r.id}`, {
    inputValue: r.remark || '',
    inputPlaceholder: '如：送货单（未收款）/ 月结 / 已付定金'
  }).catch(() => ({ value: undefined }))
  if (value === undefined) return
  const res = await api.updateLedgerRemark(r.id, value)
  ElMessage.success(res.message)
  unpaidMap.value[partnerId] = await api.unpaidRecords(partnerId)
}

/** 导出 Excel：汇总 + 逐单位挂账明细（含商品名/备注） */
async function doExport() {
  const list = currentList()
  if (!list.length) { ElMessage.info('没有可导出的数据'); return }
  const detailRows = []
  for (const row of list) {
    const records = await api.unpaidRecords(row.partnerId)
    for (const r of records) {
      detailRows.push({
        往来单位: row.name, 单据号: r.billNo || ('#' + r.id), 时间: r.createdAt || '',
        商品名称: r.productName, 数量: r.quantity, 单价: r.price, 金额: r.amount,
        备注: r.remark || ''
      })
    }
  }
  exportSheets(`${typeLabel()}对账_${new Date().toLocaleDateString('zh-CN')}.xlsx`, [
    { name: typeLabel() + '汇总', rows: list.map(r => ({
      名称: r.name, 电话: r.phone || '', 期初余额: r.opening || 0,
      挂账笔数: r.cnt, 挂账金额: r.amount, 合计: r.amount
    })) },
    { name: '挂账明细', rows: detailRows }
  ])
}
onMounted(() => {
  load()
  api.getShopInfo().then(s => shop.value = s).catch(() => {})
})
</script>

<template>
  <el-tabs v-model="tab" @tab-change="load">
    <el-tab-pane name="receivable">
      <template #label>应收（客户欠我）</template>
    </el-tab-pane>
    <el-tab-pane name="payable">
      <template #label>应付（我欠供应商）</template>
    </el-tab-pane>
  </el-tabs>

  <el-row style="margin-bottom:14px">
    <el-col :span="12">
      <el-statistic :title="`总${typeLabel()}款`" :value="totalOf()" :precision="2" prefix="￥" />
    </el-col>
    <el-col :span="12" style="text-align:right">
      <PaperSelect style="margin-right:10px" />
      <el-button @click="doExport">导出 Excel（汇总+明细）</el-button>
    </el-col>
  </el-row>

  <el-table :data="currentList()" border stripe>
    <el-table-column type="expand">
      <template #default="{ row }">
        <div style="padding: 8px 24px" v-if="expanded[row.partnerId]">
          <el-table :data="unpaidMap[row.partnerId] || []" size="small" border>
            <el-table-column label="单据号" width="150">
              <template #default="{ row: r }">{{ r.billNo || '#' + r.id }}</template>
            </el-table-column>
            <el-table-column label="时间" prop="createdAt" width="160" />
            <el-table-column label="商品名称" prop="productName" min-width="130" />
            <el-table-column label="备注" min-width="140">
              <template #default="{ row: r }">
                <span>{{ r.remark || '-' }}</span>
                <el-button link size="small" type="primary" @click="editRemark(r, row.partnerId)">修改</el-button>
              </template>
            </el-table-column>
            <el-table-column label="数量" prop="quantity" width="65" />
            <el-table-column label="单价" prop="price" width="75" />
            <el-table-column label="金额" width="95">
              <template #default="{ row: r }">￥{{ r.amount?.toFixed(2) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="95" fixed="right">
              <template #default="{ row: r }">
                <el-button size="small" type="success" plain @click="settleOne(r.id, row.partnerId)">核销</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </el-table-column>
    <el-table-column prop="name" label="往来单位" min-width="150" />
    <el-table-column prop="phone" label="电话" width="130" />
    <el-table-column label="期初余额" width="100">
      <template #default="{ row }">{{ row.opening ? '￥' + row.opening : '-' }}</template>
    </el-table-column>
    <el-table-column prop="cnt" label="挂账笔数" width="100" />
    <el-table-column label="挂账金额" width="130">
      <template #default="{ row }"><b style="color:#f56c6c">￥{{ row.amount }}</b></template>
    </el-table-column>
    <el-table-column label="操作" width="230" fixed="right">
      <template #default="{ row }">
        <el-button size="small" @click="toggleExpand(row)">明细</el-button>
        <el-button size="small" type="success" @click="settleAll(row)">结清</el-button>
        <el-button size="small" type="primary" plain @click="doPrint(row)">打印对账单</el-button>
      </template>
    </el-table-column>
  </el-table>
  <el-empty v-if="!currentList().length" description="没有挂账单据——开单时选择“挂账”会出现在这里" />

  <el-dialog v-model="printDialog" title="打印对账单" width="440px">
    <p style="margin-top:0">往来单位：<b>{{ printJob?.name }}</b>（共 {{ printRecords.length }} 笔未结）</p>
    <el-form label-width="90px">
      <el-form-item label="日期范围">
        <el-space wrap>
          <el-date-picker v-model="printRange.start" type="date" value-format="YYYY-MM-DD"
            placeholder="开始（留空不限）" style="width:135px" clearable />
          <span>~</span>
          <el-date-picker v-model="printRange.end" type="date" value-format="YYYY-MM-DD"
            placeholder="结束（留空不限）" style="width:135px" clearable />
        </el-space>
      </el-form-item>
    </el-form>
    <p style="color:#909399;font-size:12px;margin:4px 0 0">
      当前范围：{{ rangeCount() }} 笔、￥{{ rangeAmount().toFixed(2) }}。
      送货当天可以先按当天日期打一张送货对账单给客户，年底结清时再打一张不限日期的总单。
    </p>
    <template #footer>
      <el-button @click="printDialog = false">取消</el-button>
      <el-button type="primary" :disabled="!rangeCount()" @click="confirmPrint">生成并打印</el-button>
    </template>
  </el-dialog>
</template>
