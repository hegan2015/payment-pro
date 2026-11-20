package com.qoder.payment.channel.spi;

import lombok.Data;

/**
 * CH-4 渠道对账文件载体（适配器 downloadBill 返回）：内容 + 声明汇总（解析完整性校验依据）。
 *
 * <p>格式 CSV：首行表头 {@code channel_order_no,biz_no,type,amount,status,trade_time}，
 * 末行 {@code #TOTAL,<count>,<amount>}（渠道声明汇总）。金额单位分。
 */
@Data
public class ChannelBillFile {

    private String channelCode;
    private String bizDate;
    private String fileName;
    /** 文件全文（UTF-8）。 */
    private String content;
    /** 渠道声明笔数/金额（#TOTAL 行）；null 表示文件未声明，由解析汇总回填。 */
    private Long declaredCount;
    private Long declaredAmount;
    private String format;

    public static ChannelBillFile csv(String channelCode, String bizDate, String content,
                                      Long declaredCount, Long declaredAmount) {
        ChannelBillFile f = new ChannelBillFile();
        f.setChannelCode(channelCode);
        f.setBizDate(bizDate);
        f.setFileName(channelCode + "-" + bizDate + ".csv");
        f.setContent(content);
        f.setDeclaredCount(declaredCount);
        f.setDeclaredAmount(declaredAmount);
        f.setFormat("CSV");
        return f;
    }
}