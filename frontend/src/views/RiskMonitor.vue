<template>
  <div class="space-y-6">
    <div class="bg-white rounded-lg shadow-sm p-6">
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-lg font-semibold text-gray-800">区域高负荷风险监控</h2>
        <button @click="loadRiskAreas" class="px-4 py-2 bg-blue-500 text-white rounded-lg hover:bg-blue-600 transition-colors">
          刷新数据
        </button>
      </div>
      
      <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div class="bg-red-50 border border-red-200 rounded-lg p-4">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-sm text-red-600">高风险区域</div>
              <div class="text-3xl font-bold text-red-600 mt-1">{{ highRiskCount }}</div>
            </div>
            <div class="w-12 h-12 bg-red-100 rounded-full flex items-center justify-center">
              <svg class="w-6 h-6 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"></path>
              </svg>
            </div>
          </div>
        </div>
        
        <div class="bg-orange-50 border border-orange-200 rounded-lg p-4">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-sm text-orange-600">中风险区域</div>
              <div class="text-3xl font-bold text-orange-600 mt-1">{{ mediumRiskCount }}</div>
            </div>
            <div class="w-12 h-12 bg-orange-100 rounded-full flex items-center justify-center">
              <svg class="w-6 h-6 text-orange-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"></path>
              </svg>
            </div>
          </div>
        </div>
        
        <div class="bg-green-50 border border-green-200 rounded-lg p-4">
          <div class="flex items-center justify-between">
            <div>
              <div class="text-sm text-green-600">安全区域</div>
              <div class="text-3xl font-bold text-green-600 mt-1">{{ safeCount }}</div>
            </div>
            <div class="w-12 h-12 bg-green-100 rounded-full flex items-center justify-center">
              <svg class="w-6 h-6 text-green-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"></path>
              </svg>
            </div>
          </div>
        </div>
      </div>
      
      <div class="mt-6">
        <h3 class="text-sm font-medium text-gray-700 mb-4">长期高负荷风险区域标记</h3>
        <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
          <div 
            v-for="area in riskAreas" 
            :key="area.id"
            class="rounded-lg p-4 border"
            :class="getRiskCardClass(area.riskLevel)"
          >
            <div class="flex items-center justify-between mb-2">
              <h4 class="font-medium">{{ area.areaName }}</h4>
              <span 
                class="px-2 py-1 rounded text-xs font-medium"
                :class="getRiskBadgeClass(area.riskLevel)"
              >
                {{ getRiskText(area.riskLevel) }}
              </span>
            </div>
            <div class="space-y-1 text-sm">
              <div class="flex justify-between">
                <span class="text-gray-600">最大接待客流:</span>
                <span>{{ area.maxCapacity }}</span>
              </div>
              <div class="flex justify-between">
                <span class="text-gray-600">在岗人员配额:</span>
                <span>{{ area.staffQuota }}</span>
              </div>
              <div v-if="area.description" class="text-gray-500 mt-2">
                {{ area.description }}
              </div>
            </div>
          </div>
          
          <div v-if="riskAreas.length === 0" class="col-span-full bg-gray-50 rounded-lg p-8 text-center text-gray-500">
            <div class="w-16 h-16 bg-gray-200 rounded-full flex items-center justify-center mx-auto mb-4">
              <svg class="w-8 h-8 text-gray-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"></path>
              </svg>
            </div>
            <p>暂无风险区域</p>
            <p class="text-sm mt-1">所有区域运行状态良好</p>
          </div>
        </div>
      </div>
      
      <div class="mt-6 bg-gray-50 rounded-lg p-6">
        <h3 class="text-sm font-medium text-gray-700 mb-4">风险等级分布</h3>
        <div ref="riskChartRef" class="w-full h-48"></div>
      </div>
      
      <div class="mt-6">
        <h3 class="text-sm font-medium text-gray-700 mb-4">风险处理建议</h3>
        <div class="space-y-3">
          <div v-if="highRiskCount > 0" class="bg-red-50 border border-red-200 rounded-lg p-4">
            <h4 class="font-medium text-red-800 mb-2">高风险区域处理建议</h4>
            <ul class="text-sm text-red-700 space-y-1">
              <li>• 立即增加人员配置，确保每个高风险区域至少增加2名工作人员</li>
              <li>• 考虑临时分流方案，引导部分客流到低负荷区域</li>
              <li>• 设置客流警戒线，超过阈值时启动应急预案</li>
              <li>• 增加现场管理人员，加强秩序维护</li>
            </ul>
          </div>
          
          <div v-if="mediumRiskCount > 0" class="bg-orange-50 border border-orange-200 rounded-lg p-4">
            <h4 class="font-medium text-orange-800 mb-2">中风险区域处理建议</h4>
            <ul class="text-sm text-orange-700 space-y-1">
              <li>• 密切监控客流变化，适时调整人员分配</li>
              <li>• 准备后备人员，随时支援</li>
              <li>• 优化服务流程，提高接待效率</li>
            </ul>
          </div>
          
          <div v-if="highRiskCount === 0 && mediumRiskCount === 0" class="bg-green-50 border border-green-200 rounded-lg p-4">
            <h4 class="font-medium text-green-800 mb-2">当前状态</h4>
            <p class="text-sm text-green-700">所有区域运行状态良好，建议继续保持当前配置方案。</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed, nextTick } from 'vue'
import * as echarts from 'echarts'
import { areaApi, type StoreArea } from '@/api'

const riskAreas = ref<StoreArea[]>([])
const riskChartRef = ref<HTMLElement | null>(null)
let riskChart: echarts.ECharts | null = null

const highRiskCount = computed(() => riskAreas.value.filter(a => a.riskLevel === 3).length)
const mediumRiskCount = computed(() => riskAreas.value.filter(a => a.riskLevel === 2).length)
const safeCount = computed(() => riskAreas.value.filter(a => a.riskLevel === 0).length)

const loadRiskAreas = async () => {
  try {
    riskAreas.value = await areaApi.getRiskAreas()
    
    await nextTick()
    renderRiskChart()
  } catch (error) {
    console.error('加载风险区域失败')
  }
}

const renderRiskChart = () => {
  if (!riskChartRef.value) return
  
  if (riskChart) {
    riskChart.dispose()
  }
  
  riskChart = echarts.init(riskChartRef.value)
  
  const option: echarts.EChartsOption = {
    tooltip: {
      trigger: 'item'
    },
    legend: {
      orient: 'horizontal',
      bottom: 0
    },
    series: [
      {
        name: '风险等级',
        type: 'pie',
        radius: ['40%', '70%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 10,
          borderColor: '#fff',
          borderWidth: 2
        },
        label: {
          show: false,
          position: 'center'
        },
        emphasis: {
          label: {
            show: true,
            fontSize: 16,
            fontWeight: 'bold'
          }
        },
        labelLine: {
          show: false
        },
        data: [
          { value: highRiskCount.value, name: '高风险', itemStyle: { color: '#ff4d4f' } },
          { value: mediumRiskCount.value, name: '中风险', itemStyle: { color: '#fa8c16' } },
          { value: safeCount.value, name: '安全', itemStyle: { color: '#52c41a' } }
        ]
      }
    ]
  }
  
  riskChart.setOption(option)
  
  window.addEventListener('resize', () => {
    riskChart?.resize()
  })
}

const getRiskCardClass = (level: number) => {
  switch (level) {
    case 3: return 'bg-red-50 border-red-200'
    case 2: return 'bg-orange-50 border-orange-200'
    case 1: return 'bg-yellow-50 border-yellow-200'
    default: return 'bg-green-50 border-green-200'
  }
}

const getRiskBadgeClass = (level: number) => {
  switch (level) {
    case 3: return 'bg-red-100 text-red-800'
    case 2: return 'bg-orange-100 text-orange-800'
    case 1: return 'bg-yellow-100 text-yellow-800'
    default: return 'bg-green-100 text-green-800'
  }
}

const getRiskText = (level: number) => {
  switch (level) {
    case 3: return '高风险'
    case 2: return '中风险'
    case 1: return '低风险'
    default: return '安全'
  }
}

onMounted(() => {
  loadRiskAreas()
})

onUnmounted(() => {
  riskChart?.dispose()
})
</script>
