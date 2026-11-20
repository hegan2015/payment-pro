package com.qoder.payment.risk.facade;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.result.FacadeSupport;
import com.qoder.payment.common.result.Result;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.risk.BehaviorEventDTO;
import com.qoder.payment.facade.risk.DeviceAccountDTO;
import com.qoder.payment.facade.risk.DeviceProfileDTO;
import com.qoder.payment.facade.risk.IpProfileDTO;
import com.qoder.payment.facade.risk.RiskProfileFacade;
import com.qoder.payment.risk.device.BehaviorSequenceService;
import com.qoder.payment.risk.device.DeviceProfileService;
import com.qoder.payment.risk.device.IpProfileService;
import lombok.RequiredArgsConstructor;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * R-5 设备指纹/IP 画像运营 facade（控制台经网关 RiskProfileOpsController 调用）。
 * 参数/状态错误统一转 BizException（A0400/A0409），不向调用方抛异常。
 */
@DubboService
@RequiredArgsConstructor
public class RiskProfileFacadeImpl implements RiskProfileFacade {

    private final DeviceProfileService deviceProfile;
    private final IpProfileService ipProfile;
    private final BehaviorSequenceService behavior;

    private static <T> Result<T> run(Supplier<T> action) {
        return FacadeSupport.execute(() -> {
            try {
                return action.get();
            } catch (IllegalArgumentException e) {
                throw new BizException(ResultCode.PARAM_ERROR, e.getMessage());
            } catch (IllegalStateException e) {
                throw new BizException(ResultCode.CONFLICT, e.getMessage());
            }
        });
    }

    @Override
    public Result<DeviceProfileDTO> getDeviceProfile(String deviceId) {
        return run(() -> {
            if (deviceId == null || deviceId.isBlank()) {
                throw new IllegalArgumentException("deviceId 必填");
            }
            return deviceProfile.profile(deviceId);
        });
    }

    @Override
    public Result<IpProfileDTO> getIpProfile(String ip) {
        return run(() -> {
            if (ip == null || ip.isBlank()) {
                throw new IllegalArgumentException("ip 必填");
            }
            return ipProfile.profile(ip);
        });
    }

    @Override
    public Result<List<DeviceAccountDTO>> listDeviceAccounts(String deviceId) {
        return run(() -> deviceProfile.deviceAccounts(deviceId));
    }

    @Override
    public Result<List<DeviceAccountDTO>> listAccountDevices(String accountId) {
        return run(() -> {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("accountId 必填");
            }
            return deviceProfile.accountDevices(accountId);
        });
    }

    @Override
    public Result<List<BehaviorEventDTO>> listBehavior(String payerId, Integer limit) {
        return run(() -> behavior.list(payerId, limit == null || limit <= 0 ? 50 : Math.min(limit, 500)));
    }

    @Override
    public Result<Map<String, Object>> analyzeBehavior(String payerId, String deviceId, Long windowMillis) {
        return run(() -> behavior.analyze(payerId, deviceId, windowMillis));
    }

    @Override
    public Result<Map<String, Object>> profileStatus() {
        return run(() -> {
            Map<String, Object> m = new LinkedHashMap<>(deviceProfile.status());
            m.putAll(ipProfile.status());
            m.putAll(behavior.status());
            return m;
        });
    }

    @Override
    public Result<List<Map<String, Object>>> ipSandbox() {
        return run(ipProfile::sandbox);
    }

    @Override
    public Result<Map<String, Object>> applyRetention(String operator, Long beforeMillis) {
        return run(() -> deviceProfile.applyRetention(operator, beforeMillis));
    }
}