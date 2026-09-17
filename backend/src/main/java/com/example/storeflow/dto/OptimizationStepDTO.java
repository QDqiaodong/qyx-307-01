package com.example.storeflow.dto;

import lombok.Data;

@Data
public class OptimizationStepDTO {

    private Integer stepNumber;

    private String sourceArea;

    private String targetArea;

    private Integer staffTransfer;

    private Integer flowTransfer;

    private String description;

    private Double improvementRate;
}
