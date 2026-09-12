const { get, post, put } = require('../../utils/api')

Page({
  data: { msgs: [], input: '', sending: false, scrollInto: '' },
  onShow() {
    const u = wx.getStorageSync('username')
    if (u && !this.data.msgs.length) {
      this.setData({
        msgs: [{ role: 'sys', text: '你好 ' + u + '！我是以你的身份工作的 AI 助手：可以查库存/销售/毛利/应收应付/年检/同行货源，也能帮你开草稿单（你确认后才过账）。试着问我「有什么风险」或「应收多少钱」' }]
      })
    }
  },
  onInput(e) { this.setData({ input: e.detail.value }) },
  send() {
    const q = this.data.input.trim()
    if (!q || this.data.sending) return
    const msgs = this.data.msgs.concat([{ role: 'user', text: q }, { role: 'ai', text: '', loading: true }])
    this.setData({ msgs, input: '', sending: true, scrollInto: 'msg-bottom' })
    post('/api/ai/agent', { message: q }).then(r => {
      const i = this.data.msgs.length - 1
      this.setData({
        ['msgs[' + i + '].loading']: false,
        ['msgs[' + i + '].text']: r.reply || '(无回复)',
        ['msgs[' + i + '].draft']: r.draft || null
      })
      this.setData({ scrollInto: 'msg-bottom' })
    }).catch(e => {
      const i = this.data.msgs.length - 1
      this.setData({ ['msgs[' + i + '].loading']: false, ['msgs[' + i + '].text']: '出错了：' + e.message })
    }).finally(() => this.setData({ sending: false }))
  },
  postDraft(e) {
    const d = e.currentTarget.dataset.draft
    const doPost = (usePeer) => {
      const req = usePeer ? put('/api/bills/' + d.id + '/post-with-peer', {}) : post('/api/bills/' + d.id + '/post', {})
      req.then(r => {
        wx.showToast({ title: r.message || '已过账', icon: 'success' })
        const i = this.data.msgs.length - 1
        this.setData({ ['msgs[' + i + '].draft']: null })
      }).catch(e2 => wx.showToast({ title: e2.message, icon: 'none' }))
    }
    if (d.peerPurchaseNo) {
      wx.showModal({
        title: '确认过账',
        content: '同行调货：采购 ' + d.peerPurchaseNo + ' 和销售 ' + d.billNo + ' 将一起过账（库存/账务生效）',
        success(r) { if (r.confirm) doPost(true) }
      })
    } else {
      wx.showModal({ title: '确认过账', content: '过账后影响库存与账务，确认？', success(r) { if (r.confirm) doPost(false) } })
    }
  }
})
