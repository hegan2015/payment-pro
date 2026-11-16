package com.qoder.payment.payment.config;

import com.qoder.payment.facade.tcc.TccParticipantFacade;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;

import java.util.Map;

/** TCC 参与者远程引用：按 Dubbo group 区分同接口的不同实现。 */
@Component
public class TccParticipants {

    @DubboReference(group = "accounting")
    private TccParticipantFacade accounting;

    @DubboReference(group = "channel")
    private TccParticipantFacade channel;

    @DubboReference(group = "trade")
    private TccParticipantFacade trade;

    public Map<String, TccParticipantFacade> asMap() {
        return Map.of("accounting", accounting, "channel", channel, "trade", trade);
    }
}