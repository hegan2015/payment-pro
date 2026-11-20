package com.qoder.payment.payment.service;

import com.qoder.tcc.GlobalStatus;

/**
 * 支付补单决策表（P-1）：dtx 全局状态 + 一阶段就绪度 + 渠道终态 → 唯一动作。
 *
 * <p>红线：补单绝不绕过 TCC 直接改支付单状态（除「无协调器记录」这一资金未动场景），
 * 一律经 resumeCommit/resumeCancel/drive 保证账务-订单-渠道三方一致；
 * 渠道状态未知（查询失败/无记录）时只等待，不臆断成功或失败。
 *
 * <pre>
 * dtx        一阶段     渠道状态                 动作
 * TRYING     任意       任意                     DRIVE（recover 现状：全 TRY_OK→提交，否则回滚）
 * WAITING    未全 OK    任意                     RESUME_CANCEL（空回滚/防悬挂由参与者模板保证）
 * WAITING    全 OK      CAPTURED/REFUNDED        RESUME_COMMIT
 * WAITING    全 OK      PREAUTH（同步渠道语义）  RESUME_COMMIT（扣款由我方 Confirm 驱动）
 * WAITING    全 OK      FAILED/VOIDED            RESUME_CANCEL
 * WAITING    全 OK      SUBMITTED/未知           WAIT（退避后再查）
 * WAITING    全 OK      无渠道分支（BALANCE）    RESUME_COMMIT
 * CONFIRMING/CANCELLING 任意 任意                DRIVE（挂起续驱）
 * CONFIRMED/CANCELLED   任意 任意                NOOP（终态已由完结监听落库）
 * DEAD/null  任意       任意                     MANUAL（差错/人工，不自动动账）
 * </pre>
 */
public final class PaymentReconcileDecision {

    public enum Action {
        /** 渠道已扣款：WAITING→CONFIRMING，二阶段过账 */
        RESUME_COMMIT,
        /** 渠道失败/一阶段未完成：WAITING→CANCELLING，解冻释放 */
        RESUME_CANCEL,
        /** 交给协调器按 recover 语义续驱（TRYING/CONFIRMING/CANCELLING）：调度补单不重复驱动，手动补单即时 drive */
        DRIVE,
        /** 渠道仍受理中或状态未知：退避后再查 */
        WAIT,
        /** DEAD/状态缺失：转人工与差错平台，不自动动账 */
        MANUAL,
        /** 已终态：幂等无副作用 */
        NOOP
    }

    private PaymentReconcileDecision() {
    }

    public static Action decide(GlobalStatus dtx, boolean allTryOk, String channelStatus, boolean channelBranch) {
        if (dtx == null || dtx == GlobalStatus.DEAD) {
            return Action.MANUAL;
        }
        switch (dtx) {
            case CONFIRMED:
            case CANCELLED:
                return Action.NOOP;
            case TRYING:
            case CONFIRMING:
            case CANCELLING:
                return Action.DRIVE;
            case WAITING:
                if (!allTryOk) {
                    return Action.RESUME_CANCEL;
                }
                if (!channelBranch) {
                    return Action.RESUME_COMMIT;
                }
                if ("CAPTURED".equals(channelStatus) || "REFUNDED".equals(channelStatus) || "PREAUTH".equals(channelStatus)) {
                    return Action.RESUME_COMMIT;
                }
                if ("FAILED".equals(channelStatus) || "VOIDED".equals(channelStatus)) {
                    return Action.RESUME_CANCEL;
                }
                return Action.WAIT;
            default:
                return Action.MANUAL;
        }
    }
}