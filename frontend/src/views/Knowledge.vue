<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const status = ref({})
const docs = ref([])
const dialog = ref(false)
const form = ref({ title: '', text: '' })
const testQuery = ref('')
const testHits = ref([])
const testing = ref(false)
const viewDialog = ref(false)
const viewDoc = ref(null)

async function load() {
  status.value = await api.kbStatus()
  docs.value = await api.kbDocuments()
}
async function view(row) {
  viewDoc.value = await api.kbDocumentDetail(row.id)
  viewDialog.value = true
}
function add() { form.value = { title: '', text: '' }; dialog.value = true }
async function save() {
  if (!form.value.title || !form.value.text) { ElMessage.warning('标题和内容必填'); return }
  const r = await api.kbAddDoc(form.value.title, form.value.text)
  if (r.error) { ElMessage.error(r.error); return }
  ElMessage.success(r.message)
  dialog.value = false
  load()
}
const TEXT_RE = /\.(txt|md|csv|json|log)$/i
const BIN_RE = /\.(docx|pdf|xlsx|xls)$/i
const uploading = ref(false)
async function onFile(e) {
  const file = e.target.files[0]
  if (!file) return
  if (BIN_RE.test(file.name)) {
    // Word/PDF/Excel：传给后端解析提取文字
    const fd = new FormData()
    fd.append('file', file)
    uploading.value = true
    try {
      const r = await api.kbUploadDoc(fd)
      if (r.error) { ElMessage.error(r.error); return }
      ElMessage.success(r.message)
      dialog.value = false
      load()
    } catch (err) { ElMessage.error(err.response?.data?.error || '上传失败') }
    uploading.value = false
    e.target.value = ''
    return
  }
  if (!TEXT_RE.test(file.name)) { ElMessage.warning('支持的格式：txt / md / csv / json / log / docx / pdf / xlsx / xls'); return }
  const reader = new FileReader()
  reader.onload = () => {
    form.value.text = String(reader.result)
    if (!form.value.title) form.value.title = file.name.replace(TEXT_RE, '')
  }
  reader.readAsText(file, 'utf-8')
}
function del(row) {
  ElMessageBox.confirm(`删除知识库文档《${row.title}》？`, '确认', { type: 'warning' })
    .then(() => api.kbDeleteDoc(row.id))
    .then(() => { ElMessage.success('已删除'); load() })
    .catch(() => {})
}
async function reindex() {
  const r = await api.kbReindex()
  ElMessage.success(r.message)
}
async function runTest() {
  if (!testQuery.value.trim()) return
  testing.value = true
  testHits.value = (await api.kbSearch(testQuery.value, 4)).chunks
  testing.value = false
}
onMounted(load)
</script>

<template>
  <el-alert :type="status.vectorMode ? 'success' : 'info'" :closable="false" show-icon style="margin-bottom:14px"
    :title="status.vectorMode
      ? `语义检索已启用（向量模型：${status.embeddingModel}），共 ${status.docCount} 文档 / ${status.chunkCount} 片段`
      : '当前为关键词检索模式。配置向量模型后可启用语义检索（application.yml 的 ai.embedding）'" />

  <el-space style="margin-bottom:14px">
    <el-button type="primary" @click="add">新增文档</el-button>
    <el-button v-if="status.vectorMode" @click="reindex">重建向量索引</el-button>
  </el-space>

  <el-table :data="docs" border stripe>
    <el-table-column prop="id" label="ID" width="60" />
    <el-table-column prop="title" label="文档标题" min-width="180" />
    <el-table-column label="来源" width="100">
      <template #default="{ row }">
        <el-tag size="small" :type="row.source === 'guide' ? 'success' : 'info'">
          {{ { guide: '内置指南', manual: '手工录入', file: '文件导入' }[row.source] || row.source }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column prop="chunks" label="切片数" width="90" />
    <el-table-column prop="createdAt" label="入库时间" width="180" />
    <el-table-column label="操作" width="140" fixed="right">
      <template #default="{ row }">
        <el-button size="small" type="primary" plain @click="view(row)">查看</el-button>
        <el-button size="small" type="danger" plain @click="del(row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-dialog v-model="viewDialog" :title="viewDoc ? `文档查看 - ${viewDoc.title}` : '文档查看'" width="720px" top="5vh">
    <template v-if="viewDoc">
      <el-descriptions :column="3" size="small" border style="margin-bottom:12px">
        <el-descriptions-item label="来源">{{ { guide: '内置指南', manual: '手工录入', file: '文件导入' }[viewDoc.source] || viewDoc.source }}</el-descriptions-item>
        <el-descriptions-item label="切片数">{{ viewDoc.chunks.length }}</el-descriptions-item>
        <el-descriptions-item label="入库时间">{{ viewDoc.createdAt }}</el-descriptions-item>
      </el-descriptions>
      <div v-for="c in viewDoc.chunks" :key="c.index" class="chunk-item">
        <div style="display:flex;justify-content:space-between;margin-bottom:4px">
          <span style="font-size:12px;color:#909399">片段 #{{ c.index }}（{{ c.length }} 字）</span>
          <el-tag size="small" :type="c.vectorized ? 'success' : 'info'">{{ c.vectorized ? '已向量化' : '未向量化(走关键词检索)' }}</el-tag>
        </div>
        <div class="chunk-content">{{ c.content }}</div>
      </div>
    </template>
    <template #footer>
      <el-button @click="viewDialog = false">关闭</el-button>
    </template>
  </el-dialog>

  <el-card shadow="hover" style="margin-top:16px">
    <template #header><b>检索测试</b>（看看问题会命中哪些片段）</template>
    <el-space>
      <el-input v-model="testQuery" placeholder="例如：怎么冲正？/ 如何核销挂账？" style="width:400px" @keyup.enter="runTest" />
      <el-button type="primary" :loading="testing" @click="runTest">检索</el-button>
    </el-space>
    <div v-for="(h, i) in testHits" :key="i" style="margin-top:12px">
      <div style="font-size:12px;color:#909399">《{{ h.docTitle }}》 相关度: {{ h.score }}</div>
      <div style="font-size:13px;background:#f5f7fa;padding:8px;border-radius:6px;margin-top:4px">{{ h.content }}</div>
    </div>
    <el-empty v-if="!testHits.length && testQuery" description="没有命中，换个说法试试" :image-size="60" />
  </el-card>

  <el-dialog v-model="dialog" title="新增知识库文档" width="640px">
    <el-form :model="form" label-width="70px">
      <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
      <el-form-item label="内容">
        <el-input v-model="form.text" type="textarea" :rows="14" placeholder="粘贴操作手册、商品说明、常见问题等，长文本会自动切块" />
      </el-form-item>
      <el-form-item label="导入">
        <input type="file" accept=".txt,.md,.csv,.json,.log,.docx,.pdf,.xlsx,.xls" @change="onFile" />
        <div style="font-size:12px;color:#909399;margin-top:2px">
          支持文本类（txt/md/csv/json/log）与 Word（.docx）/ PDF / Excel（.xlsx/.xls，自动提取文字入库）
          <span v-if="uploading">｜ 解析上传中…</span>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" @click="save">入库</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.chunk-item { margin-bottom: 14px; }
.chunk-content {
  font-size: 13px;
  background: #f5f7fa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
  white-space: pre-wrap;
  line-height: 1.7;
  max-height: 260px;
  overflow-y: auto;
}
</style>
