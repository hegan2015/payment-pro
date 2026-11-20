package com.qoder.payment.facade.dispute;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.common.PageDTO;

/**
 * X-1 差错处理平台（归 recon 域）：差错全生命周期闭环
 * 登记→分类→自动修复（S-4）/人工工单→核销→关闭，全程 fix_log 审计。
 *
 * <p>幂等：登记 (DISPUTE, source[:checkName][:bizDate]:bizNo)；修复 (DISPUTE_FIX, disputeId)，
 * 修复凭证 bizNo 确定性生成（DSPF/DSPB+disputeId），重复执行不重复动账。
 * <p>状态机：OPEN→ASSIGNED→FIXING→RESOLVED→CLOSED；OPEN/ASSIGNED→IGNORED（reject）；
 * 对账复核通过/通知重放送达可自动 RESOLVED（留痕）。
 */
public interface DisputeFacade {

    /** 登记差错单（幂等去重；重复来源返回既有单）。 */
    Result<DisputeDTO> create(DisputeCreateRequest request);

    Result<PageDTO<DisputeDTO>> list(DisputeQuery query);

    /** 详情（含审计日志）。 */
    Result<DisputeDTO> get(long disputeId);

    /** 分派工单：OPEN/ASSIGNED→ASSIGNED。 */
    Result<DisputeDTO> assign(long disputeId, String assignee, String operator);

    /** 人工批准执行修复（S-4 策略路由，幂等）：OPEN/ASSIGNED→FIXING→RESOLVED。 */
    Result<DisputeDTO> approve(long disputeId, String operator);

    /** 驳回：OPEN/ASSIGNED→IGNORED。 */
    Result<DisputeDTO> reject(long disputeId, String operator, String reason);

    /** 人工核销：OPEN/ASSIGNED/FIXING→RESOLVED。 */
    Result<DisputeDTO> resolve(long disputeId, String operator, String resolution);

    /** 关闭：RESOLVED/IGNORED→CLOSED。 */
    Result<DisputeDTO> close(long disputeId, String operator);

    /** S-4 自动修复单个差错单（与 approve 同路径，operator=autofix）。 */
    Result<DisputeDTO> autoFix(long disputeId);

    /**
     * 手动/调度触发批量自动修复。execute=null 时按配置 recon.dispute.autofix（默认 false=影子模式，
     * 只返回建议策略不动账）；execute=true 显式执行（人工触发视为授权）。
     */
    Result<DisputeAutoFixResultDTO> autoFixRun(Boolean execute);

    /** 按来源+业务号自动核销（通知重放送达/对账复核通过回调），无匹配单时返回 null。 */
    Result<DisputeDTO> resolveBySourceBiz(String source, String bizNo, String operator, String detail);

    Result<DisputeStatsDTO> stats();
}