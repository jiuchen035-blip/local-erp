const { post } = require('../../utils/api')

Page({
  data: { user: '', role: '' },
  onShow() {
    this.setData({
      user: wx.getStorageSync('username') || '',
      role: wx.getStorageSync('role') || ''
    })
  },
  go(e) { wx.navigateTo({ url: e.currentTarget.dataset.url }) },
  logout() {
    wx.showModal({
      title: '退出登录？',
      success: r => {
        if (!r.confirm) return
        post('/api/auth/logout').catch(() => {}).finally(() => {
          wx.clearStorageSync()
          wx.reLaunch({ url: '/pages/login/login' })
        })
      }
    })
  }
})
