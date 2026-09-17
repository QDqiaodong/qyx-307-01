package com.example.storeflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class AreaConfigDTO {

    private Long id;

    @NotBlank(message = "区域名称不能为空")
    private String areaName;

    @NotNull(message = "最大接待客流不能为空")
    @Positive(message = "最大接待客流必须为正数")
    private Integer maxCapacity;

    @NotNull(message = "在岗人员配额不能为空")
    @Positive(message = "在岗人员配额必须为正数")
    private Integer staffQuota;

    private String description;
}
