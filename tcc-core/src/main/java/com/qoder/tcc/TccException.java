package com.qoder.tcc;

/**
 * TCC 框架通用业务异常（零业务耦合，替代具体项目的业务异常体系）。
 *
 * <p>语义分两类：
 * <ul>
 *   <li>{@link #CONFLICT}：冲突类——当前状态不允许该操作（幂等处理中、悬挂拒绝、全局事务已存在等）；</li>
 *   <li>{@link #BIZ_ERROR}：业务错误类——业务处理失败（Try 失败、分支状态非法等）。</li>
 * </ul>
 * 接入方可在适配层把本异常映射为自身错误码体系（如 payment-pro 的 BizException/ResultCode）。
 */
public class TccException extends RuntimeException {

    /** 冲突类错误码常量。 */
    public static final String CONFLICT = "CONFLICT";
    /** 业务错误类错误码常量。 */
    public static final String BIZ_ERROR = "BIZ_ERROR";

    private final String code;

    public TccException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}