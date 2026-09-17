package com.example.storeflow.repository;

import com.example.storeflow.entity.BatchStatus;
import com.example.storeflow.entity.OptimizationBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OptimizationBatchRepository extends JpaRepository<OptimizationBatch, Long> {

    List<OptimizationBatch> findByScenarioIdOrderByIdDesc(Long scenarioId);

    List<OptimizationBatch> findAllByOrderByIdDesc();

    /** 某场景下处于指定状态集合的批次（如仍处在会签流程中的 PENDING_CONFIRM / CONFIRMED）。 */
    List<OptimizationBatch> findByScenarioIdAndStatusIn(Long scenarioId, List<BatchStatus> statuses);
}
