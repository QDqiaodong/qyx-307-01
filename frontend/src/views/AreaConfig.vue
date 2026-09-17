<template>
  <div class="space-y-6">
    <div class="bg-white rounded-lg shadow-sm p-6">
      <div class="flex items-center justify-between mb-6">
        <h2 class="text-lg font-semibold text-gray-800">门店活动区域配置</h2>
        <button @click="showModal = true" class="px-4 py-2 bg-blue-500 text-white rounded-lg hover:bg-blue-600 transition-colors">
          添加区域
        </button>
      </div>
      
      <div class="overflow-x-auto">
        <table class="w-full">
          <thead>
            <tr class="bg-gray-50">
              <th class="px-4 py-3 text-left text-sm font-medium text-gray-600">区域名称</th>
              <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">最大接待客流</th>
              <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">在岗人员配额</th>
              <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">风险等级</th>
              <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">描述</th>
              <th class="px-4 py-3 text-center text-sm font-medium text-gray-600">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="area in areas" :key="area.id" class="border-b">
              <td class="px-4 py-3">{{ area.areaName }}</td>
              <td class="px-4 py-3 text-center">{{ area.maxCapacity }}</td>
              <td class="px-4 py-3 text-center">{{ area.staffQuota }}</td>
              <td class="px-4 py-3 text-center">
                <span 
                  class="px-2 py-1 rounded text-xs font-medium"
                  :class="getRiskClass(area.riskLevel)"
                >
                  {{ getRiskText(area.riskLevel) }}
                </span>
              </td>
              <td class="px-4 py-3 text-center">{{ area.description || '-' }}</td>
              <td class="px-4 py-3 text-center">
                <div class="flex items-center justify-center space-x-2">
                  <button @click="editArea(area)" class="text-blue-500 hover:text-blue-700">编辑</button>
                  <span class="text-gray-300">|</span>
                  <button @click="deleteArea(area.id)" class="text-red-500 hover:text-red-700">删除</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      
      <div v-if="areas.length === 0" class="text-center py-12 text-gray-500">
        暂无区域配置，请点击上方按钮添加
      </div>
    </div>
    
    <el-dialog v-model="showModal" :title="editingArea ? '编辑区域' : '添加区域'" width="500px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="区域名称" prop="areaName">
          <el-input v-model="form.areaName" placeholder="请输入区域名称" />
        </el-form-item>
        <el-form-item label="最大接待客流" prop="maxCapacity">
          <el-input-number v-model="form.maxCapacity" :min="1" :max="10000" placeholder="请输入最大接待客流" />
        </el-form-item>
        <el-form-item label="在岗人员配额" prop="staffQuota">
          <el-input-number v-model="form.staffQuota" :min="1" :max="100" placeholder="请输入在岗人员配额" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="form.description" type="textarea" placeholder="请输入区域描述" :rows="3" />
        </el-form-item>
      </el-form>
      
      <template #footer>
        <el-button @click="showModal = false">取消</el-button>
        <el-button type="primary" @click="saveArea">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { areaApi, type StoreArea, type AreaConfigDTO } from '@/api'

const areas = ref<StoreArea[]>([])
const showModal = ref(false)
const editingArea = ref<StoreArea | null>(null)

const form = ref<AreaConfigDTO>({
  areaName: '',
  maxCapacity: 0,
  staffQuota: 0,
  description: ''
})

const loadAreas = async () => {
  try {
    areas.value = await areaApi.getAll()
  } catch (error) {
    ElMessage.error('加载区域数据失败')
  }
}

const editArea = (area: StoreArea) => {
  editingArea.value = area
  form.value = {
    id: area.id,
    areaName: area.areaName,
    maxCapacity: area.maxCapacity,
    staffQuota: area.staffQuota,
    description: area.description || ''
  }
  showModal.value = true
}

const saveArea = async () => {
  if (!form.value.areaName || form.value.maxCapacity <= 0 || form.value.staffQuota <= 0) {
    ElMessage.warning('请填写完整信息')
    return
  }
  
  try {
    if (editingArea.value) {
      await areaApi.update(editingArea.value.id, form.value)
      ElMessage.success('更新成功')
    } else {
      await areaApi.create(form.value)
      ElMessage.success('创建成功')
    }
    showModal.value = false
    editingArea.value = null
    form.value = { areaName: '', maxCapacity: 0, staffQuota: 0, description: '' }
    await loadAreas()
  } catch (error) {
    ElMessage.error(editingArea.value ? '更新失败' : '创建失败')
  }
}

const deleteArea = async (id: number) => {
  try {
    await ElMessageBox.confirm('确定要删除该区域吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await areaApi.delete(id)
    ElMessage.success('删除成功')
    await loadAreas()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('删除失败')
    }
  }
}

const getRiskClass = (level: number) => {
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
  loadAreas()
})
</script>
