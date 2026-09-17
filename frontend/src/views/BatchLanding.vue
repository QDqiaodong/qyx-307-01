<template>
  <div class="space-y-6">
    <!-- 口径说明 -->
    <div class="bg-white rounded-lg shadow-sm p-6">
      <div class="flex items-center justify-between mb-2">
        <h2 class="text-lg font-semibold text-gray-800">推演落地批次 · 会签</h2>
        <span class="text-xs text-gray-500">现场经理口径：两步都点头，场景才允许改方案</span>
      </div>
      <p class="text-sm text-gray-500">
        每执行一轮优化推演都会冻成一份批次，冻住当时每块区域的核定容量、编制配额、优化前与优化后分配。
        <b>测算岗确认数字</b> 之后、<b>现场经理签收</b> 之前，场景人员/客流分配仍保持旧方案；
        只有签收完成的同一笔事务才把场景分配改成冻住的优化后方案。若测算确认后又跑了新一轮推演，
        旧批次确认作废，不能再拿它改场景。
      </p>

      <div class="mt-4 flex items-center gap-3">
        <el-select v-model="filterScenarioId" placeholder="全部场景" clearable filterable class="w-72"
                   @change="loadBatches">
          <el-option v-for="s in scenarios" :key="s.id"
                     :label="`${s.scenarioName}（${s.festivalName}）`" :value="s.id" />
        </el-select>
        <button class="px-3 py-1.5 text-sm border rounded-lg hover:bg-gray-50" @click="reload">刷新</button>
      </div>
    </div>

    <div v-if="loading" class="text-center py-10 text-gray-500">加载批次中…</div>
    <div v-else-if="batches.length === 0" class="bg-white rounded-lg shadow-sm p-10 text-center text-gray-500">
      暂无落地批次，请到「优化推演」页执行一轮推演，系统会自动冻出批次。
    </div>

    <div v-else class="space-y-5">
      <div v-for="b in batches" :key="b.id" class="bg-white rounded-lg shadow-sm overflow-hidden"
           :class="cardClass(b)">
        <!-- 批次头 -->
        <div class="flex flex-wrap items-center justify-between gap-3 px-5 py-3 bg-gray-50 border-b">
          <div class="flex items-center gap-3">
            <span class="text-xs text-gray-400">#{{ b.id }}</span>
            <span class="font-medium text-gray-800">{{ b.scenarioName }}</span>
            <el-tag :type="tagType(b)" effect="dark" size="small">{{ b.statusText }}</el-tag>
            <el-tag v-if="!b.current && b.status !== 'LANDED'" type="danger" effect="plain" size="small">
              冻的是第{{ b.optimizationRound }}轮 · 场景已是第{{ b.currentRound }}轮
            </el-tag>
            <el-tag v-else type="info" effect="plain" size="small">冻结第{{ b.optimizationRound }}轮推演</el-tag>
          </div>
          <div class="text-xs text-gray-500">{{ formatDate(b.createdAt) }}</div>
        </div>

        <div class="p-5 space-y-4">
          <!-- 作废失败回执 -->
          <el-alert v-if="b.status === 'SUPERSEDED' || (b.live === false && b.status !== 'LANDED')"
                    type="error" :closable="false" show-icon>
            <template #title>
              <span class="font-medium">该批次不能再落地</span>
            </template>
            <div class="text-sm mt-1">{{ b.failure?.message || b.supersedeReason }}</div>
            <div class="text-xs text-gray-600 mt-1">
              批次 #{{ b.id }} · 冻结第{{ b.optimizationRound }}轮 · 场景当前第{{ b.currentRound }}轮
            </div>
          </el-alert>

          <!-- 推演概览 -->
          <div class="grid grid-cols-2 md:grid-cols-6 gap-3 text-sm">
            <div class="rounded-lg bg-gray-50 p-3">
              <div class="text-xs text-gray-500">优化前最大饱和度</div>
              <div class="text-lg font-bold text-orange-600">{{ fmt(b.beforeMaxSaturation) }}%</div>
            </div>
            <div class="rounded-lg bg-gray-50 p-3">
              <div class="text-xs text-gray-500">优化后最大饱和度</div>
              <div class="text-lg font-bold text-green-600">{{ fmt(b.afterMaxSaturation) }}%</div>
            </div>
            <div class="rounded-lg bg-gray-50 p-3">
              <div class="text-xs text-gray-500">优化前过载数</div>
              <div class="text-lg font-bold text-red-600">{{ b.beforeOverloadedCount }}</div>
            </div>
            <div class="rounded-lg bg-gray-50 p-3">
              <div class="text-xs text-gray-500">优化后过载数</div>
              <div class="text-lg font-bold" :class="b.afterOverloadedCount > 0 ? 'text-orange-600' : 'text-green-600'">
                {{ b.afterOverloadedCount }}
              </div>
            </div>
            <div class="rounded-lg bg-gray-50 p-3">
              <div class="text-xs text-gray-500">人员总量 前→后</div>
              <div class="text-sm font-semibold mt-1">{{ b.beforeTotalStaff }} → {{ b.afterTotalStaff }}</div>
            </div>
            <div class="rounded-lg bg-gray-50 p-3">
              <div class="text-xs text-gray-500">客流总量 前→后</div>
              <div class="text-sm font-semibold mt-1">{{ b.beforeTotalFlow }} → {{ b.afterTotalFlow }}</div>
            </div>
          </div>

          <!-- 冻结档案表 -->
          <div class="overflow-x-auto">
            <table class="w-full text-sm">
              <thead>
                <tr class="bg-gray-50 text-gray-600">
                  <th class="px-3 py-2 text-left">区域</th>
                  <th class="px-3 py-2 text-right">核定容量</th>
                  <th class="px-3 py-2 text-right">编制配额</th>
                  <th class="px-3 py-2 text-right">优化前人员</th>
                  <th class="px-3 py-2 text-right">优化前客流</th>
                  <th class="px-3 py-2 text-right">优化前饱和度</th>
                  <th class="px-3 py-2 text-right">优化后人员</th>
                  <th class="px-3 py-2 text-right">优化后客流</th>
                  <th class="px-3 py-2 text-right">优化后饱和度</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="i in b.items" :key="i.areaId" class="border-b">
                  <td class="px-3 py-2">{{ i.areaName }}</td>
                  <td class="px-3 py-2 text-right">{{ i.frozenMaxCapacity }}</td>
                  <td class="px-3 py-2 text-right">{{ i.frozenStaffQuota }}</td>
                  <td class="px-3 py-2 text-right">{{ i.beforeStaff }}</td>
                  <td class="px-3 py-2 text-right">{{ i.beforeFlow }}</td>
                  <td class="px-3 py-2 text-right">
                    <span :class="i.beforeOverloaded ? 'text-red-600 font-medium' : 'text-gray-600'">
                      {{ fmt(i.beforeSaturationRate) }}%
                    </span>
                  </td>
                  <td class="px-3 py-2 text-right font-medium">{{ i.afterStaff }}</td>
                  <td class="px-3 py-2 text-right font-medium">{{ i.afterFlow }}</td>
                  <td class="px-3 py-2 text-right">
                    <span :class="i.afterOverloaded ? 'text-orange-600 font-medium' : 'text-green-600 font-medium'">
                      {{ fmt(i.afterSaturationRate) }}%
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- 会签闸门 -->
          <div class="rounded-lg border p-4" :class="b.status === 'LANDED' ? 'border-green-200 bg-green-50' : 'border-gray-200 bg-gray-50'">
            <div class="flex flex-wrap items-center justify-between gap-4">
              <div class="flex items-center gap-6">
                <!-- 第一步 -->
                <div class="flex items-center gap-2">
                  <span class="w-6 h-6 rounded-full flex items-center justify-center text-xs text-white"
                        :class="b.analystConfirmedAt ? 'bg-green-500' : 'bg-gray-300'">1</span>
                  <div>
                    <div class="text-sm font-medium text-gray-800">测算岗确认数字</div>
                    <div class="text-xs text-gray-500">
                      {{ b.analystConfirmedAt ? '已确认 ' + formatDate(b.analystConfirmedAt) : '待确认（只确认，不改场景）' }}
                    </div>
                  </div>
                </div>
                <div class="text-gray-300">→</div>
                <!-- 第二步 -->
                <div class="flex items-center gap-2">
                  <span class="w-6 h-6 rounded-full flex items-center justify-center text-xs text-white"
                        :class="b.landedAt ? 'bg-green-500' : (b.canManagerSign ? 'bg-blue-500' : 'bg-gray-300')">2</span>
                  <div>
                    <div class="text-sm font-medium text-gray-800">现场经理签收落地</div>
                    <div class="text-xs text-gray-500">
                      <template v-if="b.landedAt">已签收 {{ formatDate(b.landedAt) }} · 场景已改为优化后方案</template>
                      <template v-else-if="b.status === 'CONFIRMED'">待签收（签收才改场景分配）</template>
                      <template v-else>需测算岗先确认</template>
                    </div>
                  </div>
                </div>
              </div>

              <div class="flex items-center gap-2">
                <button v-if="b.canAnalystConfirm"
                        :disabled="busy === b.id"
                        class="px-4 py-2 text-sm rounded-lg text-white bg-blue-500 hover:bg-blue-600 disabled:bg-gray-300"
                        @click="confirmBatch(b)">
                  {{ busy === b.id ? '提交中…' : '测算岗确认' }}
                </button>
                <button v-if="b.canManagerSign"
                        :disabled="busy === b.id"
                        class="px-4 py-2 text-sm rounded-lg text-white bg-green-600 hover:bg-green-700 disabled:bg-gray-300"
                        @click="signBatch(b)">
                  {{ busy === b.id ? '签收落地中…' : '现场经理签收并落地' }}
                </button>
                <el-tag v-if="b.status === 'LANDED'" type="success" effect="dark" size="default">已会签落地</el-tag>
                <el-tag v-if="b.status === 'SUPERSEDED'" type="danger" effect="plain" size="default">确认已作废</el-tag>
              </div>
            </div>
            <div v-if="b.status === 'CONFIRMED'" class="mt-3 text-xs text-blue-700">
              数字已确认但场景尚未改动——当前场景人员/客流仍是优化前旧方案，等待现场经理签收。
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  batchApi, scenarioApi,
  type FlowScenario, type OptimizationBatchDTO, type OptimizationBatchStatus
} from '@/api'

const route = useRoute()
const scenarios = ref<FlowScenario[]>([])
const batches = ref<OptimizationBatchDTO[]>([])
const filterScenarioId = ref<number | null>(null)
const loading = ref(false)
const busy = ref<number | null>(null)

const fmt = (v?: number | null) => (v === null || v === undefined ? '-' : Number(v).toFixed(1))
const formatDate = (d?: string) => (d ? new Date(d).toLocaleString('zh-CN') : '-')

const tagType = (b: OptimizationBatchDTO): 'success' | 'warning' | 'info' | 'danger' | 'primary' => {
  const map: Record<OptimizationBatchStatus, 'success' | 'warning' | 'info' | 'danger' | 'primary'> = {
    DRAFT: 'info',
    CONFIRMED: 'warning',
    SUPERSEDED: 'danger',
    LANDED: 'success'
  }
  return map[b.status]
}

const cardClass = (b: OptimizationBatchDTO) => {
  if (b.status === 'LANDED') return 'border border-green-200'
  if (b.status === 'SUPERSEDED') return 'border border-red-200 opacity-95'
  return 'border border-gray-200'
}

const loadScenarios = async () => {
  try {
    scenarios.value = await scenarioApi.getAll()
  } catch {
    scenarios.value = []
  }
}

const loadBatches = async () => {
  loading.value = true
  try {
    batches.value = await batchApi.list(filterScenarioId.value ?? undefined)
  } catch {
    ElMessage.error('加载落地批次失败')
  } finally {
    loading.value = false
  }
}

const reload = async () => {
  await Promise.all([loadScenarios(), loadBatches()])
}

const confirmBatch = async (b: OptimizationBatchDTO) => {
  try {
    const { value } = await ElMessageBox.prompt(
      `确认批次 #${b.id} 冻住的第${b.optimizationRound}轮推演数字无误？确认后场景仍保持旧方案，等待现场经理签收。`,
      '测算岗确认', { confirmButtonText: '确认数字', cancelButtonText: '取消', inputPlaceholder: '备注（可选）' })
    busy.value = b.id
    await batchApi.confirm(b.id, value || undefined)
    ElMessage.success('测算岗已确认，场景仍为旧方案，待现场经理签收')
    await loadBatches()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(extractError(e))
  } finally {
    busy.value = null
  }
}

const signBatch = async (b: OptimizationBatchDTO) => {
  try {
    const { value } = await ElMessageBox.prompt(
      `签收后将把「${b.scenarioName}」当前人员/客流分配一次性改成批次 #${b.id} 冻住的第${b.optimizationRound}轮优化后方案（测算岗已确认）。确认签收落地？`,
      '现场经理签收', {
        confirmButtonText: '签收并落地',
        cancelButtonText: '取消',
        type: 'warning',
        inputPlaceholder: '签收备注（可选）',
        inputValue: ''
      })
    busy.value = b.id
    await batchApi.sign(b.id, value || undefined)
    ElMessage.success('会签完成：场景分配已改为该批次的优化后方案')
    await loadBatches()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(extractError(e))
  } finally {
    busy.value = null
  }
}

const extractError = (e: unknown): string => {
  const data = (e as { response?: { data?: { message?: string } } })?.response?.data
  return data?.message || '操作失败'
}

onMounted(async () => {
  const sid = route.query.scenarioId
  filterScenarioId.value = sid ? Number(sid) : null
  await loadScenarios()
  await loadBatches()
})
</script>
