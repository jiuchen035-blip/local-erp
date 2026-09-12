module.exports = {
  money(v) { return '￥' + (Number(v) || 0).toFixed(2) },
  billType(t) {
    return { PURCHASE: '采购入库', SALE: '销售出库', PURCHASE_RETURN: '采购退货',
      SALE_RETURN: '销售退货', LOSS: '报损', GAIN: '盘盈', TRANSFER: '调拨' }[t] || t
  }
}
