const { get } = require('./utils/api')

App({
  onLaunch() {
    const token = wx.getStorageSync('token')
    if (!token) { wx.reLaunch({ url: '/pages/login/login' }); return }
    // 启动校验 token（后端重启会失效）
    get('/api/auth/me').then(() => {}).catch(() => {})
  },
  globalData: { user: null, role: null }
})
