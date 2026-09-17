package com.example.storeflow.repository;

import com.example.storeflow.entity.FlowScenario;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FlowScenarioRepository extends JpaRepository<FlowScenario, Long> {

    List<FlowScenario> findByFestivalName(String festivalName);

    List<FlowScenario> findAllByOrderByCreatedAtDesc();

    /**
     * 悲观写锁锁定场景行：串行化同一场景上的「优化推演 / 批次确认 / 批次签收落地」，
     * 保证推演轮次单调递增、同一份批次的落地只发生一次。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FlowScenario s where s.id = :id")
    Optional<FlowScenario> lockScenario(@Param("id") Long id);
}
