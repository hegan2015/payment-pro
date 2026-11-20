package com.qoder.payment.facade.channel;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * CH-3 路由决策结果（可观测）：选中渠道 + 评分 + 原因 + 全部候选的硬过滤/打分明细。
 * 决策预览与真实受理共用同一路由器，保证「预览即受理口径」。
 */
@Data
public class RouteResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 选中渠道；无可用渠道时为 null（上游必须拒绝受理，不臆断路由）。 */
    private String channelCode;
    /** 决策原因（选中理由 / 无可用渠道理由）。 */
    private String reason;
    private Double score;
    /** 候选顺序（含被过滤者），首个 eligible 即选中渠道；故障切换按此顺序向后取。 */
    private List<Candidate> candidates = new ArrayList<>();
    /** 是否商户指定渠道（指定渠道不可用时直接拒绝，不静默改道）。 */
    private boolean specified;
    private Long decidedAt;
    /** 策略标识与权重快照（动态配置生效证据）。 */
    private String strategy;
    private String weights;

    @Data
    public static class Candidate implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String channelCode;
        private boolean eligible;
        /** 硬过滤原因（维护窗口/限额/熔断/异步开关/未注册/停用）；eligible=true 时为打分说明。 */
        private String reason;
        private Double score;
        private Double successRate;
        private Long avgCostMs;
        private Integer feeBps;
        private Integer weight;
        private Long singleLimit;
        private Long dailyRemaining;
        private boolean inMaintenance;
        private boolean breakerOpen;
    }
}