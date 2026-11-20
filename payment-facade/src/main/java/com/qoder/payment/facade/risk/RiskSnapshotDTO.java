package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * R-1~R-4 配置快照版本（规则集/限额集/名单集/阈值集统一版本化）。
 * 回滚 = 取上一版 payload 生成新的 ACTIVE 版本（旧版位置为 ROLLED_BACK，历史行永不删除）。
 */
@Data
public class RiskSnapshotDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    /** RULES/QUOTAS/LISTS/THRESHOLDS。 */
    private String kind;
    private Integer version;
    /** JSON 载荷。 */
    private String payload;
    /** CONFIG（DynamicConfig 下发）/CONSOLE（控制台编辑）/ROLLBACK（回滚）/BOOT（内置默认）。 */
    private String source;
    /** ACTIVE/ROLLED_BACK/SUPERSEDED。 */
    private String status;
    private String operator;
    private String changeNote;
    private Long createdAt;
}