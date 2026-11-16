package com.qoder.payment.dtx;

/** 协调器侧分支状态。 */
public enum BranchStatus {
    INIT,
    TRY_OK,
    TRY_FAIL,
    CONFIRMED,
    CANCELLED
}