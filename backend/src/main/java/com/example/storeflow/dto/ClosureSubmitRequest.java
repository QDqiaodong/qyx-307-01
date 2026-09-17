package com.example.storeflow.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 提交临时封区请求：选要封的区域 + 绑正在用的客流场景。
 */
@Data
public class ClosureSubmitRequest {

    @NotNull(message = "客流场景ID不能为空")
    @Positive(message = "客流场景ID必须为正数")
    private Long scenarioId;

    @NotNull(message = "被封区域ID不能为空")
    @Positive(message = "被封区域ID必须为正数")
    private Long areaId;

    /** 封区原因：设备故障 / 客流顶满等。 */
    private String reason;
}
