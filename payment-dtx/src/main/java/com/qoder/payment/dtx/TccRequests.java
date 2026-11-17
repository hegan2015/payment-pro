package com.qoder.payment.dtx;

import com.qoder.tcc.TccBranchRequest;

/**
 * DTO 桥接：tcc-core 通用分支请求 ↔ Dubbo 线上契约 DTO。
 * facade 的 TccBranchRequest 保持不变（线上契约），转换仅发生在适配层。
 */
public final class TccRequests {

    private TccRequests() {
    }

    /** 组件 DTO → facade DTO（协调器经 Dubbo 调用参与者前转换）。 */
    public static com.qoder.payment.facade.tcc.TccBranchRequest toFacade(TccBranchRequest req) {
        com.qoder.payment.facade.tcc.TccBranchRequest dto = new com.qoder.payment.facade.tcc.TccBranchRequest();
        dto.setXid(req.getXid());
        dto.setBranchId(req.getBranchId());
        dto.setAction(req.getAction());
        dto.setContext(req.getContext());
        return dto;
    }

    /** facade DTO → 组件 DTO（参与者 Facade 实现收到 Dubbo 请求后转换）。 */
    public static TccBranchRequest toCore(com.qoder.payment.facade.tcc.TccBranchRequest req) {
        TccBranchRequest dto = new TccBranchRequest();
        dto.setXid(req.getXid());
        dto.setBranchId(req.getBranchId());
        dto.setAction(req.getAction());
        dto.setContext(req.getContext());
        return dto;
    }
}