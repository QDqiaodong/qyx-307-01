<template>
  <div class="space-y-6">
    <div class="bg-white rounded-lg shadow-sm p-6">
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-lg font-semibold text-gray-800">资源均衡调配推演</h2>
        <div class="flex items-center space-x-4">
          <el-select 
            v-model="selectedScenarioId" 
            placeholder="选择场景" 
            class="w-64"
          >
            <el-option 
              v-for="scenario in scenarios" 
              :key="scenario.id" 
              :label="scenario.scenarioName" 
              :value="scenario.id" 
            />
          </el-select>
          <button
            @click="runOptimization"
            :loading="optimizing"
            class="px-4 py-2 bg-green-500 text-white rounded-lg hover:bg-green-600 transition-colors"
          >
            执行优化推演
          </button>
        </div>
      </div>

      <!-- 落地会签状态横幅：推演数字不等于场景已改，必须两步会签 -->
      <div v-if="selectedScenarioId" class="mb-6">
        <div v-if="inFlightBatches.length > 0"
             class="rounded-lg border border-blue-200 bg-blue-50 p-4 flex flex-wrap items-center justify-between gap-3">
          <div class="text-sm">
            <div class="font-medium text-blue-800">
              第{{ inFlightBatches[0].optimizationRound }}轮推演已冻成批次 #{{ inFlightBatches[0].id }}
              <el-tag size="small" :type="inFlightBatches[0].status === 'CONFIRMED' ? 'warning' : 'info'" class="ml-2">
                {{ inFlightBatches[0].statusText }}
              </el-tag>
            </div>
            <div class="text-blue-700 mt-1">
              <template v-if="inFlightBatches[0].status === 'CONFIRMED'">
                测算岗已确认、现场经理尚未签收——场景人员/客流分配仍是优化前旧方案。
              </template>
              <template v-else>
                批次已冻结核定容量、编制配额与优化前/后分配，等待测算岗确认；会签两步完成前场景不会改动。
              </template>
            </div>
          </div>
          <button class="px-4 py-2 text-sm rounded-lg text-white bg-blue-600 hover:bg-blue-700"
                  @click="goToBatch(inFlightBatches[0].id)">
            去会签落地
          </button>
        </div>
        <div v-else-if="lastLandedBatch"
             class="rounded-lg border border-green-200 bg-green-50 p-4 flex flex-wrap items-center justify-between gap-3">
          <div class="text-sm text-green-800">
            场景当前采用批次 #{{ lastLandedBatch.id }} 冻住的第{{ lastLandedBatch.optimizationRound }}轮优化后方案
            <span class="text-green-600">（{{ formatDate(lastLandedBatch.landedAt) }} 已会签落地）</span>
          </div>
          <button class="px-3 py-1.5 text-sm border border-green-300 rounded-lg hover:bg-green-100"
                  @click="goToBatch(lastLandedBatch.id)">查看批次档案</button>
        </div>
        <div v-else class="rounded-lg border border-gray-200 bg-gray-50 p-3 text-xs text-gray-500">
          尚未冻结任何落地批次。执行推演只会冻出批次与草稿，<b>不会直接改动场景分配</b>；需在「落地会签」页经测算岗确认、现场经理签收后才按优化后方案排人。
        </div>
      </div>

      <div v-if="!selectedScenarioId" class="text-center py-12 text-gray-500">
        请先选择一个客流场景
      </div>
      
      <div v-else-if="!optimizationResult" class="text-center py-12 text-gray-500">
        点击"执行优化推演"按钮开始资源均衡调配
      </div>
      
      <div v-else class="space-y-6">
        <div class="grid grid-cols-1 md:grid-cols-6 gap-4">
          <div class="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div class="text-xs text-gray-500">优化前最大饱和度</div>
            <div class="text-xl font-bold mt-1 text-orange-600">{{ optimizationResult.beforeMaxSaturation.toFixed(1) }}%</div>
          </div>
          <div class="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div class="text-xs text-gray-500">优化后最大饱和度</div>
            <div class="text-xl font-bold mt-1 text-green-600">{{ optimizationResult.afterMaxSaturation.toFixed(1) }}%</div>
          </div>
          <div class="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div class="text-xs text-gray-500">优化前平均饱和度</div>
            <div class="text-xl font-bold mt-1 text-orange-600">{{ optimizationResult.beforeAvgSaturation.toFixed(1) }}%</div>
          </div>
          <div class="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div class="text-xs text-gray-500">优化后平均饱和度</div>
            <div class="text-xl font-bold mt-1 text-green-600">{{ optimizationResult.afterAvgSaturation.toFixed(1) }}%</div>
          </div>
          <div class="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div class="text-xs text-gray-500">优化前过载数</div>
            <div class="text-xl font-bold mt-1 text-red-600">{{ optimizationResult.beforeOverloadedCount }}</div>
          </div>
          <div class="bg-gray-50 rounded-lg p-4 border border-gray-200">
            <div class="text-xs text-gray-500">优化后过载数</div>
            <div class="text-xl font-bold mt-1 text-green-600">{{ optimizationResult.afterOverloadedCount }}</div>
          </div>
        </div>
        
        <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <div class="bg-gray-50 rounded-lg p-6">
            <h3 class="text-sm font-medium text-gray-700 mb-4">优化前分配方案</h3>
            <div class="space-y-3">
              <div 
                v-for="item in optimizationResult.beforeAllocations" 
                :key="'before-' + item.areaId"
                class="bg-white rounded-lg p-4 border-l-4"
                :class="item.isOverloaded ? 'border-l-red-500' : 'border-l-green-500'"
              >
                <div class="flex items-center justify-between mb-2">
                  <span class="font-medium">{{ item.areaName }}</span>
                  <span 
                    class="px-2 py-1 rounded text-xs font-medium"
                    :class="item.isOverloaded ? 'bg-red-100 text-red-800' : 'bg-green-100 text-green-800'"
                  >
                    {{ item.isOverloaded ? '过载' : '正常' }}
                  </span>
                </div>
                <div class="flex items-center justify-between text-sm">
                  <span>客流: {{ item.allocatedFlow }}/{{ item.maxCapacity }}</span>
                  <span>人员: {{ item.allocatedStaff }}</span>
                  <span>饱和度: {{ item.saturationRate?.toFixed(1) }}%</span>
                </div>
              </div>
            </div>
          </div>
          
          <div class="bg-gray-50 rounded-lg p-6">
            <h3 class="text-sm font-medium text-gray-700 mb-4">优化后分配方案</h3>
            <div class="space-y-3">
              <div 
                v-for="item in optimizationResult.afterAllocations" 
                :key="'after-' + item.areaId"
                class="bg-white rounded-lg p-4 border-l-4"
                :class="item.isOverloaded ? 'border-l-red-500' : 'border-l-green-500'"
              >
                <div class="flex items-center justify-between mb-2">
                  <span class="font-medium">{{ item.areaName }}</span>
                  <span 
                    class="px-2 py-1 rounded text-xs font-medium"
                    :class="item.isOverloaded ? 'bg-red-100 text-red-800' : 'bg-green-100 text-green-800'"
                  >
                    {{ item.isOverloaded ? '过载' : '正常' }}
                  </span>
                </div>
                <div class="flex items-center justify-between text-sm">
                  <span>客流: {{ item.allocatedFlow }}/{{ item.maxCapacity }}</span>
                  <span>人员: {{ item.allocatedStaff }}</span>
                  <span>饱和度: {{ item.saturationRate?.toFixed(1) }}%</span>
                </div>
              </div>
            </div>
          </div>
        </div>
        
        <div class="bg-gray-50 rounded-lg p-6">
          <h3 class="text-sm font-medium text-gray-700 mb-4">优化步骤详情</h3>
          <div class="space-y-3">
            <div 
              v-for="step in optimizationResult.optimizationSteps" 
              :key="step.stepNumber"
              class="bg-white rounded-lg p-4"
            >
              <div class="flex items-center justify-between mb-2">
                <span class="font-medium">步骤 {{ step.stepNumber }}</span>
                <span class="text-sm text-green-600">改善率: {{ step.improvementRate }}%</span>
              </div>
              <p class="text-sm text-gray-600">{{ step.description }}</p>
              <div class="flex items-center space-x-4 mt-2 text-xs text-gray-500">
                <span>人员调配: {{ step.sourceArea }} → {{ step.targetArea }} ({{ step.staffTransfer }}人)</span>
                <span>客流调配: {{ step.sourceArea }} → {{ step.targetArea }} ({{ step.flowTransfer }}人)</span>
              </div>
            </div>
            
            <div v-if="optimizationResult.optimizationSteps.length === 0" class="text-center py-8 text-gray-500">
              当前方案已达到最优状态，无需进一步优化
            </div>
          </div>
        </div>
        
        <div class="bg-gray-50 rounded-lg p-6">
          <h3 class="text-sm font-medium text-gray-700 mb-4">优化效果对比图</h3>
          <div ref="comparisonChartRef" class="w-full h-60"></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import {
  scenarioApi, batchApi,
  type FlowScenario, type OptimizationResultDTO, type OptimizationBatchDTO
} from '@/api'

const router = useRouter()
const scenarios = ref<FlowScenario[]>([])
const selectedScenarioId = ref<number | null>(null)
const optimizationResult = ref<OptimizationResultDTO | null>(null)
const optimizing = ref(false)
const comparisonChartRef = ref<HTMLElement | null>(null)
let comparisonChart: echarts.ECharts | null = null
const batches = ref<OptimizationBatchDTO[]>([])

/** 当前场景仍在会签流程中（且冻的是最新一轮）的批次：DRAFT / CONFIRMED。 */
const inFlightBatches = computed(() =>
  batches.value
    .filter(b => b.current && (b.status === 'DRAFT' || b.status === 'CONFIRMED'))
    .sort((a, b2) => b2.optimizationRound - a.optimizationRound)
)

/** 最近一份已会签落地的批次（再进页面时场景仍应是这套优化后数字）。 */
const lastLandedBatch = computed(() =>
  batches.value.filter(b => b.status === 'LANDED')
    .sort((a, b2) => b2.optimizationRound - a.optimizationRound)[0] || null
)

const formatDate = (d?: string) => (d ? new Date(d).toLocaleString('zh-CN') : '-')

const goToBatch = (id: number) => {
  router.push({ path: '/batches', query: { scenarioId: String(selectedScenarioId.value ?? ''), batch: String(id) } })
}

const loadBatchesForScenario = async (scenarioId: number | null) => {
  if (!scenarioId) {
    batches.value = []
    return
  }
  try {
    batches.value = await batchApi.list(scenarioId)
  } catch {
    batches.value = []
  }
}

const loadScenarios = async () => {
  try {
    scenarios.value = await scenarioApi.getAll()
    if (scenarios.value.length > 0) {
      selectedScenarioId.value = scenarios.value[0].id
    }
  } catch (error) {
    console.error('加载场景失败')
  }
}

const runOptimization = async () => {
  if (!selectedScenarioId.value) {
    ElMessage.warning('请先选择一个场景')
    return
  }
  
  optimizing.value = true
  
  try {
    optimizationResult.value = await scenarioApi.optimize(selectedScenarioId.value)
    ElMessage.success('优化推演完成，已冻结落地批次（需测算确认 + 现场签收，场景暂不改动）')

    await loadBatchesForScenario(selectedScenarioId.value)
    await nextTick()
    renderComparisonChart()
  } catch (error) {
    ElMessage.error('优化推演失败')
  } finally {
    optimizing.value = false
  }
}

watch(selectedScenarioId, (id) => {
  optimizationResult.value = null
  loadBatchesForScenario(id)
})

const renderComparisonChart = () => {
  if (!comparisonChartRef.value || !optimizationResult.value) return
  
  if (comparisonChart) {
    comparisonChart.dispose()
  }
  
  comparisonChart = echarts.init(comparisonChartRef.value)
  
  const areaNames = optimizationResult.value.beforeAllocations.map(item => item.areaName || '')
  const beforeRates = optimizationResult.value.beforeAllocations.map(item => item.saturationRate || 0)
  const afterRates = optimizationResult.value.afterAllocations.map(item => item.saturationRate || 0)
  
  const option: echarts.EChartsOption = {
    tooltip: {
      trigger: 'axis',
      axisPointer: {
        type: 'shadow'
      }
    },
    legend: {
      data: ['优化前', '优化后'],
      top: 0
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      top: '15%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: areaNames
    },
    yAxis: {
      type: 'value',
      max: 120,
      axisLabel: {
        formatter: '{value}%'
      }
    },
    series: [
      {
        name: '优化前',
        type: 'bar',
        data: beforeRates,
        itemStyle: {
          color: '#fa8c16'
        }
      },
      {
        name: '优化后',
        type: 'bar',
        data: afterRates,
        itemStyle: {
          color: '#52c41a'
        }
      }
    ]
  }
  
  comparisonChart.setOption(option)
  
  window.addEventListener('resize', () => {
    comparisonChart?.resize()
  })
}

onMounted(async () => {
  await loadScenarios()
  await loadBatchesForScenario(selectedScenarioId.value)
})

onUnmounted(() => {
  comparisonChart?.dispose()
})
</script>
