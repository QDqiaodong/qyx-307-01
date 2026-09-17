package com.example.storeflow.repository;

import com.example.storeflow.entity.ClosureInjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClosureInjectionRepository extends JpaRepository<ClosureInjection, Long> {

    List<ClosureInjection> findByClosureIdOrderByIdAsc(Long closureId);

    List<ClosureInjection> findByScenarioIdOrderByIdAsc(Long scenarioId);

    /** 某承接区域在该场景上、来自仍生效（未撤回）封区单的回灌台账。 */
    @Query("select i from ClosureInjection i where i.scenarioId = :scenarioId "
            + "and i.receiverAreaId = :receiverAreaId and i.closureId in :liveClosureIds "
            + "order by i.id asc")
    List<ClosureInjection> findLiveInjections(@Param("scenarioId") Long scenarioId,
                                              @Param("receiverAreaId") Long receiverAreaId,
                                              @Param("liveClosureIds") List<Long> liveClosureIds);

    /** 回灌到指定承接区域、且来自给定封区单集合的全部台账（额度冲突检测用）。 */
    List<ClosureInjection> findByReceiverAreaIdAndClosureIdIn(@Param("receiverAreaId") Long receiverAreaId,
                                                              @Param("liveClosureIds") List<Long> liveClosureIds);
}
