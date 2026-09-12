<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { marked } from 'marked'
import { api } from '../api'
import { ElMessage } from 'element-plus'
import { exportSheets, downloadWord } from '../utils/export'
import { printHtml } from '../utils/print'
import PaperSelect from '../components/PaperSelect.vue'

const period = ref('MONTH')
const year = ref(new Date().getFullYear())
const month = ref(new Date().getMonth() + 1)
const quarter = ref(Math.floor(new Date().getMonth() / 3) + 1)
const data = ref(null)
const loading = ref(false)

const aiDialog = ref(false)
const aiLoading = ref(false)
const aiMarkdown = ref('')
const aiRendered = ref('')
const previewEl = ref()
const trendEl = ref()
const pieEl = ref()

const dateParam = computed(() => {
  if (period.value === 'MONTH') return `${year.value}-${String(month.value).padStart(2, '0')}-15`
  if (period.value === 'QUARTER') return `${year.value}-${String((quarter.value - 1) * 3 + 1).padStart(2, '0')}-15`
  return `${year.value}-06-15`
})
const s = computed(() => data.value?.summary || {})
const monthNames = ['一月', '二月', '三月', '四月', '五月', '六月', '七月', '八月', '九月', '十月', '十一月', '十二月']

async function load() {
  loading.value = true
  try {
    data.value = (await api.reportSales(period.value, dateParam.value))
    await nextTick()
    renderTrend()
    renderPie()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '报表加载失败')
  }
  loading.value = false
}

function renderTrend() {
  if (!trendEl.value) return
  const t = data.value.trend || []
  echarts.init(trendEl.value).setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['销售额', '毛利'] },
    grid: { left: 60, right: 20, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: t.map(x => x.label) },
    yAxis: { type: 'value' },
    series: [
      { name: '销售额', type: 'bar', data: t.map(x => x.amount), itemStyle: { color: '#409eff' } },
      { name: '毛利', type: 'line', smooth: true, data: t.map(x => x.profit), itemStyle: { color: '#67c23a' } }
    ]
  })
}
function renderPie() {
  if (!pieEl.value) return
  const c = data.value.categoryDist || []
  echarts.init(pieEl.value).setOption({
    tooltip: { trigger: 'item', formatter: '{b}: ￥{c} ({d}%)' },
    series: [{
      type: 'pie', radius: ['35%', '65%'],
      data: c.map(x => ({ name: x.category, value: x.amount }))
    }]
  })
}

function exportExcelReport() {
  const d = data.value
  if (!d) return
  exportSheets(`${d.label}经营报表.xlsx`, [
    { name: '汇总', rows: [{
      报告期: d.label, 统计起: d.start, 统计止: d.end,
      销售额: s.value.saleAmount, 销售成本: s.value.saleCost,
      毛利: s.value.profit, 毛利率: s.value.profitRate + '%',
      销售数量: s.value.saleQty, 销售单据数: s.value.billCount, 采购额: s.value.purchaseAmount
    }] },
    { name: '销售趋势', rows: d.trend.map(t => ({ 期间: t.label, 销售额: t.amount, 毛利: t.profit, 销量: t.qty })) },
    { name: '商品Top10', rows: d.topProducts.map(p => ({ 商品: p.name, 销量: p.qty, 销售额: p.amount, 毛利: p.profit })) },
    { name: '分类分布', rows: d.categoryDist.map(c => ({ 分类: c.category, 销售额: c.amount, 毛利: c.profit })) },
    { name: '客户Top10', rows: d.partnerTop.map(c => ({ 客户: c.name, 销售额: c.amount, 销量: c.qty })) }
  ])
}

async function genAiReport() {
  aiLoading.value = true
  aiDialog.value = true
  aiMarkdown.value = ''
  aiRendered.value = ''
  try {
    const r = await api.reportAiSummary(period.value, dateParam.value)
    if (r.error) { ElMessage.warning(r.error); aiDialog.value = false; return }
    aiMarkdown.value = r.markdown
    aiRendered.value = marked.parse(r.markdown)
    nextTick(() => { if (previewEl.value) previewEl.value.innerHTML = aiRendered.value })
  } catch (e) {
    ElMessage.error(e.response?.data?.error || 'AI 报告生成失败')
    aiDialog.value = false
  }
  aiLoading.value = false
}
function downloadAiWord() {
  if (!aiRendered.value) return
  downloadWord(`${data.value.label}经营分析报告.doc`, aiRendered.value)
}
function printAi() {
  printHtml(aiRendered.value)
}

onMounted(load)
</script>

<template>
  <el-space wrap style="margin-bottom:14px">
    <el-radio-group v-model="period" @change="load">
      <el-radio-button value="MONTH">月度</el-radio-button>
      <el-radio-button value="QUARTER">季度</el-radio-button>
      <el-radio-button value="YEAR">年度</el-radio-button>
    </el-radio-group>
    <el-select v-model="year" style="width:110px" @change="load">
      <el-option v-for="y in [year - 3, year - 2, year - 1, year, year + 1]" :key="y" :label="y + '年'" :value="y" />
    </el-select>
    <template v-if="period === 'MONTH'">
      <el-select v-model="month" style="width:100px" @change="load">
        <el-option v-for="(m, i) in monthNames" :key="m" :label="m" :value="i + 1" />
      </el-select>
    </template>
    <template v-if="period === 'QUARTER'">
      <el-select v-model="quarter" style="width:110px" @change="load">
        <el-option v-for="q in 4" :key="q" :label="`第${q}季度`" :value="q" />
      </el-select>
    </template>
    <el-button type="primary" :loading="loading" @click="load">查询</el-button>
    <el-divider direction="vertical" />
    <el-button type="success" plain @click="exportExcelReport">导出 Excel 报表</el-button>
    <el-button type="danger" plain :loading="aiLoading" @click="genAiReport">AI 美化经营报告</el-button>
  </el-space>

  <el-row :gutter="16" style="margin-bottom:16px" v-if="data">
    <el-col :span="4"><el-card shadow="hover"><el-statistic :title="`${data.label}销售额`" :value="s.saleAmount" :precision="2" prefix="￥" /></el-card></el-col>
    <el-col :span="4"><el-card shadow="hover"><el-statistic title="毛利" :value="s.profit" :precision="2" prefix="￥" /></el-card></el-col>
    <el-col :span="4"><el-card shadow="hover"><el-statistic title="毛利率" :value="s.profitRate" :precision="1" suffix="%" /></el-card></el-col>
    <el-col :span="4"><el-card shadow="hover"><el-statistic title="销售数量" :value="s.saleQty" /></el-card></el-col>
    <el-col :span="4"><el-card shadow="hover"><el-statistic title="销售单据数" :value="s.billCount" /></el-card></el-col>
    <el-col :span="4"><el-card shadow="hover"><el-statistic title="同期采购额" :value="s.purchaseAmount" :precision="2" prefix="￥" /></el-card></el-col>
  </el-row>

  <el-row :gutter="16" style="margin-bottom:16px" v-if="data">
    <el-col :span="15"><el-card shadow="hover"><div ref="trendEl" style="height:300px"></div></el-card></el-col>
    <el-col :span="9"><el-card shadow="hover"><div ref="pieEl" style="height:300px"></div></el-card></el-col>
  </el-row>

  <el-row :gutter="16" v-if="data">
    <el-col :span="14">
      <el-card shadow="hover">
        <template #header><b>商品销售 Top10</b></template>
        <el-table :data="data.topProducts" size="small" border>
          <el-table-column type="index" label="#" width="45" />
          <el-table-column prop="name" label="商品" min-width="140" />
          <el-table-column prop="qty" label="销量" width="80" />
          <el-table-column prop="amount" label="销售额" width="95" />
          <el-table-column prop="profit" label="毛利" width="95" />
        </el-table>
      </el-card>
    </el-col>
    <el-col :span="10">
      <el-card shadow="hover">
        <template #header><b>客户销售 Top10</b></template>
        <el-table :data="data.partnerTop" size="small" border>
          <el-table-column type="index" label="#" width="45" />
          <el-table-column prop="name" label="客户" min-width="130" />
          <el-table-column prop="amount" label="销售额" width="95" />
          <el-table-column prop="qty" label="销量" width="75" />
        </el-table>
      </el-card>
    </el-col>
  </el-row>

  <el-dialog v-model="aiDialog" :title="`AI 经营分析报告 - ${data?.label || ''}`" width="760px" top="5vh">
    <div v-loading="aiLoading" style="min-height:200px">
      <div ref="previewEl" class="ai-report"></div>
    </div>
    <template #footer>
      <PaperSelect style="margin-right:auto" />
      <el-button @click="aiDialog = false">关闭</el-button>
      <el-button type="warning" plain @click="printAi">打印 / 存 PDF</el-button>
      <el-button type="primary" @click="downloadAiWord">下载 Word (.doc)</el-button>
    </template>
  </el-dialog>
</template>

<style>
.ai-report h1 { font-size: 20px; text-align: center; }
.ai-report h2 { font-size: 16px; border-bottom: 1px solid #ddd; padding-bottom: 4px; margin-top: 18px; }
.ai-report table { border-collapse: collapse; width: 100%; }
.ai-report th, .ai-report td { border: 1px solid #999; padding: 4px 8px; }
.ai-report li { margin: 4px 0; }
</style>
