<template>
  <div class="space-y-6">
    <div class="bg-white rounded-lg shadow-sm p-6">
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-lg font-semibold text-gray-800">节日客流场景管理</h2>
        <button @click="showModal = true" class="px-4 py-2 bg-blue-500 text-white rounded-lg hover:bg-blue-600 transition-colors">
          创建场景
        </button>
      </div>
      
      <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
        <div 
          v-for="scenario in scenarios" 
          :key="scenario.id"
          class="bg-gray-50 rounded-lg p-4 hover:shadow-md transition-shadow cursor-pointer"
          @click="selectScenario(scenario)"
        >
          <div class="flex items-start justify-between mb-3">
            <h3 class="font-medium text-gray-800">{{ scenario.scenarioName }}</h3>
            <button @click.stop="deleteScenario(scenario.id)" class="text-red-500 hover:text-red-700">
              <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12"></path>
              </svg>
            </button>
          </div>
          <p class="text-sm text-gray-500 mb-2">节日: {{ scenario.festivalName }}</p>
          <p class="text-sm text-gray-500 mb-2">预估总客流: {{ scenario.estimatedTotalFlow.toLocaleString() }}</p>
          <p class="text-xs text-gray-400">{{ formatDate(scenario.createdAt) }}</p>
        </div>
      </div>
      
      <div v-if="scenarios.length === 0" class="text-center py-12 text-gray-500">
        暂无客流场景，请点击上方按钮创建
      </div>
    </div>
    
    <div v-if="selectedScenario" class="bg-white rounded-lg shadow-sm p-6">
      <div v-if="inFlightBatch" class="mb-4 rounded-lg border border-blue-200 bg-blue-50 px-4 py-2 text-sm text-blue-800">
        第{{ inFlightBatch.optimizationRound }}轮推演已冻成批次 #{{ inFlightBatch.id }}（{{ inFlightBatch.statusText }}），
        会签两步完成前<b>本页分配仍是旧方案</b>。
        <router-link :to="{ path: '/batches', query: { scenarioId: String(selectedScenario.id) } }"
                     class="text-blue-600 hover:underline ml-1">去会签</router-link>
      </div>
      <div v-else-if="landedBatch" class="mb-4 rounded-lg border border-green-200 bg-green-50 px-4 py-2 text-sm text-green-800 flex items-center justify-between">
        <span>
          当前人员/客流分配为已会签落地的优化后方案：批次 #{{ landedBatch.id }}（第{{ landedBatch.optimizationRound }}轮，
          {{ formatDate(landedBatch.landedAt) }} 签收）。
        </span>
        <router-link :to="{ path: '/batches', query: { scenarioId: String(selectedScenario.id) } }"
                     class="text-blue-600 hover:underline ml-3">查看批次档案</router-link>
      </div>
      <div class="flex items-center justify-between mb-6">
        <h3 class="text-lg font-semibold text-gray-800">初始人员/客流分配方案</h3>
        <button @click="saveAllocation" class="px-4 py-2 bg-green-500 text-white rounded-lg hover:bg-green-600 transition-colors">
          保存分配
        </button>
      </div>
      
      <div class="overflow-x-auto">
        <table class="w-full">
          <thead>
            <tr class="bg-gray-50">
              <th class="px-4 py-3 text-left text-sm font-medium text-gray-600">区域名称</th>
              <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">最大容量</th>
              <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">分配人员</th>
              <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">分配客流</th>
              <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">饱和度</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="alloc in allocations" :key="alloc.areaId" class="border-b"
                :class="isClosed(alloc.areaId) ? 'bg-red-50' : ''">
              <td class="px-4 py-3">
                {{ alloc.areaName }}
                <el-tag v-if="isClosed(alloc.areaId)" type="danger" size="small" effect="plain" class="ml-2">
                  封区生效 · 已清0禁写回
                </el-tag>
              </td>
              <td class="px-4 py-3 text-center">{{ alloc.maxCapacity }}</td>
              <td class="px-4 py-3 text-center">
                <el-input-number
                  v-model="alloc.allocatedStaff"
                  :min="isClosed(alloc.areaId) ? 0 : 1"
                  :max="100"
                  :disabled="isClosed(alloc.areaId)"
                  class="w-24"
                />
              </td>
              <td class="px-4 py-3 text-center">
                <el-input-number
                  v-model="alloc.allocatedFlow"
                  :min="isClosed(alloc.areaId) ? 0 : 1"
                  :max="alloc.maxCapacity || 10000"
                  :disabled="isClosed(alloc.areaId)"
                  class="w-24"
                />
              </td>
              <td class="px-4 py-3 text-center">
                <span
                  class="px-2 py-1 rounded text-xs font-medium"
                  :class="getSaturationClass(alloc)"
                >
                  {{ alloc.saturationRate ? alloc.saturationRate.toFixed(1) + '%' : '-' }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    
    <el-dialog v-model="showModal" title="创建节日客流场景" width="600px">
      <el-form :model="scenarioForm" label-width="120px">
        <el-form-item label="场景名称" prop="scenarioName">
          <el-input v-model="scenarioForm.scenarioName" placeholder="请输入场景名称" />
        </el-form-item>
        <el-form-item label="节日名称" prop="festivalName">
          <el-input v-model="scenarioForm.festivalName" placeholder="请输入节日名称" />
        </el-form-item>
        <el-form-item label="预估总客流" prop="estimatedTotalFlow">
          <el-input-number v-model="scenarioForm.estimatedTotalFlow" :min="1" :max="100000" placeholder="请输入预估总客流" />
        </el-form-item>
      </el-form>
      
      <template #footer>
        <el-button @click="showModal = false">取消</el-button>
        <el-button type="primary" @click="createScenario">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { areaApi, scenarioApi, closureApi, batchApi, type FlowScenario, type FlowScenarioDTO, type AllocationDTO, type StoreArea, type OptimizationBatchDTO } from '@/api'

const scenarios = ref<FlowScenario[]>([])
const showModal = ref(false)
const selectedScenario = ref<FlowScenario | null>(null)
const allocations = ref<AllocationDTO[]>([])
const areas = ref<StoreArea[]>([])
const closedAreaIds = ref<Set<number>>(new Set())
const batches = ref<OptimizationBatchDTO[]>([])

const landedBatch = computed(() =>
  batches.value.filter(b => b.status === 'LANDED')
    .sort((a, b2) => b2.optimizationRound - a.optimizationRound)[0] || null
)
const inFlightBatch = computed(() =>
  batches.value
    .filter(b => b.current && (b.status === 'DRAFT' || b.status === 'CONFIRMED'))
    .sort((a, b2) => b2.optimizationRound - a.optimizationRound)[0] || null
)

const loadBatches = async (scenarioId: number) => {
  try {
    batches.value = await batchApi.list(scenarioId)
  } catch {
    batches.value = []
  }
}

const isClosed = (areaId: number) => closedAreaIds.value.has(areaId)

const loadClosedAreas = async (scenarioId: number) => {
  try {
    const list = await closureApi.list(scenarioId)
    closedAreaIds.value = new Set(list.filter(c => c.live).map(c => c.areaId))
  } catch {
    closedAreaIds.value = new Set()
  }
}

const scenarioForm = ref<FlowScenarioDTO>({
  scenarioName: '',
  festivalName: '',
  estimatedTotalFlow: 0
})

const loadScenarios = async () => {
  try {
    scenarios.value = await scenarioApi.getAll()
  } catch (error) {
    ElMessage.error('加载场景数据失败')
  }
}

const loadAreas = async () => {
  try {
    areas.value = await areaApi.getAll()
  } catch (error) {
    ElMessage.error('加载区域数据失败')
  }
}

const createScenario = async () => {
  if (!scenarioForm.value.scenarioName || !scenarioForm.value.festivalName || scenarioForm.value.estimatedTotalFlow <= 0) {
    ElMessage.warning('请填写完整信息')
    return
  }
  
  try {
    await scenarioApi.create(scenarioForm.value)
    ElMessage.success('创建成功')
    showModal.value = false
    scenarioForm.value = { scenarioName: '', festivalName: '', estimatedTotalFlow: 0 }
    await loadScenarios()
  } catch (error) {
    ElMessage.error('创建失败')
  }
}

const deleteScenario = async (id: number) => {
  try {
    await ElMessageBox.confirm('确定要删除该场景吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await scenarioApi.delete(id)
    ElMessage.success('删除成功')
    if (selectedScenario.value?.id === id) {
      selectedScenario.value = null
      allocations.value = []
    }
    await loadScenarios()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

const selectScenario = async (scenario: FlowScenario) => {
  selectedScenario.value = scenario
  await Promise.all([
    loadAllocations(scenario.id),
    loadClosedAreas(scenario.id),
    loadBatches(scenario.id)
  ])
}

const loadAllocations = async (scenarioId: number) => {
  try {
    const loadData = await scenarioApi.calculateLoad(scenarioId)
    allocations.value = loadData.map(item => ({
      areaId: item.areaId,
      areaName: item.areaName,
      allocatedStaff: item.allocatedStaff,
      allocatedFlow: item.currentFlow,
      maxCapacity: item.maxCapacity,
      saturationRate: item.saturationRate
    }))
    
    if (allocations.value.length === 0 && areas.value.length > 0) {
      allocations.value = areas.value.map(area => ({
        areaId: area.id,
        areaName: area.areaName,
        allocatedStaff: Math.ceil(area.staffQuota / 2),
        allocatedFlow: Math.ceil(area.maxCapacity / 2),
        maxCapacity: area.maxCapacity,
        saturationRate: 50
      }))
    }
  } catch (error) {
    allocations.value = areas.value.map(area => ({
      areaId: area.id,
      areaName: area.areaName,
      allocatedStaff: Math.ceil(area.staffQuota / 2),
      allocatedFlow: Math.ceil(area.maxCapacity / 2),
      maxCapacity: area.maxCapacity,
      saturationRate: 50
    }))
  }
}

const saveAllocation = async () => {
  if (!selectedScenario.value) return

  try {
    for (const alloc of allocations.value) {
      // 封区生效区域已清 0、后端禁止写回，保存时跳过
      if (isClosed(alloc.areaId)) continue
      alloc.saturationRate = alloc.maxCapacity ? (alloc.allocatedFlow / alloc.maxCapacity) * 100 : 0
      await scenarioApi.updateAllocation(selectedScenario.value.id, {
        areaId: alloc.areaId,
        staff: alloc.allocatedStaff,
        flow: alloc.allocatedFlow
      })
    }
    ElMessage.success('保存成功（已跳过封区生效区域）')
  } catch (error) {
    const msg = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '保存失败')
  }
}

const getSaturationClass = (alloc: AllocationDTO) => {
  const rate = alloc.saturationRate || 0
  if (rate >= 100) return 'bg-red-100 text-red-800'
  if (rate >= 85) return 'bg-orange-100 text-orange-800'
  if (rate >= 70) return 'bg-yellow-100 text-yellow-800'
  return 'bg-green-100 text-green-800'
}

const formatDate = (dateStr?: string) => {
  return dateStr ? new Date(dateStr).toLocaleString('zh-CN') : '-'
}

watch(selectedScenario, async (newVal) => {
  if (newVal) {
    await Promise.all([
      loadAllocations(newVal.id),
      loadClosedAreas(newVal.id),
      loadBatches(newVal.id)
    ])
  }
})

onMounted(() => {
  loadScenarios()
  loadAreas()
})
</script>
