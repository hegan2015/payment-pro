package com.qoder.tcc;

/** 全局事务状态机。 */
public enum GlobalStatus {
    /** 一阶段执行中 */
    TRYING,
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