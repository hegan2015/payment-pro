package com.qoder.payment.accounting.service;

import com.qoder.payment.accounting.dataobject.TransferDO;
import com.qoder.payment.accounting.mapper.TransferMapper;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * S-6 凭证不可篡改护栏：transfer_record.lines 落库后只读，任何修改拒绝。
 *
 * <p>凭证只追加（INSERT）：post/reverse/suspensePost 均不得 UPDATE 原 lines，
 * 冲正/挂账只能追加新凭证（REVERSE/SUSPENSE）。同 bizNo 重放且 lines 逐字一致
 * 视为幂等（调用方跳过重复过账）；lines 或凭证类型不一致一律拒绝。
 */
@Component
@RequiredArgsConstructor
public class VoucherGuard {

    /** 凭证类型：普通过账。 */
    public static final String NORMAL = "NORMAL";
    /** 凭证类型：红冲（AC-1，biz_no=RV+seq）。 */
    public static final String REVERSE = "REVERSE";
    /** 凭证类型：差错挂账/核销（S-6，SYS_SUSPENSE）。 */
    public static final String SUSPENSE = "SUSPENSE";

    private final TransferMapper transferMapper;

    /** 凭证类型推断：RV+seq 为冲正凭证（与 AC-1 命名约定一致），其余为普通凭证。 */
    public static String inferType(String bizNo) {
        return bizNo != null && bizNo.matches("RV\\d+") ? REVERSE : NORMAL;
    }

    /**
     * 追加前护栏：返回同 bizNo 既有凭证（null 表示可追加新凭证）。
     * 既有凭证存在时校验 lines/类型不可篡改，校验失败抛业务异常。
     */
    public TransferDO checkAppend(String bizNo, String linesJson, String voucherType) {
        TransferDO existing = transferMapper.selectByBizNo(bizNo);
        if (existing != null) {
            assertUnchanged(existing, linesJson, voucherType);
        }
        return existing;
    }

    /** 既有凭证不可篡改：lines 必须逐字一致，凭证类型不可变更（历史空值按 NORMAL 兼容）。 */
    public void assertUnchanged(TransferDO existing, String linesJson, String voucherType) {
        if (!Objects.equals(existing.getLines(), linesJson)) {
            throw new BizException(ResultCode.BIZ_ERROR, "凭证不可篡改，禁止修改已落库 lines: " + existing.getBizNo());
        }
        String existingType = existing.getVoucherType() == null ? NORMAL : existing.getVoucherType();
        if (voucherType != null && !existingType.equals(voucherType)) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "凭证类型不可变更: " + existing.getBizNo() + " " + existingType + " -> " + voucherType);
        }
    }
}