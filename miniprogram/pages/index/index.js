const { get } = require('../../utils/api')
const fmt = require('../../utils/format')

Page({
  data: { d: {}, trend: [], top: [], low: 0, loading: true },
  onShow() { this.load() },
  load() {
    this.setData({ loading: true })
    get('/api/dashboard').then(d => {
      this.setData({ d, trend: (d.saleTrend || []).map(x => x), top: d.topProducts || [], low: d.lowStockCount || 0, loading: false })
      this.drawTrend(d.saleTrend || [])
    }).catch(e => { this.setData({ loading: false }); wx.showToast({ title: e.message, icon: 'none' }) })
  },
  drawTrend(trend) {
    // 简易柱状趋势（免 echarts 依赖）
    const ctx = wx.createCanvasContext('trend', this)
    const W = 320, H = 150, pad = 24
    const max = Math.max.apply(null, trend.map(x => x.amount).concat([1]))
    ctx.clearRect(0, 0, 400, 200)
    const n = trend.length || 1
    const bw = (W - pad * 2) / n
    trend.forEach((x, i) => {
      const h = Math.max(2, (x.amount / max) * (H - pad * 2))
      ctx.fillStyle = '#2563eb'
      ctx.fillRect(pad + i * bw + 2, H - pad - h, bw - 4, h)
      if (i % 3 === 0) {
        ctx.fillStyle = '#98a2b3'; ctx.font = '9px sans-serif'
        ctx.fillText(x.date, pad + i * bw, H - 6)
      }
    })
    ctx.draw()
  },
  go(e) { wx.navigateTo({ url: e.currentTarget.dataset.url }) },
  fmtMoney: fmt.money
})
