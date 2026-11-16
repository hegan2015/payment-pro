package com.qoder.payment.common.health;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.HealthFacade;
import org.apache.dubbo.config.annotation.DubboService;

/** 统一健康探针：按应用名分组导出，供网关聚合。 */
@DubboService(group = "${spring.application.name}")
public class HealthFacadeImpl implements HealthFacade {

    @Override
    public Result<String> ping() {
        return Result.ok("pong");
    }
}