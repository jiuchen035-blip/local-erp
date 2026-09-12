/** 打印：纸张设置（localStorage 持久化）+ 打印 HTML（配合 App.vue 的 @media print 样式） */

export const PAPER_PRESETS = {
  A4: { size: 'A4 portrait', margin: '12mm' },
  A5: { size: 'A5 portrait', margin: '10mm' },
  ticket80: { size: '80mm auto', margin: '3mm' }
}

export const PAPER_OPTIONS = [
  { value: 'A4', label: 'A4 纸' },
  { value: 'A5', label: 'A5 纸' },
  { value: 'ticket80', label: '80mm 小票' }
]

export function getPaper() {
  return localStorage.getItem('erp_paper') || 'A4'
}

export function setPaper(p) {
  localStorage.setItem('erp_paper', p)
}

function applyPageStyle(paper) {
  const preset = PAPER_PRESETS[paper] || PAPER_PRESETS.A4
  let style = document.getElementById('print-page-style')
  if (!style) {
    style = document.createElement('style')
    style.id = 'print-page-style'
    document.head.appendChild(style)
  }
  // 注入 @page：浏览器"更多设置→纸张大小"为"默认"时由此规则生效
  style.textContent = `@page { size: ${preset.size}; margin: ${preset.margin}; }`
}

/** 打印 HTML：填入全局打印区（按纸张挂 class）并调起系统打印 */
export function printHtml(innerHtml, paper) {
  const key = paper || getPaper()
  applyPageStyle(key)
  let el = document.getElementById('print-area')
  if (!el) {
    el = document.createElement('div')
    el.id = 'print-area'
    document.body.appendChild(el)
  }
  el.className = 'paper-' + key
  el.innerHTML = innerHtml
  window.print()
}

/** 店铺抬头块（店名 + 单据标题） */
export function shopHeaderHtml(shopName, docTitle) {
  const name = shopName || '本地商家后台'
  return `<div class="shop-header">
    <div class="shop-name">${name}</div>
    <div class="doc-title">${docTitle}</div>
  </div>`
}

/** 生成单据打印样式（小票风格）。opts: { shopName, showSign } */
export function receiptHtml(title, metaRows, tableRows, totalText, opts = {}) {
  const meta = metaRows.map(([k, v]) => `<div class="meta"><span>${k}</span><span>${v ?? '-'}</span></div>`).join('')
  const head = Object.keys(tableRows[0] || {})
  const thead = `<tr>${head.map(h => `<th>${h}</th>`).join('')}</tr>`
  const tbody = tableRows.map(r => `<tr>${head.map(h => `<td>${r[h] ?? '-'}</td>`).join('')}</tr>`).join('')
  const header = opts.shopName ? shopHeaderHtml(opts.shopName, title) : `<h3>${title}</h3>`
  const sign = opts.showSign
    ? `<div class="sign-row"><span>客户签字：______________</span><span>日期：______________</span><span>店家盖章：______________</span></div>`
    : ''
  return `
  <div class="receipt">
    ${header}
    ${meta}
    <table><thead>${thead}</thead><tbody>${tbody}</tbody></table>
    <div class="total">${totalText}</div>
    ${sign}
    <div class="sign">打印时间：${new Date().toLocaleString('zh-CN')}<br/>感谢惠顾，欢迎再次光临</div>
  </div>`
}
