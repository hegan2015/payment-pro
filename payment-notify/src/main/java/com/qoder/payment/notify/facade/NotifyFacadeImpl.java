package com.qoder.payment.notify.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.notify.NotifyFacade;
import com.qoder.payment.facade.notify.NotifyRecordDTO;
import com.qoder.payment.facade.notify.NotifyRequest;
import com.qoder.payment.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class NotifyFacadeImpl implements NotifyFacade {

    private final NotifyService notifyService;

    @Override
    public Result<NotifyRecordDTO> send(NotifyRequest request) {
        return FacadeSupport.execute(() -> notifyService.send(request));
    }

    @Override
    public Result<List<NotifyRecordDTO>> records(String bizId) {
        return FacadeSupport.execute(() -> notifyService.records(bizId));
    }

    @Override
    public Result<NotifyRecordDTO> get(String notifyId) {
        return FacadeSupport.execute(() -> notifyService.get(notifyId));
    }
}