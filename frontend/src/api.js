import axios from 'axios'

axios.interceptors.request.use(cfg => {
  const t = localStorage.getItem('erp_token')
  if (t) cfg.headers['X-Auth-Token'] = t
  return cfg
})
axios.interceptors.response.use(
  r => r,
  err => {
    if (err.response?.status === 401) {
      localStorage.removeItem('erp_token')
      if (!location.hash.includes('login')) location.reload()
    }
    return Promise.reject(err)
  }
)

const get = (url) => axios.get(url).then(r => r.data)
const post = (url, data) => axios.post(url, data).then(r => r.data)

export const api = {
  // 认证
  login: (username, password) => post('/api/auth/login', { username, password }),
  logout: () => post('/api/auth/logout'),
  me: () => get('/api/auth/me'),
  // 用户管理 (ADMIN)
  users: () => get('/api/users'),
  createUser: (d) => post('/api/users', d),
  resetPassword: (id, password) => post(`/api/users/${id}/reset-password`, { password }),
  toggleUser: (id) => post(`/api/users/${id}/toggle`),
  deleteUser: (id) => axios.delete(`/api/users/${id}`).then(r => r.data),
  // 系统
  backups: () => get('/api/system/backups'),
  backupNow: () => post('/api/system/backup'),
  restoreBackup: (name) => post('/api/system/backups/restore', { name }),
  deleteBackup: (name) => axios.delete('/api/system/backups/' + encodeURIComponent(name)).then(r => r.data),
  uploadBackup: (fd) => axios.post('/api/system/backups/upload', fd).then(r => r.data),
  downloadBackup: async (name) => {
    const r = await axios.get('/api/system/backups/download?name=' + encodeURIComponent(name), { responseType: 'blob' })
    const url = URL.createObjectURL(r.data)
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.click()
    URL.revokeObjectURL(url)
  },
  logs: () => get('/api/system/logs'),
  // 商品
  products: (keyword, category) => {
    const q = new URLSearchParams()
    if (keyword) q.set('keyword', keyword)
    if (category) q.set('category', category)
    return get('/api/products' + (q.toString() ? '?' + q.toString() : ''))
  },
  nextSku: (c1, c2, c3) => {
    const q = new URLSearchParams({ category: c1 || '', subCategory: c2 || '', sub2Category: c3 || '' })
    return get('/api/products/next-sku?' + q.toString())
  },
  applyCategory: (d) => post('/api/products/categories/apply', d),
  importProducts: (d) => post('/api/products/import/batch', d),
  saveProduct: (p) => p.id
    ? axios.put(`/api/products/${p.id}`, p).then(r => r.data)
    : post('/api/products', p),
  deleteProduct: (id) => axios.delete(`/api/products/${id}`).then(r => r.data),
  // 单据
  createBill: (dto) => post('/api/bills', dto),
  bills: (status) => get('/api/bills' + (status ? `?status=${status}` : '')),
  billDetail: (id) => get(`/api/bills/${id}`),
  postBill: (id) => post(`/api/bills/${id}/post`),
  reverseBill: (id) => post(`/api/bills/${id}/reverse`),
  deleteBill: (id) => axios.delete(`/api/bills/${id}`).then(r => r.data),
  records: (productId) => get('/api/stock/records' + (productId ? `?productId=${productId}` : '')),
  // 看板 / 预警
  dashboard: () => get('/api/dashboard'),
  lowStock: () => get('/api/stock/low-stock'),
  peerSources: (partnerId, keyword) => {
    const q = new URLSearchParams()
    if (partnerId) q.set('partnerId', partnerId)
    if (keyword) q.set('keyword', keyword)
    return get('/api/stock/peer-sources?' + q.toString())
  },
  peerSale: (d) => post('/api/bills/peer-sale', d),
  postWithPeer: (id) => post(`/api/bills/${id}/post-with-peer`, {}),
  peerStock: (partnerId, category, keyword) => {
    const q = new URLSearchParams()
    if (partnerId) q.set('partnerId', partnerId)
    if (category) q.set('category', category)
    if (keyword) q.set('keyword', keyword)
    return get('/api/peer-stock?' + q.toString())
  },
  addPeerStock: (d) => post('/api/peer-stock', d),
  updatePeerStock: (id, d) => axios.put(`/api/peer-stock/${id}`, d).then(r => r.data),
  deletePeerStock: (id) => axios.delete(`/api/peer-stock/${id}`).then(r => r.data),
  importPeerStock: (rows) => post('/api/peer-stock/import', { rows }),
  // AI
  getAiSettings: () => get('/api/ai/settings'),
  saveAiSettings: (d) => post('/api/ai/settings', d),
  fetchAiModels: (provider, apiKey, baseUrl) => {
    const q = new URLSearchParams({ provider })
    if (apiKey) q.set('apiKey', apiKey)
    if (baseUrl) q.set('baseUrl', baseUrl)
    return get('/api/ai/settings/models?' + q.toString())
  },
  testAi: (d) => post('/api/ai/settings/test', d),
  aiSource: () => get('/api/ai/source'),
  webllmStatus: () => get('/api/ai/webllm/status'),
  webllmStart: () => post('/api/ai/webllm/start', {}),
  webllmLogin: (site) => post(`/api/ai/webllm/login?site=${site}`, {}),
  webllmLoginResult: (site) => get(`/api/ai/webllm/login/result?site=${site}`),
  aiChat: (message) => post('/api/ai/chat', { message }),
  aiQuery: (message) => post('/api/ai/query', { message }),
  agent: (message) => post('/api/ai/agent', { message }),
  // 演示数据
  seed: () => post('/api/demo/seed'),
  reset: () => post('/api/demo/reset'),
  // 供应商/客户
  partners: (type) => get('/api/partners' + (type ? `?type=${type}` : '')),
  savePartner: (p) => p.id
    ? axios.put(`/api/partners/${p.id}`, p).then(r => r.data)
    : post('/api/partners', p),
  deletePartner: (id) => axios.delete(`/api/partners/${id}`).then(r => r.data),
  // 仓库
  warehouses: () => get('/api/warehouses'),
  saveWarehouse: (w) => w.id
    ? axios.put(`/api/warehouses/${w.id}`, w).then(r => r.data)
    : post('/api/warehouses', w),
  deleteWarehouse: (id) => axios.delete(`/api/warehouses/${id}`).then(r => r.data),
  // 应收应付
  ledgerSummary: () => get('/api/ledger/summary'),
  unpaidRecords: (partnerId) => get(`/api/ledger/unpaid?partnerId=${partnerId}`),
  settle: (recordId) => post(`/api/ledger/settle/${recordId}`),
  updateLedgerRemark: (recordId, remark) => post('/api/ledger/update-remark', { recordId, remark }),
  settlePartner: (partnerId, direction) => post(`/api/ledger/settle-partner/${partnerId}${direction ? '?direction=' + direction : ''}`),
  // 分仓库库存
  stockByWarehouse: () => get('/api/stock/by-warehouse'),
  // 知识库
  kbStatus: () => get('/api/kb/status'),
  kbDocuments: () => get('/api/kb/documents'),
  kbDocumentDetail: (id) => get(`/api/kb/documents/${id}`),
  kbAddDoc: (title, text) => post('/api/kb/documents', { title, text }),
  kbDeleteDoc: (id) => axios.delete(`/api/kb/documents/${id}`).then(r => r.data),
  kbReindex: () => post('/api/kb/reindex'),
  kbSearch: (query, topK = 4) => post('/api/kb/search', { query, topK }),
  kbAsk: (message) => post('/api/kb/ask', { message }),
  kbUploadDoc: (fd) => axios.post('/api/kb/upload', fd).then(r => r.data),
  // 灭火器年检
  inspections: (params) => {
    // 过滤空值，避免 URLSearchParams 把 undefined 拼成 "undefined" 导致后端全量过滤
    const q = Object.entries(params || {}).filter(([, v]) => v !== undefined && v !== null && v !== '')
    return get('/api/inspections' + (q.length ? '?' + new URLSearchParams(q) : ''))
  },
  dueList: (dueDays) => get('/api/inspections/due-list' + (dueDays ? `?dueDays=${dueDays}` : '')),
  purposes: () => get('/api/inspections/purposes'),
  saveInspection: (i) => i.id
    ? axios.put(`/api/inspections/${i.id}`, i).then(r => r.data)
    : post('/api/inspections', i),
  renewInspection: (id, date) => post(`/api/inspections/${id}/renew`, { date }),
  deleteInspection: (id) => axios.delete(`/api/inspections/${id}`).then(r => r.data),
  // 经营报表
  reportSales: (period, date) => get(`/api/reports/sales?period=${period}&date=${date}`),
  reportAiSummary: (period, date) => post('/api/reports/ai-summary', { period, date }),
  // 店铺信息（打印抬头）
  getShopInfo: () => get('/api/shop/info'),
  saveShopInfo: (d) => post('/api/shop/info', d),
  // 盘点
  stocktakeSheet: (warehouseId) => get(`/api/stocktake?warehouseId=${warehouseId}`),
  stocktakeSubmit: (d) => post('/api/stocktake', d),
  // 批次临期
  expiringBatches: (days) => get('/api/stock/expiring-batches' + (days ? `?days=${days}` : '')),
  uploadImage: (id, fd) => axios.post(`/api/products/${id}/image`, fd).then(r => r.data),
  // 商品分类字典
  productCategories: () => get('/api/products/categories'),
  // 期初结清
  settleOpening: (id, direction) => post(`/api/partners/${id}/settle-opening?direction=${direction}`),
  // ============ 财务模块（仅管理员） ============
  financeAccounts: () => get('/api/finance/accounts'),
  saveFinanceAccount: (a) => a.id
    ? axios.put(`/api/finance/accounts/${a.id}`, a).then(r => r.data)
    : post('/api/finance/accounts', a),
  deleteFinanceAccount: (id) => axios.delete(`/api/finance/accounts/${id}`).then(r => r.data),
  financeVouchers: (params) => {
    const q = Object.entries(params || {}).filter(([, v]) => v !== undefined && v !== null && v !== '')
    return get('/api/finance/vouchers' + (q.length ? '?' + new URLSearchParams(q) : ''))
  },
  financeVoucherDetail: (id) => get(`/api/finance/vouchers/${id}`),
  createVoucher: (d) => post('/api/finance/vouchers', d),
  deleteVoucher: (id) => axios.delete(`/api/finance/vouchers/${id}`).then(r => r.data),
  trialBalance: (start, end) => get(`/api/finance/trial-balance?start=${start}&end=${end}`),
  ledgerDetail: (accountId, start, end) => get(`/api/finance/ledger-detail?accountId=${accountId}&start=${start}&end=${end}`),
  statements: (start, end) => get(`/api/finance/statements?start=${start}&end=${end}`),
  closeProfit: (date) => post(`/api/finance/close-profit?date=${date}`),
  financeSettings: () => get('/api/finance/settings'),
  saveFinanceSettings: (d) => post('/api/finance/settings', d),
  vatReport: (start, end) => get(`/api/finance/vat?start=${start}&end=${end}`),
  cashFlow: (start, end) => get(`/api/finance/cash-flow?start=${start}&end=${end}`),
  invoiceList: (start, end) => get(`/api/finance/invoice-list?start=${start}&end=${end}`),
  // ============ 电商对接（仅管理员） ============
  integrationPlatforms: () => get('/api/integrations/platforms'),
  integrationChannels: () => get('/api/integrations/channels'),
  saveIntegrationChannel: (d) => post('/api/integrations/channels', d),
  testIntegrationChannel: (id) => post(`/api/integrations/channels/${id}/test`),
  importPlatformOrders: (d) => post('/api/integrations/orders/import', d),
  platformOrders: (platform) => get('/api/integrations/orders' + (platform ? `?platform=${platform}` : ''))
}
