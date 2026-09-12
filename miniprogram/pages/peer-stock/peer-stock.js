const { get, post, put, del } = require('../../utils/api')
const SIZE = 15

Page({
  data: { list: [], paged: [], partners: [], categories: [], filterPartner: '', filterCategory: '', keyword: '',
    page: 1, total: 0, dialog: false, form: {} },
  onShow() { this.load(); this.loadPartners() },
  loadPartners() { get('/api/partners?type=SUPPLIER').then(p => this.setData({ partners: p })).catch(() => {}) },
  load() {
    const q = []
    if (this.data.filterPartner) q.push('partnerId=' + this.data.filterPartner)
    if (this.data.filterCategory) q.push('category=' + this.data.filterCategory)
    if (this.data.keyword) q.push('keyword=' + this.data.keyword)
    get('/api/peer-stock' + (q.length ? '?' + q.join('&') : '')).then(list => {
      this.setData({ list, categories: [...new Set(list.map(x => x.category).filter(Boolean))], page: 1, total: list.length })
      this.applyPage()
    }).catch(e => wx.showToast({ title: e.message, icon: 'none' }))
  },
  applyPage() {
    const s = (this.data.page - 1) * SIZE
    this.setData({ paged: this.data.list.slice(s, s + SIZE) })
  },
  prevPage() { if (this.data.page > 1) { this.setData({ page: this.data.page - 1 }); this.applyPage() } },
  nextPage() { if (this.data.page * SIZE < this.data.total) { this.setData({ page: this.data.page + 1 }); this.applyPage() } },
  setFilter(e) { this.setData({ [e.currentTarget.dataset.field]: e.currentTarget.dataset.value || e.detail.value || '', page: 1 }); this.load() },
  onKw(e) { this.setData({ keyword: e.detail.value, page: 1 }); this.load() },
  add() { this.setData({ dialog: true, form: { partnerName: '', productName: '', category: '', lastPrice: null, unit: '个' } }) },
  edit(e) { this.setData({ dialog: true, form: { ...e.currentTarget.dataset.row } }) },
  setForm(e) { this.setData({ ['form.' + e.currentTarget.dataset.field]: e.detail.value }) },
  save() {
    const f = this.data.form
    if (!f.productName) { wx.showToast({ title: '商品名称必填', icon: 'none' }); return }
    const data = { ...f }
    if (typeof data.partnerId === 'string') { data.partnerName = data.partnerId; delete data.partnerId }
    if (!data.partnerId && !data.partnerName) { wx.showToast({ title: '请选择或输入同行', icon: 'none' }); return }
    const req = f.id ? put('/api/peer-stock/' + f.id, data) : post('/api/peer-stock', data)
    req.then(() => { wx.showToast({ title: '已保存' }); this.setData({ dialog: false }); this.load(); this.loadPartners() })
      .catch(e => wx.showToast({ title: e.message, icon: 'none' }))
  },
  del(e) {
    const row = e.currentTarget.dataset.row
    wx.showModal({ title: '删除', content: row.partnerName + ' - ' + row.productName, success: r => {
      if (!r.confirm) return
      del('/api/peer-stock/' + row.id).then(() => { wx.showToast({ title: '已删除' }); this.load() })
    } })
  }
})
