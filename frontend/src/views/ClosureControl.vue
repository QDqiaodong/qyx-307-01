<template>
  <div class="space-y-6">
    <!-- 提交封区 -->
    <div class="bg-white rounded-lg shadow-sm p-6">
      <div class="flex items-center justify-between mb-2">
        <h2 class="text-lg font-semibold text-gray-800">临时封区回灌</h2>
        <span class="text-xs text-gray-500">安全岗口径：回灌落不成账，绝不能关区</span>
      </div>
      <p class="text-sm text-gray-500 mb-5">
        选择要封的区域并绑定正在使用的客流场景。提交会在一笔事务内把该区域在场景中的人员/客流清 0，
        并按开放区域当时的剩余容量、剩余编制一次性回灌到至少一块仍开放区域；承接不下则整单失败，区域保持可营业。
      </p>

      <div class="grid grid-cols-1 md:grid-cols-4 gap-4 items-end">
        <div>
          <label class="block text-sm text-gray-600 mb-1">客流场景（绑定正在用）</label>
          <el-select v-model="form.scenarioId" placeholder="选择场景" class="w-full" filterable
                     @change="onScenarioChange">
            <el-option v-for="s in scenarios" :key="s.id"
                       :label="`${s.scenarioName}（${s.festivalName}）`" :value="s.id" />
          </el-select>
        </div>
        <div>
          <label class="block text-sm text-gray-600 mb-1">要封的区域</label>
          <el-select v-model="form.areaId" placeholder="选择区域" class="w-full" filterable
                     :disabled="!form.scenarioId" @change="onAreaChange">
            <el-option
              v-for="a in availableAreas"
              :key="a.id"
              :label="areaOptionLabel(a)"
              :value="a.id"
              :disabled="isAreaClosed(a.id)"
            />
          </el-select>
        </div>
        <div>
          <label class="block text-sm text-gray-600 mb-1">封区原因</label>
          <el-select v-model="form.reason" placeholder="设备故障 / 客流顶满" class="w-full" allow-create filterable>
            <el-option label="设备故障" value="设备故障" />
            <el-option label="客流顶满" value="客流顶满" />
            <el-option label="临时管控" value="临时管控" />
          </el-select>
        </div>
        <div>
          <button :disabled="submitting || !form.scenarioId || !form.areaId"
                  class="w-full px-4 py-2 rounded-lg text-white transition-colors disabled:bg-gray-300"
                  :class="form.scenarioId && form.areaId ? 'bg-red-500 hover:bg-red-600' : 'bg-gray-300'"
                  @click="submit">
            {{ submitting ? '提交中…' : '提交封区（原子）' }}
          </button>
        </div>
      </div>

      <!-- 封区前预览 -->
      <div v-if="form.scenarioId && form.areaId && selectedAreaCurrent" class="mt-5 rounded-lg border border-gray-200 bg-gray-50 p-4">
        <div class="text-sm font-medium text-gray-700 mb-2">封区前该区域在本场景的占用（将被清 0 并回灌）</div>
        <div class="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
          <div>在岗人员：<b>{{ selectedAreaCurrent.allocatedStaff }}</b> / 编制 {{ selectedAreaCurrent.staffQuota }}</div>
          <div>在场客流：<b>{{ selectedAreaCurrent.allocatedFlow }}</b> / 容量 {{ selectedAreaCurrent.maxCapacity }}</div>
          <div>剩余编制：<b :class="openStats.totalRemainingStaff < selectedAreaCurrent.allocatedStaff ? 'text-red-600' : 'text-green-600'">{{ openStats.totalRemainingStaff }}</b></div>
          <div>剩余容量：<b :class="openStats.totalRemainingFlow < selectedAreaCurrent.allocatedFlow ? 'text-red-600' : 'text-green-600'">{{ openStats.totalRemainingFlow }}</b></div>
        </div>
        <div v-if="willFail" class="mt-3 text-sm text-red-600">
          ⚠ 开放区域合计剩余额度吃不下本次回灌，提交将整单失败、区域不会关闭。
        </div>
      </div>
    </div>

    <!-- 封区单列表 -->
    <div class="bg-white rounded-lg shadow-sm p-6">
      <div class="flex items-center justify-between mb-4">
        <h3 class="text-lg font-semibold text-gray-800">封区单台账</h3>
        <div class="flex items-center gap-3">
          <el-select v-model="filterScenarioId" placeholder="全部场景" clearable class="w-48" @change="loadClosures">
            <el-option v-for="s in scenarios" :key="s.id" :label="s.scenarioName" :value="s.id" />
          </el-select>
          <button class="px-3 py-1.5 text-sm border rounded-lg hover:bg-gray-50" @click="reloadAll">刷新</button>
        </div>
      </div>

      <div v-if="closures.length === 0" class="text-center py-10 text-gray-500">暂无封区单</div>

      <div class="space-y-4">
        <div v-for="c in closures" :key="c.id" class="border rounded-lg overflow-hidden"
             :class="cardClass(c.status)">
          <!-- 单据头 -->
          <div class="flex flex-wrap items-center justify-between gap-3 px-4 py-3 bg-gray-50">
            <div class="flex items-center gap-3">
              <span class="text-xs text-gray-400">#{{ c.id }}</span>
              <span class="font-medium text-gray-800">{{ c.areaName }}</span>
              <el-tag :type="tagType(c.status)" effect="dark" size="small">{{ c.statusText }}</el-tag>
              <span v-if="c.live" class="text-xs text-red-600 font-medium">封区仍生效 · 禁调入/禁写回</span>
            </div>
            <div class="text-xs text-gray-500">{{ formatDate(c.createdAt) }}<span v-if="c.reason"> · {{ c.reason }}</span></div>
          </div>

          <div class="p-4 space-y-3">
            <!-- 数字概览 -->
            <div class="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm">
              <div class="rounded bg-gray-50 p-2">疏散人员<br><b>{{ c.evacuatedStaff }}</b></div>
              <div class="rounded bg-gray-50 p-2">疏散客流<br><b>{{ c.evacuatedFlow }}</b></div>
              <div class="rounded bg-gray-50 p-2">已回灌人员<br><b>{{ c.injectedStaff }}</b></div>
              <div class="rounded bg-gray-50 p-2">已回灌客流<br><b>{{ c.injectedFlow }}</b></div>
            </div>

            <!-- 成功：回灌落到哪些开放区域 -->
            <div v-if="c.injections && c.injections.length">
              <div class="text-sm font-medium text-gray-700 mb-2">回灌落到的开放区域</div>
              <table class="w-full text-sm">
                <thead>
                  <tr class="bg-gray-50 text-gray-600">
                    <th class="px-3 py-2 text-left">承接区域</th>
                    <th class="px-3 py-2 text-right">回灌人员</th>
                    <th class="px-3 py-2 text-right">回灌客流</th>
                    <th class="px-3 py-2 text-right">落账前人员/客流</th>
                    <th class="px-3 py-2 text-right">当时剩余编制/容量</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="i in c.injections" :key="i.id" class="border-b">
                    <td class="px-3 py-2">{{ i.receiverAreaName }}</td>
                    <td class="px-3 py-2 text-right">+{{ i.injectedStaff }}</td>
                    <td class="px-3 py-2 text-right">+{{ i.injectedFlow }}</td>
                    <td class="px-3 py-2 text-right text-gray-500">{{ i.staffBefore }} / {{ i.flowBefore }}</td>
                    <td class="px-3 py-2 text-right text-gray-500">{{ i.remainingStaffAtTime }} / {{ i.remainingFlowAtTime }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <!-- 失败回执 -->
            <div v-if="c.status === 'FAILED' && c.failure" class="rounded-lg border border-red-200 bg-red-50 p-3">
              <div class="text-sm font-medium text-red-700 mb-2">封区失败回执 · 区域保持可营业 · 旧分配未动</div>
              <div class="grid grid-cols-2 md:grid-cols-4 gap-3 text-sm mb-2">
                <div>需疏散人员：<b>{{ c.failure.evacuatedStaff }}</b></div>
                <div>需疏散客流：<b>{{ c.failure.evacuatedFlow }}</b></div>
                <div :class="c.failure.shortStaff > 0 ? 'text-red-600' : ''">还差人员编制：<b>{{ c.failure.shortStaff }}</b></div>
                <div :class="c.failure.shortFlow > 0 ? 'text-red-600' : ''">还差容量：<b>{{ c.failure.shortFlow }}</b></div>
              </div>
              <table class="w-full text-sm mb-2">
                <thead>
                  <tr class="bg-red-100 text-red-700">
                    <th class="px-3 py-1.5 text-left">卡住的承接区域</th>
                    <th class="px-3 py-1.5 text-right">已占人员/客流</th>
                    <th class="px-3 py-1.5 text-right">剩余编制/容量</th>
                    <th class="px-3 py-1.5 text-left">剩余量被谁占掉</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="r in c.failure.receivers" :key="r.areaId" class="border-b border-red-100">
                    <td class="px-3 py-1.5">{{ r.areaName }}</td>
                    <td class="px-3 py-1.5 text-right">{{ r.staffBefore }} / {{ r.flowBefore }}</td>
                    <td class="px-3 py-1.5 text-right"
                        :class="(r.remainingStaff === 0 || r.remainingFlow === 0) ? 'text-red-600 font-medium' : ''">
                      {{ r.remainingStaff }} / {{ r.remainingFlow }}
                    </td>
                    <td class="px-3 py-1.5 text-xs text-gray-600">
                      <span v-if="r.occupiedByClosureIds && r.occupiedByClosureIds.length">
                        封区单 {{ r.occupiedByClosureIds.map(x => '#' + x).join('、') }}
                      </span>
                      <span v-else>—</span>
                    </td>
                  </tr>
                  <tr v-if="c.failure.noOpenArea"><td colspan="4" class="px-3 py-1.5 text-red-600">没有任何仍开放的承接区域</td></tr>
                </tbody>
              </table>
              <div class="text-xs text-red-700 leading-relaxed">{{ c.failure.message }}</div>
            </div>

            <!-- 额度冲突 -->
            <div v-if="c.status === 'QUOTA_CONFLICT' && c.quotaConflict" class="rounded-lg border border-orange-300 bg-orange-50 p-3">
              <div class="text-sm font-medium text-orange-700 mb-2">额度冲突 · 禁止解封（需值班长手工作废；回灌数字不改）</div>
              <table class="w-full text-sm">
                <thead>
                  <tr class="bg-orange-100 text-orange-700">
                    <th class="px-3 py-1.5 text-left">承接区域</th>
                    <th class="px-3 py-1.5 text-right">本单回灌 人员/客流</th>
                    <th class="px-3 py-1.5 text-right">新编制/容量</th>
                    <th class="px-3 py-1.5 text-right">超出 人员/客流</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="q in c.quotaConflict.conflicts" :key="q.receiverAreaId" class="border-b border-orange-100">
                    <td class="px-3 py-1.5">{{ q.receiverAreaName }}</td>
                    <td class="px-3 py-1.5 text-right">{{ q.injectedStaff }} / {{ q.injectedFlow }}</td>
                    <td class="px-3 py-1.5 text-right">{{ q.newStaffQuota }} / {{ q.newMaxCapacity }}</td>
                    <td class="px-3 py-1.5 text-right text-red-600">
                      {{ q.staffOverflow ?? 0 }} / {{ q.flowOverflow ?? 0 }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <!-- 操作 -->
            <div class="flex items-center gap-2 pt-1">
              <button v-if="c.status === 'EFFECTIVE'"
                      class="px-3 py-1.5 text-sm rounded-lg border border-blue-400 text-blue-600 hover:bg-blue-50"
                      @click="reopen(c)">解封（撤回回灌并恢复营业）</button>
              <button v-if="c.status === 'QUOTA_CONFLICT'" disabled
                      class="px-3 py-1.5 text-sm rounded-lg bg-gray-200 text-gray-400 cursor-not-allowed"
                      title="额度冲突，禁止解封；请作废">解封（已禁用）</button>
              <button v-if="c.live"
                      class="px-3 py-1.5 text-sm rounded-lg border border-red-400 text-red-600 hover:bg-red-50"
                      @click="voidClosure(c)">值班长作废（回灌保留）</button>
              <span v-if="c.status === 'REOPENED' || c.status === 'VOIDED'" class="text-xs text-gray-400">
                {{ c.terminalNote ? '备注：' + c.terminalNote : '' }}
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
  areaApi, scenarioApi, closureApi,
  type StoreArea, type FlowScenario, type AreaClosureDTO, type AreaLoadDTO
} from '@/api'

const areas = ref<StoreArea[]>([])
const scenarios = ref<FlowScenario[]>([])
const closures = ref<AreaClosureDTO[]>([])
const loadRows = ref<AreaLoadDTO[]>([])
const submitting = ref(false)
const filterScenarioId = ref<number | undefined>(undefined)

const form = ref<{ scenarioId: number | undefined; areaId: number | undefined; reason: string }>({
  scenarioId: undefined,
  areaId: undefined,
  reason: '设备故障'
})

const loadAreas = async () => { areas.value = await areaApi.getAll() }
const loadScenarios = async () => { scenarios.value = await scenarioApi.getAll() }
const loadClosures = async () => {
  closures.value = await closureApi.list(filterScenarioId.value)
}
const reloadAll = async () => {
  await Promise.all([loadAreas(), loadScenarios()])
  if (form.value.scenarioId) await refreshLoad(form.value.scenarioId)
  await loadClosures()
}

const onScenarioChange = async (id: number) => {
  form.value.areaId = undefined
  loadRows.value = []
  await refreshLoad(id)
  await loadClosures()
}

const refreshLoad = async (scenarioId: number) => {
  try {
    loadRows.value = await scenarioApi.calculateLoad(scenarioId)
  } catch {
    loadRows.value = []
  }
}

const onAreaChange = () => {}

const liveClosedAreaIds = computed<Set<number>>(() => {
  const sid = form.value.scenarioId
  return new Set(
    closures.value
      .filter(c => c.live && (sid == null || c.scenarioId === sid))
      .map(c => c.areaId)
  )
})

const isAreaClosed = (areaId: number) => liveClosedAreaIds.value.has(areaId)

const availableAreas = computed(() => {
  if (!form.value.scenarioId) return areas.value
  return areas.value
})

const areaOptionLabel = (a: StoreArea) => {
  const closed = isAreaClosed(a.id) ? '（已封）' : ''
  return `${a.areaName}${closed} · 容量${a.maxCapacity}/编制${a.staffQuota}`
}

const selectedAreaCurrent = computed(() => {
  if (!form.value.areaId) return null
  const a = areas.value.find(x => x.id === form.value.areaId)
  const row = loadRows.value.find(x => x.areaId === form.value.areaId)
  if (!a) return null
  return {
    maxCapacity: a.maxCapacity,
    staffQuota: a.staffQuota,
    allocatedStaff: row?.allocatedStaff ?? 0,
    allocatedFlow: row?.currentFlow ?? 0
  }
})

const openStats = computed(() => {
  let totalRemainingStaff = 0
  let totalRemainingFlow = 0
  const sid = form.value.scenarioId
  for (const a of areas.value) {
    if (a.id === form.value.areaId || liveClosedAreaIds.value.has(a.id)) continue
    const row = sid ? loadRows.value.find(x => x.areaId === a.id) : undefined
    const staffBefore = row?.allocatedStaff ?? 0
    const flowBefore = row?.currentFlow ?? 0
    totalRemainingStaff += Math.max(0, a.staffQuota - staffBefore)
    totalRemainingFlow += Math.max(0, a.maxCapacity - flowBefore)
  }
  return { totalRemainingStaff, totalRemainingFlow }
})

const willFail = computed(() => {
  const cur = selectedAreaCurrent.value
  if (!cur) return false
  return openStats.value.totalRemainingStaff < cur.allocatedStaff ||
         openStats.value.totalRemainingFlow < cur.allocatedFlow
})

const submit = async () => {
  if (!form.value.scenarioId || !form.value.areaId) return
  try {
    await ElMessageBox.confirm(
      '提交封区将立即清 0 该区域人员/客流并回灌。若开放区域吃不下，整单失败、区域不会关闭。确认提交？',
      '确认临时封区', { confirmButtonText: '提交封区', cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  submitting.value = true
  try {
    const dto = await closureApi.submit({
      scenarioId: form.value.scenarioId,
      areaId: form.value.areaId,
      reason: form.value.reason
    })
    if (dto.status === 'EFFECTIVE') {
      ElMessage.success(`封区生效，已回灌到 ${dto.injections.length} 块开放区域`)
    } else {
      ElMessageBox.alert(dto.failure?.message ?? '封区失败，区域保持可营业', '封区失败 · 未关区', {
        confirmButtonText: '我知道了', type: 'error'
      })
    }
    await reloadAll()
  } catch (e: unknown) {
    const msg = (e as { response?: { data?: { message?: string } } })?.response?.data?.message
    ElMessage.error(msg || '提交失败')
  } finally {
    submitting.value = false
  }
}

const reopen = async (c: AreaClosureDTO) => {
  try {
    const { value } = await ElMessageBox.prompt('解封将撤回本单回灌并恢复区域营业，可填备注：', `解封封区单 #${c.id}`, {
      confirmButtonText: '确认解封', cancelButtonText: '取消', inputValue: ''
    })
    await closureApi.reopen(c.id, value || undefined)
    ElMessage.success('已解封')
    await reloadAll()
  } catch (err) {
    if (err !== 'cancel') {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      ElMessage.error(msg || '解封失败')
      await reloadAll()
    }
  }
}

const voidClosure = async (c: AreaClosureDTO) => {
  try {
    await ElMessageBox.confirm(
      '作废后被封区域恢复可营业，但已回灌到承接区域的人员/客流不会自动撤回。确认作废？',
      `值班长作废封区单 #${c.id}`, { confirmButtonText: '确认作废', cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('请填写作废说明（必填）：', '作废说明', {
      confirmButtonText: '确认作废', cancelButtonText: '取消', inputValue: '值班长手工作废'
    })
    await closureApi.voidClosure(c.id, value || '值班长手工作废')
    ElMessage.success('已作废，回灌保留')
    await reloadAll()
  } catch (err) {
    if (err !== 'cancel') {
      const msg = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      ElMessage.error(msg || '作废失败')
      await reloadAll()
    }
  }
}

const tagType = (s: AreaClosureDTO['status']) => {
  switch (s) {
    case 'EFFECTIVE': return 'danger'
    case 'FAILED': return 'info'
    case 'QUOTA_CONFLICT': return 'warning'
    case 'REOPENED': return 'success'
    case 'VOIDED': return 'info'
    default: return 'info'
  }
}

const cardClass = (s: AreaClosureDTO['status']) => {
  switch (s) {
    case 'EFFECTIVE': return 'border-red-300'
    case 'FAILED': return 'border-gray-300 opacity-90'
    case 'QUOTA_CONFLICT': return 'border-orange-400'
    default: return 'border-gray-200'
  }
}

const formatDate = (d: string) => d ? new Date(d).toLocaleString('zh-CN') : ''

onMounted(async () => {
  await Promise.all([loadAreas(), loadScenarios()])
  await loadClosures()
})
</script>
