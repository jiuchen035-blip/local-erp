<script setup>
// 同行货源台账：谁（哪个同行）有啥、最近什么价——数据来自带供应商的采购流水，
// 按 供应商×商品 聚合取最近进价。可按同行筛选、按商品搜索、导出 Excel。
import { ref, computed, onMounted } from 'vue'
import { api } from '../api'
import { exportExcel } from '../utils/export'

const list = ref([])
const partners = ref([])
const filterPartner = ref(null)
const keyword = ref('')
const loading = ref(false)
const page = ref(1)
const pageSize = 50

async function load() {
  loading.value = true
  try {
    list.value = await api.peerSources(filterPartner.value || null, keyword.value.trim())
    page.value = 1
  } finally { loading.value = false }
}
function changeFilter() { load() }

const paged = computed(() => list.value.slice((page.value - 1) * pageSize, page.value * pageSize))

function doExport() {
  exportExcel(`同行货源台账_${new Date().toLocaleDateString('zh-CN')}.xlsx`, '同行货源',
    list.value.map(r => ({
      同行: r.partnerName, 商品名称: r.productName, 分类: r.category || '',
      最近调货价: r.lastPrice, 累计调货量: r.totalQty,
      最近调货时间: r.lastTime, 当前零售价: r.salePrice
    })))
}

onMounted(async () => {
  load()
  try { partners.value = await api.partners('SUPPLIER') } catch { /* 忽略 */ }
})
</script>

<template>
  <el-space wrap style="margin-bottom:14px">
    <el-select v-model="filterPartner" clearable filterable placeholder="按同行筛选（只看这家）"
      style="width:200px" @change="changeFilter">
      <el-option v-for="p in partners" :key="p.id" :label="p.name" :value="p.id" />
    </el-select>
    <el-input v-model="keyword" placeholder="搜商品名称 / SKU / 条码" clearable style="width:200px"
      @keyup.enter="load" @clear="load" />
    <el-button @click="load">查询</el-button>
    <el-button type="success" plain @click="doExport">导出 Excel</el-button>
    <span style="font-size:12px;color:#909399">共 {{ list.length }} 条货源记录（按最近调货时间倒序）</span>
  </el-space>

  <el-table :data="paged" border stripe v-loading="loading">
    <el-table-column prop="partnerName" label="同行（供应商）" min-width="140" />
    <el-table-column prop="productName" label="商品名称" min-width="150" />
    <el-table-column label="分类" width="110">
      <template #default="{ row }">{{ row.category || '-' }}</template>
    </el-table-column>
    <el-table-column label="最近调货价" width="110">
      <template #default="{ row }">￥{{ row.lastPrice?.toFixed(2) }}</template>
    </el-table-column>
    <el-table-column prop="totalQty" label="累计调货量" width="105" />
    <el-table-column prop="lastTime" label="最近调货时间" width="170" />
    <el-table-column label="当前零售价" width="105">
      <template #default="{ row }">￥{{ row.salePrice?.toFixed(2) }}</template>
    </el-table-column>
  </el-table>
  <el-pagination v-model:current-page="page" :page-size="pageSize" :total="list.length"
    layout="total, prev, pager, next" style="margin-top:10px;justify-content:flex-end" />

  <el-alert type="info" :closable="false" show-icon style="margin-top:14px"
    title="数据来自带供应商的采购流水：开单勾选「同行调货」会自动生成同行采购入库单并记入此表；也可以在这里回看正常进货的供应商给过你的价格。" />
</template>
