<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const users = ref([])
const dialog = ref(false)
const form = ref({ username: '', password: '', role: 'OPERATOR' })

async function load() { users.value = await api.users() }
function add() { form.value = { username: '', password: '', role: 'OPERATOR' }; dialog.value = true }
async function save() {
  try {
    const r = await api.createUser(form.value)
    ElMessage.success(r.message)
    dialog.value = false
    load()
  } catch (e) { ElMessage.error(e.response?.data?.error || '创建失败') }
}
async function resetPwd(row) {
  const { value } = await ElMessageBox.prompt(`为「${row.username}」设置新密码（至少6位）`, '重置密码', { inputType: 'password' })
  const r = await api.resetPassword(row.id, value)
  ElMessage.success(r.message)
}
async function toggle(row) {
  const r = await api.toggleUser(row.id)
  ElMessage.success(r.message)
  load()
}
async function del(row) {
  await ElMessageBox.confirm(`删除用户「${row.username}」？`, '确认', { type: 'warning' })
  const r = await api.deleteUser(row.id)
  ElMessage.success(r.message)
  load()
}
onMounted(load)
</script>

<template>
  <el-button type="primary" style="margin-bottom:14px" @click="add">新增操作员</el-button>
  <el-table :data="users" border stripe>
    <el-table-column prop="id" label="ID" width="60" />
    <el-table-column prop="username" label="用户名" width="140" />
    <el-table-column label="角色" width="120">
      <template #default="{ row }">
        <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'" size="small">{{ row.role === 'ADMIN' ? '管理员' : '操作员' }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="100">
      <template #default="{ row }">
        <el-tag :type="row.enabled === 1 ? 'success' : 'warning'" size="small">{{ row.enabled === 1 ? '启用' : '停用' }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column prop="createdAt" label="创建时间" width="180" />
    <el-table-column label="操作" min-width="240">
      <template #default="{ row }">
        <el-button size="small" @click="resetPwd(row)">重置密码</el-button>
        <el-button v-if="row.username !== 'admin'" size="small" :type="row.enabled === 1 ? 'warning' : 'success'" plain @click="toggle(row)">
          {{ row.enabled === 1 ? '停用' : '启用' }}
        </el-button>
        <el-button v-if="row.username !== 'admin'" size="small" type="danger" plain @click="del(row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>

  <!-- 新增操作员弹窗：form 里没有 id 时走 POST /api/users 创建 -->
  <el-dialog v-model="dialog" title="新增操作员" width="420px">
    <el-form :model="form" label-width="80px">
      <el-form-item label="用户名" required>
        <el-input v-model="form.username" autocomplete="off" />
      </el-form-item>
      <el-form-item label="密码" required>
        <el-input v-model="form.password" type="password" show-password
                  autocomplete="off" placeholder="至少 6 位" />
      </el-form-item>
      <el-form-item label="角色">
        <el-select v-model="form.role" style="width:100%">
          <el-option label="操作员" value="OPERATOR" />
          <el-option label="管理员" value="ADMIN" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" @click="save">确定</el-button>
    </template>
  </el-dialog>
</template>
