<script setup>
// 分类式选商品：一级/二级/三级分类下拉逐级筛选 + 商品名/条码直接搜索，
// 下方列表点一下即选中。触发按钮显示已选商品，支持清空。
import { ref, computed, watch } from 'vue'
import { api } from '../api'

const props = defineProps({
  modelValue: { type: [Number, String], default: null },
  products: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue', 'select'])

const visible = ref(false)
const sel = ref({ l1: '', l2: '', l3: '' })
const keyword = ref('')
const catDict = ref({ categories: [], subOf: {}, sub2Of: {} })

watch(visible, v => { if (v) api.productCategories().then(d => catDict.value = d).catch(() => {}) })

const l2Options = computed(() => catDict.value.subOf[sel.value.l1] || [])
const l3Options = computed(() => catDict.value.sub2Of[`${sel.value.l1}/${sel.value.l2}`] || [])
function setL1(v) { sel.value = { l1: v || '', l2: '', l3: '' } }
function setL2(v) { sel.value = { l1: sel.value.l1, l2: v || '', l3: '' } }

function inScope(p) {
  if (sel.value.l1 && p.category !== sel.value.l1) return false
  if (sel.value.l2 && (p.subCategory || '') !== sel.value.l2) return false
  if (sel.value.l3 && (p.sub2Category || '') !== sel.value.l3) return false
  return true
}
const matched = computed(() => {
  const k = keyword.value.trim()
  return props.products.filter(p =>
    inScope(p) && (!k || (p.name || '').includes(k) || (p.sku || '').includes(k) || (p.barcode || '').includes(k)))
})
function choose(p) {
  emit('update:modelValue', p.id)
  emit('select', p.id)
  visible.value = false
}
function clearSel() {
  emit('update:modelValue', null)
  emit('select', null)
  sel.value = { l1: '', l2: '', l3: '' }
  keyword.value = ''
}
const selected = computed(() => props.products.find(p => p.id === props.modelValue))
</script>

<template>
  <el-popover v-model:visible="visible" width="460" trigger="click" placement="bottom-start">
    <template #reference>
      <el-button style="width:200px;justify-content:flex-start;overflow:hidden">
        <span v-if="selected" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap">
          {{ selected.name }}（库存{{ selected.stock }}{{ selected.unit || '' }}）</span>
        <span v-else style="color:#a8abb2">选择商品（按分类或名称）</span>
      </el-button>
    </template>

    <el-space wrap style="margin-bottom:8px">
      <!-- teleported=false：下拉选项渲染在弹层内部，点选项不会把整个弹层关掉 -->
      <el-select :model-value="sel.l1" @update:model-value="setL1" clearable placeholder="一级分类"
        filterable :teleported="false" style="width:130px" size="small">
        <el-option v-for="c in catDict.categories" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select :model-value="sel.l2" @update:model-value="setL2" clearable placeholder="二级分类"
        filterable :teleported="false" :disabled="!sel.l1" style="width:130px" size="small">
        <el-option v-for="c in l2Options" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select :model-value="sel.l3" @update:model-value="v => sel.l3 = v || ''" clearable placeholder="三级分类"
        filterable :teleported="false" :disabled="!sel.l2" style="width:130px" size="small">
        <el-option v-for="c in l3Options" :key="c" :label="c" :value="c" />
      </el-select>
    </el-space>
    <el-input v-model="keyword" placeholder="直接输商品名 / SKU / 条码" size="small" clearable style="margin-bottom:8px" />

    <div style="max-height:280px;overflow-y:auto">
      <div v-for="p in matched.slice(0, 60)" :key="p.id" @click="choose(p)"
        style="display:flex;justify-content:space-between;align-items:center;padding:7px 8px;border-radius:6px;cursor:pointer"
        class="pick-item">
        <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1">
          {{ p.name }}<span v-if="p.spec" style="color:#909399">（{{ p.spec }}）</span></span>
        <span style="font-size:12px;color:#909399;margin-left:8px;white-space:nowrap">
          库存{{ p.stock }}{{ p.unit || '' }} ｜ ￥{{ p.salePrice }}</span>
      </div>
      <div v-if="!matched.length" style="text-align:center;color:#909399;font-size:12px;padding:14px 0">
        没有匹配的商品（试试清空分类或换个关键词）
      </div>
    </div>
  </el-popover>
</template>

<style scoped>
.pick-item:hover { background: #ecf5ff; }
</style>
