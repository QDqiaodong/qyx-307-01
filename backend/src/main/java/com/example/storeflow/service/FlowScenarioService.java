package com.example.storeflow.service;

import com.example.storeflow.dto.*;
import com.example.storeflow.entity.FlowScenario;
import com.example.storeflow.entity.StaffAllocation;
import com.example.storeflow.entity.StoreArea;
import com.example.storeflow.exception.ResourceNotFoundException;
import com.example.storeflow.repository.FlowScenarioRepository;
import com.example.storeflow.repository.StaffAllocationRepository;
import com.example.storeflow.util.LoadCalculationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FlowScenarioService {

    private final FlowScenarioRepository scenarioRepository;
    private final StaffAllocationRepository allocationRepository;
    private final StoreAreaService areaService;
    private final ClosureService closureService;
    private final OptimizationBatchService optimizationBatchService;

    @Transactional
    public FlowScenario createScenario(FlowScenarioDTO dto) {
        FlowScenario scenario = new FlowScenario();
        scenario.setScenarioName(dto.getScenarioName());
        scenario.setFestivalName(dto.getFestivalName());
        scenario.setEstimatedTotalFlow(dto.getEstimatedTotalFlow());
        
        FlowScenario saved = scenarioRepository.save(scenario);
        
        if (dto.getInitialAllocations() != null) {
            for (AllocationDTO alloc : dto.getInitialAllocations()) {
                StaffAllocation allocation = new StaffAllocation();
                allocation.setScenarioId(saved.getId());
                allocation.setAreaId(alloc.getAreaId());
                allocation.setAllocatedStaff(alloc.getAllocatedStaff());
                allocation.setAllocatedFlow(alloc.getAllocatedFlow());
                allocation.setIsOptimized(false);
                allocationRepository.save(allocation);
            }
        }
        
        log.info("创建客流场景: {}", saved.getScenarioName());
        return saved;
    }

    @Transactional
    public void deleteScenario(Long id) {
        allocationRepository.deleteByScenarioId(id);
        scenarioRepository.deleteById(id);
        log.info("删除客流场景: {}", id);
    }

    public FlowScenario getScenarioById(Long id) {
        return scenarioRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("场景不存在: " + id));
    }

    public List<FlowScenario> getAllScenarios() {
        return scenarioRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<AreaLoadDTO> calculateLoad(Long scenarioId) {
        FlowScenario scenario = getScenarioById(scenarioId);
        List<StaffAllocation> allocations = allocationRepository.findByScenarioIdAndIsOptimized(scenarioId, false);
        List<StoreArea> areas = areaService.getAllAreas();
        
        Map<Long, String> areaNameMap = areas.stream()
                .collect(Collectors.toMap(StoreArea::getId, StoreArea::getAreaName));
        
        List<AllocationDTO> allocationDTOs = allocations.stream()
                .map(alloc -> {
                    AllocationDTO dto = new AllocationDTO();
                    dto.setAreaId(alloc.getAreaId());
                    dto.setAreaName(areaNameMap.get(alloc.getAreaId()));
                    dto.setAllocatedStaff(alloc.getAllocatedStaff());
                    dto.setAllocatedFlow(alloc.getAllocatedFlow());
                    return dto;
                })
                .collect(Collectors.toList());
        
        return LoadCalculationUtil.calculateAreaLoads(allocationDTOs, areas);
    }

    @Transactional
    public OptimizationResultDTO optimize(Long scenarioId) {
        // 锁场景行串行化推演与会签落账；推演只产出新轮次草稿并冻结批次，绝不直接改在用分配。
        FlowScenario scenario = scenarioRepository.lockById(scenarioId)
                .orElseThrow(() -> new ResourceNotFoundException("场景不存在: " + scenarioId));

        // 又跑一轮：上一轮所有在途批次（测算已确认但现场未签收的也算）一律作废，
        // 现场经理不能再拿旧批次去改场景。
        optimizationBatchService.supersedeLiveBatchesForNewRound(scenario);

        int round = (scenario.getOptimizationRound() == null ? 0 : scenario.getOptimizationRound()) + 1;
        scenario.setOptimizationRound(round);
        scenarioRepository.save(scenario);

        // 封控区域（仍生效封区单）在推演中既不能作为调入目标，也不能作为调出源，整体排除。
        Set<Long> closedAreaIds = closureService.liveClosedAreaIds(scenarioId);
        List<StaffAllocation> originalAllocations = allocationRepository.lockBaseAllocations(scenarioId)
                .stream().filter(a -> !closedAreaIds.contains(a.getAreaId())).collect(Collectors.toList());
        List<StoreArea> areas = areaService.getAllAreas().stream()
                .filter(a -> !closedAreaIds.contains(a.getId())).collect(Collectors.toList());

        Map<Long, StoreArea> areaMap = areas.stream()
                .collect(Collectors.toMap(StoreArea::getId, a -> a));
        Map<Long, String> areaNameMap = areas.stream()
                .collect(Collectors.toMap(StoreArea::getId, StoreArea::getAreaName));

        List<AllocationDTO> beforeDTOs = originalAllocations.stream()
                .map(alloc -> {
                    AllocationDTO dto = new AllocationDTO();
                    dto.setAreaId(alloc.getAreaId());
                    dto.setAreaName(areaNameMap.get(alloc.getAreaId()));
                    dto.setAllocatedStaff(alloc.getAllocatedStaff());
                    dto.setAllocatedFlow(alloc.getAllocatedFlow());
                    StoreArea area = areaMap.get(alloc.getAreaId());
                    if (area != null) {
                        dto.setMaxCapacity(area.getMaxCapacity());
                        dto.setSaturationRate(LoadCalculationUtil.calculateSaturationRate(alloc.getAllocatedFlow(), area.getMaxCapacity()));
                        dto.setIsOverloaded(LoadCalculationUtil.isOverloaded(dto.getSaturationRate()));
                    }
                    return dto;
                })
                .collect(Collectors.toList());

        OptimizationResultDTO result = new OptimizationResultDTO();
        result.setScenarioId(scenarioId);
        result.setScenarioName(scenario.getScenarioName());
        result.setOptimizationRound(round);
        result.setBeforeAllocations(beforeDTOs);

        // 旧轮次草稿全部废弃；新轮草稿随轮次写入，不影响在用分配（is_optimized=false 行不动）。
        allocationRepository.deleteByScenarioIdAndIsOptimized(scenarioId, true);

        List<OptimizationStepDTO> steps = new ArrayList<>();
        // after 复制时带上推演当时的容量，草稿行据此冻快照。
        List<AllocationDTO> afterDTOs = beforeDTOs.stream()
                .map(dto -> {
                    AllocationDTO copy = new AllocationDTO();
                    copy.setAreaId(dto.getAreaId());
                    copy.setAreaName(dto.getAreaName());
                    copy.setAllocatedStaff(dto.getAllocatedStaff());
                    copy.setAllocatedFlow(dto.getAllocatedFlow());
                    copy.setMaxCapacity(dto.getMaxCapacity());
                    return copy;
                })
                .collect(Collectors.toList());
        
        int totalStaff = afterDTOs.stream().mapToInt(AllocationDTO::getAllocatedStaff).sum();
        int totalFlow = afterDTOs.stream().mapToInt(AllocationDTO::getAllocatedFlow).sum();
        
        boolean optimized = true;
        int step = 0;
        
        while (optimized && step < 100) {
            optimized = false;
            
            List<AllocationDTO> sortedBySaturation = afterDTOs.stream()
                    .filter(d -> d.getMaxCapacity() != null && d.getMaxCapacity() > 0)
                    .sorted(Comparator.comparing(d -> LoadCalculationUtil.calculateSaturationRate(d.getAllocatedFlow(), d.getMaxCapacity()), Comparator.reverseOrder()))
                    .collect(Collectors.toList());
            
            if (sortedBySaturation.size() < 2) {
                break;
            }
            
            AllocationDTO highest = sortedBySaturation.get(0);
            double highestSaturation = LoadCalculationUtil.calculateSaturationRate(highest.getAllocatedFlow(), highest.getMaxCapacity());
            
            AllocationDTO lowest = sortedBySaturation.get(sortedBySaturation.size() - 1);
            double lowestSaturation = LoadCalculationUtil.calculateSaturationRate(lowest.getAllocatedFlow(), lowest.getMaxCapacity());
            
            if (highestSaturation < 0.85) {
                break;
            }
            
            if (highestSaturation - lowestSaturation > 0.1) {
                StoreArea highestArea = areaMap.get(highest.getAreaId());
                StoreArea lowestArea = areaMap.get(lowest.getAreaId());
                
                int staffTransfer = Math.min(
                        highest.getAllocatedStaff() - 1,
                        (lowestArea != null ? lowestArea.getStaffQuota() : 100) - lowest.getAllocatedStaff() + 1
                );
                
                int flowTransfer = Math.min(
                        (int) (highest.getAllocatedFlow() * 0.1),
                        (lowestArea != null ? lowestArea.getMaxCapacity() : 10000) - lowest.getAllocatedFlow()
                );
                
                if (staffTransfer > 0 || flowTransfer > 0) {
                    step++;
                    optimized = true;
                    
                    highest.setAllocatedStaff(highest.getAllocatedStaff() - staffTransfer);
                    highest.setAllocatedFlow(highest.getAllocatedFlow() - flowTransfer);
                    
                    lowest.setAllocatedStaff(lowest.getAllocatedStaff() + staffTransfer);
                    lowest.setAllocatedFlow(lowest.getAllocatedFlow() + flowTransfer);
                    
                    OptimizationStepDTO optStep = new OptimizationStepDTO();
                    optStep.setStepNumber(step);
                    optStep.setSourceArea(highest.getAreaName());
                    optStep.setTargetArea(lowest.getAreaName());
                    optStep.setStaffTransfer(staffTransfer);
                    optStep.setFlowTransfer(flowTransfer);
                    optStep.setDescription(String.format("从%s调配%d名人员和%d客流到%s",
                            highest.getAreaName(), staffTransfer, flowTransfer, lowest.getAreaName()));
                    optStep.setImprovementRate(Math.round((highestSaturation - LoadCalculationUtil.calculateSaturationRate(highest.getAllocatedFlow(), highest.getMaxCapacity())) * 10000) / 100.0);
                    steps.add(optStep);
                }
            }
        }
        
        for (AllocationDTO dto : afterDTOs) {
            StoreArea area = areaMap.get(dto.getAreaId());
            if (area != null) {
                dto.setSaturationRate(LoadCalculationUtil.calculateSaturationRate(dto.getAllocatedFlow(), area.getMaxCapacity()));
                dto.setIsOverloaded(LoadCalculationUtil.isOverloaded(dto.getSaturationRate()));
            }
        }
        
        result.setAfterAllocations(afterDTOs);
        result.setOptimizationSteps(steps);
        result.setBeforeMaxSaturation(LoadCalculationUtil.calculateMaxSaturation(beforeDTOs, areas));
        result.setAfterMaxSaturation(LoadCalculationUtil.calculateMaxSaturation(afterDTOs, areas));
        result.setBeforeAvgSaturation(LoadCalculationUtil.calculateAvgSaturation(beforeDTOs, areas));
        result.setAfterAvgSaturation(LoadCalculationUtil.calculateAvgSaturation(afterDTOs, areas));
        result.setBeforeOverloadedCount(LoadCalculationUtil.countOverloadedAreas(beforeDTOs, areas));
        result.setAfterOverloadedCount(LoadCalculationUtil.countOverloadedAreas(afterDTOs, areas));
        
        for (AllocationDTO alloc : afterDTOs) {
            StaffAllocation newAlloc = new StaffAllocation();
            newAlloc.setScenarioId(scenarioId);
            newAlloc.setAreaId(alloc.getAreaId());
            newAlloc.setAllocatedStaff(alloc.getAllocatedStaff());
            newAlloc.setAllocatedFlow(alloc.getAllocatedFlow());
            newAlloc.setIsOptimized(true);
            newAlloc.setOptimizationRound(round);
            StoreArea area = areaMap.get(alloc.getAreaId());
            AllocationDTO before = beforeDTOs.stream()
                    .filter(b -> b.getAreaId().equals(alloc.getAreaId())).findFirst().orElse(null);
            newAlloc.setBeforeStaff(before != null ? before.getAllocatedStaff() : alloc.getAllocatedStaff());
            newAlloc.setBeforeFlow(before != null ? before.getAllocatedFlow() : alloc.getAllocatedFlow());
            newAlloc.setFrozenMaxCapacity(area != null ? area.getMaxCapacity() : alloc.getMaxCapacity());
            newAlloc.setFrozenStaffQuota(area != null ? area.getStaffQuota() : null);
            allocationRepository.save(newAlloc);
        }

        for (AllocationDTO alloc : afterDTOs) {
            StoreArea area = areaMap.get(alloc.getAreaId());
            if (area != null) {
                double saturation = LoadCalculationUtil.calculateSaturationRate(alloc.getAllocatedFlow(), area.getMaxCapacity());
                int riskLevel = LoadCalculationUtil.determineRiskLevel(saturation);
                areaService.updateAreaRisk(area.getId(), riskLevel > 0, riskLevel);
            }
        }

        // 推演完一轮即冻成一份可复查的落地批次（同一事务）：冻结当时每块区域的核定容量、
        // 编制配额、优化前/优化后分配。批次未走完两段会签前，场景在用分配一律不动。
        OptimizationBatchDTO batch = optimizationBatchService.freezeFromLatestRound(scenarioId);
        result.setBatchId(batch.getId());

        log.info("完成场景优化: {}, 第{}轮, 优化步骤: {}, 已冻结落地批次#{}",
                scenario.getScenarioName(), round, steps.size(), batch.getId());
        return result;
    }

    public List<AllocationDTO> getOptimizedAllocations(Long scenarioId) {
        List<StaffAllocation> allocations = allocationRepository.findByScenarioIdAndIsOptimized(scenarioId, true);
        List<StoreArea> areas = areaService.getAllAreas();
        
        Map<Long, String> areaNameMap = areas.stream()
                .collect(Collectors.toMap(StoreArea::getId, StoreArea::getAreaName));
        Map<Long, Integer> areaCapacityMap = areas.stream()
                .collect(Collectors.toMap(StoreArea::getId, StoreArea::getMaxCapacity));
        
        return allocations.stream()
                .map(alloc -> {
                    AllocationDTO dto = new AllocationDTO();
                    dto.setAreaId(alloc.getAreaId());
                    dto.setAreaName(areaNameMap.get(alloc.getAreaId()));
                    dto.setAllocatedStaff(alloc.getAllocatedStaff());
                    dto.setAllocatedFlow(alloc.getAllocatedFlow());
                    Integer capacity = areaCapacityMap.get(alloc.getAreaId());
                    if (capacity != null) {
                        dto.setMaxCapacity(capacity);
                        dto.setSaturationRate(LoadCalculationUtil.calculateSaturationRate(alloc.getAllocatedFlow(), capacity));
                        dto.setIsOverloaded(LoadCalculationUtil.isOverloaded(dto.getSaturationRate()));
                    }
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Transactional
    public void updateAllocation(Long scenarioId, Long areaId, Integer staff, Integer flow) {
        // 封锁守卫：仍生效封区区域已清 0，禁止手工把人员/客流写回去。
        closureService.assertCanWriteAllocation(scenarioId, areaId);

        List<StaffAllocation> allocations = allocationRepository.findByScenarioIdAndIsOptimized(scenarioId, false);
        
        Optional<StaffAllocation> existing = allocations.stream()
                .filter(a -> a.getAreaId().equals(areaId))
                .findFirst();
        
        if (existing.isPresent()) {
            StaffAllocation alloc = existing.get();
            alloc.setAllocatedStaff(staff);
            alloc.setAllocatedFlow(flow);
            allocationRepository.save(alloc);
        } else {
            StaffAllocation alloc = new StaffAllocation();
            alloc.setScenarioId(scenarioId);
            alloc.setAreaId(areaId);
            alloc.setAllocatedStaff(staff);
            alloc.setAllocatedFlow(flow);
            alloc.setIsOptimized(false);
            allocationRepository.save(alloc);
        }
        
        log.info("更新分配: 场景{}, 区域{}, 人员{}, 客流{}", scenarioId, areaId, staff, flow);
    }
}
