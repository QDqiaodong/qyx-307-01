package com.example.storeflow.repository;

import com.example.storeflow.entity.FlowScenario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FlowScenarioRepository extends JpaRepository<FlowScenario, Long> {
    
    List<FlowScenario> findByFestivalName(String festivalName);
    
    List<FlowScenario> findAllByOrderByCreatedAtDesc();
}
