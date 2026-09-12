<script setup>
import { ref } from 'vue'
import Dashboard from './views/Dashboard.vue'
import Products from './views/Products.vue'
import Orders from './views/Orders.vue'
import Alerts from './views/Alerts.vue'
import PeerSources from './views/PeerSources.vue'
import PeerStock from './views/PeerStock.vue'
import Partners from './views/Partners.vue'
import Ledger from './views/Ledger.vue'
import Warehouses from './views/Warehouses.vue'
import AiChat from './views/AiChat.vue'
import Knowledge from './views/Knowledge.vue'
import System from './views/System.vue'
import Inspection from './views/Inspection.vue'
import Stocktake from './views/Stocktake.vue'
import Reports from './views/Reports.vue'
import Finance from './views/Finance.vue'
import Ecommerce from './views/Ecommerce.vue'
import Login from './views/Login.vue'

const token = ref(localStorage.getItem('erp_token'))
const role = ref(localStorage.getItem('erp_role') || 'OPERATOR')
const username = ref(localStorage.getItem('erp_user') || '')

const view = ref('dashboard')
const views = {
  dashboard: { comp: Dashboard, title: '经营看板' },
  reports: { comp: Reports, title: '经营报表' },
  products: { comp: Products, title: '商品管理' },
  orders: { comp: Orders, title: '进货 / 销售' },
  peerStock: { comp: PeerStock, title: '同行库存' },
  peerSources: { comp: PeerSources, title: '调货记录' },
  alerts: { comp: Alerts, title: '库存预警' },
  partners: { comp: Partners, title: '供应商 / 客户' },
  ledger: { comp: Ledger, title: '应收应付' },
  finance: { comp: Finance, title: '财务记账', adminOnly: true },
  ecommerce: { comp: Ecommerce, title: '电商对接', adminOnly: true },
  warehouses: { comp: Warehouses, title: '仓库管理' },
  inspection: { comp: Inspection, title: '灭火器年检' },
  stocktake: { comp: Stocktake, title: '库存盘点' },
  ai: { comp: AiChat, title: 'AI 助手' },
  kb: { comp: Knowledge, title: '知识库' },
  system: { comp: System, title: '系统设置', adminOnly: true }
}

function onLogin(user) {
  token.value = user.token
  role.value = user.role
  username.value = user.username
  localStorage.setItem('erp_role', user.role)
  localStorage.setItem('erp_user', user.username)
}
async function logout() {
  try { await api.logout() } catch (e) { /* token失效也继续 */ }
  localStorage.removeItem('erp_token')
  localStorage.removeItem('erp_role')
  localStorage.removeItem('erp_user')
  location.reload()
}
import { api } from './api'
</script>

<template>
  <Login v-if="!token" @done="onLogin" />
  <el-container v-else class="layout">
    <el-aside width="200px" class="aside">
      <div class="logo">
        <div class="logo-icon">卫</div>
        <div>
          <div class="logo-name">账管卫士</div>
          <div class="logo-sub">AI 原生商家经营系统</div>
        </div>
      </div>
      <el-menu :default-active="view" @select="k => view = k" class="menu">
        <el-menu-item-group title="经营分析">
          <el-menu-item index="dashboard"><el-icon><DataLine /></el-icon>经营看板</el-menu-item>
          <el-menu-item index="reports"><el-icon><TrendCharts /></el-icon>经营报表</el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="商品与库存">
          <el-menu-item index="products"><el-icon><Goods /></el-icon>商品管理</el-menu-item>
          <el-menu-item index="orders"><el-icon><EditPen /></el-icon>进货 / 销售</el-menu-item>
          <el-menu-item index="peerStock"><el-icon><Box /></el-icon>同行库存</el-menu-item>
          <el-menu-item index="peerSources"><el-icon><Notebook /></el-icon>调货记录</el-menu-item>
          <el-menu-item index="alerts"><el-icon><Bell /></el-icon>库存预警</el-menu-item>
          <el-menu-item index="warehouses"><el-icon><OfficeBuilding /></el-icon>仓库管理</el-menu-item>
          <el-menu-item index="stocktake"><el-icon><ListBox /></el-icon>库存盘点</el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="资金与协作">
          <el-menu-item index="partners"><el-icon><User /></el-icon>供应商 / 客户</el-menu-item>
          <el-menu-item index="ledger"><el-icon><Wallet /></el-icon>应收应付</el-menu-item>
          <el-menu-item v-if="role === 'ADMIN'" index="finance"><el-icon><Money /></el-icon>财务记账</el-menu-item>
          <el-menu-item index="inspection"><el-icon><Timer /></el-icon>灭火器年检</el-menu-item>
          <el-menu-item v-if="role === 'ADMIN'" index="ecommerce"><el-icon><ShoppingCart /></el-icon>电商对接</el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="智能助手">
          <el-menu-item index="ai"><el-icon><ChatDotRound /></el-icon>AI 助手</el-menu-item>
          <el-menu-item index="kb"><el-icon><Collection /></el-icon>知识库</el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group v-if="role === 'ADMIN'" title="系统">
          <el-menu-item index="system"><el-icon><Setting /></el-icon>系统设置</el-menu-item>
        </el-menu-item-group>
      </el-menu>
      <div class="footer">
        <div style="margin-bottom:8px">👤 {{ username }}（{{ role === 'ADMIN' ? '管理员' : '操作员' }}）</div>
        <el-button size="small" text style="color:#c3cdd9" @click="logout">退出登录</el-button>
      </div>
    </el-aside>
    <el-main class="main">
      <h3 class="page-title">{{ views[view].title }}</h3>
      <component :is="views[view].comp" />
    </el-main>
  </el-container>
</template>

<style>
body { margin: 0; font-family: "Microsoft YaHei", sans-serif; background: #f5f7fa; }
.layout { height: 100vh; }
.aside { background: #1d2939; color: #fff; display: flex; flex-direction: column; }
.logo {
  padding: 18px 16px;
  display: flex;
  align-items: center;
  gap: 10px;
  color: #fff;
  border-bottom: 1px solid rgba(255, 255, 255, .08);
}
.logo-icon {
  width: 38px; height: 38px; border-radius: 10px;
  background: linear-gradient(135deg, #2563eb, #4f8ef7);
  display: flex; align-items: center; justify-content: center;
  font-size: 18px; font-weight: bold; flex-shrink: 0;
}
.logo-name { font-size: 16px; font-weight: bold; line-height: 1.2; }
.logo-sub { font-size: 11px; color: #98a2b3; margin-top: 2px; }
.menu { border-right: none; flex: 1; }
.menu .el-menu-item { color: #c3cdd9; }
.menu .el-menu-item.is-active { color: #409eff; background: #263449; }
.menu :deep(.el-menu-item-group__title) {
  padding: 14px 16px 6px;
  font-size: 11px;
  color: #667085;
  letter-spacing: 1px;
}
.menu :deep(.el-menu-item) { height: 44px; margin: 2px 8px; border-radius: 8px; }
.menu :deep(.el-menu-item:hover) { background: rgba(37, 99, 235, .15); }
.footer { padding: 16px; font-size: 12px; color: #6b7a8d; }
.main { background: #f5f7fa; }
.page-title { margin: 0 0 16px; }

/* ===== 打印：只输出 #print-area 内容 ===== */
#print-area { display: none; }
@media print {
  body > *:not(#print-area) { display: none !important; }
  #print-area { display: block !important; }
  .receipt { font-family: "Microsoft YaHei", sans-serif; font-size: 13px; }
  /* A4/A5：内容占满纸宽；80mm 小票保持窄幅 */
  .paper-A4 .receipt, .paper-A5 .receipt { max-width: 98%; }
  .paper-ticket80 .receipt { max-width: 300px; font-size: 12px; }
  .shop-header { text-align: center; margin-bottom: 10px; }
  .shop-header .shop-name { font-size: 22px; font-weight: bold; letter-spacing: 2px; }
  .shop-header .doc-title { font-size: 15px; margin-top: 2px; letter-spacing: 4px; }
  .sign-row { display: flex; justify-content: space-between; margin-top: 34px; font-size: 13px; }
  .receipt h3 { text-align: center; margin: 6px 0; }
  .receipt .meta { display: flex; justify-content: space-between; margin: 2px 0; }
  .receipt table { width: 100%; border-collapse: collapse; margin: 8px 0; }
  .receipt th, .receipt td { border: 1px solid #333; padding: 4px 6px; text-align: left; }
  .receipt .total { text-align: right; font-weight: bold; margin: 6px 0; }
  .receipt .sign { margin-top: 16px; color: #555; font-size: 12px; text-align: center; }
}
</style>
