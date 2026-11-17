package com.qoder.payment.common.result;

import com.qoder.payment.common.exception.BizException;
import com.qoder.tcc.TccException;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * Dubbo 边界统一异常转换（阿里规约：RPC 接口不向调用方抛异常，一律 Result 包装）。
 * 业务异常透传错误码，TCC 组件异常映射为业务错误码（CONFLICT→A0409、BIZ_ERROR→B0001），
 * 未知异常归一为系统错误并记录日志。
 */
@Slf4j
public final class FacadeSupport {

    private FacadeSupport() {
    }

    public static <T> Result<T> execute(Supplier<T> action) {
        try {
            return Result.ok(action.get());
        } catch (BizException e) {
            return Result.fail(e.getCode(), e.getMessage());
        } catch (TccException e) {
            if (TccException.CONFLICT.equals(e.getCode())) {
                return Result.fail(ResultCode.CONFLICT, e.getMessage());
            }
            if (TccException.BIZ_ERROR.equals(e.getCode())) {
                return Result.fail(ResultCode.BIZ_ERROR, e.getMessage());
            }
            return Result.fail(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Facade 未预期异常", e);
            return Result.fail(ResultCode.SYSTEM_ERROR);
        }
    }
}