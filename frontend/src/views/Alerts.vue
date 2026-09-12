<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { exportExcel } from '../utils/export'

const tab = ref('alert')
const list = ref([])
const batches = ref([])
const batchDays = ref(90)
const page = ref(1)
const pageSize = 50
const paged = computed(() => list.value.slice((page.value - 1) * pageSize, page.value * pageSize))

async function load() { list.value = await api.lowStock(); page.value = 1 }
async function loadBatches() { batches.value = await api.expiringBatches(batchDays.value) }
function switchTab() {
  if (tab.value === 'batches') loadBatches()
  else load()
}
async function restock(row) {
  const need = Math.max(row.safeStock * 2 - row.stock, 10)
  try {
    // 采购入库必须带仓库，否则货进不了具体仓库、之后按仓销售会报库存不足
    let warehouseId = null
    try {
      const whs = await api.warehouses()
      warehouseId = whs?.[0]?.id ?? null
    } catch { /* 取不到就按原逻辑 */ }
    const created = await api.createBill({
      type: 'PURCHASE', warehouseId, paid: 1, autoPost: true,
      remark: '库存预警一键补货',
      items: [{ productId: row.id, quantity: need }]
    })
    ElMessage.success(`已生成并过账补货单 ${created.billNo}（入库 ${need} 件）`)
    load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '补货失败')
  }
}
/** 设为不监控：安全库存置 0，库存再低也不进预警（同行调货/代发商品用） */
function setNoMonitor(row) {
  ElMessageBox.confirm(`将「${row.name}」安全库存设为 0？之后该商品不再出现在库存预警（适合同行调货、卖完即止的商品）。`, '设为不监控', { type: 'warning' })
    .then(() => api.saveProduct({ id: row.id, noAlert: 1 }))
    .then(() => { ElMessage.success('已设为不监控'); load() })
    .catch(() => {})
}
function exportBatches() {
  exportExcel(`批次临期_${new Date().toLocaleDateString('zh-CN')}.xlsx`, '批次临期',
    batches.value.map(b => ({
      商品: b.productName, 批次号: b.batchNo || '', 数量: b.quantity,
      生产日期: b.productionDate, 到期日: b.expiryDate,
      剩余天数: b.daysLeft < 0 ? `已过期${-b.daysLeft}天` : b.daysLeft,
      状态: b.expired ? '已过期' : b.daysLeft <= 30 ? '临期' : '正常'
    })))
}
onMounted(load)
</script>

<template>
  <el-tabs v-model="tab" @tab-change="switchTab">
    <el-tab-pane label="库存预警" name="alert" />
    <el-tab-pane label="批次临期" name="batches" />
  </el-tabs>

  <template v-if="tab === 'alert'">
    <el-alert type="warning" :closable="false" show-icon style="margin-bottom:14px"
      :title="`共 ${list.length} 个商品低于安全库存，建议尽快补货。同行调货、卖完即止的商品点「设为不监控」可移出预警`" />
    <el-table :data="paged" border stripe>
      <el-table-column prop="name" label="商品名称" min-width="150" />
      <el-table-column label="规格" width="95">
        <template #default="{ row }">{{ row.spec || '-' }}</template>
      </el-table-column>
      <el-table-column prop="sku" label="SKU" width="110" />
      <el-table-column prop="category" label="分类" width="90" />
      <el-table-column label="当前库存" width="100">
        <template #default="{ row }"><b style="color:#f56c6c">{{ row.stock }}</b></template>
      </el-table-column>
      <el-table-column prop="safeStock" label="安全库存" width="100" />
      <el-table-column label="建议补货量" width="110">
        <template #default="{ row }">{{ Math.max(row.safeStock * 2 - row.stock, 10) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="restock(row)">一键补货</el-button>
          <el-button size="small" @click="setNoMonitor(row)">设为不监控</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="pageSize" :total="list.length"
      layout="total, prev, pager, next" style="margin-top:10px;justify-content:flex-end" />
  </template>

  <template v-else>
    <el-space style="margin-bottom:14px">
      <span>查</span>
      <el-input-number v-model="batchDays" :min="7" :max="365" :step="15" @change="loadBatches" />
      <span>天内到期的批次（需商品设置保质期、采购时登记生产日期）</span>
      <el-button type="success" plain @click="exportBatches">导出 Excel</el-button>
    </el-space>
    <el-table :data="batches" border stripe>
      <el-table-column prop="productName" label="商品" min-width="140" />
      <el-table-column prop="batchNo" label="批次号" width="110" />
      <el-table-column prop="quantity" label="采购数量" width="90" />
      <el-table-column prop="productionDate" label="生产日期" width="110" />
      <el-table-column prop="expiryDate" label="到期日" width="110" />
      <el-table-column label="剩余" width="110">
        <template #default="{ row }">
          <el-tag :type="row.expired ? 'danger' : row.daysLeft <= 30 ? 'warning' : 'info'" size="small">
            {{ row.expired ? `已过期${-row.daysLeft}天` : `${row.daysLeft}天` }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!batches.length" description="没有临期批次。采购开单时填生产日期、商品设置保质期天数即可启用"
      :image-size="80" />
  </template>
</template>
