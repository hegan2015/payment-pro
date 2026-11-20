package com.qoder.payment.facade.cashier;

import com.qoder.payment.facade.account.AccountDTO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
public class PayMethodDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String method;
    private String name;
    private String hint;
    private List<AccountDTO> accounts;

    // ---------- P1 C-1 additive（多支付方式动态渲染；既有字段与语义不变） ----------

    /** C-1 是否可用（实时聚合账户状态/余额/签约/渠道/配置）。 */
    private Boolean available;
    /** C-1 不可用原因（冻结/余额不足/未签约/无券/维护中/即将上线），available=true 时为 null。 */
    private String unavailableReason;
    /** C-1 单笔限额（分）；null 表示不限。金额一律「分」，展示层由前端转元。 */
    private Long limit;
    /** C-1 方式分类：REAL=当前可受理；RESERVED=预留扩展位（组合/授信/券/网银等待后续泳道）。 */
    private String category;
    /** C-1 是否处于维护窗口（I-2 可配）。 */
    private Boolean maintenance;
    /** C-1 是否被配置启用（I-2 动态开关，关闭则不渲染或标灰）。 */
    private Boolean enabled;
    /** C-1 前端图标键（wallet/bank/scan/code/huabei/redpacket/bank-net/credit/combo），供 H5 对齐设计稿。 */
    private String icon;
}