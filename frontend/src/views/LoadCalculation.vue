<template>
  <div class="space-y-6">
    <div class="bg-white rounded-lg shadow-sm p-6">
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-lg font-semibold text-gray-800">区域客流负荷饱和度测算</h2>
        <div class="flex items-center space-x-4">
          <el-select 
            v-model="selectedScenarioId" 
            placeholder="选择场景" 
            class="w-64"
            @change="loadLoadData"
          >
            <el-option 
              v-for="scenario in scenarios" 
              :key="scenario.id" 
              :label="scenario.scenarioName" 
              :value="scenario.id" 
            />
          </el-select>
          <button 
            @click="loadLoadData" 
            class="px-4 py-2 bg-blue-500 text-white rounded-lg hover:bg-blue-600 transition-colors"
          >
            重新测算
          </button>
        </div>
      </div>
      
      <div v-if="!selectedScenarioId" class="text-center py-12 text-gray-500">
        请先选择一个客流场景
      </div>
      
      <div v-else class="space-y-6">
        <div class="grid grid-cols-1 md:grid-cols-4 gap-4">
          <div class="bg-gradient-to-br from-blue-500 to-blue-600 rounded-lg p-4 text-white">
            <div class="text-sm opacity-80">最大饱和度</div>
            <div class="text-2xl font-bold mt-1">{{ loadResult.maxSaturation?.toFixed(1) || '-' }}%</div>
          </div>
          <div class="bg-gradient-to-br from-green-500 to-green-600 rounded-lg p-4 text-white">
            <div class="text-sm opacity-80">平均饱和度</div>
            <div class="text-2xl font-bold mt-1">{{ loadResult.avgSaturation?.toFixed(1) || '-' }}%</div>
          </div>
          <div class="bg-gradient-to-br from-orange-500 to-orange-600 rounded-lg p-4 text-white">
            <div class="text-sm opacity-80">过载区域数</div>
            <div class="text-2xl font-bold mt-1">{{ loadResult.overloadedCount || 0 }}</div>
          </div>
          <div class="bg-gradient-to-br from-purple-500 to-purple-600 rounded-lg p-4 text-white">
            <div class="text-sm opacity-80">总客流</div>
            <div class="text-2xl font-bold mt-1">{{ loadResult.totalFlow?.toLocaleString() || '-' }}</div>
          </div>
        </div>
        
        <div class="bg-gray-50 rounded-lg p-6">
          <h3 class="text-sm font-medium text-gray-700 mb-4">区域负荷饱和度热力图</h3>
          <div ref="heatmapRef" class="w-full h-80"></div>
        </div>
        
        <div class="overflow-x-auto">
          <table class="w-full">
            <thead>
              <tr class="bg-gray-50">
                <th class="px-4 py-3 text-left text-sm font-medium text-gray-600">区域名称</th>
                <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">当前客流</th>
                <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">最大容量</th>
                <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">饱和度</th>
                <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">分配人员</th>
                <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">人员配额</th>
                <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">人员负荷</th>
                <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in loadData" :key="item.areaId" class="border-b">
                <td class="px-4 py-3">{{ item.areaName }}</td>
                <td class="px-4 py-3 text-center">{{ item.currentFlow }}</td>
                <td class="px-4 py-3 text-center">{{ item.maxCapacity }}</td>
                <td class="px-4 py-3 text-center">
                  <div class="flex items-center justify-center space-x-2">
                    <div class="w-24 h-2 bg-gray-200 rounded-full overflow-hidden">
                      <div 
                        class="h-full rounded-full transition-all"
                        :class="getSaturationBarClass(item.saturationRate)"
                        :style="{ width: Math.min(item.saturationRate, 100) + '%' }"
                      ></div>
                    </div>
                    <span class="text-sm font-medium" :class="getSaturationTextClass(item.saturationRate)">
                      {{ item.saturationRate.toFixed(1) }}%
                    </span>
                  </div>
                </td>
                <td class="px-4 py-3 text-center">{{ item.allocatedStaff }}</td>
                <td class="px-4 py-3 text-center">{{ item.staffQuota }}</td>
                <td class="px-4 py-3 text-center">{{ item.staffLoadRate.toFixed(1) }}%</td>
                <td class="px-4 py-3 text-center">
                  <span 
                    class="px-2 py-1 rounded text-xs font-medium"
                    :class="item.isOverloaded ? 'bg-red-100 text-red-800' : 'bg-green-100 text-green-800'"
                  >
                    {{ item.isOverloaded ? '过载' : '正常' }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import * as echarts from 'echarts'
import { scenarioApi, type FlowScenario, type AreaLoadDTO } from '@/api'

const scenarios = ref<FlowScenario[]>([])
const selectedScenarioId = ref<number | null>(null)
const loadData = ref<AreaLoadDTO[]>([])
const heatmapRef = ref<HTMLElement | null>(null)
let heatmapChart: echarts.ECharts | null = null

const loadResult = ref({
  maxSaturation: 0,
  avgSaturation: 0,
  overloadedCount: 0,
  totalFlow: 0
})

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

const loadLoadData = async () => {
  if (!selectedScenarioId.value) return
  
  try {
    loadData.value = await scenarioApi.calculateLoad(selectedScenarioId.value)
    
    if (loadData.value.length > 0) {
      loadResult.value = {
        maxSaturation: Math.max(...loadData.value.map(item => item.saturationRate)),
        avgSaturation: loadData.value.reduce((sum, item) => sum + item.saturationRate, 0) / loadData.value.length,
        overloadedCount: loadData.value.filter(item => item.isOverloaded).length,
        totalFlow: loadData.value.reduce((sum, item) => sum + item.currentFlow, 0)
      }
    }
    
    await nextTick()
    renderHeatmap()
  } catch (error) {
    console.error('测算失败')
  }
}

const renderHeatmap = () => {
  if (!heatmapRef.value) return
  
  if (heatmapChart) {
    heatmapChart.dispose()
  }
  
  heatmapChart = echarts.init(heatmapRef.value)
  
  const data = loadData.value.map((item, index) => [0, index, item.saturationRate])
  
  const option: echarts.EChartsOption = {
    tooltip: {
      formatter: (params: unknown) => {
        const p = params as { data: number[] }
        const item = loadData.value[p.data[1]]
        return `${item.areaName}<br/>饱和度: ${item.saturationRate.toFixed(1)}%<br/>客流: ${item.currentFlow}/${item.maxCapacity}`
      }
    },
    grid: {
      left: '3%',
      right: '4%',
      bottom: '3%',
      top: '10%',
      containLabel: true
    },
    xAxis: {
      type: 'category',
      data: ['饱和度'],
      splitArea: {
        show: true
      },
      axisLabel: {
        show: false
      },
      axisTick: {
        show: false
      }
    },
    yAxis: {
      type: 'category',
      data: loadData.value.map(item => item.areaName),
      splitArea: {
        show: true
      }
    },
    visualMap: {
      min: 0,
      max: 100,
      calculable: true,
      orient: 'horizontal',
      left: 'center',
      bottom: '0%',
      inRange: {
        color: ['#52c41a', '#73d13d', '#95de64', '#bae637', '#ffe58f', '#ffa940', '#ff7a45', '#ff4d4f']
      }
    },
    series: [
      {
        name: '饱和度',
        type: 'heatmap',
        data,
        label: {
          show: true,
          formatter: (params: unknown) => {
            const p = params as { value: number[] }
            return `${p.value[2].toFixed(0)}%`
          }
        },
        emphasis: {
          itemStyle: {
            shadowBlur: 10,
            shadowColor: 'rgba(0, 0, 0, 0.5)'
          }
        }
      }
    ]
  }
  
  heatmapChart.setOption(option)
  
  window.addEventListener('resize', () => {
    heatmapChart?.resize()
  })
}

const getSaturationBarClass = (rate: number) => {
  if (rate >= 100) return 'bg-red-500'
  if (rate >= 85) return 'bg-orange-500'
  if (rate >= 70) return 'bg-yellow-500'
  return 'bg-green-500'
}

const getSaturationTextClass = (rate: number) => {
  if (rate >= 100) return 'text-red-600'
  if (rate >= 85) return 'text-orange-600'
  if (rate >= 70) return 'text-yellow-600'
  return 'text-green-600'
}

watch(selectedScenarioId, () => {
  loadLoadData()
})

onMounted(() => {
  loadScenarios()
})

onUnmounted(() => {
  heatmapChart?.dispose()
})
</script>
