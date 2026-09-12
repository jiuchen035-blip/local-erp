const { get } = require('../../utils/api')
const fmt = require('../../utils/format')
const SIZE = 15

Page({
  data: {
    typeOptions: [
      { v: 'SALE', n: '销售出库' }, { v: 'PURCHASE', n: '采购入库' },
      { v: 'PURCHASE_RETURN', n: '采购退货' }, { v: 'SALE_RETURN', n: '销售退货' },
      { v: 'LOSS', n: '报损' }, { v: 'GAIN', n: '盘盈' }, { v: 'TRANSFER', n: '调拨' }
    ],
    typeIndex: -1, type: '',
    list: [], paged: [], page: 1, total: 0, loading: true,
    detail: null, detailDialog: false
  },
  onShow() { this.load() },
  load() {
    this.setData({ loading: true })
    const q = this.data.type ? '?type=' + this.data.type : ''
    get('/api/bills' + q).then(list => {
      this.setData({ list, page: 1, total: list.length })
      this.applyPage()
    }).catch(e => { this.setData({ loading: false }); wx.showToast({ title: e.message, icon: 'none' }) })
  },
  applyPage() {
    const s = (this.data.page - 1) * SIZE
    this.setData({ paged: this.data.list.slice(s, s + SIZE) })
  },
  setType(e) {
    const i = Number(e.detail.value)
    this.setData({ typeIndex: i, type: i >= 0 && this.data.typeOptions[i] ? this.data.typeOptions[i].v : '', page: 1 })
    this.load()
  },
  openDetail(e) {
    const id = e.currentTarget.dataset.id
    get('/api/bills/' + id).then(d => {
      this.setData({ detail: d, detailDialog: true })
    }).catch(e2 => wx.showToast({ title: e2.message || '加载失败', icon: 'none' }))
  },
  closeDetail() { this.setData({ detailDialog: false }) },
  noop() {},
  prevPage() { if (this.data.page > 1) { this.setData({ page: this.data.page - 1 }); this.applyPage() } },
  nextPage() { if (this.data.page * SIZE < this.data.total) { this.setData({ page: this.data.page + 1 }); this.applyPage() } }
})
