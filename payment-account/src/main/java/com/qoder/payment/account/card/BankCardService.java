package com.qoder.payment.account.card;

import com.qoder.payment.account.dataobject.AccountDO;
import com.qoder.payment.account.dataobject.BankCardDO;
import com.qoder.payment.account.mapper.AccountMapper;
import com.qoder.payment.account.mapper.BankCardMapper;
import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.account.BankCardDTO;
import com.qoder.payment.facade.account.BankCardUtil;
import com.qoder.payment.facade.account.BindCardRequest;
import com.qoder.payment.facade.account.IdCardUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A-2 绑卡管理：四要素沙箱核验（姓名+身份证校验位+卡号 Luhn/BIN+手机号格式）、
 * 默认卡切换（一账户一默认卡）、解绑（UNBOUND 留痕不删除，在途交易卡快照不受影响）、
 * (accountId,cardNoHash) 幂等。<b>卡号明文绝不落库/落日志</b>——入库即 hash+mask（后四位）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankCardService {

    private final BankCardMapper cardMapper;
    private final AccountMapper accountMapper;

    @Transactional
    public BankCardDTO bind(BindCardRequest request) {
        if (request == null || request.getAccountId() == null || request.getAccountId().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "accountId 必填");
        }
        AccountDO account = accountMapper.selectById(request.getAccountId());
        if (account == null) {
            throw new BizException(ResultCode.NOT_FOUND, "账户不存在: " + request.getAccountId());
        }
        if (!"ACTIVE".equals(account.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "账户非 ACTIVE，不可绑卡");
        }
        if (!BankCardUtil.validCardNo(request.getCardNo())) {
            throw new BizException(ResultCode.PARAM_ERROR, "卡号格式/Luhn 校验不通过");
        }
        if (!BankCardUtil.validMobile(request.getMobile())) {
            throw new BizException(ResultCode.PARAM_ERROR, "手机号格式不通过");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "持卡人姓名必填");
        }
        if (request.getIdCardNo() != null && !request.getIdCardNo().isBlank()
                && !IdCardUtil.valid(request.getIdCardNo())) {
            throw new BizException(ResultCode.PARAM_ERROR, "身份证号校验位不通过");
        }
        String hash = BankCardUtil.sha256(request.getCardNo());
        BankCardDO existing = cardMapper.selectByAccountAndHash(request.getAccountId(), hash);
        if (existing != null) {
            if ("BOUND".equals(existing.getStatus())) {
                return toDTO(existing);
            }
            // 解绑后重绑：复用原行（幂等键不变），重新签约
            existing.setStatus("BOUND");
            existing.setSignNo(newSignNo());
            existing.setUnboundAt(null);
            existing.setIsDefault(cardMapper.countDefault(request.getAccountId()) == 0 ? 1 : 0);
            cardMapper.updateBinding(existing);
            log.info("重新绑卡 cardId={} accountId={} card={}", existing.getId(), request.getAccountId(),
                    existing.getCardNoMask());
            return toDTO(existing);
        }
        BankCardDO card = new BankCardDO();
        card.setId("BC" + cardMapper.nextId());
        card.setAccountId(request.getAccountId());
        card.setCardNoHash(hash);
        card.setCardNoMask(BankCardUtil.maskCard(request.getCardNo()));
        card.setBankName(request.getBankName() == null || request.getBankName().isBlank()
                ? BankCardUtil.binBank(request.getCardNo()) : request.getBankName().trim());
        card.setMobileHash(BankCardUtil.sha256(request.getMobile()));
        card.setMobileMask(BankCardUtil.maskMobile(request.getMobile()));
        card.setNameMask(IdCardUtil.maskName(request.getName()));
        card.setStatus("BOUND");
        card.setIsDefault(cardMapper.countDefault(request.getAccountId()) == 0 ? 1 : 0);
        card.setSignNo(newSignNo());
        card.setCreatedAt(System.currentTimeMillis());
        try {
            cardMapper.insert(card);
        } catch (DuplicateKeyException e) {
            return toDTO(cardMapper.selectByAccountAndHash(request.getAccountId(), hash));
        }
        log.info("绑卡成功 cardId={} accountId={} card={} bank={} default={}", card.getId(),
                request.getAccountId(), card.getCardNoMask(), card.getBankName(), card.getIsDefault());
        return toDTO(card);
    }

    @Transactional
    public BankCardDTO unbind(String cardId, String operator) {
        BankCardDO card = require(cardId);
        if ("UNBOUND".equals(card.getStatus())) {
            return toDTO(card);
        }
        boolean wasDefault = card.getIsDefault() != null && card.getIsDefault() == 1;
        card.setStatus("UNBOUND");
        card.setIsDefault(0);
        card.setUnboundAt(System.currentTimeMillis());
        cardMapper.updateBinding(card);
        if (wasDefault) {
            // 默认卡顺位：最早绑定的 BOUND 卡顶上（无则无默认卡）
            cardMapper.selectByAccount(card.getAccountId()).stream()
                    .filter(c -> "BOUND".equals(c.getStatus()))
                    .min(Comparator.comparing(BankCardDO::getCreatedAt).thenComparing(BankCardDO::getId))
                    .ifPresent(next -> cardMapper.markDefault(next.getId()));
        }
        log.info("解绑 cardId={} accountId={} operator={}（记录保留，在途交易卡快照不受影响）",
                cardId, card.getAccountId(), operator);
        return toDTO(card);
    }

    @Transactional
    public BankCardDTO setDefault(String accountId, String cardId) {
        BankCardDO card = require(cardId);
        if (!card.getAccountId().equals(accountId)) {
            throw new BizException(ResultCode.PARAM_ERROR, "卡不属于该账户");
        }
        if (!"BOUND".equals(card.getStatus())) {
            throw new BizException(ResultCode.CONFLICT, "仅 BOUND 卡可设为默认卡");
        }
        cardMapper.clearDefault(accountId);
        if (cardMapper.markDefault(cardId) == 0) {
            throw new BizException(ResultCode.CONFLICT, "默认卡设置失败（卡状态已变更）");
        }
        log.info("默认卡切换 accountId={} cardId={}", accountId, cardId);
        return toDTO(cardMapper.selectById(cardId));
    }

    public List<BankCardDTO> list(String accountId) {
        return cardMapper.selectByAccount(accountId).stream()
                .sorted(Comparator.comparing((BankCardDO c) -> !"BOUND".equals(c.getStatus()))
                        .thenComparing(BankCardDO::getCreatedAt).thenComparing(BankCardDO::getId))
                .map(BankCardService::toDTO).toList();
    }

    private BankCardDO require(String cardId) {
        BankCardDO card = cardMapper.selectById(cardId);
        if (card == null) {
            throw new BizException(ResultCode.NOT_FOUND, "卡不存在: " + cardId);
        }
        return card;
    }

    private static String newSignNo() {
        return "SIGN" + UUID.randomUUID().toString().replace("-", "");
    }

    private static BankCardDTO toDTO(BankCardDO card) {
        BankCardDTO dto = new BankCardDTO();
        dto.setId(card.getId());
        dto.setAccountId(card.getAccountId());
        dto.setCardNoMask(card.getCardNoMask());
        dto.setBankName(card.getBankName());
        dto.setMobileMask(card.getMobileMask());
        dto.setStatus(card.getStatus());
        dto.setIsDefault(card.getIsDefault() != null && card.getIsDefault() == 1);
        dto.setSignNo(card.getSignNo());
        dto.setCreatedAt(card.getCreatedAt());
        dto.setUnboundAt(card.getUnboundAt());
        return dto;
    }
}