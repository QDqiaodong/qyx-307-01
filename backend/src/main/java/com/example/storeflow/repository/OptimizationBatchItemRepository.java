package com.example.storeflow.repository;

import com.example.storeflow.entity.OptimizationBatchItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OptimizationBatchItemRepository extends JpaRepository<OptimizationBatchItem, Long> {

    /** 批次的逐区域冻结快照（按区域 id 排序，展示/落地都按此顺序）。 */
    List<OptimizationBatchItem> findByBatchIdOrderByAreaIdAsc(Long batchId);
}
