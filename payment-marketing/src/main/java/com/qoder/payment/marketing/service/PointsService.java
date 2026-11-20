package com.qoder.payment.marketing.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.marketing.PointsAccountDTO;
import com.qoder.payment.facade.marketing.PointsEarnRequest;
import com.qoder.payment.facade.marketing.PointsFreezeRequest;
import com.qoder.payment.facade.marketing.PointsLogDTO;
import com.qoder.payment.facade.marketing.PointsUseRequest;
import com.qoder.payment.marketing.dataobject.PointsBatchDO;
import com.qoder.payment.marketing.mapper.PointsAccountMapper;
import com.qoder.payment.marketing.mapper.PointsBatchMapper;
import com.qoder.payment.marketing.mapper.PointsLogMapper;
import com.qoder.tcc.IdempotentTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * X-3b 积分账户：赚取 / 消耗 / 冻结 / 解冻 / 冻结转消耗 / 退回 / 过期。
 *
 * <p><b>账本边界（资金红线 §5.5）</b>：积分是<b>虚拟权益</b>，{@code points_account/points_batch/points_log}
 * 是独立权益账本，单位=积分，<b>不是金额、不参与复式记账、不影响 Σbalance=0</b>。
 * 只有「积分抵扣成金额」那一刻（USE/SETTLE）才经 {@link MarketingFundService} 走 accounting 复式记账
 * {@code D SYS_MARKETING / C 用户户}；退回反向。两侧由同一 bizNo 关联、各自幂等，绝不双花。
 *
 * <p><b>兑换率</b>：{@code marketing.points.exchange-rate}（I-2 DynamicConfig 可热更，默认 100 积分=1 分），
 * 受理时快照落 {@code points_log.amount}，热更不影响在途单；不足 1 分的余数不抵扣（向下取整，流水可审计）。
 *
 * <p><b>幂等键</b>：{@code (POINTS_EARN|POINTS_USE|POINTS_FREEZE|POINTS_UNFREEZE|POINTS_REFUND, bizNo)}
 * + {@code points_log} UNIQUE(biz_no,type) + 账户/批次<b>原子条件 UPDATE</b>（可用余额充足才扣）。
 *
 * <p><b>FIFO 与过期</b>：赚取落批次（带有效期），消耗按「先到期先用」扣批次；过期作业只扣<b>未冻结</b>部分，
 * 冻结积分不静默蒸发（需先解冻），批次 remaining 归零后置 EXPIRED，账户 expired 累计留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsService {

    public static final String SOURCE = "POINTS";
    public static final String TYPE_EARN = "EARN";
    public static final String TYPE_USE = "USE";
    public static final String TYPE_SETTLE = "SETTLE";
    public static final String TYPE_FREEZE = "FREEZE";
    public static final String TYPE_UNFREEZE = "UNFREEZE";
    public static final String TYPE_REFUND = "REFUND";
    public static final String TYPE_EXPIRE = "EXPIRE";

    private final PointsAccountMapper accountMapper;
    private final PointsBatchMapper batchMapper;
    private final PointsLogMapper logMapper;
    private final MarketingSupport support;
    private final MarketingFundService fundService;
    private final IdempotentTemplate idempotentTemplate;

    // ---------------- 账户 ----------------

    public PointsAccountDTO account(String memberId) {
        MarketingSupport.require(MarketingSupport.notBlank(memberId), "memberId 必填");
        PointsAccountDTO account = accountMapper.selectById(memberId);
        if (account == null) {
            account = ensureAccount(memberId);
        }
        return withAvailable(account);
    }

    private PointsAccountDTO ensureAccount(String memberId) {
        long now = System.currentTimeMillis();
        PointsAccountDTO account = new PointsAccountDTO();
        account.setMemberId(memberId);
        account.setBalance(0L);
        account.setFrozen(0L);
        account.setExpired(0L);
        account.setVersion(0L);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            // 并发开户：唯一键命中即取既有户（幂等懒开户，与 account.open 语义一致）
            log.info("积分账户并发开户命中既有户 member={}", memberId);
        }
        return accountMapper.selectById(memberId);
    }

    private static PointsAccountDTO withAvailable(PointsAccountDTO account) {
        account.setAvailable(MarketingSupport.nz(account.getBalance()) - MarketingSupport.nz(account.getFrozen()));
        return account;
    }

    public List<PointsLogDTO> logs(String memberId, int limit) {
        MarketingSupport.require(MarketingSupport.notBlank(memberId), "memberId 必填");
        return logMapper.selectByMember(memberId, limit <= 0 ? 50 : Math.min(limit, 500));
    }

    // ---------------- 赚取 ----------------

    public PointsLogDTO earn(PointsEarnRequest request) {
        MarketingSupport.require(request != null, "请求必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getMemberId()), "memberId 必填");
        MarketingSupport.require(request.getPoints() != null && request.getPoints() > 0, "points 必须为正整数");
        MarketingSupport.requireKey(request.getBizNo());
        MarketingSupport.require(request.getBizNo().length() <= 62, "bizNo 需 ≤62 字符（批次号前缀 E:）");
        long points = request.getPoints();
        return idempotentTemplate.execute("POINTS_EARN", request.getBizNo(), PointsLogDTO.class, () -> {
            PointsLogDTO existing = logMapper.selectByKey(request.getBizNo(), TYPE_EARN);
            if (existing != null) {
                return existing;
            }
            long now = System.currentTimeMillis();
            ensureAccount(request.getMemberId());
            long expireAt = request.getExpireAt() != null && request.getExpireAt() > 0 ? request.getExpireAt()
                    : MarketingSupport.plusDays(now, request.getValidDays() != null && request.getValidDays() > 0
                    ? request.getValidDays() : support.pointsValidDays());
            newBatch(request.getMemberId(), points, expireAt, "E:" + request.getBizNo(), now);
            accountMapper.earn(request.getMemberId(), points, now);
            PointsLogDTO row = writeLog(request.getMemberId(), points, TYPE_EARN, request.getBizNo(),
                    request.getRefId(), null, null, null, request.getOperator(), now);
            support.audit("POINTS", request.getMemberId(), TYPE_EARN, null, null, points, request.getBizNo(),
                    request.getOperator(), "积分赚取 expireAt=" + expireAt);
            return row;
        });
    }

    private void newBatch(String memberId, long points, long expireAt, String bizNo, long now) {
        PointsBatchDO batch = new PointsBatchDO();
        batch.setId(support.newId("PB"));
        batch.setMemberId(memberId);
        batch.setAmount(points);
        batch.setRemaining(points);
        batch.setExpireAt(expireAt);
        batch.setStatus("ACTIVE");
        batch.setBizNo(bizNo);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batchMapper.insert(batch);
    }

    // ---------------- 消耗（积分 → 金额，资金侧复式记账） ----------------

    public PointsLogDTO use(PointsUseRequest request) {
        fundService.requireEnabled();
        MarketingSupport.require(request != null, "请求必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getMemberId()), "memberId 必填");
        MarketingSupport.require(request.getPoints() != null && request.getPoints() > 0, "points 必须为正整数");
        MarketingSupport.require(MarketingSupport.notBlank(request.getPaymentId()), "paymentId 必填（分摊与资金关联）");
        boolean fromFrozen = Boolean.TRUE.equals(request.getFromFrozen());
        String bizNo = MarketingSupport.notBlank(request.getBizNo()) ? request.getBizNo()
                : "PU:" + request.getMemberId() + ":" + request.getPaymentId();
        MarketingSupport.requireKey(bizNo);
        String fundBizNo = MarketingSupport.FUND_PREFIX + "P:" + bizNo;
        MarketingSupport.requireKey(fundBizNo);
        long points = request.getPoints();
        long rate = support.pointsRate();
        long amount = points / rate;
        MarketingSupport.requireBiz(amount > 0,
                "积分不足抵扣 1 分：points=" + points + " exchangeRate=" + rate);
        String type = fromFrozen ? TYPE_SETTLE : TYPE_USE;
        return idempotentTemplate.execute("POINTS_USE", bizNo, PointsLogDTO.class, () -> {
            PointsLogDTO existing = logMapper.selectByKey(bizNo, type);
            if (existing != null) {
                return existing;
            }
            long now = System.currentTimeMillis();
            ensureAccount(request.getMemberId());
            String accountId = fundService.resolveAccount(request.getMemberId(), request.getAccountId());
            int cas = fromFrozen
                    ? accountMapper.settleFrozen(request.getMemberId(), points, now)
                    : accountMapper.use(request.getMemberId(), points, now);
            if (cas != 1) {
                throw new BizException(ResultCode.BIZ_ERROR,
                        (fromFrozen ? "冻结积分不足" : "可用积分不足") + "：points=" + points);
            }
            consumeBatches(request.getMemberId(), points, now);
            // 先动账（账务 bizNo 确定性幂等）再落流水：任一步失败重试都不会重复补贴
            fundService.subsidy(fundBizNo, accountId, amount, "营销积分抵扣", request.getPaymentId());
            PointsLogDTO row = writeLog(request.getMemberId(), -points, type, bizNo, request.getPaymentId(),
                    fundBizNo, accountId, amount, request.getOperator(), now);
            fundService.recordSource(request.getPaymentId(), SOURCE, row.getId(), amount,
                    MarketingFundService.TYPE_USE, fundBizNo);
            support.audit("POINTS", request.getMemberId(), type, null, null, points, fundBizNo,
                    request.getOperator(), "积分抵扣 payment=" + request.getPaymentId() + " amount=" + amount
                            + " rate=" + rate + " account=" + accountId);
            return row;
        });
    }

    /** FIFO 扣批次（先到期先用），必须恰好扣满 points，否则整体回滚（绝不部分扣减）。 */
    private void consumeBatches(String memberId, long points, long now) {
        List<PointsBatchDO> batches = batchMapper.selectFifoForUpdate(memberId, now);
        long left = points;
        for (PointsBatchDO batch : batches) {
            if (left <= 0) {
                break;
            }
            long take = Math.min(left, MarketingSupport.nz(batch.getRemaining()));
            if (take <= 0) {
                continue;
            }
            int rows = batchMapper.consume(batch.getId(), take, now);
            if (rows != 1) {
                throw new BizException(ResultCode.CONFLICT, "积分批次并发变更，扣减未受理: " + batch.getId());
            }
            if (MarketingSupport.nz(batch.getRemaining()) - take == 0) {
                batchMapper.markExhausted(batch.getId(), now);
            }
            left -= take;
        }
        if (left > 0) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "积分批次余额与账户不一致（批次不足 " + left + "），已回滚");
        }
    }

    // ---------------- 冻结 / 解冻（纯权益，不动资金） ----------------

    public PointsLogDTO freeze(PointsFreezeRequest request) {
        return freezeOrUnfreeze(request, true);
    }

    public PointsLogDTO unfreeze(PointsFreezeRequest request) {
        return freezeOrUnfreeze(request, false);
    }

    private PointsLogDTO freezeOrUnfreeze(PointsFreezeRequest request, boolean freeze) {
        MarketingSupport.require(request != null, "请求必填");
        MarketingSupport.require(MarketingSupport.notBlank(request.getMemberId()), "memberId 必填");
        MarketingSupport.require(request.getPoints() != null && request.getPoints() > 0, "points 必须为正整数");
        MarketingSupport.requireKey(request.getBizNo());
        long points = request.getPoints();
        String type = freeze ? TYPE_FREEZE : TYPE_UNFREEZE;
        String bizType = freeze ? "POINTS_FREEZE" : "POINTS_UNFREEZE";
        return idempotentTemplate.execute(bizType, request.getBizNo(), PointsLogDTO.class, () -> {
            PointsLogDTO existing = logMapper.selectByKey(request.getBizNo(), type);
            if (existing != null) {
                return existing;
            }
            long now = System.currentTimeMillis();
            ensureAccount(request.getMemberId());
            int cas = freeze
                    ? accountMapper.freeze(request.getMemberId(), points, now)
                    : accountMapper.unfreeze(request.getMemberId(), points, now);
            if (cas != 1) {
                throw new BizException(ResultCode.BIZ_ERROR,
                        (freeze ? "可用积分不足，冻结未受理" : "冻结积分不足，解冻未受理") + "：points=" + points);
            }
            PointsLogDTO row = writeLog(request.getMemberId(), freeze ? points : -points, type,
                    request.getBizNo(), null, null, null, null, request.getOperator(), now);
            support.audit("POINTS", request.getMemberId(), type, null, null, points, request.getBizNo(),
                    request.getOperator(), freeze ? "积分冻结（不动资金）" : "积分解冻（不动资金）"
                            + (MarketingSupport.notBlank(request.getReason()) ? " reason=" + request.getReason() : ""));
            return row;
        });
    }

    // ---------------- 退回 ----------------

    public PointsLogDTO refund(String origBizNo, String requestId) {
        fundService.requireEnabled();
        MarketingSupport.requireKey(origBizNo);
        // 幂等键恒为原抵扣 bizNo（一笔抵扣只能退回一次）；requestId 仅作调用方追踪号，
        // 绝不参与幂等键 —— 否则换 requestId 重放会二次回补积分（资金侧因 bizNo 确定性派生不会重复动账，
        // 但权益侧会双花）。这是 X-3b 资金/权益两侧幂等键必须同源的关键护栏。
        String key = origBizNo;
        MarketingSupport.require(key.length() <= 62, "origBizNo 需 ≤62 字符（退回批次号前缀 R:）");
        return idempotentTemplate.execute("POINTS_REFUND", key, PointsLogDTO.class, () -> {
            PointsLogDTO existing = logMapper.selectByKey(key, TYPE_REFUND);
            if (existing != null) {
                return existing;
            }
            PointsLogDTO orig = logMapper.selectByKey(origBizNo, TYPE_USE);
            if (orig == null) {
                orig = logMapper.selectByKey(origBizNo, TYPE_SETTLE);
            }
            MarketingSupport.requireFound(orig, "原积分抵扣流水不存在: " + origBizNo);
            MarketingSupport.requireFound(orig.getFundBizNo(), "原积分抵扣无资金凭证，不可退回: " + origBizNo);
            long points = -MarketingSupport.nz(orig.getChangeAmount());
            long amount = MarketingSupport.nz(orig.getAmount());
            MarketingSupport.requireBiz(points > 0 && amount > 0, "原积分抵扣流水金额异常: " + origBizNo);
            long now = System.currentTimeMillis();
            String fundBizNo = MarketingSupport.FUND_REFUND_PREFIX + "P:" + origBizNo;
            MarketingSupport.requireKey(fundBizNo);
            // 资金反向按原户原额（绝不改户、绝不多退）
            fundService.subsidyBack(fundBizNo, orig.getAccountId(), amount, "营销积分退回", orig.getRefId());
            ensureAccount(orig.getMemberId());
            accountMapper.earn(orig.getMemberId(), points, now);
            newBatch(orig.getMemberId(), points, MarketingSupport.plusDays(now, support.pointsValidDays()),
                    "R:" + key, now);
            PointsLogDTO row = writeLog(orig.getMemberId(), points, TYPE_REFUND, key, orig.getRefId(),
                    fundBizNo, orig.getAccountId(), amount, orig.getOperator(), now);
            if (MarketingSupport.notBlank(orig.getRefId())) {
                fundService.recordSource(orig.getRefId(), SOURCE, row.getId(), amount,
                        MarketingFundService.TYPE_REFUND, fundBizNo);
            }
            support.audit("POINTS", orig.getMemberId(), TYPE_REFUND, null, null, points, fundBizNo,
                    orig.getOperator(), "积分退回 origBizNo=" + origBizNo + " amount=" + amount);
            return row;
        });
    }

    // ---------------- 过期 ----------------

    /** 过期作业：只扣未冻结部分（冻结积分受保护），批次 remaining 归零置 EXPIRED，账户 expired 累计。 */
    public long expireDue() {
        long now = System.currentTimeMillis();
        List<PointsBatchDO> expired = batchMapper.selectExpired(now);
        long total = 0L;
        for (PointsBatchDO batch : expired) {
            long remaining = MarketingSupport.nz(batch.getRemaining());
            if (remaining <= 0) {
                continue;
            }
            PointsAccountDTO account = accountMapper.selectForUpdate(batch.getMemberId());
            if (account == null) {
                continue;
            }
            long available = MarketingSupport.nz(account.getBalance()) - MarketingSupport.nz(account.getFrozen());
            long take = Math.min(remaining, Math.max(0L, available));
            if (take <= 0) {
                log.info("积分批次已到期但全额冻结，保护不蒸发 batch={} member={} remaining={}",
                        batch.getId(), batch.getMemberId(), remaining);
                continue;
            }
            int rows = batchMapper.consume(batch.getId(), take, now);
            if (rows != 1) {
                continue;
            }
            accountMapper.expire(batch.getMemberId(), take, now);
            if (remaining - take == 0) {
                batchMapper.markExpired(batch.getId(), now);
            }
            String bizNo = "EXP:" + batch.getId() + ":" + now;
            writeLog(batch.getMemberId(), -take, TYPE_EXPIRE, bizNo, batch.getId(), null, null, null, "SYSTEM", now);
            support.audit("POINTS", batch.getMemberId(), TYPE_EXPIRE, null, null, take, bizNo, "SYSTEM",
                    "积分过期 batch=" + batch.getId() + " expireAt=" + batch.getExpireAt());
            total += take;
        }
        return total;
    }

    // ---------------- 流水 ----------------

    private PointsLogDTO writeLog(String memberId, long change, String type, String bizNo, String refId,
                                  String fundBizNo, String accountId, Long amount, String operator, long now) {
        PointsAccountDTO account = accountMapper.selectById(memberId);
        PointsLogDTO row = new PointsLogDTO();
        row.setId(support.newId("PL"));
        row.setMemberId(memberId);
        row.setChangeAmount(change);
        row.setBalanceAfter(account == null ? 0L : MarketingSupport.nz(account.getBalance()));
        row.setFrozenAfter(account == null ? 0L : MarketingSupport.nz(account.getFrozen()));
        row.setType(type);
        row.setBizNo(bizNo);
        row.setRefId(refId);
        row.setFundBizNo(fundBizNo);
        row.setAccountId(accountId);
        row.setAmount(amount);
        row.setOperator(MarketingSupport.notBlank(operator) ? operator : "SYSTEM");
        row.setCreatedAt(now);
        logMapper.insert(row);
        return row;
    }
}