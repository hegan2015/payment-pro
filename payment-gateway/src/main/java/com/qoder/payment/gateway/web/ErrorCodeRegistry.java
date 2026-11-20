package com.qoder.payment.gateway.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G-3 统一错误码注册表（网关侧单一事实来源，供错误体映射 + API 文档 + 控制台码表查询）。
 *
 * <p><b>additive 红线</b>：既有 A/B/C 三段式码（{@code com.qoder.payment.common.result.ResultCode}）
 * 的码值/文案/HTTP 映射<b>一字不改</b>；本表只在其上<b>追加</b>子码与元数据（category/说明）。
 * 成功响应结构不受本类影响（网关裸数据风格、account.balance 裸数字 long、Dubbo Result 信封均不变）。
 *
 * <p>三段式语义：A=用户端错误，B=本系统错误，C=第三方/下游错误。
 */
public final class ErrorCodeRegistry {

    /** 错误码定义：码值 / 默认文案 / HTTP 状态 / 分类 / 说明。 */
    public record ErrorDef(String code, String message, int httpStatus, String category, String description) {
    }

    /** 既有码（与 ResultCode 枚举逐一对齐，文案/状态不变）。 */
    private static final List<ErrorDef> LEGACY = List.of(
            new ErrorDef("00000", "成功", 200, "SUCCESS", "成功（Dubbo Result 信封 code；网关成功响应为裸数据）"),
            new ErrorDef("A0400", "请求参数错误", 400, "A", "参数缺失/非法、JSON 不合法、OpenAPI 验签失败/timestamp 超窗"),
            new ErrorDef("A0401", "未登录或会话已过期", 401, "A", "控制台 /api/** 缺失或失效 Bearer"),
            new ErrorDef("A0403", "无权限执行该操作", 403, "A", "RBAC 权限点不足；敏感操作缺 X-Step-Up-Token"),
            new ErrorDef("A0404", "资源不存在", 404, "A", "路径或业务对象不存在"),
            new ErrorDef("A0409", "当前状态不允许该操作", 409, "A", "状态机冲突；OpenAPI nonce 重放"),
            new ErrorDef("A0429", "请求过于频繁", 429, "A", "登录失败限流 / Sentinel QPS 与热点参数限流（受理前拒绝）"),
            new ErrorDef("B0001", "业务处理失败", 400, "B", "域内业务规则拒绝"),
            new ErrorDef("B0002", "系统内部错误", 500, "B", "未预期异常（响应不含堆栈/下游地址）"),
            new ErrorDef("B0003", "服务熔断降级", 503, "B", "Sentinel 熔断/降级（受理前拒绝，不产生在途单）"),
            new ErrorDef("C0001", "下游服务异常", 502, "C", "Dubbo 下游不可达/异常（细节只落服务端日志）"));

    /** 新增子码（只 additive，不与既有码冲突）。 */
    private static final List<ErrorDef> ADDITIVE = List.of(
            new ErrorDef("A0428", "REVIEW_PENDING 大额操作需双人复核", 400, "A",
                    "S-7 大额退款/冲正未复核（PENDING/REJECTED）拒绝，绝不动账；响应附 reviewId/reviewStatus"),
            new ErrorDef("A0405", "请求方法不支持", 405, "A", "HTTP method 与端点不匹配"),
            new ErrorDef("A0413", "请求体过大", 413, "A", "批量操作超出单批上限（如批量重发 > 100 条）"),
            new ErrorDef("B0004", "风控挑战", 403, "B", "R-4 风控要求二次校验/挑战（预留，处置留痕）"),
            new ErrorDef("C0002", "下游服务超时", 504, "C", "下游 RPC 超时（与 C0001 区分，便于商户重试决策）"));

    private static final Map<String, ErrorDef> INDEX = new LinkedHashMap<>();

    static {
        for (ErrorDef def : LEGACY) {
            INDEX.put(def.code(), def);
        }
        for (ErrorDef def : ADDITIVE) {
            INDEX.put(def.code(), def);
        }
    }

    private ErrorCodeRegistry() {
    }

    public static List<ErrorDef> all() {
        return List.copyOf(INDEX.values());
    }

    public static List<ErrorDef> additive() {
        return ADDITIVE;
    }

    public static ErrorDef find(String code) {
        return code == null ? null : INDEX.get(code);
    }

    /** 未知码兜底 HTTP 状态（与 GatewaySupport 既有 default 口径一致：400）。 */
    public static int httpStatusOf(String code) {
        ErrorDef def = find(code);
        return def == null ? 400 : def.httpStatus();
    }

    /** 码表 Markdown（供 Swagger 文档描述，G-2/G-3 联动）。 */
    public static String markdown() {
        StringBuilder sb = new StringBuilder("| code | HTTP | 类别 | 含义 | 说明 |\n|---|---|---|---|---|\n");
        for (ErrorDef def : all()) {
            sb.append("| ").append(def.code()).append(" | ").append(def.httpStatus()).append(" | ")
                    .append(def.category()).append(" | ").append(def.message()).append(" | ")
                    .append(def.description()).append(" |\n");
        }
        return sb.toString();
    }
}