<script setup>
import { ref } from 'vue'
import { getPaper, setPaper, PAPER_OPTIONS } from '../utils/print'

const val = ref(getPaper())
</script>

<template>
  <el-select v-model="val" size="small" style="width:140px" @change="setPaper">
    <el-option v-for="o in PAPER_OPTIONS" :key="o.value" :label="'纸张：' + o.label" :value="o.value" />
  </el-select>
</template>
