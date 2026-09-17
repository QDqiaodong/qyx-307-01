package com.example.storeflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

@Data
public class FlowScenarioDTO {

    private Long id;

    @NotBlank(message = "场景名称不能为空")
    private String scenarioName;

    @NotBlank(message = "节日名称不能为空")
    private String festivalName;

    @NotNull(message = "预估总客流不能为空")
    @Positive(message = "预估总客流必须为正数")
    private Integer estimatedTotalFlow;

    private List<AllocationDTO> initialAllocations;
}
