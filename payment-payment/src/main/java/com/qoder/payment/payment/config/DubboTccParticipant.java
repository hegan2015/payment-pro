package com.qoder.payment.payment.config;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.facade.tcc.TccParticipantFacade;
import com.qoder.tcc.TccBranchRequest;
import com.qoder.tcc.TccException;
import com.qoder.tcc.TccParticipant;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dubbo 参与者桥接：把线上契约 {@link TccParticipantFacade}（Result 包装 + orThrow）
 * 桥接为 tcc-core 传输无关 SPI {@link TccParticipant}（直接抛异常）。
 *
 * <p>语义等价：Result 失败经 orThrow 抛 BizException，此处转为 TccException
 * 并保留原错误码/消息；RPC 层异常原样穿透，由协调器统一捕获补偿。
 */
public class DubboTccParticipant implements TccParticipant {

    private final TccParticipantFacade facade;

    public DubboTccParticipant(TccParticipantFacade facade) {
        this.facade = facade;
    }

    /** 批量包装：participant 名 → Dubbo 引用，转为 participant 名 → 组件 SPI。 */
    public static Map<String, TccParticipant> wrap(Map<String, TccParticipantFacade> participants) {
        Map<String, TccParticipant> wrapped = new LinkedHashMap<>();
        participants.forEach((name, facade) -> wrapped.put(name, new DubboTccParticipant(facade)));
        return wrapped;
    }

    @Override
    public void tryAction(TccBranchRequest request) {
        invoke(() -> facade.tryAction(request).orThrow());
    }

    @Override
    public void confirm(TccBranchRequest request) {
        invoke(() -> facade.confirm(request).orThrow());
    }

    @Override
    public void cancel(TccBranchRequest request) {
        invoke(() -> facade.cancel(request).orThrow());
    }

    private static void invoke(Runnable action) {
        try {
            action.run();
        } catch (BizException e) {
            throw new TccException(e.getCode(), e.getMessage());
        }
    }
}