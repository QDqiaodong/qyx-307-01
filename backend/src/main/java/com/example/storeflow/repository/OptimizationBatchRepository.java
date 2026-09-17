package com.example.storeflow.repository;

import com.example.storeflow.entity.BatchStatus;
import com.example.storeflow.entity.OptimizationBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OptimizationBatchRepository extends JpaRepository<OptimizationBatch, Long> {

    List<OptimizationBatch> findByScenarioIdOrderByOptimizationRoundDescIdDesc(Long scenarioId);

    /** 某一轮推演冻结出来的最新批次（按 id 倒序取首条）。 */
    List<OptimizationBatch> findByScenarioIdAndOptimizationRoundOrderByIdDesc(Long scenarioId, Integer round);

    /**
     * 悲观锁锁定该场景上一批在途批次（DRAFT / CONFIRMED）。
     * 推演作废旧批次、测算确认、现场签收在此串行化，避免「签收旧批次」与「新一轮作废」并发交错。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from OptimizationBatch b where b.scenarioId = :scenarioId and b.status in :statuses order by b.id asc")
    List<OptimizationBatch> lockLiveByScenarioId(@Param("scenarioId") Long scenarioId,
                                                 @Param("statuses") List<BatchStatus> statuses);

    @Query("select b from OptimizationBatch b where b.scenarioId = :scenarioId and b.status in :statuses order by b.id desc")
    List<OptimizationBatch> findLiveByScenarioId(@Param("scenarioId") Long scenarioId,
                                                 @Param("statuses") List<BatchStatus> statuses);
}
