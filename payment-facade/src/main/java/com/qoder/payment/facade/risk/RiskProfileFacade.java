package com.qoder.payment.facade.risk;

import com.qoder.payment.common.result.Result;

import java.util.List;
import java.util.Map;

/**
 * R-5 设备指纹 / IP 画像运营契约（<b>新增接口，additive</b>；不改既有 {@link RiskRuleFacade}）。
 *
 * <p>控制台经网关 {@code RiskProfileOpsController}（{@code /api/risk/device|ip|behavior/**}）调用，
 * 复用既有 {@code risk:read}/{@code risk:write} 权限点与二次认证。
 * <p>画像因子<b>接入既有 R-4 评分的 DEVICE/IP 因子</b>（权重 25/20 不变），不另起评分体系；
 * 默认 {@code risk.device.profile-score-enabled=false} / {@code risk.ip.profile-score-enabled=false}
 * （新因子默认影子），正常 ride/smoke 流量 score 贡献恒 0。
 */
public interface RiskProfileFacade {

    /** 设备画像（入参为端侧原始 deviceId，内部转脱敏存储键查询；返回不含原始标识）。 */
    Result<DeviceProfileDTO> getDeviceProfile(String deviceId);

    /** IP 画像（离线沙箱数据源 + 本域历史拒绝聚合）。 */
    Result<IpProfileDTO> getIpProfile(String ip);

    /** 同设备关联账户（一设备多账户图谱）。 */
    Result<List<DeviceAccountDTO>> listDeviceAccounts(String deviceId);

    /** 同账户关联设备（一账户多设备图谱）。 */
    Result<List<DeviceAccountDTO>> listAccountDevices(String accountId);

    /** 行为序列明细。 */
    Result<List<BehaviorEventDTO>> listBehavior(String payerId, Integer limit);

    /** 行为序列异常分析：窗口内笔数 / 地域跳变 / 突发（burst）。 */
    Result<Map<String, Object>> analyzeBehavior(String payerId, String deviceId, Long windowMillis);

    /** 画像运行态：开关 / 阈值 / 留存期 / 脱敏 / 数据源 / 计数。 */
    Result<Map<String, Object>> profileStatus();

    /** 离线沙箱 IP 数据源可见性（CIDR → 归属地/代理/IDC），证明不连外部情报 API。 */
    Result<List<Map<String, Object>>> ipSandbox();

    /**
     * 留存期执行：到期行<b>匿名化</b>（不 DELETE 历史行），返回处理条数。
     *
     * @param beforeMillis 参考时间点（毫秒）；null=当前时间。供补跑历史留存窗口。
     */
    Result<Map<String, Object>> applyRetention(String operator, Long beforeMillis);
}