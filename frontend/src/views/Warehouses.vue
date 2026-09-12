<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'
import { ElMessage } from 'element-plus'

const list = ref([])
const dialog = ref(false)
const form = ref({})

async function load() { list.value = await api.warehouses() }
function add() {
  form.value = { name: '', remark: '' }
  dialog.value = true
}
function edit(row) { form.value = { ...row }; dialog.value = true }
async function save() {
  if (!form.value.name) { ElMessage.warning('仓库名称必填'); return }
  await api.saveWarehouse(form.value)
  dialog.value = false
  ElMessage.success('已保存')
  load()
}
onMounted(load)
</script>

<template>
  <el-button type="primary" style="margin-bottom:14px" @click="add">新增仓库</el-button>
  <el-table :data="list" border stripe>
    <el-table-column prop="id" label="ID" width="70" />
    <el-table-column prop="name" label="仓库名称" min-width="150" />
    <el-table-column prop="remark" label="备注" min-width="200" />
    <el-table-column prop="createdAt" label="创建时间" width="180" />
    <el-table-column label="操作" width="100">
      <template #default="{ row }">
        <el-button size="small" @click="edit(row)">编辑</el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-dialog v-model="dialog" :title="form.id ? '编辑仓库' : '新增仓库'" width="420px">
    <el-form :model="form" label-width="90px">
      <el-form-item label="仓库名称"><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>
