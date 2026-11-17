package com.qoder.payment.dtx;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.tcc.TccException;

/**
 * 异常桥接：tcc-core 通用异常 ↔ payment 业务异常体系。
 *
 * <p>保证组件化后对外错误码/错误消息与组件化前完全一致：
 * CONFLICT → A0409，BIZ_ERROR → B0001，其余错误码原样透传。
 */
public final class TccExceptions {

    private TccExceptions() {
    }

    /** 组件异常 → 业务异常（Facade 边界仍按 BizException 统一转 Result）。 */
    public static BizException toBiz(TccException e) {
        if (TccException.CONFLICT.equals(e.getCode())) {
            return new BizException(ResultCode.CONFLICT, e.getMessage());
        }
        if (TccException.BIZ_ERROR.equals(e.getCode())) {
            return new BizException(ResultCode.BIZ_ERROR, e.getMessage());
        }
        return new BizException(e.getCode(), e.getMessage());
    }

    /** 业务异常 → 组件异常（保留原错误码与消息，供协调器记录 fail_reason）。 */
    public static TccException fromBiz(BizException e) {
        return new TccException(e.getCode(), e.getMessage());
    }
}