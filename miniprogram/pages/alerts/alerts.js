const { get, post, put } = require('../../utils/api')
const SIZE = 50

Page({
  data: { list: [], paged: [], page: 1, total: 0, loading: true },
  onShow() { this.load() },
  load() {
    this.setData({ loading: true })
    get('/api/stock/low-stock').then(list => {
      this.setData({ list, page: 1, total: list.length, paged: list.slice(0, SIZE), loading: false })
    }).catch(e => { this.setData({ loading: false }); wx.showToast({ title: e.message, icon: 'none' }) })
  },
  applyPage() {
    const s = (this.data.page - 1) * SIZE
    this.setData({ paged: this.data.list.slice(s, s + SIZE) })
  },
  prevPage() { if (this.data.page > 1) { this.setData({ page: this.data.page - 1 }); this.applyPage() } },
  nextPage() { if (this.data.page * SIZE < this.data.total) { this.setData({ page: this.data.page + 1 }); this.applyPage() } },
  restock(e) {
    const row = e.currentTarget.dataset.row
    const need = Math.max(row.safeStock * 2 - row.stock, 10)
    wx.showModal({
      title: '一键补货',
      content: '为「' + row.name + '」采购入库 ' + need + ' 件并过账？',
      success: r => {
        if (!r.confirm) return
        post('/api/bills', { type: 'PURCHASE', autoPost: true, paid: 1,
          remark: '库存预警一键补货', items: [{ productId: row.id, quantity: need }] })
          .then(x => { wx.showToast({ title: '已补货 ' + x.billNo, icon: 'success' }); this.load() })
          .catch(e2 => wx.showToast({ title: e2.message, icon: 'none' }))
      }
    })
  },
  mute(e) {
    const row = e.currentTarget.dataset.row
    wx.showModal({
      title: '设为不监控',
      content: '「' + row.name + '」不再出现在库存预警（同行调货商品用）？',
      success: r => {
        if (!r.confirm) return
        put('/api/products/' + row.id, { noAlert: 1 })
          .then(() => { wx.showToast({ title: '已设为不监控' }); this.load() })
          .catch(e2 => wx.showToast({ title: e2.message, icon: 'none' }))
      }
    })
  }
})
