const { get, post, put } = require('../../utils/api')

Page({
  data: { tab: 'SUPPLIER', list: [], paged: [], page: 1, total: 0, keyword: '',
    dialog: false, form: {} },
  onShow() { this.load() },
  load() {
    get('/api/partners').then(list => {
      this.setData({ all: list })
      this.applyFilter()
    }).catch(e => wx.showToast({ title: e.message, icon: 'none' }))
  },
  applyFilter() {
    const k = (this.data.keyword || '').trim().toLowerCase()
    let rows = this.data.all.filter(p => p.type === this.data.tab)
    if (k) rows = rows.filter(p => (p.name || '').toLowerCase().includes(k) || (p.phone || '').includes(k))
    const s = (this.data.page - 1) * 15
    this.setData({ paged: rows.slice(s, s + 15), total: rows.length })
  },
  setTab(e) { this.setData({ tab: e.currentTarget.dataset.tab, page: 1 }); this.applyFilter() },
  onKw(e) { this.setData({ keyword: e.detail.value, page: 1 }); this.applyFilter() },
  prevPage() { if (this.data.page > 1) { this.setData({ page: this.data.page - 1 }); this.applyFilter() } },
  nextPage() { if (this.data.page * 15 < this.data.total) { this.setData({ page: this.data.page + 1 }); this.applyFilter() } },
  add() { this.setData({ dialog: true, form: { name: '', type: this.data.tab === 'PAYABLE' ? 'SUPPLIER' : 'CUSTOMER', phone: '', address: '' } }) },
  edit(e) { this.setData({ dialog: true, form: { ...e.currentTarget.dataset.row } }) },
  setForm(e) { this.setData({ ['form.' + e.currentTarget.dataset.field]: e.detail.value }) },
  save() {
    const f = this.data.form
    if (!f.name) { wx.showToast({ title: '名称必填', icon: 'none' }); return }
    const req = f.id ? put('/api/partners/' + f.id, f) : post('/api/partners', f)
    req.then(() => { wx.showToast({ title: '已保存' }); this.setData({ dialog: false }); this.load() })
      .catch(e => wx.showToast({ title: e.message, icon: 'none' }))
  },
  noop() {}
})
