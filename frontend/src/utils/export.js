import * as XLSX from 'xlsx'

/** 前端导出 Excel：rows 为对象数组，键即表头 */
export function exportExcel(filename, sheetName, rows) {
  exportSheets(filename, [{ name: sheetName, rows }])
}

/** 多 sheet 导出 */
export function exportSheets(filename, sheets) {
  const wb = XLSX.utils.book_new()
  for (const s of sheets) {
    const ws = XLSX.utils.json_to_sheet(s.rows && s.rows.length ? s.rows : [{ 提示: '无数据' }])
    XLSX.utils.book_append_sheet(wb, ws, s.name.slice(0, 31))
  }
  XLSX.writeFile(wb, filename)
}

/** 下载 Word（.doc，HTML 内容，Word 可直接打开编辑） */
export function downloadWord(filename, innerHtml) {
  const html = `<html xmlns:w="urn:schemas-microsoft-com:office:word"><head><meta charset="utf-8">
<style>body{font-family:'Microsoft YaHei',sans-serif;font-size:12pt;line-height:1.6}
h1{text-align:center;font-size:18pt}h2{font-size:14pt;border-bottom:1px solid #999;padding-bottom:4px}
table{border-collapse:collapse;width:100%}th,td{border:1px solid #666;padding:4px 8px}th{background:#eee}
</style></head><body>${innerHtml}</body></html>`
  const blob = new Blob(['\ufeff', html], { type: 'application/msword' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = filename
  a.click()
  URL.revokeObjectURL(a.href)
}
