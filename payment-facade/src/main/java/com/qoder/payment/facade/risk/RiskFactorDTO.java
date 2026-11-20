package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** R-4 风险因子命中明细（评分可解释性最小单元）。 */
@Data
public class RiskFactorDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 因子编码：FREQ/AMOUNT_DEV/LIST/DEVICE/IP/TIME/RULE。 */
    private String code;
    /** 因子中文名。 */
    private String name;
    /** 权重（满分贡献）。 */
    private Integer weight;
    /** 观测值（如窗口内笔数、金额偏离倍数）。 */
    private String value;
    /** 实际得分（0=未命中）。 */
    private Integer score;
    /** 说明（阈值/命中原因）。 */
    private String detail;

    public static RiskFactorDTO of(String code, String name, int weight, String value, int score, String detail) {
        RiskFactorDTO f = new RiskFactorDTO();
        f.code = code;
        f.name = name;
        f.weight = weight;
        f.value = value;
        f.score = score;
        f.detail = detail;
        return f;
    }
}