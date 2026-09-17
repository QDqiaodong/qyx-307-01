package com.example.storeflow.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "store_area")
public class StoreArea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "area_name", nullable = false, unique = true)
    private String areaName;

    @Column(name = "max_capacity", nullable = false)
    private Integer maxCapacity;

    @Column(name = "staff_quota", nullable = false)
    private Integer staffQuota;

    @Column(name = "description")
    private String description;

    @Column(name = "risk_flag")
    private Boolean riskFlag = false;

    @Column(name = "risk_level")
    private Integer riskLevel = 0;
}
