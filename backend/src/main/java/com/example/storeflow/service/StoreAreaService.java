package com.example.storeflow.service;

import com.example.storeflow.dto.AreaConfigDTO;
import com.example.storeflow.entity.StoreArea;
import com.example.storeflow.event.AreaQuotaChangedEvent;
import com.example.storeflow.repository.StoreAreaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoreAreaService {

    private final StoreAreaRepository storeAreaRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    private static final String REDIS_KEY_PREFIX = "store:area:";
    private static final String REDIS_SORTED_SET_KEY = "store:area:capacity:sorted";

    @Transactional
    public StoreArea createArea(AreaConfigDTO dto) {
        StoreArea area = new StoreArea();
        area.setAreaName(dto.getAreaName());
        area.setMaxCapacity(dto.getMaxCapacity());
        area.setStaffQuota(dto.getStaffQuota());
        area.setDescription(dto.getDescription());
        
        StoreArea saved = storeAreaRepository.save(area);
        cacheArea(saved);
        log.info("创建区域: {}", saved.getAreaName());
        return saved;
    }

    @Transactional
    public StoreArea updateArea(Long id, AreaConfigDTO dto) {
        // 锁区域行，与封区提交/解封互斥，避免额度变更和回灌落账交错。
        StoreArea area = storeAreaRepository.lockById(id)
                .orElseThrow(() -> new RuntimeException("区域不存在: " + id));

        Integer oldStaffQuota = area.getStaffQuota();
        Integer oldMaxCapacity = area.getMaxCapacity();

        area.setAreaName(dto.getAreaName());
        area.setMaxCapacity(dto.getMaxCapacity());
        area.setStaffQuota(dto.getStaffQuota());
        area.setDescription(dto.getDescription());

        StoreArea updated = storeAreaRepository.save(area);
        cacheArea(updated);

        // 同事务发布：若该区域是某生效封区单的承接区域且被改小，对应封区单进入额度冲突。
        eventPublisher.publishEvent(new AreaQuotaChangedEvent(this, id,
                oldStaffQuota, oldMaxCapacity,
                updated.getStaffQuota(), updated.getMaxCapacity()));

        log.info("更新区域: {}", updated.getAreaName());
        return updated;
    }

    @Transactional
    public void deleteArea(Long id) {
        StoreArea area = storeAreaRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("区域不存在: " + id));
        
        storeAreaRepository.delete(area);
        redisTemplate.delete(REDIS_KEY_PREFIX + id);
        log.info("删除区域: {}", area.getAreaName());
    }

    public Optional<StoreArea> getAreaById(Long id) {
        String cacheKey = REDIS_KEY_PREFIX + id;
        Object cached = redisTemplate.opsForValue().get(cacheKey);
        
        if (cached instanceof StoreArea) {
            return Optional.of((StoreArea) cached);
        }
        
        Optional<StoreArea> area = storeAreaRepository.findById(id);
        area.ifPresent(this::cacheArea);
        return area;
    }

    public List<StoreArea> getAllAreas() {
        Set<Object> cachedAreas = redisTemplate.opsForZSet().range(REDIS_SORTED_SET_KEY, 0, -1);
        if (cachedAreas != null && !cachedAreas.isEmpty()) {
            List<StoreArea> result = new java.util.ArrayList<>();
            for (Object areaName : cachedAreas) {
                Long id = findAreaIdByName((String) areaName);
                if (id != null) {
                    getAreaById(id).ifPresent(result::add);
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        List<StoreArea> areas = storeAreaRepository.findAll();
        areas.forEach(this::cacheArea);
        return areas;
    }

    private Long findAreaIdByName(String areaName) {
        return storeAreaRepository.findByAreaName(areaName)
                .map(StoreArea::getId)
                .orElse(null);
    }

    public List<StoreArea> getRiskAreas() {
        return storeAreaRepository.findByRiskFlagTrue();
    }

    private void cacheArea(StoreArea area) {
        String cacheKey = REDIS_KEY_PREFIX + area.getId();
        redisTemplate.opsForValue().set(cacheKey, area);
        
        redisTemplate.opsForZSet().add(REDIS_SORTED_SET_KEY, 
                area.getAreaName(), area.getMaxCapacity());
    }

    @Transactional
    public void updateAreaRisk(Long id, Boolean riskFlag, Integer riskLevel) {
        StoreArea area = storeAreaRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("区域不存在: " + id));
        
        area.setRiskFlag(riskFlag);
        area.setRiskLevel(riskLevel);
        storeAreaRepository.save(area);
        cacheArea(area);
        log.info("更新区域风险状态: {} - 风险标志: {}, 风险等级: {}", 
                area.getAreaName(), riskFlag, riskLevel);
    }
}
