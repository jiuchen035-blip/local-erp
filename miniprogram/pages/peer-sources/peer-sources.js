const { get, post } = require('../../utils/api')

Page({
  data: { list: [], pending: [], partners: [], filterPartner: '', keyword: '', confirming: false },
  onShow() { this.loadPending(); this.load(); get('/api/partners?type=SUPPLIER').then(p => this.setData({ partners: p })).catch(() => {}) },
  load() {
    const q = []
    if (this.data.filterPartner) q.push('partnerId=' + this.data.filterPartner)
    if (this.data.keyword) q.push('keyword=' + encodeURIComponent(this.data.keyword))
    get('/api/stock/peer-sources' + (q.length ? '?' + q.join('&') : '')).then(list => this.setData({ list }))
      .catch(e => wx.showToast({ title: e.message, icon: 'none' }))
  },
  // 待确认的同行调货：销售草稿（关联采购草稿），确认后两张一起过账
  loadPending() {
    get('/api/bills').then(list => {
      const rows = list
        .filter(b => b.peerBillId && b.status === 'DRAFT')
        .map(s => {
          const pur = list.find(b => b.id === s.peerBillId)
          return { id: s.id, billNo: s.billNo, purchaseNo: pur ? pur.billNo : '', totalAmount: s.totalAmount, createdAt: s.createdAt }
        })
      this.setData({ pending: rows })
    }).catch(() => {})
  },
  confirmPeer(e) {
    if (this.data.confirming) return
    const id = e.currentTarget.dataset.id
    this.setData({ confirming: true })
    post('/api/bills/' + id + '/post-with-peer').then(r => {
      this.setData({ confirming: false })
      wx.showToast({ title: r.message || '已过账', icon: 'none' })
      this.loadPending()
      this.load()
    }).catch(e2 => {
      this.setData({ confirming: false })
      wx.showToast({ title: e2.message || '过账失败', icon: 'none' })
    })
  },
  setPartner(e) { this.setData({ filterPartner: e.detail.value || '' }); this.load() },
  onKw(e) { this.setData({ keyword: e.detail.value }); this.load() }
})
