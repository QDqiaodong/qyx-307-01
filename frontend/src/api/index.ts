import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  timeout: 30000
})

api.interceptors.response.use(
  (response) => response.data,
  (error) => {
    console.error('API Error:', error)
    throw error
  }
)

export interface StoreArea {
  id: number
  areaName: string
  maxCapacity: number
  staffQuota: number
  description?: string
  riskFlag: boolean
  riskLevel: number
}

export interface AreaConfigDTO {
  id?: number
  areaName: string
  maxCapacity: number
  staffQuota: number
  description?: string
}

// ============================ 推演落地批次会签 ============================

export type OptimizationBatchStatus = 'DRAFT' | 'CONFIRMED' | 'SUPERSEDED' | 'LANDED'

export interface BatchItemDTO {
  id: number
  areaId: number
  areaName: string
  frozenMaxCapacity: number
  frozenStaffQuota: number
  beforeStaff: number
  beforeFlow: number
  afterStaff: number
  afterFlow: number
  beforeSaturationRate: number
  afterSaturationRate: number
  beforeOverloaded: boolean
  afterOverloaded: boolean
}

export interface BatchFailureDTO {
  code: string
  batchId: number
  scenarioId: number
  frozenRound: number
  currentRound: number
  status: string
  message: string
}

export interface OptimizationBatchDTO {
  id: number
  scenarioId: number
  scenarioName: string
  optimizationRound: number
  currentRound: number
  status: OptimizationBatchStatus
  statusText: string
  live: boolean
  current: boolean
  canAnalystConfirm: boolean
  canManagerSign: boolean
  beforeMaxSaturation: number
  afterMaxSaturation: number
  beforeAvgSaturation: number
  afterAvgSaturation: number
  beforeOverloadedCount: number
  afterOverloadedCount: number
  beforeTotalStaff: number
  beforeTotalFlow: number
  afterTotalStaff: number
  afterTotalFlow: number
  items: BatchItemDTO[]
  analystConfirmedAt?: string
  managerSignedAt?: string
  landedAt?: string
  analystNote?: string
  managerNote?: string
  supersedeReason?: string
  failure?: BatchFailureDTO | null
  createdAt: string
  updatedAt: string
}

export interface FlowScenario {
  id: number
  scenarioName: string
  festivalName: string
  estimatedTotalFlow: number
  createdAt: string
}

export interface FlowScenarioDTO {
  id?: number
  scenarioName: string
  festivalName: string
  estimatedTotalFlow: number
  initialAllocations?: AllocationDTO[]
}

export interface AllocationDTO {
  id?: number
  areaId: number
  allocatedStaff: number
  allocatedFlow: number
  areaName?: string
  maxCapacity?: number
  saturationRate?: number
  isOverloaded?: boolean
}

export interface AreaLoadDTO {
  areaId: number
  areaName: string
  currentFlow: number
  maxCapacity: number
  allocatedStaff: number
  staffQuota: number
  saturationRate: number
  staffLoadRate: number
  isOverloaded: boolean
  riskLevel: number
}

export interface OptimizationResultDTO {
  scenarioId: number
  scenarioName: string
  optimizationRound: number
  beforeAllocations: AllocationDTO[]
  afterAllocations: AllocationDTO[]
  beforeMaxSaturation: number
  afterMaxSaturation: number
  beforeAvgSaturation: number
  afterAvgSaturation: number
  beforeOverloadedCount: number
  afterOverloadedCount: number
  batchId: number
  optimizationSteps: OptimizationStepDTO[]
}

export interface OptimizationStepDTO {
  stepNumber: number
  sourceArea: string
  targetArea: string
  staffTransfer: number
  flowTransfer: number
  description: string
  improvementRate: number
}

// ============================ 临时封区回灌 ============================

export type ClosureStatus =
  | 'EFFECTIVE'
  | 'FAILED'
  | 'QUOTA_CONFLICT'
  | 'REOPENED'
  | 'VOIDED'

export interface ClosureSubmitRequest {
  scenarioId: number
  areaId: number
  reason?: string
}

export interface ReceiverCapacityView {
  areaId: number
  areaName: string
  staffBefore: number
  flowBefore: number
  staffQuota: number
  maxCapacity: number
  remainingStaff: number
  remainingFlow: number
  occupiedByClosureIds: number[]
}

export interface ClosureFailureDTO {
  scenarioId: number
  closedAreaId: number
  closedAreaName: string
  evacuatedStaff: number
  evacuatedFlow: number
  totalRemainingStaff: number
  totalRemainingFlow: number
  shortStaff: number
  shortFlow: number
  noOpenArea: boolean
  receivers: ReceiverCapacityView[]
  message: string
}

export interface QuotaConflictArea {
  receiverAreaId: number
  receiverAreaName: string
  injectedStaff: number
  injectedFlow: number
  newStaffQuota: number
  newMaxCapacity: number
  staffOverflow: number | null
  flowOverflow: number | null
}

export interface QuotaConflictDTO {
  conflicts: QuotaConflictArea[]
  message: string
}

export interface InjectionDTO {
  id: number
  closureId: number
  scenarioId: number
  receiverAreaId: number
  receiverAreaName: string
  injectedStaff: number
  injectedFlow: number
  staffBefore: number
  flowBefore: number
  remainingStaffAtTime: number
  remainingFlowAtTime: number
}

export interface AreaClosureDTO {
  id: number
  scenarioId: number
  scenarioName?: string
  areaId: number
  areaName: string
  reason?: string
  status: ClosureStatus
  statusText: string
  live: boolean
  unsealBlocked: boolean
  evacuatedStaff: number
  evacuatedFlow: number
  injectedStaff: number
  injectedFlow: number
  failure?: ClosureFailureDTO | null
  quotaConflict?: QuotaConflictDTO | null
  injections: InjectionDTO[]
  terminalNote?: string
  createdAt: string
  updatedAt: string
}

type ApiResult<T> = Promise<T>

export const areaApi = {
  getAll: (): ApiResult<StoreArea[]> => api.get('/areas'),
  getById: (id: number): ApiResult<StoreArea> => api.get(`/areas/${id}`),
  create: (data: AreaConfigDTO): ApiResult<StoreArea> => api.post('/areas', data),
  update: (id: number, data: AreaConfigDTO): ApiResult<StoreArea> => api.put(`/areas/${id}`, data),
  delete: (id: number): ApiResult<void> => api.delete(`/areas/${id}`),
  getRiskAreas: (): ApiResult<StoreArea[]> => api.get('/areas/risk')
}

export const scenarioApi = {
  getAll: (): ApiResult<FlowScenario[]> => api.get('/scenarios'),
  getById: (id: number): ApiResult<FlowScenario> => api.get(`/scenarios/${id}`),
  create: (data: FlowScenarioDTO): ApiResult<FlowScenario> => api.post('/scenarios', data),
  delete: (id: number): ApiResult<void> => api.delete(`/scenarios/${id}`),
  calculateLoad: (id: number): ApiResult<AreaLoadDTO[]> => api.get(`/scenarios/${id}/load`),
  optimize: (id: number): ApiResult<OptimizationResultDTO> => api.post(`/scenarios/${id}/optimize`),
  getOptimizedAllocations: (id: number): ApiResult<AllocationDTO[]> => api.get(`/scenarios/${id}/optimized`),
  updateAllocation: (id: number, data: { areaId: number; staff: number; flow: number }): ApiResult<void> =>
    api.put(`/scenarios/${id}/allocation`, data)
}

export const closureApi = {
  submit: (data: ClosureSubmitRequest): ApiResult<AreaClosureDTO> => api.post('/closures', data),
  list: (scenarioId?: number): ApiResult<AreaClosureDTO[]> =>
    api.get('/closures', { params: scenarioId ? { scenarioId } : {} }),
  getById: (id: number): ApiResult<AreaClosureDTO> => api.get(`/closures/${id}`),
  voidClosure: (id: number, note?: string): ApiResult<AreaClosureDTO> =>
    api.post(`/closures/${id}/void`, { note }),
  reopen: (id: number, note?: string): ApiResult<AreaClosureDTO> =>
    api.post(`/closures/${id}/reopen`, { note })
}

export const batchApi = {
  list: (scenarioId?: number): ApiResult<OptimizationBatchDTO[]> =>
    api.get('/batches', { params: scenarioId ? { scenarioId } : {} }),
  getById: (id: number): ApiResult<OptimizationBatchDTO> => api.get(`/batches/${id}`),
  confirm: (id: number, note?: string): ApiResult<OptimizationBatchDTO> =>
    api.post(`/batches/${id}/confirm`, { note }),
  sign: (id: number, note?: string): ApiResult<OptimizationBatchDTO> =>
    api.post(`/batches/${id}/sign`, { note })
}

export const healthApi = {
  check: (): ApiResult<unknown> => api.get('/health')
}

export default api
