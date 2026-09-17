package com.example.storeflow.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 区域核定容量 / 在岗编制配额变更事件。
 *
 * <p>由 {@code StoreAreaService.updateArea} 在同一事务内发布；
 * {@code ClosureService} 监听后检查已生效封区单是否因新额度而超额，
 * 若超额则把封区单置为「额度冲突、禁止解封」（不改台账回灌数字）。
 * 同事务监听保证：区域额度更新与封区单冲突标记原子提交。
 */
@Getter
public class AreaQuotaChangedEvent extends ApplicationEvent {

    private final Long areaId;
    private final Integer oldStaffQuota;
    private final Integer oldMaxCapacity;
    private final Integer newStaffQuota;
    private final Integer newMaxCapacity;

    public AreaQuotaChangedEvent(Object source, Long areaId,
                                 Integer oldStaffQuota, Integer oldMaxCapacity,
                                 Integer newStaffQuota, Integer newMaxCapacity) {
        super(source);
        this.areaId = areaId;
        this.oldStaffQuota = oldStaffQuota;
        this.oldMaxCapacity = oldMaxCapacity;
        this.newStaffQuota = newStaffQuota;
        this.newMaxCapacity = newMaxCapacity;
    }
}
