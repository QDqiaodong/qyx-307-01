package com.example.storeflow.util;

import com.example.storeflow.dto.AllocationDTO;
import com.example.storeflow.dto.AreaLoadDTO;
import com.example.storeflow.entity.StoreArea;

import java.util.List;
import java.util.stream.Collectors;

public class LoadCalculationUtil {

    private static final double OVERLOAD_THRESHOLD = 0.85;
    private static final double WARNING_THRESHOLD = 0.70;
    private static final double HIGH_RISK_THRESHOLD = 1.0;

    public static Double calculateSaturationRate(Integer currentFlow, Integer maxCapacity) {
        if (maxCapacity == null || maxCapacity <= 0) {
            return 0.0;
        }
        return Math.round((currentFlow * 100.0 / maxCapacity) * 100) / 100.0;
    }

    public static Double calculateStaffLoadRate(Integer allocatedStaff, Integer staffQuota) {
        if (staffQuota == null || staffQuota <= 0) {
            return 0.0;
        }
        return Math.round((allocatedStaff * 100.0 / staffQuota) * 100) / 100.0;
    }

    public static Boolean isOverloaded(Double saturationRate) {
        return saturationRate != null && saturationRate >= OVERLOAD_THRESHOLD;
    }

    public static Integer determineRiskLevel(Double saturationRate) {
        if (saturationRate == null) {
            return 0;
        }
        if (saturationRate >= HIGH_RISK_THRESHOLD) {
            return 3;
        } else if (saturationRate >= OVERLOAD_THRESHOLD) {
            return 2;
        } else if (saturationRate >= WARNING_THRESHOLD) {
            return 1;
        }
        return 0;
    }

    public static List<AreaLoadDTO> calculateAreaLoads(List<AllocationDTO> allocations, List<StoreArea> areas) {
        return allocations.stream().map(alloc -> {
            AreaLoadDTO dto = new AreaLoadDTO();
            StoreArea area = areas.stream()
                    .filter(a -> a.getId().equals(alloc.getAreaId()))
                    .findFirst()
                    .orElse(null);
            
            dto.setAreaId(alloc.getAreaId());
            dto.setAreaName(alloc.getAreaName());
            dto.setCurrentFlow(alloc.getAllocatedFlow());
            dto.setAllocatedStaff(alloc.getAllocatedStaff());
            
            if (area != null) {
                dto.setMaxCapacity(area.getMaxCapacity());
                dto.setStaffQuota(area.getStaffQuota());
                dto.setSaturationRate(calculateSaturationRate(alloc.getAllocatedFlow(), area.getMaxCapacity()));
                dto.setStaffLoadRate(calculateStaffLoadRate(alloc.getAllocatedStaff(), area.getStaffQuota()));
                dto.setIsOverloaded(isOverloaded(dto.getSaturationRate()));
                dto.setRiskLevel(determineRiskLevel(dto.getSaturationRate()));
            }
            
            return dto;
        }).collect(Collectors.toList());
    }

    public static double calculateMaxSaturation(List<AllocationDTO> allocations, List<StoreArea> areas) {
        return allocations.stream()
                .mapToDouble(alloc -> {
                    StoreArea area = areas.stream()
                            .filter(a -> a.getId().equals(alloc.getAreaId()))
                            .findFirst()
                            .orElse(null);
                    return area != null ? calculateSaturationRate(alloc.getAllocatedFlow(), area.getMaxCapacity()) : 0;
                })
                .max()
                .orElse(0);
    }

    public static double calculateAvgSaturation(List<AllocationDTO> allocations, List<StoreArea> areas) {
        return allocations.stream()
                .mapToDouble(alloc -> {
                    StoreArea area = areas.stream()
                            .filter(a -> a.getId().equals(alloc.getAreaId()))
                            .findFirst()
                            .orElse(null);
                    return area != null ? calculateSaturationRate(alloc.getAllocatedFlow(), area.getMaxCapacity()) : 0;
                })
                .average()
                .orElse(0);
    }

    public static int countOverloadedAreas(List<AllocationDTO> allocations, List<StoreArea> areas) {
        return (int) allocations.stream()
                .filter(alloc -> {
                    StoreArea area = areas.stream()
                            .filter(a -> a.getId().equals(alloc.getAreaId()))
                            .findFirst()
                            .orElse(null);
                    return area != null && isOverloaded(calculateSaturationRate(alloc.getAllocatedFlow(), area.getMaxCapacity()));
                })
                .count();
    }
}
