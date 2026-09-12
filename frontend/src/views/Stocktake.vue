<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage } from 'element-plus'

const warehouses = ref([])
const warehouseId = ref(null)
const sheet = ref([])
const actuals = ref({})   // productId -> 实盘数量
const remark = ref('')
const submitting = ref(false)

const diffs = computed(() => sheet.value
  .filter(p => actuals.value[p.productId] !== undefined && actuals.value[p.productId] !== null && actuals.value[p.productId] !== '')
  .map(p => ({ ...p, actual: actuals.value[p.productId], diff: actuals.value[p.productId] - p.bookQty }))
  .filter(p => p.diff !== 0))

async function loadWarehouses() {
  warehouses.value = await api.warehouses()
  if (warehouses.value.length && !warehouseId.value) {
    warehouseId.value = warehouses.value[0].id
    await loadSheet()
  }
}
async function loadSheet() {
  if (!warehouseId.value) return
  sheet.value = await api.stocktakeSheet(warehouseId.value)
  actuals.value = {}
}
function diffColor(d) { return d > 0 ? '#67c23a' : '#f56c6c' }
async function submit() {
  if (!diffs.value.length) { ElMessage.info('没有录入任何与账面不同的实盘数'); return }
  submitting.value = true
  try {
    const r = await api.stocktakeSubmit({
      warehouseId: warehouseId.value,
      remark: remark.value,
      lines: diffs.value.map(p => ({ productId: p.productId, actualQty: p.actual }))
    })
    const parts = []
    if (r.gainBillNo) parts.push('盘盈单 ' + r.gainBillNo)
    if (r.lossBillNo) parts.push('报损单 ' + r.lossBillNo)
    ElMessage.success(parts.length ? `已生成并过账：${parts.join('、')}（${r.diffCount} 项差异）` : r.message)
    loadSheet()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '盘点提交失败')
  } finally { submitting.value = false }
}
onMounted(loadWarehouses)
</script>

<template>
  <el-space wrap style="margin-bottom:14px">
    <span>盘点仓库：</span>
    <el-select v-model="warehouseId" style="width:180px" @change="loadSheet">
      <el-option v-for="w in warehouses" :key="w.id" :value="w.id" :label="w.name" />
    </el-select>
    <el-input v-model="remark" placeholder="盘点备注（选填，如：9月底盘点）" style="width:240px" />
    <el-button type="primary" :loading="submitting" :disabled="!diffs.length" @click="submit">
      提交盘点（{{ diffs.length }} 项差异）
    </el-button>
    <span style="color:#909399;font-size:12px">差异>0 自动生成盘盈单，<0 生成报损单，按加权成本计价</span>
  </el-space>

  <el-table :data="sheet" border stripe max-height="620" size="default">
    <el-table-column prop="name" label="商品" min-width="160" />
    <el-table-column prop="sku" label="SKU" width="110" />
    <el-table-column prop="bookQty" label="账面数量" width="100" />
    <el-table-column label="实盘数量" width="150">
      <template #default="{ row }">
        <el-input-number v-model="actuals[row.productId]" :min="0" size="small" placeholder="留空=不盘点" style="width:130px" />
      </template>
    </el-table-column>
    <el-table-column label="差异" width="100">
      <template #default="{ row }">
        <span v-if="actuals[row.productId] !== undefined && actuals[row.productId] !== null && actuals[row.productId] !== ''"
          :style="{ color: diffColor(row.bookQty ? actuals[row.productId] - row.bookQty : 0), fontWeight: 'bold' }">
          {{ actuals[row.productId] - row.bookQty > 0 ? '+' : '' }}{{ actuals[row.productId] - row.bookQty }}
        </span>
        <span v-else style="color:#c0c4cc">-</span>
      </template>
    </el-table-column>
  </el-table>
</template>
