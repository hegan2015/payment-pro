package com.qoder.payment.accounting.dataobject;

import lombok.Data;

/**
 * AC-3 在途 TCC 会计日快照（accounting_day_snapshot）：Try（freeze）时落受理日，
 * Confirm（postAndUnfreeze）时按本快照归属凭证 biz_date —— 日切不破坏在途单。
 * 只追加；无快照（升级前受理的在途单）回落当前会计日。
 */
@Data
public class DaySnapshotDO {

    private String bizNo;
    private Long bizDate;
    private Long createdAt;
}