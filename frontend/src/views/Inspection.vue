<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exportExcel } from '../utils/export'

const rows = ref([])
const stat = ref({ overdue: 0, due: 0, normal: 0, total: 0 })
const purposes = ref([])
const filters = ref({ status: '', purpose: '', keyword: '', dueDays: 30 })
const dialog = ref(false)
const form = ref({})
const renewDialog = ref(false)
const renewForm = ref({ id: null, name: '', date: '' })
// 批量操作（对齐小程序）：多选 → 批量续检 / 批量删除
const selected = ref([])
const batchBusy = ref(false)
const batchRenewDialog = ref(false)
const batchRenewDate = ref('')

const STATUS_META = {
  OVERDUE: { label: '已到期待检', type: 'danger' },
  DUE: { label: '即将到期', type: 'warning' },
  NORMAL: { label: '正常', type: 'success' }
}

async function load() {
  const r = await api.inspections({
    status: filters.value.status || undefined,
    purpose: filters.value.purpose || undefined,
    keyword: filters.value.keyword || undefined,
    dueDays: filters.value.dueDays
  })
  rows.value = r.rows
  stat.value = { overdue: r.overdue, due: r.due, normal: r.normal, total: r.total }
}
async function loadPurposes() { purposes.value = await api.purposes() }
function filterBy(status) {
  filters.value.status = status
  load()
}

function today() { return new Date().toISOString().slice(0, 10) }
function add() {
  form.value = { customerName: '', phone: '', address: '', purpose: '工地', spec: '',
    quantity: 1, price: 0, inspectDate: today(), nextDate: '', remark: '' }
  dialog.value = true
}
function edit(row) { form.value = { ...row }; dialog.value = true }
async function save() {
  if (!form.value.customerName) { ElMessage.warning('客户名称必填'); return }
  try {
    const r = await api.saveInspection(form.value)
    ElMessage.success(r.message)
    dialog.value = false
    load()
  } catch (e) { ElMessage.error(e.response?.data?.error || '保存失败') }
}
function renew(row) {
  renewForm.value = { id: row.id, name: row.customerName, date: today() }
  renewDialog.value = true
}
async function doRenew() {
  try {
    const r = await api.renewInspection(renewForm.value.id, renewForm.value.date)
    ElMessage.success(r.message)
    renewDialog.value = false
    load()
  } catch (e) { ElMessage.error(e.response?.data?.error || '续检失败') }
}
function del(row) {
  ElMessageBox.confirm(`删除「${row.customerName}」的年检记录？`, '确认', { type: 'warning' })
    .then(() => api.deleteInspection(row.id))
    .then(() => { ElMessage.success('已删除'); load() })
    .catch(() => {})
}

function openBatchRenew() {
  if (!selected.value.length || batchBusy.value) return
  batchRenewDate.value = today()
  batchRenewDialog.value = true
}
async function doBatchRenew() {
  batchBusy.value = true
  let ok = 0, fail = 0
  for (const row of selected.value) {
    try { await api.renewInspection(row.id, batchRenewDate.value); ok++ } catch { fail++ }
  }
  batchBusy.value = false
  batchRenewDialog.value = false
  ElMessage({ type: fail ? 'warning' : 'success',
    message: `批量续检完成：成功 ${ok} 条${fail ? `，失败 ${fail} 条` : ''}` })
  load()
}
async function batchDel() {
  const n = selected.value.length
  if (!n || batchBusy.value) return
  try {
    await ElMessageBox.confirm(`删除选中的 ${n} 条年检记录？删除后不可恢复。`, '批量删除', { type: 'warning' })
  } catch { return }
  batchBusy.value = true
  let ok = 0, fail = 0
  for (const row of selected.value) {
    try { await api.deleteInspection(row.id); ok++ } catch { fail++ }
  }
  batchBusy.value = false
  ElMessage({ type: fail ? 'warning' : 'success',
    message: `批量删除完成：成功 ${ok} 条${fail ? `，失败 ${fail} 条` : ''}` })
  load()
}
function doExport() {
  exportExcel(`灭火器年检_${today()}.xlsx`, '年检记录',
    rows.value.map(r => ({
      客户名称: r.customerName, 电话: r.phone, 地址: r.address, 用途: r.purpose,
      灭火器规格: r.spec, 数量: r.quantity, 单价: r.price, 金额: r.totalAmount,
      本次年检: r.inspectDate, 下次年检: r.nextDate,
      状态: { OVERDUE: '已到期待检', DUE: '即将到期', NORMAL: '正常' }[r.status],
      备注: r.remark || ''
    })))
}
/** 打电话名单：已到期+即将到期 */
async function exportDue() {
  const list = await api.dueList(filters.value.dueDays)
  if (!list.length) { ElMessage.info('当前没有到期/临期的记录'); return }
  exportExcel(`年检催检名单_${today()}.xlsx`, '催检名单',
    list.map(r => ({
      客户名称: r.customerName, 电话: r.phone, 地址: r.address, 用途: r.purpose,
      数量: r.quantity, 下次年检: r.nextDate,
      距今天数: r.daysLeft < 0 ? `已过期${-r.daysLeft}天` : `${r.daysLeft}天后`,
      状态: { OVERDUE: '已到期待检', DUE: '即将到期' }[r.status]
    })))
}
onMounted(() => { load(); loadPurposes() })
</script>

<template>
  <el-row :gutter="16" style="margin-bottom:16px">
    <el-col :span="6">
      <el-card shadow="hover" style="cursor:pointer" :class="{ 'stat-active': filters.status === 'OVERDUE' }" @click="filterBy('OVERDUE')">
        <el-statistic title="已到期待检（点击筛选）" :value="stat.overdue"><template #suffix><span style="font-size:13px;color:#f56c6c">条</span></template></el-statistic>
      </el-card>
    </el-col>
    <el-col :span="6">
      <el-card shadow="hover" style="cursor:pointer" :class="{ 'stat-active': filters.status === 'DUE' }" @click="filterBy('DUE')">
        <el-statistic :title="`即将到期(${filters.dueDays}天内，点击筛选)`" :value="stat.due"><template #suffix><span style="font-size:13px;color:#e6a23c">条</span></template></el-statistic>
      </el-card>
    </el-col>
    <el-col :span="6">
      <el-card shadow="hover" style="cursor:pointer" :class="{ 'stat-active': filters.status === 'NORMAL' }" @click="filterBy('NORMAL')">
        <el-statistic title="正常（点击筛选）" :value="stat.normal"><template #suffix><span style="font-size:13px;color:#67c23a">条</span></template></el-statistic>
      </el-card>
    </el-col>
    <el-col :span="6">
      <el-card shadow="hover" style="cursor:pointer" :class="{ 'stat-active': !filters.status }" @click="filterBy('')">
        <el-statistic title="年检档案总数（点击看全部）" :value="stat.total"><template #suffix><span style="font-size:13px;color:#909399">条</span></template></el-statistic>
      </el-card>
    </el-col>
  </el-row>

  <el-space wrap style="margin-bottom:14px">
    <el-select v-model="filters.status" clearable placeholder="状态" style="width:130px" @change="load">
      <el-option label="已到期待检" value="OVERDUE" />
      <el-option label="即将到期" value="DUE" />
      <el-option label="正常" value="NORMAL" />
    </el-select>
    <el-select v-model="filters.purpose" clearable filterable allow-create placeholder="用途" style="width:120px" @change="load">
      <el-option v-for="p in purposes" :key="p" :label="p" :value="p" />
    </el-select>
    <el-input v-model="filters.keyword" placeholder="客户名称 / 电话" clearable style="width:180px" @keyup.enter="load" @clear="load" />
    <el-input-number v-model="filters.dueDays" :min="7" :max="180" :step="15" size="default" @change="load" />
    <span style="color:#909399;font-size:12px">天前提醒</span>
    <el-button @click="load">查询</el-button>
    <el-button type="primary" @click="add">新增年检</el-button>
    <el-button type="warning" plain :disabled="!selected.length" :loading="batchBusy" @click="openBatchRenew">
      批量续检{{ selected.length ? `(${selected.length})` : '' }}</el-button>
    <el-button type="danger" plain :disabled="!selected.length" :loading="batchBusy" @click="batchDel">
      批量删除{{ selected.length ? `(${selected.length})` : '' }}</el-button>
    <el-button type="success" plain @click="exportDue">导出催检名单</el-button>
    <el-button type="success" plain @click="doExport">导出全部(Excel)</el-button>
  </el-space>

  <el-table :data="rows" border stripe :row-class-name="({ row }) => row.status === 'OVERDUE' ? 'overdue-row' : ''"
    @selection-change="rows => selected = rows">
    <el-table-column type="selection" width="45" />
    <el-table-column prop="customerName" label="客户名称" min-width="130" />
    <el-table-column prop="phone" label="电话" width="125" />
    <el-table-column prop="address" label="地址" min-width="120" show-overflow-tooltip />
    <el-table-column prop="purpose" label="用途" width="85" />
    <el-table-column prop="spec" label="规格" width="95" />
    <el-table-column prop="quantity" label="数量" width="65" />
    <el-table-column prop="price" label="单价" width="70" />
    <el-table-column label="金额" width="80">
      <template #default="{ row }">￥{{ row.totalAmount }}</template>
    </el-table-column>
    <el-table-column prop="inspectDate" label="本次年检" width="105" />
    <el-table-column label="下次年检" width="105">
      <template #default="{ row }">
        <span :style="{ color: row.status === 'OVERDUE' ? '#f56c6c' : row.status === 'DUE' ? '#e6a23c' : '', fontWeight: row.status !== 'NORMAL' ? 'bold' : '' }">{{ row.nextDate }}</span>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="100">
      <template #default="{ row }">
        <el-tag :type="STATUS_META[row.status].type" size="small">{{ STATUS_META[row.status].label }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="180" fixed="right">
      <template #default="{ row }">
        <el-button size="small" type="primary" plain @click="renew(row)">续检</el-button>
        <el-button size="small" @click="edit(row)">编辑</el-button>
        <el-button size="small" type="danger" plain @click="del(row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-dialog v-model="dialog" :title="form.id ? '编辑年检记录' : '新增年检记录'" width="560px">
    <el-form :model="form" label-width="100px">
      <el-form-item label="客户名称"><el-input v-model="form.customerName" /></el-form-item>
      <el-form-item label="电话"><el-input v-model="form.phone" /></el-form-item>
      <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
      <el-form-item label="用途">
        <el-select v-model="form.purpose" filterable allow-create default-first-option style="width:100%">
          <el-option v-for="p in purposes" :key="p" :label="p" :value="p" />
        </el-select>
      </el-form-item>
      <el-form-item label="灭火器规格">
        <el-input v-model="form.spec" placeholder="如：4kg干粉 / 35kg推车" />
      </el-form-item>
      <el-form-item label="数量"><el-input-number v-model="form.quantity" :min="1" /></el-form-item>
      <el-form-item label="年检单价"><el-input-number v-model="form.price" :min="0" :precision="2" /></el-form-item>
      <el-form-item label="本次年检日期">
        <el-date-picker v-model="form.inspectDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
      </el-form-item>
      <el-form-item label="下次年检日期">
        <el-date-picker v-model="form.nextDate" type="date" value-format="YYYY-MM-DD" style="width:100%"
          :placeholder="`默认本次+12个月，可手动修改`" />
      </el-form-item>
      <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" :rows="2" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="renewDialog" title="续检" width="400px">    <p style="margin-top:0">客户：<b>{{ renewForm.name }}</b></p>
    <el-form label-width="100px">
      <el-form-item label="本次年检日期">
        <el-date-picker v-model="renewForm.date" type="date" value-format="YYYY-MM-DD" style="width:100%" />
      </el-form-item>
    </el-form>
    <p style="color:#909399;font-size:12px">将生成一条新记录，下次年检默认为本次日期+12个月，可再编辑</p>
    <template #footer>
      <el-button @click="renewDialog = false">取消</el-button>
      <el-button type="primary" @click="doRenew">确认续检</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="batchRenewDialog" title="批量续检" width="400px">
    <p style="margin-top:0">将为选中的 <b>{{ selected.length }}</b> 条记录各生成一条新年检记录</p>
    <el-form label-width="100px">
      <el-form-item label="本次年检日期">
        <el-date-picker v-model="batchRenewDate" type="date" value-format="YYYY-MM-DD" style="width:100%" />
      </el-form-item>
    </el-form>
    <p style="color:#909399;font-size:12px">下次年检默认为本次日期+12个月，可再逐条编辑</p>
    <template #footer>
      <el-button @click="batchRenewDialog = false">取消</el-button>
      <el-button type="primary" :loading="batchBusy" @click="doBatchRenew">确认续检</el-button>
    </template>
  </el-dialog>
</template>

<style>
.overdue-row { background: #fef0f0 !important; }
.stat-active { outline: 2px solid #409eff; }
</style>
