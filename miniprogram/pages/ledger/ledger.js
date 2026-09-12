const { get } = require('../../utils/api')

Page({
  data: { tab: 'RECEIVABLE', rows: [], recvTotal: 0, payTotal: 0 },
  onShow() { this.load() },
  setTab(e) {
    this.setData({ tab: e.currentTarget.dataset.tab })
    get('/api/ledger/summary').then(d => {
      this.setData({
        rows: (this.data.tab === 'RECEIVABLE' ? d.receivables : d.payables) || [],
        recvTotal: d.receivableTotal || 0, payTotal: d.payableTotal || 0
      })
    }).catch(e => wx.showToast({ title: e.message, icon: 'none' }))
  },
  load() {
    get('/api/ledger/summary').then(d => {
      this.setData({
        rows: (this.data.tab === 'RECEIVABLE' ? d.receivables : d.payables) || [],
        recvTotal: d.receivableTotal || 0, payTotal: d.payableTotal || 0
      })
    }).catch(e => wx.showToast({ title: e.message, icon: 'none' }))
  }
})
