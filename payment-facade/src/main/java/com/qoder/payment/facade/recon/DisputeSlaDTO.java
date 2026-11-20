package com.qoder.payment.facade.recon;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * RC-4 差错处理时效报表：建单→分派→修复→核销→关闭各阶段时长（毫秒），
 * 由 dispute_order + dispute_fix_log（SINGLE 表）逐单取状态迁移时间戳后 Java 聚合。
 */
@Data
public class DisputeSlaDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 过滤会计日 yyyyMMdd（null=全部）。 */
    private String bizDate;
    private long total;
    private List<Stage> stages = new ArrayList<>();
    private List<Item> items = new ArrayList<>();

    /** 阶段统计：count 有该阶段迁移的单数，avgMs/maxMs 时长。 */
    @Data
    public static class Stage implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String name;
        private long count;
        private long avgMs;
        private long maxMs;

        public static Stage of(String name) {
            Stage s = new Stage();
            s.setName(name);
            return s;
        }
    }

    /** 逐单时效明细（CSV 导出用）。 */
    @Data
    public static class Item implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long id;
        private String disputeNo;
        private String type;
        private String source;
        private String status;
        private String bizDate;
        private Long createdAt;
        private Long assignedAt;
        private Long fixingAt;
        private Long resolvedAt;
        private Long closedAt;
        private Long createToAssignMs;
        private Long assignToFixMs;
        private Long fixToResolveMs;
        private Long resolveToCloseMs;
        private Long createToResolveMs;
    }
}