package com.example.storeflow.repository;

import com.example.storeflow.entity.AreaClosure;
import com.example.storeflow.entity.ClosureStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AreaClosureRepository extends JpaRepository<AreaClosure, Long> {

    List<AreaClosure> findByScenarioIdOrderByCreatedAtDesc(Long scenarioId);

    List<AreaClosure> findAllByOrderByCreatedAtDesc();

    /** 某场景下所有「仍生效」封区单（EFFECTIVE + QUOTA_CONFLICT）。 */
    @Query("select c from AreaClosure c where c.scenarioId = :scenarioId and c.status in :statuses")
    List<AreaClosure> findLiveByScenarioId(@Param("scenarioId") Long scenarioId,
                                           @Param("statuses") List<ClosureStatus> statuses);

    @Query("select c from AreaClosure c where c.status in :statuses")
    List<AreaClosure> findAllLive(@Param("statuses") List<ClosureStatus> statuses);

    /** 同一对（场景，被封区域）是否已有仍生效的封区单。 */
    @Query("select count(c) from AreaClosure c where c.scenarioId = :scenarioId "
            + "and c.areaId = :areaId and c.status in :statuses")
    long countLiveByScenarioAndArea(@Param("scenarioId") Long scenarioId,
                                    @Param("areaId") Long areaId,
                                    @Param("statuses") List<ClosureStatus> statuses);

    /** 悲观写锁锁定场景行，串行化同一场景上的并发封区提交（配合区域锁）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from FlowScenario s where s.id = :id")
    Optional<com.example.storeflow.entity.FlowScenario> lockScenario(@Param("id") Long id);
}
