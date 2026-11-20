package com.qoder.payment.channel.bill;

import java.util.List;

/**
 * CH-4 账单解析 SPI：按渠道格式解析为归一明细（CSV 为本期实现，定长/JSON 可同 SPI 扩展）。
 * 解析失败必须抛错（账单完整性优先），由 ChannelBillService 落 FAILED + 差错单，绝不静默丢行。
 */
public interface BillParser {

    /** 格式标识（CSV / FIXED_LENGTH / JSON）。 */
    String format();

    boolean supports(String format);

    Parsed parse(String content);

    /** 归一明细行（金额单位分）。 */
    record Row(String channelOrderNo, String bizNo, String type, long amount, String status, String raw) {
    }

    /** 解析结果：明细 + 文件声明汇总（#TOTAL 行，缺失为 null）。 */
    record Parsed(List<Row> rows, Long declaredCount, Long declaredAmount) {

        public long sumAmount() {
            return rows.stream().mapToLong(r -> Math.abs(r.amount())).sum();
        }
    }
}