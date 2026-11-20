package com.qoder.payment.channel.bill;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CH-4 CSV 账单解析器（本期选定格式，如实说明）：
 *
 * <pre>
 * channel_order_no,biz_no,type,amount,status,trade_time   ← 表头（首行，跳过）
 * SBX-PAY-CHTEST1,CHTEST1,PAY,2000,CAPTURED,1730000000000
 * #TOTAL,1,2000                                          ← 渠道声明汇总（笔数,金额绝对值和）
 * </pre>
 *
 * <p>口径：type=PAY/REFUND；amount 为分（long，杜绝浮点）；status 为渠道终态
 * CAPTURED/REFUNDED/FAILED；汇总金额 = Σ|amount|（PAY 与 REFUND 均计正数，便于完整性校验）。
 * 空行与 {@code #} 注释行跳过（#TOTAL 除外）；列数不足/金额非法/类型非法 → 抛 A0400，
 * 由上层落 bill FAILED + 差错单（不静默丢行、不臆断金额）。
 */
@Component
public class CsvBillParser implements BillParser {

    public static final String FORMAT = "CSV";
    private static final String HEADER = "channel_order_no,biz_no,type,amount,status,trade_time";

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public boolean supports(String format) {
        return format == null || FORMAT.equalsIgnoreCase(format.trim());
    }

    @Override
    public Parsed parse(String content) {
        if (content == null || content.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "账单文件为空");
        }
        List<Row> rows = new ArrayList<>();
        Long declaredCount = null;
        Long declaredAmount = null;
        int lineNo = 0;
        for (String line : content.split("\n")) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#TOTAL,")) {
                String[] cols = trimmed.split(",");
                if (cols.length < 3) {
                    throw new BizException(ResultCode.PARAM_ERROR, "账单汇总行非法(第 " + lineNo + " 行): " + trimmed);
                }
                declaredCount = parseLong(cols[1], lineNo);
                declaredAmount = parseLong(cols[2], lineNo);
                continue;
            }
            if (trimmed.startsWith("#") || trimmed.equalsIgnoreCase(HEADER)) {
                continue;
            }
            String[] cols = trimmed.split(",", -1);
            if (cols.length < 5) {
                throw new BizException(ResultCode.PARAM_ERROR, "账单明细列数不足(第 " + lineNo + " 行): " + trimmed);
            }
            String type = cols[2].trim().toUpperCase();
            if (!"PAY".equals(type) && !"REFUND".equals(type)) {
                throw new BizException(ResultCode.PARAM_ERROR, "账单明细 type 非法(第 " + lineNo + " 行): " + type);
            }
            String status = cols[4].trim().toUpperCase();
            if (status.isEmpty()) {
                throw new BizException(ResultCode.PARAM_ERROR, "账单明细 status 为空(第 " + lineNo + " 行)");
            }
            if (cols[0].trim().isEmpty() || cols[1].trim().isEmpty()) {
                throw new BizException(ResultCode.PARAM_ERROR, "账单明细单号为空(第 " + lineNo + " 行)");
            }
            rows.add(new Row(cols[0].trim(), cols[1].trim(), type, parseLong(cols[3], lineNo), status, trimmed));
        }
        return new Parsed(rows, declaredCount, declaredAmount);
    }

    private static long parseLong(String raw, int lineNo) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new BizException(ResultCode.PARAM_ERROR, "账单金额非法(第 " + lineNo + " 行): " + raw);
        }
    }
}