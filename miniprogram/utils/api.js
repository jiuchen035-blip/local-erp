const { BASE_URL } = require('../config')

function request(method, path, data) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: BASE_URL + path,
      method,
      data,
      timeout: 60000,
      header: {
        'X-Auth-Token': wx.getStorageSync('token') || '',
        'Content-Type': 'application/json'
      },
      success(res) {
        if (res.statusCode === 401) {
          wx.clearStorageSync()
          wx.reLaunch({ url: '/pages/login/login' })
          reject(new Error('未登录或登录已过期'))
          return
        }
        if (res.statusCode >= 400) {
          reject(new Error((res.data && (res.data.error || res.data.message)) || ('请求失败 ' + res.statusCode)))
          return
        }
        resolve(res.data)
      },
      fail(e) { reject(new Error('网络连接失败，请检查隧道是否开启')) }
    })
  })
}

module.exports = {
  get: (p) => request('GET', p),
  post: (p, d) => request('POST', p, d || {}),
  put: (p, d) => request('PUT', p, d),
  del: (p) => request('DELETE', p)
}
