package com.example.storeflow.dto;

import lombok.Data;

/**
 * 会签操作请求：测算岗确认 / 现场经理签收。operator 为操作人留痕，可空
 * （空时按角色默认「测算岗」/「现场经理」）。
 */
@Data
public class BatchActionRequest {

    private String operator;
}
