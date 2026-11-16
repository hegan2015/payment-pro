package com.qoder.payment.dtx;

/** 参与者侧分支状态（含空回滚标记）。 */
public enum ParticipantStatus {
    TRYING,
    CONFIRMED,
    /** 已回滚；Try 未到达时直接落此标记，兼作防悬挂屏障 */
    CANCELLED
}