<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'

const active = ref('SUPPLIER')
const list = ref([])
const dialog = ref(false)
const form = ref({})

async function load() {
  list.value = await api.partners(active.value)
}
function add() {
  form.value = { type: active.value, name: '', contact: '', phone: '', address: '', remark: '', openingReceivable: 0, openingPayable: 0 }
  dialog.value = true
}
function edit(row) {
  form.value = { ...row }
  dialog.value = true
}
async function save() {
  if (!form.value.name) { ElMessage.warning('名称必填'); return }
  await api.savePartner(form.value)
  dialog.value = false
  ElMessage.success('已保存')
  load()
}
function del(row) {
  ElMessageBox.confirm(`删除「${row.name}」？`, '确认', { type: 'warning' })
    .then(() => api.deletePartner(row.id))
    .then(() => { ElMessage.success('已删除'); load() })
    .catch(() => {})
}
function switchTab() { load() }
onMounted(load)
</script>

<template>
  <el-tabs v-model="active" @tab-change="switchTab">
    <el-tab-pane label="供应商" name="SUPPLIER" />
    <el-tab-pane label="客户" name="CUSTOMER" />
  </el-tabs>

  <el-button type="primary" style="margin-bottom:14px" @click="add">
    新增{{ active === 'SUPPLIER' ? '供应商' : '客户' }}
  </el-button>

  <el-table :data="list" border stripe>
    <el-table-column prop="name" label="名称" min-width="150" />
    <el-table-column prop="contact" label="联系人" width="110" />
    <el-table-column prop="phone" label="电话" width="130" />
    <el-table-column prop="address" label="地址" min-width="160" />
    <el-table-column label="期初应收" width="90">
      <template #default="{ row }">{{ row.openingReceivable ? '￥' + row.openingReceivable : '-' }}</template>
    </el-table-column>
    <el-table-column label="期初应付" width="90">
      <template #default="{ row }">{{ row.openingPayable ? '￥' + row.openingPayable : '-' }}</template>
    </el-table-column>
    <el-table-column prop="remark" label="备注" min-width="110" />
    <el-table-column label="操作" width="140" fixed="right">
      <template #default="{ row }">
        <el-button size="small" @click="edit(row)">编辑</el-button>
        <el-button size="small" type="danger" plain @click="del(row)">删除</el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-dialog v-model="dialog" :title="form.id ? '编辑' : '新增'" width="480px">
    <el-form :model="form" label-width="80px">
      <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="联系人"><el-input v-model="form.contact" /></el-form-item>
      <el-form-item label="电话"><el-input v-model="form.phone" /></el-form-item>
      <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
      <el-form-item v-if="form.type === 'CUSTOMER'" label="期初应收">
        <el-input-number v-model="form.openingReceivable" :min="0" :precision="2" style="width:100%" />
      </el-form-item>
      <el-form-item v-if="form.type === 'SUPPLIER'" label="期初应付">
        <el-input-number v-model="form.openingPayable" :min="0" :precision="2" style="width:100%" />
      </el-form-item>
      <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
