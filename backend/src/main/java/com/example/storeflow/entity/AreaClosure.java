package com.example.storeflow.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * 临时封区单。
 *
 * <p>一条封区单严格绑定「一个客流场景 + 一块被封区域」。提交是原子操作：
 * 只有当被封区域在该场景的人员/客流已清 0，且回灌已完整落到仍开放区域时，
 * 单据才是 {@link ClosureStatus#EFFECTIVE}；否则只落一条 {@link ClosureStatus#FAILED}
 * 回执，场景里的旧分配一行都不动。
 *
 * <p>该表只追加/按状态流转，不允许物理删除；失败单永久保留，关掉页面重开仍为失败，
 * 不会自己变成成功。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "area_closure")
public class AreaClosure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 绑定的客流场景。 */
    @Column(name = "scenario_id", nullable = false)
    private Long scenarioId;

    /** 被封区域。 */
    @Column(name = "area_id", nullable = false)
    private Long areaId;

    @Column(name = "area_name", nullable = false)
    private String areaName;

    /** 封区原因（设备故障 / 客流顶满等）。 */
    @Column(name = "reason", length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ClosureStatus status;

    // ===== 封区前快照（清空前固化，解封时据此还原；作废时不使用） =====

    /** 封区前该区域在场景里的在岗人员数。 */
    @Column(name = "evacuated_staff", nullable = false)
    private Integer evacuatedStaff = 0;

    /** 封区前该区域在场景里的客流数。 */
    @Column(name = "evacuated_flow", nullable = false)
    private Integer evacuatedFlow = 0;

    /** 实际成功回灌到开放区域的人员总数（成功时应等于 evacuatedStaff）。 */
    @Column(name = "injected_staff", nullable = false)
    private Integer injectedStaff = 0;

    /** 实际成功回灌到开放区域的客流总数（成功时应等于 evacuatedFlow）。 */
    @Column(name = "injected_flow", nullable = false)
    private Integer injectedFlow = 0;

    // ===== 失败回执（仅 FAILED） =====

    /** 失败回执：总体还差多少人员配额才能吃下回灌。 */
    @Column(name = "fail_short_staff")
    private Integer failShortStaff;

    /** 失败回执：总体还差多少容量才能吃下回灌。 */
    @Column(name = "fail_short_flow")
    private Integer failShortFlow;

    /**
     * 失败回执明细（JSON）：逐个承接区域给出剩余容量/编制、能吃多少、还差多少，
     * 以及被哪张别的封区单占掉的剩余量。结构见 ClosureFailureDTO。
     */
    @Lob
    @Column(name = "fail_receipt", columnDefinition = "TEXT")
    private String failReceipt;

    // ===== 额度冲突（EFFECTIVE -> QUOTA_CONFLICT） =====

    /**
     * 额度冲突明细（JSON）：是哪块承接区域、被改小到多少、回灌数字超出新容量/编制多少。
     * 结构见 QuotaConflictDTO；为 null 表示无冲突。
     */
    @Lob
    @Column(name = "quota_conflict", columnDefinition = "TEXT")
    private String quotaConflict;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 作废 / 解封操作人说明（值班长手工作废时填写）。 */
    @Column(name = "terminal_note", length = 500)
    private String terminalNote;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
