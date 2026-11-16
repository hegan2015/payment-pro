package com.qoder.payment.member.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.member.MemberCreateRequest;
import com.qoder.payment.facade.member.MemberDTO;
import com.qoder.payment.member.convert.MemberConverter;
import com.qoder.payment.member.dataobject.MemberDO;
import com.qoder.payment.member.mapper.MemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberMapper memberMapper;

    public MemberDTO create(MemberCreateRequest request) {
        String name = request.getName();
        String type = request.getType();
        if (name == null || name.isBlank() || name.length() > 50) {
            throw new BizException(ResultCode.PARAM_ERROR, "名称必填且不超过 50 字");
        }
        if (!"USER".equals(type) && !"MERCHANT".equals(type)) {
            throw new BizException(ResultCode.PARAM_ERROR, "type 仅支持 USER 或 MERCHANT");
        }
        MemberDO member = new MemberDO();
        member.setId("M" + memberMapper.nextId());
        member.setName(name);
        member.setType(type);
        member.setStatus("ACTIVE");
        member.setCreatedAt(System.currentTimeMillis());
        if ("MERCHANT".equals(type)) {
            member.setAppId("app" + member.getId());
            member.setAppSecret(UUID.randomUUID().toString().replace("-", ""));
        }
        memberMapper.insert(member);
        log.info("会员注册 memberId={} type={} name={}", member.getId(), type, name);
        return MemberConverter.toDTO(member);
    }

    public List<MemberDTO> list() {
        return memberMapper.selectAll().stream().map(MemberConverter::toDTO).toList();
    }

    public MemberDTO get(String memberId) {
        return MemberConverter.toDTO(require(memberId));
    }

    public MemberDTO getByAppId(String appId) {
        MemberDO member = memberMapper.selectByAppId(appId);
        if (member == null) {
            throw new BizException(ResultCode.NOT_FOUND, "appId 不存在: " + appId);
        }
        return MemberConverter.toDTO(member);
    }

    public MemberDTO updateStatus(String memberId, String status) {
        if (!"ACTIVE".equals(status) && !"FROZEN".equals(status)) {
            throw new BizException(ResultCode.PARAM_ERROR, "status 仅支持 ACTIVE 或 FROZEN");
        }
        require(memberId);
        memberMapper.updateStatus(memberId, status);
        log.info("会员状态变更 memberId={} status={}", memberId, status);
        return MemberConverter.toDTO(require(memberId));
    }

    private MemberDO require(String memberId) {
        MemberDO member = memberMapper.selectById(memberId);
        if (member == null) {
            throw new BizException(ResultCode.NOT_FOUND, "会员不存在: " + memberId);
        }
        return member;
    }
}