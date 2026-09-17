package com.example.storeflow.repository;

import com.example.storeflow.entity.StaffAllocation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StaffAllocationRepository extends JpaRepository<StaffAllocation, Long> {

    List<StaffAllocation> findByScenarioId(Long scenarioId);

    List<StaffAllocation> findByScenarioIdAndIsOptimized(Long scenarioId, Boolean isOptimized);

    /** 某轮推演冻出的优化后草稿行。 */
    List<StaffAllocation> findByScenarioIdAndIsOptimizedAndOptimizationRound(
            Long scenarioId, Boolean isOptimized, Integer optimizationRound);

    /**
     * 悲观写锁锁定该场景在用分配行（is_optimized = false）。
     *
     * <p>封区提交事务内最先调用：配合场景行锁，保证同一场景上的两次封区提交严格串行，
     * 从而让“都指向同一块仅剩开放区域”的两单中只有一单能成功落账。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StaffAllocation a where a.scenarioId = :scenarioId and a.isOptimized = false order by a.areaId asc")
    List<StaffAllocation> lockBaseAllocations(@Param("scenarioId") Long scenarioId);

    @Query("select a from StaffAllocation a where a.scenarioId = :scenarioId "
            + "and a.areaId = :areaId and a.isOptimized = false")
    StaffAllocation findBaseAllocation(@Param("scenarioId") Long scenarioId,
                                       @Param("areaId") Long areaId);

    void deleteByScenarioId(Long scenarioId);

    void deleteByScenarioIdAndIsOptimized(Long scenarioId, Boolean isOptimized);
}
