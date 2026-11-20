package com.qoder.payment.facade.risk;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** R-5 设备-账户关联图谱边（同设备多账户 / 同账户多设备双向可查）。 */
@Data
public class DeviceAccountDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    /** 脱敏设备存储键。 */
    private String deviceId;
    private String accountId;
    private Long firstSeen;
    private Long lastSeen;
    private Integer txnCount;
}