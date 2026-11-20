package com.qoder.payment.recon.web;

import com.qoder.payment.recon.service.ReconService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * I-3 分片对账只读端点 {@code /actuator/pprecon?shardTotal=N}：输出当前 SUCCESS 支付单的分片计划。
 *
 * <p>供 verify-job.py 断言「分片不重不漏」：各片集合两两不相交、并集等于全量、每片归属与
 * {@link ReconService#shardOf} 一致。只读、不执行核对、不写任何数据。
 */
@Component
@Endpoint(id = "pprecon")
@RequiredArgsConstructor
public class ReconShardEndpoint {

    private final ReconService reconService;

    @ReadOperation
    public Map<String, Object> shardPlan(@Nullable Integer shardTotal) {
        int total = shardTotal == null ? 1 : Math.max(1, shardTotal);
        Map<Integer, List<String>> plan = reconService.shardPlan(total);
        List<Map<String, Object>> shards = new ArrayList<>();
        int sum = 0;
        for (int i = 0; i < total; i++) {
            List<String> ids = plan.getOrDefault(i, List.of());
            sum += ids.size();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("shardIndex", i);
            s.put("count", ids.size());
            s.put("paymentIds", ids);
            shards.add(s);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shardTotal", total);
        out.put("totalPayments", sum);
        out.put("shards", shards);
        return out;
    }
}