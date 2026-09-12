const { get, post, put, del } = require('../../utils/api')
const SIZE = 15
const STATUS_LABEL = { OVERDUE: '已到期待检', DUE: '即将到期', NORMAL: '正常' }

function today() { return new Date().toISOString().slice(0, 10) }
function emptyForm() {
  return { id: null, customerName: '', phone: '', address: '', purpose: '工地', spec: '',
    quantity: 1, price: 0, inspectDate: today(), nextDate: '', remark: '' }
}

Page({
  data: {
    stat: { overdue: 0, due: 0, normal: 0, total: 0 },
    activeStatus: '',
    statusOptions: [
      { v: '', n: '全部状态' }, { v: 'OVERDUE', n: '已到期待检' },
      { v: 'DUE', n: '即将到期' }, { v: 'NORMAL', n: '正常' }
    ],
    statusIndex: 0,
    purposeOptions: [],
    filters: { keyword: '', dueDays: 30 },
    isAdmin: wx.getStorageSync('role') === 'ADMIN',
    rows: [], paged: [], page: 1, total: 0,
    checked: {}, checkedCount: 0, allChecked: false,
    busy: false,
    dialog: false, saving: false, form: emptyForm(),
    renewDialog: false, renewing: false, renewForm: { id: null, name: '', date: today() },
    purposeIndex: -1
  },
  onShow() { this.load(); this.loadPurposes() },

  load() {
    const q = []
    const s = this.data.statusOptions[this.data.statusIndex]
    if (s && s.v) q.push('status=' + s.v)
    const p = this.data.purposeOptions[this.data.purposeIndex]
    if (this.data.purposeIndex >= 0 && p) q.push('purpose=' + encodeURIComponent(p))
    const kw = (this.data.filters.keyword || '').trim()
    if (kw) q.push('keyword=' + encodeURIComponent(kw))
    const dd = parseInt(this.data.filters.dueDays, 10)
    q.push('dueDays=' + (dd >= 7 && dd <= 180 ? dd : 30))
    get('/api/inspections' + (q.length ? '?' + q.join('&') : '')).then(d => {
      const rows = (d.rows || []).map(r => ({ ...r, statusLabel: STATUS_LABEL[r.status] || '正常' }))
      this.setData({ stat: { overdue: d.overdue || 0, due: d.due || 0, normal: d.normal || 0, total: d.total || 0 },
        rows, page: 1, checked: {}, checkedCount: 0, allChecked: false })
      this.applyPage()
    }).catch(e => wx.showToast({ title: e.message, icon: 'none' }))
  },
  loadPurposes() {
    get('/api/inspections/purposes').then(list => this.setData({ purposeOptions: list || [] })).catch(() => {})
  },

  applyPage() {
    const s = (this.data.page - 1) * SIZE
    this.setData({ paged: this.data.rows.slice(s, s + SIZE) })
  },
  prevPage() { if (this.data.page > 1) { this.setData({ page: this.data.page - 1 }); this.applyPage() } },
  nextPage() { if (this.data.page * SIZE < this.data.rows.length) { this.setData({ page: this.data.page + 1 }); this.applyPage() } },

  // ---- 筛选 ----
  tapStat(e) {
    const v = e.currentTarget.dataset.status || ''
    const idx = this.data.statusOptions.findIndex(o => o.v === v)
    this.setData({ activeStatus: v, statusIndex: idx < 0 ? 0 : idx, page: 1 })
    this.load()
  },
  setStatus(e) {
    const i = Number(e.detail.value)
    this.setData({ statusIndex: i, activeStatus: (this.data.statusOptions[i] || {}).v || '', page: 1 })
    this.load()
  },
  setPurpose(e) { this.setData({ purposeIndex: Number(e.detail.value), page: 1 }); this.load() },
  setKw(e) { this.setData({ 'filters.keyword': e.detail.value }) },
  searchKw() { this.setData({ page: 1 }); this.load() },
  setDueDays(e) { this.setData({ 'filters.dueDays': e.detail.value }) },
  blurDueDays() { this.setData({ page: 1 }); this.load() },

  // ---- 勾选 / 批量 ----
  toggleCheck(e) {
    const id = e.currentTarget.dataset.id
    const checked = { ...this.data.checked }
    if (checked[id]) delete checked[id]; else checked[id] = true
    this.setData({ checked, checkedCount: Object.keys(checked).length })
  },
  toggleAll() {
    if (this.data.allChecked) this.setData({ checked: {}, checkedCount: 0, allChecked: false })
    else {
      const checked = {}
      this.data.rows.forEach(r => { checked[r.id] = true })
      this.setData({ checked, checkedCount: this.data.rows.length, allChecked: true })
    }
  },
  clearChecked() { this.setData({ checked: {}, checkedCount: 0, allChecked: false }) },

  selectedIds() { return Object.keys(this.data.checked).map(Number) },

  batchRenew() {
    const ids = this.selectedIds()
    if (!ids.length) { wx.showToast({ title: '请先勾选记录', icon: 'none' }); return }
    const self = this
    wx.showModal({
      title: '批量续检',
      content: `以今天（${today()}）为本次年检日期，为选中的 ${ids.length} 条各生成一条续检新记录（下次年检=今天+12个月，旧记录保留）？`,
      confirmColor: '#2563eb',
      success(r) { if (r.confirm) self.runBatch(ids, 'renew') }
    })
  },
  batchDel() {
    const ids = this.selectedIds()
    if (!ids.length) { wx.showToast({ title: '请先勾选记录', icon: 'none' }); return }
    const self = this
    wx.showModal({
      title: '批量删除',
      content: `删除选中的 ${ids.length} 条年检记录？删除后不可恢复`,
      confirmColor: '#dc2626',
      success(r) { if (r.confirm) self.runBatch(ids, 'del') }
    })
  },
  async runBatch(ids, action) {
    if (this.data.busy) return
    this.setData({ busy: true })
    wx.showLoading({ title: '处理中…', mask: true })
    let ok = 0, fail = 0, lastErr = ''
    for (const id of ids) {
      try {
        if (action === 'renew') await post(`/api/inspections/${id}/renew`, { date: today() })
        else await del(`/api/inspections/${id}`)
        ok++
      } catch (e) { fail++; lastErr = e.message || '' }
    }
    wx.hideLoading()
    this.setData({ busy: false, checked: {}, checkedCount: 0, allChecked: false })
    let msg = (action === 'renew' ? '续检完成 ' : '删除完成 ') + `成功 ${ok} 条`
    if (fail) msg += `，失败 ${fail} 条${lastErr ? '：' + lastErr : ''}`
    wx.showToast({ title: msg, icon: 'none' })
    this.load()
  },

  // ---- 单条：续检 / 编辑 / 删除 ----
  renew(e) {
    const row = e.currentTarget.dataset.row
    this.setData({ renewDialog: true, renewForm: { id: row.id, name: row.customerName, date: today() } })
  },
  closeRenew() { this.setData({ renewDialog: false }) },
  setRenewDate(e) { this.setData({ 'renewForm.date': e.detail.value }) },
  doRenew() {
    if (this.data.renewing) return
    const f = this.data.renewForm
    this.setData({ renewing: true })
    post(`/api/inspections/${f.id}/renew`, { date: f.date }).then(r => {
      this.setData({ renewDialog: false, renewing: false })
      wx.showToast({ title: r.message || '已续检', icon: 'none' })
      this.load()
    }).catch(e2 => {
      this.setData({ renewing: false })
      wx.showToast({ title: e2.message || '续检失败', icon: 'none' })
    })
  },
  edit(e) {
    const row = e.currentTarget.dataset.row
    this.setData({ dialog: true, form: { ...emptyForm(), ...row } })
  },
  del(e) {
    const row = e.currentTarget.dataset.row
    const self = this
    wx.showModal({
      title: '删除记录',
      content: `删除「${row.customerName}」的年检记录？`,
      confirmColor: '#dc2626',
      success(r) {
        if (!r.confirm) return
        del(`/api/inspections/${row.id}`).then(() => {
          wx.showToast({ title: '已删除' }); self.load()
        }).catch(e2 => wx.showToast({ title: e2.message || '删除失败', icon: 'none' }))
      }
    })
  },

  // ---- 新增 / 编辑表单 ----
  add() { this.setData({ dialog: true, form: emptyForm(), purposeIndex: 0 }) },
  setForm(e) {
    const field = e.currentTarget.dataset.field
    this.setData({ ['form.' + field]: e.detail.value })
  },
  setFormPurpose(e) {
    const i = Number(e.detail.value)
    this.setData({ purposeIndex: i, 'form.purpose': this.data.purposeOptions[i] || '' })
  },
  closeDialog() { this.setData({ dialog: false }) },
  save() {
    if (this.data.saving) return
    const f = this.data.form
    if (!f.customerName || !f.customerName.trim()) { wx.showToast({ title: '客户名称必填', icon: 'none' }); return }
    if (!f.inspectDate) { wx.showToast({ title: '本次年检日期必填', icon: 'none' }); return }
    this.setData({ saving: true })
    const body = { ...f, quantity: parseInt(f.quantity, 10) || 1, price: parseFloat(f.price) || 0 }
    const req = f.id ? put('/api/inspections/' + f.id, body) : post('/api/inspections', body)
    req.then(r => {
      wx.showToast({ title: r.message || '已保存', icon: 'none' })
      this.setData({ dialog: false, saving: false })
      this.load()
    }).catch(e => {
      this.setData({ saving: false })
      wx.showToast({ title: e.message || '保存失败', icon: 'none' })
    })
  },
  noop() {}
})
