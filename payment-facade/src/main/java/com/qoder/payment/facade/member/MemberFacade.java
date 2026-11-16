package com.qoder.payment.facade.member;

import com.qoder.payment.common.result.Result;

import java.util.List;

/** 会员域：用户/商户注册、状态管理、OpenAPI 密钥。对标支付宝会员系统。 */
public interface MemberFacade {

    Result<MemberDTO> create(MemberCreateRequest request);

    Result<List<MemberDTO>> list();

    Result<MemberDTO> get(String memberId);

    Result<MemberDTO> getByAppId(String appId);

    Result<MemberDTO> updateStatus(String memberId, String status);
}