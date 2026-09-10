<script setup lang="ts">
/**
 * 未实现模块占位页：展示「规划中」与对应 ADM 编号，禁止假装可用。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'

const route = useRoute()
const title = computed(() => String(route.meta.title ?? '未命名'))
const adm = computed(() => (route.meta.adm ? String(route.meta.adm) : '待排期'))
</script>

<template>
  <div class="page">
    <PageHeader
      :title="title"
      description="该模块已在信息架构中预留，功能按 admin-console-plan 分期交付。"
    />
    <el-card shadow="never">
      <el-empty :description="`规划中（${adm}）`">
        <template #description>
          <p class="empty-title">规划中（{{ adm }}）</p>
          <p class="empty-hint">请勿在此页进行生产操作；完成后将替换为真实功能页。</p>
        </template>
      </el-empty>
    </el-card>
  </div>
</template>

<style scoped>
.empty-title {
  margin: 0 0 6px;
  font-size: 14px;
  color: #374151;
}

.empty-hint {
  margin: 0;
  font-size: 12px;
  color: #9ca3af;
}
</style>
