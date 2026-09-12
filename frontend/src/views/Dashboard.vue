<script setup>
import { ref, onMounted } from 'vue'
import * as echarts from 'echarts'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const d = ref({})
const trendEl = ref()
const topEl = ref()

async function load() {
  d.value = await api.dashboard()
  renderTrend()
  renderTop()
}

function renderTrend() {
  const chart = echarts.init(trendEl.value)
  const t = d.value.saleTrend || []
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 50, right: 20, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: t.map(x => x.date) },
    yAxis: { type: 'value' },
    series: [{
      name: '销售额', type: 'line', smooth: true, areaStyle: { opacity: 0.15 },
      data: t.map(x => x.amount), itemStyle: { color: '#409eff' }
    }]
  })
}

function renderTop() {
  const chart = echarts.init(topEl.value)
  const t = [...(d.value.topProducts || [])].reverse()
  chart.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 120, right: 30, top: 10, bottom: 30 },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: t.map(x => x.name) },
    series: [{ name: '销售额', type: 'bar', data: t.map(x => x.amount), itemStyle: { color: '#67c23a' } }]
  })
}

function seed() {
  api.seed().then(r => { ElMessage.success(r.message); load() })
}
function resetData() {
  ElMessageBox.confirm('确定清空所有商品和单据数据？不可恢复！', '危险操作', { type: 'warning' })
    .then(() => api.reset()).then(r => { ElMessage.success(r.message); load() })
    .catch(() => {})
}

const cards = [
  { key: 'todaySale', title: '今日销售额', icon: '💰', color: '#2563eb', bg: '#eff6ff', money: true },
  { key: 'todayProfit', title: '今日毛利', icon: '📈', color: '#16a34a', bg: '#f0fdf4', money: true },
  { key: 'monthSale', title: '本月销售额', icon: '🗓️', color: '#7c3aed', bg: '#f5f3ff', money: true },
  { key: 'monthProfit', title: '本月毛利', icon: '💵', color: '#059669', bg: '#ecfdf5', money: true },
  { key: 'inventoryValue', title: '库存价值(加权)', icon: '📦', color: '#d97706', bg: '#fffbeb', money: true },
  { key: 'lowStockCount', title: '库存预警商品', icon: '⚠️', color: '#dc2626', bg: '#fef2f2', suffix: '个' }
]

onMounted(load)
</script>

<template>
  <div>
    <el-row :gutter="14">
      <el-col v-for="c in cards" :key="c.title" :span="4">
        <el-card shadow="hover" class="kpi-card" :style="{ borderTop: '3px solid ' + c.color }">
          <div class="kpi-icon" :style="{ background: c.bg }">{{ c.icon }}</div>
          <el-statistic :title="c.title" :value="d[c.key] || 0" :precision="c.money ? 2 : 0"
            :prefix="c.money ? '￥' : undefined">
            <template v-if="c.suffix" #suffix><span style="font-size:13px">{{ c.suffix }}</span></template>
          </el-statistic>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="14"><el-card shadow="hover"><div ref="trendEl" style="height:300px"></div></el-card></el-col>
      <el-col :span="10"><el-card shadow="hover"><div ref="topEl" style="height:300px"></div></el-card></el-col>
    </el-row>

    <el-row style="margin-top:16px">
      <el-col>
        <el-card shadow="hover">
          <el-space>
            <span style="color:#909399;font-size:13px">还没有数据？</span>
            <el-button type="primary" plain size="small" @click="seed">一键生成演示数据（30天模拟经营）</el-button>
            <el-button type="danger" plain size="small" @click="resetData">清空数据</el-button>
          </el-space>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.kpi-card { position: relative; overflow: hidden; padding: 4px 6px; }
.kpi-card:hover { transform: translateY(-2px); }
.kpi-icon {
  width: 34px; height: 34px; border-radius: 8px;
  display: flex; align-items: center; justify-content: center;
  font-size: 16px; margin-bottom: 8px;
}
</style>
