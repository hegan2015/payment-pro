package com.qoder.tcc;

/** 全局事务状态机。 */
public enum GlobalStatus {
    /** 一阶段执行中 */
    TRYING,
    /**
     * 等待外部终态（异步渠道增量，CH-1）：一阶段全部 TRY_OK，但渠道尚未给出终态回执，
     * 资金保持冻结、不得进入二阶段提交；由 {@code resumeCommit}/{@code resumeCancel} 唯一驱动。
     * 同步渠道（默认）不会进入此状态，行为与增量前完全一致。
     */
    WAITING,
    /** 二阶段提交中（可异步重试） */
    CONFIRMING,
    /** 二阶段回滚中（可异步重试） */
    CANCELLING,
    /** 终态：已提交 */
    CONFIRMED,
    /** 终态：已回滚 */
    CANCELLED,
    /** 终态：补偿耗尽，需人工/对账介入 */
    DEAD
}