<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import UserManage from '../components/UserManage.vue'

const tab = ref('users')
const backups = ref([])
const upBackupRef = ref(null)
const logs = ref([])
const backing = ref(false)
const shop = ref({ shopName: '', phone: '', address: '' })
const shopSaving = ref(false)

async function loadShop() {
  try { shop.value = await api.getShopInfo() } catch (e) { /* 忽略 */ }
}
async function saveShop() {
  if (!shop.value.shopName?.trim()) { ElMessage.warning('店名不能为空'); return }
  shopSaving.value = true
  try {
    await api.saveShopInfo(shop.value)
    ElMessage.success('店铺信息已保存，打印抬头立即生效')
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '保存失败')
  }
  shopSaving.value = false
}

async function load() {
  if (tab.value === 'backups') backups.value = await api.backups()
  if (tab.value === 'logs') logs.value = await api.logs()
}
async function doBackup() {
  backing.value = true
  try {
    const r = await api.backupNow()
    ElMessage.success(r.message)
    backups.value = await api.backups()
  } finally { backing.value = false }
}
async function restoreBackup(row) {
  await ElMessageBox.confirm(
    `恢复到备份「${row.name}」？\n将覆盖当前全部经营数据（恢复前会自动备份当前数据）。\n确认后需重启账管卫士生效。`,
    '恢复数据', { type: 'warning' })
  try {
    const r = await api.restoreBackup(row.name)
    ElMessage({ type: 'success', message: r.message, duration: 8000 })
  } catch (e) { ElMessage.error(e.response?.data?.error || '恢复准备失败') }
}
async function downloadBackup(row) {
  try { await api.downloadBackup(row.name) } catch (e) { ElMessage.error('下载失败') }
}
async function uploadBackup(e) {
  const file = e.target.files[0]
  if (!file) return
  const fd = new FormData()
  fd.append('file', file)
  try {
    const r = await api.uploadBackup(fd)
    ElMessage.success(r.message)
    backups.value = await api.backups()
  } catch (e2) { ElMessage.error(e2.response?.data?.error || '上传失败') }
  e.target.value = ''
}
async function deleteBackup(row) {
  await ElMessageBox.confirm(`删除备份「${row.name}」？`, '确认', { type: 'warning' })
  try {
    await api.deleteBackup(row.name)
    ElMessage.success('已删除')
    backups.value = await api.backups()
  } catch (e) { ElMessage.error(e.response?.data?.error || '删除失败') }
}
function changeTab() {
  if (tab.value === 'shop') loadShop()
  load()
}
onMounted(() => { load(); loadShop() })
</script>

<template>
  <el-tabs v-model="tab" @tab-change="changeTab">
    <el-tab-pane label="店铺信息" name="shop" />
    <el-tab-pane label="操作员" name="users" />
    <el-tab-pane label="数据备份" name="backups" />
    <el-tab-pane label="操作日志" name="logs" />
  </el-tabs>

  <template v-if="tab === 'shop'">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom:14px"
      title="店名会作为抬头出现在所有打印的单据和对账单上；电话/地址选填" />
    <el-form :model="shop" label-width="80px" style="max-width:480px">
      <el-form-item label="店名"><el-input v-model="shop.shopName" placeholder="如：××消防器材经营部" /></el-form-item>
      <el-form-item label="电话"><el-input v-model="shop.phone" /></el-form-item>
      <el-form-item label="地址"><el-input v-model="shop.address" /></el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="shopSaving" @click="saveShop">保存</el-button>
      </el-form-item>
    </el-form>
  </template>

  <UserManage v-if="tab === 'users'" />

  <template v-if="tab === 'backups'">
    <el-alert type="info" :closable="false" show-icon style="margin-bottom:14px"
      title="每次启动和每晚23点自动备份，保留最近30份。恢复：点「恢复」并重启账管卫士即自动生效（重启前当前数据会再自动备份）。可下载备份异地保存，或上传别处导出的 .db 备份到本机恢复。" />
    <el-space style="margin-bottom:14px">
      <el-button type="primary" :loading="backing" @click="doBackup">立即备份</el-button>
      <el-button @click="upBackupRef?.click()">上传备份文件</el-button>
      <input ref="upBackupRef" type="file" accept=".db" style="display:none" @change="uploadBackup" />
    </el-space>
    <el-table :data="backups" border stripe>
      <el-table-column prop="name" label="备份文件" min-width="220" />
      <el-table-column prop="sizeKb" label="大小(KB)" width="110" />
      <el-table-column prop="modified" label="备份时间" width="180" />
      <el-table-column label="操作" width="210">
        <template #default="{ row }">
          <el-button size="small" type="primary" plain @click="restoreBackup(row)">恢复</el-button>
          <el-button size="small" @click="downloadBackup(row)">下载</el-button>
          <el-button size="small" type="danger" plain @click="deleteBackup(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </template>

  <template v-if="tab === 'logs'">
    <el-table :data="logs" border stripe max-height="600">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="操作员" width="110" />
      <el-table-column prop="action" label="操作" min-width="240" />
      <el-table-column prop="detail" label="参数" min-width="180" />
      <el-table-column prop="createdAt" label="时间" width="180" />
    </el-table>
  </template>
</template>

