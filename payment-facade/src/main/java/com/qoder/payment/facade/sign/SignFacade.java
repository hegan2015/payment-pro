package com.qoder.payment.facade.sign;

import com.qoder.payment.common.result.Result;

import java.util.List;

/**
 * CH-6 签约代扣 + X-4 签约/代扣域归属（P2 · F 泳道）。
 *
 * <p><b>域归属决策（X-4）</b>：签约/代扣落在 <b>channel 域</b>（{@code payment-channel/.../sign}），
 * 不新建 sign 模块、不新增进程：
 * <ul>
 *   <li><b>耦合度</b>：协议本质是「渠道侧授权凭证」（channelCode + 卡号哈希 + 渠道协议号），
 *       与 CH-2a {@code ChannelAdapter} SPI 同域，签约/解约直接扩 SPI 默认方法即可复用既有 4 个适配器；</li>
 *   <li><b>事务边界</b>：sign_contract / sign_deduct / sign_event / sign_audit 与 channel_trade 同库
 *       （local H2 channel / docker pp_channel），协议状态 + 扣款单 + 事件留痕一个本地事务原子落地，
 *       无需跨库分布式事务；资金动账仍由 payment 域 TCC 协调（本域只调既有 facade）；</li>
 *   <li><b>部署独立性</b>：不新增进程 ⇒ 13/13 健康列表硬断言天然不破；不占用 XXL 执行器端口
 *       （9994-9998 已满），重试用 JobHandler + 既有 @Scheduled 兜底，经 {@code POST /actuator/ppjob/<job>} 触发；</li>
 *   <li><b>与既有 SPI 关系</b>：代扣<b>不新造资金路径</b>——经 {@code TradeFacade.createOrder} +
 *       {@code PaymentFacade.pay(method=CARD)} 走既有受理/风控/手续费/TCC/复式记账/五层幂等，
 *       渠道侧动作复用 {@code ChannelAdapter#agentPay}（默认委托 {@code pay}）。</li>
 * </ul>
 *
 * <p><b>X-4 账户侧授权视图</b>：{@link #listAuthorizations(String)} / {@link #revokeAuthorization}
 * 在 channel 域按 accountId 聚合 sign_contract 提供（账户存在性/状态/限额只调 {@code AccountFacade}
 * 既有方法）。<b>不给 AccountFacade 新增方法</b>（跨泳道红线）；account 域本地授权视图
 * （{@code AccountAuthorizeService} / {@code AccountFacade.getAuthorizations}）需主会话交 C 泳道接线。
 *
 * <p><b>ride 兼容红线（§0.7）</b>：本契约全部为新增方法/新增表，8 个冻结 OpenAPI 方法、
 * BALANCE 同步语义、手续费口径、notify legacy body 一字未改；ride 无签约 ⇒ 零影响。
 */
public interface SignFacade {

    // ---------- CH-6 协议生命周期 ----------

    /**
     * 签约（用户授权商户按期扣款）：四要素/卡号校验 + 渠道签约 SPI + 额度/有效期/扣款日/重试策略落库。
     * 幂等键 {@code (SIGN, accountId:merchantId:channelCode)} + {@code request_id} UNIQUE：
     * 同三元组已有 SIGNED 协议或同 requestId 重复调用返回既有协议（{@code repeated=true}），不重复签约。
     * 渠道拒绝/账户冻结/卡已解绑一律<b>受理前拒绝</b>，不落协议行。
     */
    Result<SignContractDTO> signContract(SignRequest request);

    /**
     * 解约：status SIGNED → CANCELLED（CAS），渠道 unsign SPI 成功后才翻状态；
     * 解约后不可代扣。幂等：已 CANCELLED 直接返回当前快照（不重复投递事件）。
     * 在途扣款单不被解约打断（资金由既有支付路径收敛）。
     */
    Result<SignContractDTO> unsignContract(String signNo, String requestId, String operator, String reason);

    Result<SignContractDTO> getContract(String signNo);

    /** 协议列表（accountId/merchantId/status 均可空=全量，按 id 倒序）。 */
    Result<List<SignContractDTO>> listContracts(String accountId, String merchantId, String status);

    /** 授权额度视图：单笔限额 / 周期累计已用与剩余 / 有效期 / 扣款日是否命中（只读，供受理前预检与运维）。 */
    Result<SignQuotaDTO> getQuota(String signNo);

    // ---------- CH-6 代扣执行（经既有支付受理路径 + TCC + 复式记账 + 幂等） ----------

    /**
     * 代扣执行：授权校验（SIGNED + 有效期 + 扣款日 + 商户范围 + 单笔限额 + 周期累计限额 +
     * 账户状态/限额经 AccountFacade 既有方法）全部通过后才建交易单并调 {@code PaymentFacade.pay}。
     *
     * <p>幂等：{@code request_id} UNIQUE（扣款单）+ 每次尝试确定性 requestId {@code deductNo:A<n>}
     * （支付单 request_id UNIQUE）⇒ 重复调用返回首次结果，绝不重复扣款。
     * <p><b>拒绝在受理前不落悬挂单</b>：授权/额度/有效期拒绝只留 sign_audit 痕，
     * 不建 sign_deduct / trade_order / payment / channel_trade 任何行。
     */
    Result<SignDeductDTO> agentPay(AgentPayRequest request);

    Result<SignDeductDTO> getDeduct(String deductNo);

    /** 扣款单列表（signNo/status 可空）。 */
    Result<List<SignDeductDTO>> listDeducts(String signNo, String status);

    /**
     * 手动重试一次失败扣款（与作业 {@code signDeductRetry} 同一实现，幂等）：
     * 仅 FAILED 且 attempts &lt; maxRetry 可重试；原支付单未终态先经 {@code PaymentFacade.reconverge}
     * 收敛，绝不并发重复扣款；协议已解约/到期/超额度 → TERMINATED（终止）。
     */
    Result<SignDeductDTO> retryDeduct(String deductNo, String requestId, String operator);

    /** 人工终止扣款单（不再重试）：仅 FAILED/PROCESSING 可终止，SUCCESS 拒绝（资金已成功不得抹除）。 */
    Result<SignDeductDTO> terminateDeduct(String deductNo, String operator, String reason);

    /**
     * 重试/收敛作业入口（JobHandler {@code signDeductRetry} 同一实现）：
     * 到期 FAILED 重试 + PROCESSING 补单收敛 + 重试耗尽终止 + 协议到期置 EXPIRED。返回处理笔数。
     */
    Result<Integer> runDeductRetry(int limit);

    // ---------- 事件投递（SIGN_SUCCESS / SIGN_CANCEL，N-3 已登记事件名） ----------

    /**
     * 事件投递作业入口（JobHandler {@code signEventDispatch} 同一实现）：
     * 事务 outbox 表 sign_event 的 PENDING/FAILED 行经 {@code NotifyFacade.send} 投递，
     * notify 域 {@code event:bizId} 幂等吸收重复。返回本次成功投递笔数。
     */
    Result<Integer> runEventDispatch(int limit);

    /** 事件 outbox 留痕（跨域投递不可达时仍可查证；含 notifyId/attempts/lastError）。 */
    Result<List<SignEventDTO>> listEvents(String signNo);

    /** 签约/代扣审计留痕（含受理前拒绝 REJECT 痕）。 */
    Result<List<SignAuditDTO>> listAudit(String signNo, int limit);

    // ---------- X-4 账户侧授权管理（channel 域聚合视图，不改 AccountFacade） ----------

    /** 账户已签协议视图（三方关联 accountId + merchantId + signNo 完整）。 */
    Result<List<SignContractDTO>> listAuthorizations(String accountId);

    /**
     * 账户侧解约（撤销授权）：校验 signNo 归属该 accountId（越权 A0403），
     * 复用 {@link #unsignContract} 同一状态机与事件投递（解约联动渠道 unsign）。
     */
    Result<SignContractDTO> revokeAuthorization(String accountId, String signNo, String requestId, String operator);
}