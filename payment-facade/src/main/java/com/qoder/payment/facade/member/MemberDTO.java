package com.qoder.payment.facade.member;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class MemberDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String type;
    private String status;
    private String appId;
    /** 仅 create/rotate/getByAppId(内部验签) 返回；list/get 一律 null（E-1）。 */
    private String appSecret;
    private Long createdAt;

    // ---------- M-1 additive（仅 getByAppId 内部验签链路填充） ----------

    /** 宽限期内 RETIRED 版本密钥（多密钥并存验签兜底），内部字段，list/get 置 null。 */
    private List<String> graceSecrets;

    /** 与 graceSecrets 对齐的宽限截止毫秒时间戳：验签时点再校验（商户缓存 60s TTL 内到期也即时拒绝）。 */
    private List<Long> graceDeadlines;

    /** 最新 ACTIVE 应用证书公钥 Base64(SPKI)，RSA2 验签用；无证书为 null。 */
    private String certPublicKey;

    /** 当前 ACTIVE 密钥版本号。 */
    private Integer secretVersion;
}