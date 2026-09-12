const { get, post } = require('../../utils/api')

Page({
  data: {
    warehouses: [], warehouseIndex: 0,
    products: [], partners: [],
    typeOptions: [
      { v: 'SALE', n: '销售出库' }, { v: 'PURCHASE', n: '采购入库' },
      { v: 'PURCHASE_RETURN', n: '采购退货' }, { v: 'SALE_RETURN', n: '销售退货' },
      { v: 'LOSS', n: '报损' }, { v: 'GAIN', n: '盘盈' }, { v: 'TRANSFER', n: '调拨' }
    ],
    typeIndex: 0, type: 'SALE', typeName: '销售出库',
    lines: [],
    pickKw: '', pickList: [], pickShow: false, quick: { show: false, saving: false, form: { name: '', salePrice: null, costPrice: null, unit: '个' } },
    peerShow: false, peerPartnerName: '', peerPartnerId: null, peerPaid: 0,
    peerList: [], peerPaged: [], peerKeyword: '', peerPage: 1, peerLoaded: false, supSug: [],
    custom: { show: false, name: '', price: null, category: '' },
    partnerName: '',
    discount: 100, remark: '', paid: 1,
    stockByWh: {},
    total: 0, saving: false
  },
  onShow() { this.load() },
  load() {
    Promise.all([get('/api/warehouses'), get('/api/products'), get('/api/partners'), get('/api/stock/by-warehouse')])
      .then(([wh, ps, pt, sbw]) => {
        // 分仓库存：warehouseId -> { productId: 数量 }，与后端过账同口径做库存预检
        const stockByWh = {}
        for (const r of (sbw || [])) {
          stockByWh[r.warehouseId] = stockByWh[r.warehouseId] || {}
          stockByWh[r.warehouseId][r.productId] = (stockByWh[r.warehouseId][r.productId] || 0) + (r.quantity || 0)
        }
        this.setData({ warehouses: wh, products: ps, partners: pt, stockByWh,
          warehouseIndex: this.data.warehouseIndex < wh.length ? this.data.warehouseIndex : 0 })
      }).catch(e => wx.showToast({ title: e.message, icon: 'none' }))
  },
  setType(e) {
    const i = Number(e.detail.value)
    const opt = this.data.typeOptions[i] || this.data.typeOptions[0]
    this.setData({ typeIndex: i, type: opt.v, typeName: opt.n, lines: [] })
    this.calc()
  },
  setWh(e) { this.setData({ warehouseIndex: Number(e.detail.value) }) },
  setPartnerName(e) { this.setData({ partnerName: e.detail.value }) },
  onPaid(e) { this.setData({ paid: Number(e.detail.value) }) },
  onDiscount(e) { this.setData({ discount: Number(e.detail.value) || 100 }); this.calc() },
  onRemark(e) { this.setData({ remark: e.detail.value }) },
  partnerLabel() { return ['SALE', 'SALE_RETURN'].includes(this.data.type) ? '客户' : '供应商' },

  // ---- 自有商品搜索（JS 过滤；无结果可快速建档）----
  setPickKw(e) {
    const kw = (e.detail.value || '').trim()
    const k = kw.toLowerCase()
    const filtered = k ? this.data.products.filter(p =>
      (p.name || '').toLowerCase().includes(k) || (p.sku || '').toLowerCase().includes(k) || (p.barcode || '').includes(k)).slice(0, 20) : []
    this.setData({ pickKw: e.detail.value, pickList: filtered, pickShow: kw.length > 0 })
  },
  hidePick() { this.setData({ pickShow: false, pickKw: '', pickList: [] }) },
  pickProduct(e) {
    const p = this.data.pickList[e.currentTarget.dataset.index]
    this.addLine({ productId: p.id, name: p.name, sku: p.sku, unit: p.unit || '个', quantity: 1,
      price: this.data.type === 'PURCHASE' ? p.costPrice : p.salePrice })
    this.hidePick()
  },
  quickForm(e) { this.setData({ ['quick.form.' + e.currentTarget.dataset.field]: e.detail.value }) },
  openQuickCreate() {
    this.setData({ 'quick.show': true, 'quick.form': { name: this.data.pickKw, salePrice: null, costPrice: null, unit: '个', spec: '' } })
  },
  saveQuickCreate() {
    if (this.data.quick.saving) return          // 防双击重复建档
    const f = this.data.quick.form
    if (!f.name?.trim()) { wx.showToast({ title: '商品名称必填', icon: 'none' }); return }
    this.setData({ 'quick.saving': true })
    post('/api/products', {
      name: f.name.trim(), spec: f.spec || '', salePrice: f.salePrice ?? 0, costPrice: f.costPrice ?? 0,
      unit: f.unit || '个', safeStock: 10
    }).then(p => {
      this.data.products.push(p)
      this.setData({ 'quick.show': false, 'quick.saving': false })
      this.addLine({ productId: p.id, name: p.name, sku: p.sku, unit: p.unit || '个', quantity: 1,
        price: this.data.type === 'PURCHASE' ? p.costPrice : p.salePrice })
    }).catch(e => {
      this.setData({ 'quick.saving': false })
      wx.showToast({ title: e.message, icon: 'none' })
    })
  },

  // ---- 同行商品（对齐电脑端：选/输供应商 → 查询同行库存 → 选品或自定义登记）----
  setPeerShow() {
    const show = !this.data.peerShow
    this.setData({ peerShow: show })
    if (show && this.data.peerPartnerId) this.loadPeer()
  },
  focusSupplier() { this.refreshSupSuggestions(this.data.peerPartnerName) },
  setPeerPartnerName(e) {
    // 输入即视为重选供应商；完全匹配已有档案时自动锁定 partnerId
    const name = e.detail.value
    const exact = this.data.partners.find(p => p.type === 'SUPPLIER' && p.name === name.trim())
    this.setData({ peerPartnerName: name, peerPartnerId: exact ? exact.id : null })
    this.refreshSupSuggestions(name)
  },
  refreshSupSuggestions(text) {
    const k = (text || '').trim().toLowerCase()
    const sups = this.data.partners.filter(p => p.type === 'SUPPLIER')
    const sug = (k ? sups.filter(p => (p.name || '').toLowerCase().includes(k)) : sups).slice(0, 5)
    this.setData({ supSug: sug })
  },
  pickSupplier(e) {
    const s = this.data.supSug[e.currentTarget.dataset.index]
    this.setData({ peerPartnerName: s.name, peerPartnerId: s.id, supSug: [] })
    this.loadPeer()
  },
  setPeerPaid(e) { this.setData({ peerPaid: Number(e.detail.value) }) },
  setPeerKw(e) { this.setData({ peerKeyword: e.detail.value }) },
  searchPeer() { this.setData({ supSug: [] }); this.loadPeer() },
  loadPeer() {
    const q = []
    if (this.data.peerPartnerId) q.push('partnerId=' + this.data.peerPartnerId)
    const kw = (this.data.peerKeyword || '').trim()
    if (kw) q.push('keyword=' + encodeURIComponent(kw))
    get('/api/peer-stock' + (q.length ? '?' + q.join('&') : '')).then(list => {
      this.setData({ peerList: list, peerPage: 1, peerLoaded: true })
      this.applyPeerPage()
    }).catch(() => {})
  },
  applyPeerPage() {
    const s = (this.data.peerPage - 1) * 5
    this.setData({ peerPaged: this.data.peerList.slice(s, s + 5) })
  },
  peerPrev() { if (this.data.peerPage > 1) { this.setData({ peerPage: this.data.peerPage - 1 }); this.applyPeerPage() } },
  peerNext() { if (this.data.peerPage * 5 < this.data.peerList.length) { this.setData({ peerPage: this.data.peerPage + 1 }); this.applyPeerPage() } },
  addPeerLine(ps) {
    this.addLine({ peer: true, peerStockId: ps.id, peerPartnerName: ps.partnerName,
      name: ps.productName, sku: ps.sku, unit: ps.unit || '个', quantity: 1,
      price: Math.round((ps.lastPrice || 0) * 130) / 100, peerPrice: ps.lastPrice || 0 })
  },
  pickPeer(e) { this.addPeerLine(this.data.peerList[e.currentTarget.dataset.index]) },
  openCustom() { this.setData({ custom: { show: true, name: '', price: null, category: '' } }) },
  setCustom(e) { this.setData({ ['custom.' + e.currentTarget.dataset.field]: e.detail.value }) },
  addCustomPeer() {
    const c = this.data.custom
    if (!c.name.trim()) { wx.showToast({ title: '商品名称必填', icon: 'none' }); return }
    if (c.price == null || c.price < 0) { wx.showToast({ title: '请填调货价', icon: 'none' }); return }
    if (!this.data.peerPartnerName.trim()) { wx.showToast({ title: '请先填调货供应商', icon: 'none' }); return }
    const partnerName = this.data.peerPartnerName.trim()
    const self = this
    post('/api/peer-stock', { partnerName, productName: c.name.trim(), lastPrice: c.price, category: c.category || '' })
      .then(ps => {
        // 新供应商已由后端自动建档：记住 id，之后的查询按该同行过滤
        if (ps.partnerId && !self.data.peerPartnerId) {
          self.data.partners.push({ id: ps.partnerId, name: partnerName, type: 'SUPPLIER' })
          self.setData({ peerPartnerId: ps.partnerId })
        }
        self.loadPeer()
        self.addPeerLine(ps)
        self.setData({ custom: { show: false, name: '', price: null, category: '' } })
      }).catch(e => wx.showToast({ title: e.message, icon: 'none' }))
  },

  // ---- 明细行 ----
  addLine(line) {
    const lines = this.data.lines.concat([line])
    this.setData({ lines })
    this.calc()
  },
  setQty(e) { this.setLine(e, 'quantity') },
  setPrice(e) { this.setLine(e, 'price') },
  setPeerPrice(e) { this.setLine(e, 'peerPrice') },
  setLine(e, field) {
    const i = e.currentTarget.dataset.index
    this.setData({ ['lines[' + i + '].' + field]: Number(e.detail.value) || 0 })
    this.calc()
  },
  delLine(e) {
    const i = e.currentTarget.dataset.index
    const lines = this.data.lines.slice(); lines.splice(i, 1)
    this.setData({ lines }); this.calc()
  },
  calc() {
    const total = this.data.lines.reduce((s, l) => s + l.quantity * (l.price || 0), 0) * this.data.discount / 100
    this.setData({ total: Math.round(total * 100) / 100 })
  },

  save() { this.submit(false) },
  saveDraft() { this.submit(true) },
  submit(asDraft) {
    const lines = this.data.lines
    if (!lines.length) { wx.showToast({ title: '请先添加商品明细', icon: 'none' }); return }
    const peers = lines.filter(l => l.peer)
    const owns = lines.filter(l => !l.peer)
    const wh = this.data.warehouses[this.data.warehouseIndex]
    if (!wh) { wx.showToast({ title: '请选择仓库', icon: 'none' }); return }
    const partnerLabel = ['SALE', 'SALE_RETURN'].includes(this.data.type) ? '客户' : '供应商'
    const partnerType = ['SALE', 'SALE_RETURN'].includes(this.data.type) ? 'CUSTOMER' : 'SUPPLIER'

    if (peers.length) {
      if (this.data.type !== 'SALE') { wx.showToast({ title: '同行调货仅支持销售出库', icon: 'none' }); return }
      if (!this.data.peerPartnerName) { wx.showToast({ title: '请填写调货供应商', icon: 'none' }); return }
      if (peers.some(l => !l.peerPrice && l.peerPrice !== 0)) { wx.showToast({ title: '同行行请填调货价', icon: 'none' }); return }
      if (owns.some(l => !l.price && l.price !== 0)) { wx.showToast({ title: '同行行请填售价', icon: 'none' }); return }
    }
    // 与后端过账同口径：出库类型按所选仓库的库存预检（总库存够 ≠ 该仓库够）
    if (['SALE', 'PURCHASE_RETURN', 'LOSS', 'TRANSFER'].includes(this.data.type)) {
      const whStock = this.data.stockByWh[wh.id] || {}
      for (const l of owns) {
        const remain = whStock[l.productId] || 0
        if (l.quantity > remain) {
          const p = this.data.products.find(x => x.id === l.productId)
          wx.showModal({ title: '库存不足', showCancel: false,
            content: '「' + (p ? p.name : l.name) + '」在仓库「' + wh.name + '」仅剩 ' + remain
              + '，请改仓库或减量' + (this.data.type === 'SALE' ? '；同行调货请展开同行商品选品' : '') })
          return
        }
      }
    }
    this.setData({ saving: true })
    const finish = (msg) => {
      wx.showToast({ title: msg, icon: 'success' })
      this.setData({ lines: [], remark: '', total: 0, saving: false })
      this.load()
    }
    const fail = (e) => { this.setData({ saving: false }); wx.showToast({ title: e.message, icon: 'none' }) }
    // name 对应档案类型由第二个参数决定：调货方固定 SUPPLIER，单据往来单位按单据类型
    const ensurePartner = (name, type) => {
      const t = type || partnerType
      const exist = this.data.partners.find(p => p.name === name && p.type === t)
      if (exist) return Promise.resolve(exist.id)
      return post('/api/partners', { name, type: t }).then(r => {
        this.data.partners.push({ id: r.id, name, type: t })
        this.setData({ partners: this.data.partners })
        return r.id
      })
    }

    if (peers.length) {
      ensurePartner(this.data.peerPartnerName, 'SUPPLIER')
        .then(peerId => {
          if (!peerId) throw new Error('调货供应商建档失败')
          const cname = (this.data.partnerName || '').trim()
          const custReady = cname ? ensurePartner(cname, 'CUSTOMER') : Promise.resolve(null)
          return custReady.then(custId => post('/api/bills/peer-sale', {
            sale: { type: 'SALE', partnerId: custId || null, warehouseId: wh.id, paid: this.data.paid,
              discount: this.data.discount, remark: this.data.remark,
              items: owns.map(l => ({ productId: l.productId, quantity: l.quantity, price: l.price })) },
            purchase: { type: 'PURCHASE', partnerId: peerId, warehouseId: wh.id, paid: this.data.peerPaid, remark: '同行调货' },
            peerLines: peers.map(l => ({ stockId: l.peerStockId, quantity: l.quantity, salePrice: l.price, peerPrice: l.peerPrice }))
          }).then(r => {
            wx.showModal({
              title: '同行调货草稿已生成', showCancel: false,
              content: '采购 ' + r.purchaseNo + ' + 销售 ' + r.saleNo,
              success() { finish('已生成，可在调货记录确认过账') }
            })
          }))
        }).catch(fail)
      return
    }

    const doCreate = (partnerId) => {
      post('/api/bills', {
        type, warehouseId: wh.id, partnerId: partnerId || null, paid: this.data.paid,
        discount: this.data.discount, remark: this.data.remark, autoPost: !asDraft,
        items: owns.map(l => ({ productId: l.productId, quantity: l.quantity, price: l.price }))
      }).then(r => finish(asDraft ? '已存草稿 ' + r.billNo : '已过账 ' + r.billNo)).catch(e => {
        this.setData({ saving: false })
        if (asDraft) { wx.showToast({ title: e.message, icon: 'none' }); return }
        // 过账失败时草稿已保留：弹窗明确告知，避免误以为过账成功
        wx.showModal({ title: '已存为草稿，但过账失败', showCancel: false,
          content: e.message + '。可在电脑端「进货/销售」页找到这张草稿过账或删除' })
      })
    }
    if (this.data.partnerName && this.data.partnerName.trim()) {
      ensurePartner(this.data.partnerName.trim(), partnerType)
        .then(id => { if (id) doCreate(id); else doCreate(null) })
        .catch(fail)
    } else {
      doCreate(null)
    }
  },
  goOrders() { wx.navigateTo({ url: '/pages/orders/orders' }) },
  closeQuick() { this.setData({ 'quick.show': false }) },
  noop() {}
})
