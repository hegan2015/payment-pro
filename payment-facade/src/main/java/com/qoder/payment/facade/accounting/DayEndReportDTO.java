package com.qoder.payment.facade.accounting;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AC-3 日终批处理报告（day_end_report）：损益汇总 + 结转状态。
 *
 * <p>{@code incomeTotal} = Σ收入类科目当日(贷−借)；{@code expenseTotal} = Σ费用类科目当日(借−贷)；
 * {@code profit} = income − expense。{@code transferred=true} 表示已复式结转 SYS_PROFIT
 * （D 收入户 / C SYS_PROFIT），结转后损益户余额归零且试算仍平衡。
 *
 * <p><b>结转默认关闭</b>（{@code accounting.dayend.profit-transfer.enabled=false}）：
 * 既有断言依赖 SYS_FEE 余额口径（手续费闭环），共享栈上不得自动清零；
 * 需要结转时经 DynamicConfig 显式打开或由隔离环境验证。
 */
@Data
public class DayEndReportDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long bizDate;
    private Long incomeTotal;
    private Long expenseTotal;
    private Long profit;
    private Boolean transferred;
    /** 结转凭证号（未结转为 null）。 */
    private String transferBizNo;
    private Long debitTotal;
    private Long creditTotal;
    private Boolean balanced;
    private Long balanceSum;
    private Integer subjectCount;
    private Integer entryCount;
    /** 历史分录（biz_date 为空，升级前存量）笔数——只读透明，绝不回填改写。 */
    private Integer legacyEntryCount;
    /** RUNNING / SUCCESS / FAILED。 */
    private String status;
    private Long updatedAt;
}