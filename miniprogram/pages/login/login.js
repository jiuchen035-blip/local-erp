const { post } = require('../../utils/api')

Page({
  data: { username: 'admin', password: '', loading: false },
  onInput(e) { this.setData({ [e.currentTarget.dataset.field]: e.detail.value }) },
  login() {
    const { username, password } = this.data
    if (!username || !password) { wx.showToast({ title: '请输入账号密码', icon: 'none' }); return }
    this.setData({ loading: true })
    post('/api/auth/login', { username, password }).then(r => {
      wx.setStorageSync('token', r.token)
      wx.setStorageSync('username', r.username)
      wx.setStorageSync('role', r.role)
      wx.switchTab({ url: '/pages/index/index' })
    }).catch(e => {
      wx.showToast({ title: e.message, icon: 'none' })
    }).finally(() => this.setData({ loading: false }))
  }
})
