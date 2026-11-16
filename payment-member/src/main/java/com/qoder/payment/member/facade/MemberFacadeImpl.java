package com.qoder.payment.member.facade;

import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.member.MemberCreateRequest;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.facade.member.MemberFacade;
import com.qoder.payment.member.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

@DubboService
@RequiredArgsConstructor
public class MemberFacadeImpl implements MemberFacade {

    private final MemberService memberService;

    @Override
    public Result<MemberDTO> create(MemberCreateRequest request) {
        return FacadeSupport.execute(() -> memberService.create(request));
    }

    @Override
    public Result<List<MemberDTO>> list() {
        return FacadeSupport.execute(memberService::list);
    }

    @Override
    public Result<MemberDTO> get(String memberId) {
        return FacadeSupport.execute(() -> memberService.get(memberId));
    }

    @Override
    public Result<MemberDTO> getByAppId(String appId) {
        return FacadeSupport.execute(() -> memberService.getByAppId(appId));
    }

    @Override
    public Result<MemberDTO> updateStatus(String memberId, String status) {
        return FacadeSupport.execute(() -> memberService.updateStatus(memberId, status));
    }
}