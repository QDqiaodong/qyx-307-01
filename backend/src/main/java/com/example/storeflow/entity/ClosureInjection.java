package com.example.storeflow.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 封区回灌台账（不可变明细）。
 *
 * <p>每一行记录一张生效封区单回灌到「一块仍开放区域」的人员/客流数量，以及落账那一刻
 * 该承接区域当时的剩余容量和剩余编制配额。台账数字在任何后续区域额度调整中都不会被改写；
 * 若承接区域额度被改小导致超额，由封区单进入 QUOTA_CONFLICT 表达，而不是改这里的数字。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "closure_injection")
public class ClosureInjection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "closure_id", nullable = false)
    private Long closureId;

    @Column(name = "scenario_id", nullable = false)
    private Long scenarioId;

    /** 承接区域。 */
    @Column(name = "receiver_area_id", nullable = false)
    private Long receiverAreaId;

    @Column(name = "receiver_area_name", nullable = false)
    private String receiverAreaName;

    /** 回灌到该承接区域的人员数。 */
    @Column(name = "injected_staff", nullable = false)
    private Integer injectedStaff;

    /** 回灌到该承接区域的客流数。 */
    @Column(name = "injected_flow", nullable = false)
    private Integer injectedFlow;

    /** 落账前该承接区域在场景中已占用的人员（含之前别的封区单的回灌）。 */
    @Column(name = "staff_before", nullable = false)
    private Integer staffBefore;

    /** 落账前该承接区域在场景中已占用的客流（含之前别的封区单的回灌）。 */
    @Column(name = "flow_before", nullable = false)
    private Integer flowBefore;

    /** 落账那一刻该承接区域的剩余编制配额（quota - staffBefore）。 */
    @Column(name = "remaining_staff_at_time", nullable = false)
    private Integer remainingStaffAtTime;

    /** 落账那一刻该承接区域的剩余容量（capacity - flowBefore）。 */
    @Column(name = "remaining_flow_at_time", nullable = false)
    private Integer remainingFlowAtTime;
}
