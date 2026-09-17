<template>
  <div class="space-y-6">
    <!-- 说明 -->
    <div class="bg-white rounded-lg shadow-sm p-6">
      <div class="flex items-center justify-between mb-2">
        <h2 class="text-lg font-semibold text-gray-800">优化落地批次会签</h2>
        <span class="text-xs text-gray-500">现场经理口径：两步会签都点头，场景才允许切成推演后方案</span>
      </div>
      <p class="text-sm text-gray-500">
        每跑一轮优化推演，系统就把这一轮冻成一份落地批次：钉死当时每块区域的核定容量、编制配额、
        优化前分配和优化后分配。测算岗确认数字 → 现场经理签收，两步都完成后，场景当前分配才会在同一笔事务里
        切换成批次的优化后方案；确认之后、签收之前，场景仍是旧方案。确认后又跑了新一轮推演的，
        旧批次确认自动作废，不能再拿去改场景。
      </p>
    </div>

    <!-- 批次台账 -->
    <div class="bg-white rounded-lg shadow-sm p-6">
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-lg font-semibold text-gray-800">落地批次台账</h3>
        <div class="flex items-center gap-3">
          <el-select v-model="filterScenarioId" placeholder="全部场景" clearable class="w-56" @change="loadBatches">
            <el-option v-for="s in scenarios" :key="s.id" :label="s.scenarioName" :value="s.id" />
          </el-select>
          <button class="px-3 py-1.5 text-sm border rounded-lg hover:bg-gray-50" @click="reloadAll">刷新</button>
        </div>
      </div>

      <div v-if="filterScenarioId && currentScenario" class="mb-4 rounded-lg border border-blue-200 bg-blue-50 px-4 py-2.5 text-sm text-blue-800">
        场景「{{ currentScenario.scenarioName }}」当前已跑到第 <b>{{ currentScenario.currentRunSeq ?? 0 }}</b> 轮推演；
        当前分配：{{ currentScenario.appliedRunSeq
          ? `第 ${currentScenario.appliedRunSeq} 轮推演方案（批次会签落地）`
          : '手工分配方案（尚无批次落地）' }}
      </div>

      <div v-if="batches.length === 0" class="text-center py-10 text-gray-500">
        暂无落地批次，请先在「优化推演」页执行一轮推演
      </div>

      <div class="space-y-4">
        <div v-for="b in batches" :key="b.id" class="border rounded-lg overflow-hidden" :class="cardClass(b.status)">
          <!-- 批次头 -->
          <div class="flex flex-wrap items-center justify-between gap-3 px-4 py-3 bg-gray-50">
            <div class="flex items-center gap-3">
              <span class="text-xs text-gray-400">#{{ b.id }}</span>
              <span class="font-medium text-gray-800">{{ b.scenarioName }}</span>
              <span class="text-sm text-gray-600">第 {{ b.runSeq }} 轮推演</span>
              <el-tag :type="tagType(b.status)" effect="dark" size="small">{{ b.statusText }}</el-tag>
            </div>
            <div class="text-xs text-gray-500">{{ formatDate(b.createdAt) }}</div>
          </div>

          <div class="p-4 space-y-3">
            <!-- 作废原因 -->
            <div v-if="b.status === 'STALE'" class="rounded-lg border border-orange-300 bg-orange-50 p-3 text-sm text-orange-800">
              已作废：{{ b.staleReason || '被新一轮推演取代' }}
              <span v-if="b.scenarioCurrentRunSeq != null">（场景当前第 {{ b.scenarioCurrentRunSeq }} 轮）</span>
            </div>

            <!-- 效果总览 -->
            <div class="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm">
              <div class="rounded bg-gray-50 p-2">优化前最大饱和度<br>
                <b>{{ b.beforeMaxSaturation != null ? b.beforeMaxSaturation.toFixed(1) + '%' : '-' }}</b></div>
              <div class="rounded bg-gray-50 p-2">优化后最大饱和度<br>
                <b class="text-green-700">{{ b.afterMaxSaturation != null ? b.afterMaxSaturation.toFixed(1) + '%' : '-' }}</b></div>
              <div class="rounded bg-gray-50 p-2">优化前过载区域<br><b>{{ b.beforeOverloadedCount ?? '-' }}</b></div>
              <div class="rounded bg-gray-50 p-2">优化后过载区域<br><b>{{ b.afterOverloadedCount ?? '-' }}</b></div>
            </div>

            <!-- 冻结快照 -->
            <div>
              <div class="text-sm font-medium text-gray-700 mb-2">冻结档案（推演当时，后续改动不回写）</div>
              <div class="overflow-x-auto">
                <table class="w-full text-sm">
                  <thead>
                    <tr class="bg-gray-50 text-gray-600">
                      <th class="px-3 py-2 text-left">区域</th>
                      <th class="px-3 py-2 text-right">核定容量(冻)</th>
                      <th class="px-3 py-2 text-right">编制配额(冻)</th>
                      <th class="px-3 py-2 text-right">优化前 人员/客流</th>
                      <th class="px-3 py-2 text-right">优化后 人员/客流</th>
                      <th class="px-3 py-2 text-right">前饱和度</th>
                      <th class="px-3 py-2 text-right">后饱和度</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="item in b.items" :key="item.areaId" class="border-b">
                      <td class="px-3 py-2">{{ item.areaName }}</td>
                      <td class="px-3 py-2 text-right">{{ item.maxCapacity }}</td>
                      <td class="px-3 py-2 text-right">{{ item.staffQuota }}</td>
                      <td class="px-3 py-2 text-right text-gray-500">{{ item.beforeStaff }} / {{ item.beforeFlow }}</td>
                      <td class="px-3 py-2 text-right font-medium">{{ item.afterStaff }} / {{ item.afterFlow }}</td>
                      <td class="px-3 py-2 text-right" :class="saturationClass(item.beforeSaturation)">
                        {{ item.beforeSaturation?.toFixed(1) }}%
                      </td>
                      <td class="px-3 py-2 text-right" :class="saturationClass(item.afterSaturation)">
                        {{ item.afterSaturation?.toFixed(1) }}%
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

            <!-- 会签留痕 -->
            <div class="grid grid-cols-1 md:grid-cols-3 gap-3 text-sm">
              <div class="rounded border p-2.5" :class="b.confirmedAt ? 'border-green-200 bg-green-50' : 'border-gray-200 bg-gray-50'">
                <div class="text-xs text-gray-500 mb-1">① 测算岗确认</div>
                <div v-if="b.confirmedAt" class="text-green-800">
                  {{ b.confirmedBy }}<br><span class="text-xs">{{ formatDate(b.confirmedAt) }}</span>
                </div>
                <div v-else class="text-gray-400">未确认</div>
              </div>
              <div class="rounded border p-2.5" :class="b.signedAt ? 'border-green-200 bg-green-50' : 'border-gray-200 bg-gray-50'">
                <div class="text-xs text-gray-500 mb-1">② 现场经理签收</div>
                <div v-if="b.signedAt" class="text-green-800">
                  {{ b.signedBy }}<br><span class="text-xs">{{ formatDate(b.signedAt) }}</span>
                </div>
                <div v-else class="text-gray-400">未签收</div>
              </div>
              <div class="rounded border p-2.5" :class="b.appliedAt ? 'border-blue-200 bg-blue-50' : 'border-gray-200 bg-gray-50'">
                <div class="text-xs text-gray-500 mb-1">③ 场景切换落地</div>
                <div v-if="b.appliedAt" class="text-blue-800">
                  已切成第 {{ b.runSeq }} 轮优化后方案<br><span class="text-xs">{{ formatDate(b.appliedAt) }}</span>
                </div>
                <div v-else class="text-gray-400">未落地（场景仍是旧方案）</div>
              </div>
            </div>

            <!-- 会签操作 -->
            <div class="flex items-center gap-2 pt-1">
              <button v-if="b.status === 'PENDING_CONFIRM'"
                      class="px-3 py-1.5 text-sm rounded-lg bg-blue-500 text-white hover:bg-blue-600"
                      @click="confirmBatch(b)">测算岗确认数字</button>
              <button v-if="b.status === 'CONFIRMED'"
                      class="px-3 py-1.5 text-sm rounded-lg bg-green-500 text-white hover:bg-green-600"
                      @click="signBatch(b)">现场经理签收并落地</button>
              <span v-if="b.status === 'CONFIRMED'" class="text-xs text-gray-500">
                签收后场景当前分配将立即切换为本批次优化后方案（一笔事务完成）
              </span>
              <span v-if="b.status === 'PENDING_CONFIRM'" class="text-xs text-gray-500">
                确认只冻结会签进度，场景分配仍是旧方案
              </span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  scenarioApi, batchApi,
  type FlowScenario, type OptimizationBatchDTO, type BatchStatus
} from '@/api'

const scenarios = ref<FlowScenario[]>([])
const batches = ref<OptimizationBatchDTO[]>([])
const filterScenarioId = ref<number | undefined>(undefined)

const currentScenario = computed(() =>
  scenarios.value.find(s => s.id === filterScenarioId.value) || null
)

const loadScenarios = async () => { scenarios.value = await scenarioApi.getAll() }
const loadBatches = async () => { batches.value = await batchApi.list(filterScenarioId.value) }
const reloadAll = async () => { await Promise.all([loadScenarios(), loadBatches()]) }

const showConflict = (e: unknown) => {
  const data = (e as { response?: { data?: { message?: string; code?: string } } })?.response?.data
  ElMessageBox.alert(
    data?.message || '操作失败',
    `会签被拒绝${data?.code ? '（' + data.code + '）' : ''}`,
    { confirmButtonText: '我知道了', type: 'error' }
  )
}

const confirmBatch = async (b: OptimizationBatchDTO) => {
  let operator = '测算岗'
  try {
    const { value } = await ElMessageBox.prompt(
      `确认批次 #${b.id}（第 ${b.runSeq} 轮推演）冻结的数字无误。确认后场景分配仍保持旧方案，待现场经理签收才切换。`,
      '测算岗确认',
      { confirmButtonText: '确认', cancelButtonText: '取消', inputValue: operator, inputPlaceholder: '确认人' }
    )
    operator = value || operator
  } catch {
    return
  }
  try {
    await batchApi.confirm(b.id, operator)
    ElMessage.success('测算岗已确认，待现场经理签收（场景仍是旧方案）')
  } catch (e) {
    showConflict(e)
  } finally {
    await reloadAll()
  }
}

const signBatch = async (b: OptimizationBatchDTO) => {
  try {
    await ElMessageBox.confirm(
      `签收批次 #${b.id}（第 ${b.runSeq} 轮推演）后，场景当前分配将立即切换为本批次的优化后方案，与签收同一笔事务完成。确认签收？`,
      '现场经理签收',
      { confirmButtonText: '签收并落地', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  let operator = '现场经理'
  try {
    const { value } = await ElMessageBox.prompt('签收人：', '现场经理签收', {
      confirmButtonText: '确定', cancelButtonText: '取消', inputValue: operator
    })
    operator = value || operator
  } catch {
    return
  }
  try {
    await batchApi.sign(b.id, operator)
    ElMessage.success('会签完成，场景已切换到本批次优化后方案')
  } catch (e) {
    showConflict(e)
  } finally {
    await reloadAll()
  }
}

const tagType = (s: BatchStatus) => {
  switch (s) {
    case 'PENDING_CONFIRM': return 'warning'
    case 'CONFIRMED': return 'primary'
    case 'APPLIED': return 'success'
    case 'STALE': return 'info'
    default: return 'info'
  }
}

const cardClass = (s: BatchStatus) => {
  switch (s) {
    case 'PENDING_CONFIRM': return 'border-yellow-300'
    case 'CONFIRMED': return 'border-blue-300'
    case 'APPLIED': return 'border-green-300'
    case 'STALE': return 'border-gray-300 opacity-90'
    default: return 'border-gray-200'
  }
}

const saturationClass = (rate?: number) => {
  const r = rate || 0
  if (r >= 100) return 'text-red-600 font-medium'
  if (r >= 85) return 'text-orange-600'
  return 'text-green-600'
}

const formatDate = (d?: string) => d ? new Date(d).toLocaleString('zh-CN') : ''

onMounted(reloadAll)
</script>
