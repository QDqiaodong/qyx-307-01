package com.example.storeflow.dto;

import lombok.Data;

/** 会签动作请求体（测算岗确认 / 现场经理签收共用，备注可选）。 */
@Data
public class BatchSignRequest {

    private String note;
}
