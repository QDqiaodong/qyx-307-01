package com.example.storeflow.dto;

import lombok.Data;

/**
 * 旧批次不能再落地的失败回执（HTTP 409）。
 *
 * <p>必须让人一眼看清：是哪一份批次（{@link #batchId}）、它冻的是哪一轮推演
 * （{@link #frozenRound}）、场景现在已经是哪一轮（{@link #currentRound}）。
 */
@Data
public class BatchFailureDTO {

    /** 机器可读错误码：BATCH_SUPERSEDED / BATCH_NOT_CONFIRMED / BATCH_NOT_LIVE 等。 */
    private String code;

    private Long batchId;

    private Long scenarioId;

    /** 这份批次冻住的推演轮次。 */
    private Integer frozenRound;

    /** 场景当前已执行到的推演轮次（比冻住的轮次新，说明又跑过推演）。 */
    private Integer currentRound;

    private String status;

    private String message;
}
