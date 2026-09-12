const { get, post, put, del } = require('../../utils/api')
const SIZE = 15

Page({
  data: {
    all: [], paged: [],
    view: 'cat',                        // cat=分类浏览（默认）/ all=全部商品
    catL1: '', catL2: '', catL3: '',
    rows: [],                           // 当前层级混合列表：分类行 {isCat, name, count, path} + 商品行 {isCat:false...}
    keyword: '', page: 1, total: 0, loading: false,
    addDialog: false, saving: false, editing: false,
    form: { id: null, name: '', spec: '', category: '', subCategory: '', sub2Category: '', salePrice: null, costPrice: null, unit: '个', barcode: '' }
  },
  onShow() { this.load() },
  load() {
    this.setData({ loading: true })
    get('/api/products').then(list => {
      this.setData({ all: list, loading: false })
      this.buildRows()
      this.applyPage()
    }).catch(e => { this.setData({ loading: false }); wx.showToast({ title: e.message, icon: 'none' }) })
  },

  // 当前层级混合列表：子分类行（排在前面）+ 直属商品行
  buildRows() {
    const view = this.data.view, catL1 = this.data.catL1, catL2 = this.data.catL2, catL3 = this.data.catL3
    let catRows = []
    if (view === 'cat') {
      if (!catL1) {
        const m = new Map()
        let unc = 0
        for (const p of this.data.all) {
          if (p.category) m.set(p.category, (m.get(p.category) || 0) + 1)
          else unc++
        }
        catRows = [...m.entries()].map(([name, count]) => ({ isCat: true, name, count })).sort((a, b) => b.count - a.count)
        if (unc) catRows.push({ isCat: true, name: '未分类', count: unc, unc: true })
      } else if (!catL2) {
        const m = new Map()
        for (const p of this.data.all) {
          if (p.category !== catL1 || !p.subCategory) continue
          m.set(p.subCategory, (m.get(p.subCategory) || 0) + 1)
        }
        catRows = [...m.entries()].map(([name, count]) => ({ isCat: true, name, count })).sort((a, b) => b.count - a.count)
      } else if (!catL3) {
        const m = new Map()
        for (const p of this.data.all) {
          if (p.category !== catL1 || (p.subCategory || '') !== catL2 || !p.sub2Category) continue
          m.set(p.sub2Category, (m.get(p.sub2Category) || 0) + 1)
        }
        catRows = [...m.entries()].map(([name, count]) => ({ isCat: true, name, count })).sort((a, b) => b.count - a.count)
      }
    }
    // 商品行：分类浏览时只显示直属当前层级的商品；全部商品视图显示所有
    const k = (this.data.keyword || '').trim().toLowerCase()
    let prods = this.data.all.filter(p => {
      if (view === 'cat') {
        if (catL1 === '__unc__') return !p.category
        if (!catL1) return false        // 根级只显示分类夹，未分类商品收进「未分类」，避免同一商品出现两次
        if (catL1 !== p.category) return false
        if (catL2 && (p.subCategory || '') !== catL2) return false
        if (catL3 && (p.sub2Category || '') !== catL3) return false
        // 层级中间态：有下层分类时只显示直属商品（下层分类的走分类行）
        if (!catL2 && p.subCategory) return false
        if (catL2 && !catL3 && p.sub2Category) return false
      }
      if (k) return (p.name || '').toLowerCase().includes(k) || (p.sku || '').toLowerCase().includes(k) || (p.barcode || '').includes(k) || (p.spec || '').toLowerCase().includes(k)
      return true
    })
    // 合并：分类行在前，商品行在后
    this.setData({ rows: catRows.concat(prods.map(p => ({ isCat: false, ...p }))) })
  },
  applyPage() {
    const s = (this.data.page - 1) * SIZE
    const rows = this.data.rows
    this.setData({ paged: rows.slice(s, s + SIZE), total: rows.length })
  },
  tapRow(e) {
    const row = e.currentTarget.dataset.row
    if (!row.isCat) return                     // 商品行点击不跳转（编辑/删除在行右侧）
    if (row.unc) { this.setData({ catL1: '__unc__', catL2: '', catL3: '', page: 1 }); this.buildRows(); this.applyPage(); return }
    if (!this.data.catL1) this.setData({ catL1: row.name, catL2: '', catL3: '', page: 1 })
    else if (!this.data.catL2) this.setData({ catL2: row.name, catL3: '', page: 1 })
    else this.setData({ catL3: row.name, page: 1 })
    this.buildRows()
    this.applyPage()
  },
  crumbTo(e) {
    const lvl = Number(e.currentTarget.dataset.lvl)
    if (lvl === 0) this.setData({ catL1: '', catL2: '', catL3: '', page: 1 })
    else if (lvl === 1) this.setData({ catL2: '', catL3: '', page: 1 })
    else this.setData({ catL3: '', page: 1 })
    this.buildRows()
    this.applyPage()
  },
  setView(e) {
    const v = e.currentTarget.dataset.view || (this.data.view === 'cat' ? 'all' : 'cat')
    this.setData({ view: v, page: 1 })
    this.buildRows()
    this.applyPage()
  },
  applyPage() {
    const s = (this.data.page - 1) * SIZE
    this.setData({ paged: this.data.rows.slice(s, s + SIZE), total: this.data.rows.length })
  },
  onKw(e) { this.setData({ keyword: e.detail.value, page: 1 }); this.buildRows(); this.applyPage() },
  prevPage() { if (this.data.page > 1) { this.setData({ page: this.data.page - 1 }); this.applyPage() } },
  nextPage() { if (this.data.page * SIZE < this.data.total) { this.setData({ page: this.data.page + 1 }); this.applyPage() } },

  // ---- 新增 / 编辑（同一弹窗，编辑时预填）----
  openAdd() {
    this.setData({
      addDialog: true, editing: false, saving: false,
      form: { id: null, name: '', spec: '', category: this.data.catL1 || '', subCategory: this.data.catL2 || '', sub2Category: this.data.catL3 || '', salePrice: null, costPrice: null, unit: '个', barcode: '' }
    })
    this.buildFormCats()
  },
  openEdit(e) {
    const row = e.currentTarget.dataset.row
    this.setData({
      addDialog: true, editing: true, saving: false,
      form: { id: row.id, name: row.name, spec: row.spec || '', category: row.category || '', subCategory: row.subCategory || '', sub2Category: row.sub2Category || '', salePrice: row.salePrice, costPrice: row.costPrice, unit: row.unit || '个', barcode: row.barcode || '' }
    })
    this.buildFormCats()
  },
  buildFormCats() {
    const c1 = this.data.form.category, c2 = this.data.form.subCategory
    let l1 = [...new Set(this.data.all.map(p => p.category).filter(Boolean))].sort()
    let l2 = [], l3 = []
    if (c1) {
      const l2Set = new Set(), l3Set = new Set()
      for (const p of this.data.all) {
        if (p.category !== c1) continue
        if (p.subCategory) {
          l2Set.add(p.subCategory)
          if (c2 && p.subCategory === c2 && p.sub2Category) l3Set.add(p.sub2Category)
        }
      }
      l2 = [...l2Set].sort(); l3 = [...l3Set].sort()
    }
    this.setData({ l1Options: l1, l2Options: l2, l3Options: l3 })
  },
  setForm(e) { this.setData({ ['form.' + e.currentTarget.dataset.field]: e.detail.value }) },
  setFormCat1(e) {
    this.setData({ 'form.category': e.detail.value, 'form.subCategory': '', 'form.sub2Category': '' })
    this.buildFormCats()
  },
  setFormCat2(e) {
    this.setData({ 'form.subCategory': e.detail.value, 'form.sub2Category': '' })
    this.buildFormCats()
  },
  save() {
    if (this.data.saving) return               // 防双击重复建档
    const f = this.data.form
    if (!f.name || !f.name.trim()) { wx.showToast({ title: '商品名称必填', icon: 'none' }); return }
    if (f.salePrice == null) { wx.showToast({ title: '请填零售价', icon: 'none' }); return }
    this.setData({ saving: true })
    const req = f.id ? put('/api/products/' + f.id, f) : post('/api/products', f)
    req.then(p => {
      wx.showToast({ title: '已保存 ' + (p.sku || ''), icon: 'success' })
      this.setData({ addDialog: false })
      this.load()
    }).catch(e => {
      this.setData({ saving: false })
      wx.showToast({ title: e.message, icon: 'none' })
    })
  },
  closeAdd() { this.setData({ addDialog: false }) },
  del(e) {
    const row = e.currentTarget.dataset.row
    wx.showModal({
      title: '删除商品',
      content: `删除「${row.name}」（SKU ${row.sku}）？删除后不可恢复`,
      confirmColor: '#dc2626',
      success: r => {
        if (!r.confirm) return
        del('/api/products/' + row.id).then(() => { wx.showToast({ title: '已删除' }); this.load() })
          .catch(e2 => wx.showToast({ title: e2.message || '删除失败', icon: 'none' }))
      }
    })
  },
  noop() {}
})
