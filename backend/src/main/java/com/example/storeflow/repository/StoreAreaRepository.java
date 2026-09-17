package com.example.storeflow.repository;

import com.example.storeflow.entity.StoreArea;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StoreAreaRepository extends JpaRepository<StoreArea, Long> {

    Optional<StoreArea> findByAreaName(String areaName);

    List<StoreArea> findByRiskFlagTrue();

    List<StoreArea> findByRiskLevelGreaterThan(Integer riskLevel);

    /** 按 id 升序对一批区域加悲观写锁（统一加锁顺序，避免并发封区互相死锁）。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StoreArea a where a.id in :ids order by a.id asc")
    List<StoreArea> lockAllByIdInOrder(@Param("ids") List<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from StoreArea a where a.id = :id")
    Optional<StoreArea> lockById(@Param("id") Long id);
}
