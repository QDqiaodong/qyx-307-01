package com.example.storeflow.repository;

import com.example.storeflow.entity.OptimizationBatchItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OptimizationBatchItemRepository extends JpaRepository<OptimizationBatchItem, Long> {

    List<OptimizationBatchItem> findByBatchIdOrderByAreaIdAsc(Long batchId);
}
