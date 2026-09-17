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

    /** 悲观写锁锁定场景行，串行化推演/会签落账等改动场景分配的操作。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FlowScenario s where s.id = :id")
    Optional<FlowScenario> lockById(@Param("id") Long id);
}
