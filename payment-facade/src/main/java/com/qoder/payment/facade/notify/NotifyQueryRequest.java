package com.qoder.payment.facade.notify;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * N-4 通知记录分页查询条件（全部可选，AND 组合）。
 * 商户维度用 appId（notify_record.app_id）；时间窗为 created_at 毫秒闭区间。
 */
@Data
public class NotifyQueryRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 商户 appId（OpenAPI 侧强制为验签商户自身，控制台可选）。 */
    private String appId;
    /** 事件类型（NotifyEvent 名，也接受历史自定义字符串）。 */
    private String event;
    /** 状态：PENDING / DELIVERED / FAILED / PULL。 */
    private String status;
    /** 业务单号精确匹配。 */
    private String bizId;
    /** created_at 起（毫秒，含）。 */
    private Long from;
    /** created_at 止（毫秒，含）。 */
    private Long to;
    /** 页码，从 1 起，默认 1。 */
    private Integer page;
    /** 每页条数，默认 20，上限 200。 */
    private Integer size;
}