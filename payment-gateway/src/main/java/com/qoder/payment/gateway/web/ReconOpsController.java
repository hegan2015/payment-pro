package com.qoder.payment.gateway.web;

import com.qoder.payment.common.result.Result;
import com.qoder.payment.facade.recon.ReconFacade;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * P1-J 对账工作流 REST（RC-2/RC-3/RC-4/RC-5，薄适配层，业务经 Dubbo 路由到 recon 域）。
 *
 * <p><b>独立新增 Controller</b>：不改 ApiController 既有端点——{@code POST /api/recon/run}
 * 与 {@code GET /api/recon/latest} 的判定口径/返回结构一字未动（ride §0.7 红线）。
 * 返回裸数据（与 ApiController/DisputeController 现有风格一致，非 Result 信封）。
 *
 * <p>bizDate 入参兼容 epoch millis 与 yyyyMMdd 文本；为空 = accounting 当前账面会计日
 * （不硬编码当前日期）。CSV 导出返回 text/csv 附件。
 *
 * <p>鉴权：/api/** 统一 Bearer 认证覆盖（SecurityConfig 兜底 authenticated）；
 * 细粒度权限点（recon:run 扩展到 daily/channel 触发）由主会话统一增补。
 */
@RestController
@RequestMapping("/api/recon")
public class ReconOpsController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 批量对账/报表为长耗时操作（逐笔回查五域），显式放宽超时；既有 ApiController 的 run()/latest() 引用不受影响。 */
    @DubboReference(timeout = 120000, retries = 0)
    private ReconFacade reconFacade;

    // ---------------- RC-2 日切批量对账 ----------------

    /** 按会计日批量对账（六项口径与实时一致；幂等重跑不重复建单；默认不自动跑批）。 */
    @PostMapping("/daily")
    public ResponseEntity<Object> daily(@RequestParam(required = false) String bizDate,
                                        @RequestBody(required = false) Map<String, Object> body) {
        Long day = parseDay(bizDate, body);
        return GatewaySupport.of(reconFacade.runDaily(day));
    }

    // ---------------- RC-3 渠道内外对账 ----------------

    /** 内部渠道流水 vs 外部账单逐笔比对（长款/短款/金额/状态/手续费分类建单+处置建议）。 */
    @PostMapping("/channel")
    public ResponseEntity<Object> channel(@RequestParam(required = false) String channelCode,
                                          @RequestParam(required = false) String bizDate,
                                          @RequestBody(required = false) Map<String, Object> body) {
        Long day = parseDay(bizDate, body);
        String code = channelCode != null ? channelCode : str(body, "channelCode");
        return GatewaySupport.of(reconFacade.runChannelRecon(code, day));
    }

    @GetMapping("/channel-diffs")
    public ResponseEntity<Object> channelDiffs(@RequestParam(required = false) String channelCode,
                                               @RequestParam(required = false) String bizDate) {
        return GatewaySupport.of(reconFacade.channelDiffs(channelCode, parseDay(bizDate, null)));
    }

    // ---------------- RC-4 报表 / 进度 ----------------

    @GetMapping("/reports")
    public ResponseEntity<Object> reports(@RequestParam(required = false) String bizDate,
                                          @RequestParam(required = false) String reconType,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return GatewaySupport.of(reconFacade.listReports(parseDay(bizDate, null), reconType, page, size));
    }

    @GetMapping("/reports/{id}")
    public ResponseEntity<Object> report(@PathVariable String id) {
        return GatewaySupport.of(reconFacade.getReport(id));
    }

    @GetMapping("/batches")
    public ResponseEntity<Object> batches(@RequestParam(required = false) String bizDate,
                                          @RequestParam(required = false) String reconType) {
        return GatewaySupport.of(reconFacade.listBatches(parseDay(bizDate, null), reconType));
    }

    @GetMapping("/progress")
    public ResponseEntity<Object> progress(@RequestParam(required = false) String bizDate,
                                           @RequestParam(required = false) String reconType) {
        return GatewaySupport.of(reconFacade.getProgress(parseDay(bizDate, null), reconType));
    }

    @GetMapping("/summary")
    public ResponseEntity<Object> summary(@RequestParam(required = false) String bizDate) {
        return GatewaySupport.of(reconFacade.summary(parseDay(bizDate, null)));
    }

    @GetMapping("/dispute-sla")
    public ResponseEntity<Object> disputeSla(@RequestParam(required = false) String bizDate) {
        return GatewaySupport.of(reconFacade.disputeSla(parseDay(bizDate, null)));
    }

    // ---------------- CSV 导出 ----------------

    @GetMapping("/reports/export.csv")
    public ResponseEntity<byte[]> reportsCsv(@RequestParam(required = false) String bizDate,
                                             @RequestParam(required = false) String reconType) {
        return csv(reconFacade.reportsCsv(parseDay(bizDate, null), reconType), "recon-reports.csv");
    }

    @GetMapping("/dispute-sla/export.csv")
    public ResponseEntity<byte[]> disputeSlaCsv(@RequestParam(required = false) String bizDate) {
        return csv(reconFacade.disputeSlaCsv(parseDay(bizDate, null)), "dispute-sla.csv");
    }

    @GetMapping("/channel-diffs/export.csv")
    public ResponseEntity<byte[]> channelDiffsCsv(@RequestParam(required = false) String channelCode,
                                                  @RequestParam(required = false) String bizDate) {
        return csv(reconFacade.channelDiffsCsv(channelCode, parseDay(bizDate, null)), "channel-recon-diffs.csv");
    }

    // ------------------------------------------------------------------ 工具

    private static ResponseEntity<byte[]> csv(Result<String> result, String filename) {
        if (!result.isSuccess()) {
            return ResponseEntity.status(500).contentType(MediaType.APPLICATION_JSON)
                    .body(String.valueOf(result.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
        String data = result.getData() == null ? "" : result.getData();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(data.getBytes(StandardCharsets.UTF_8));
    }

    /** bizDate 兼容 epoch millis / yyyyMMdd；query 优先于 body；均空 = null（当前账面会计日）。 */
    private static Long parseDay(String query, Map<String, Object> body) {
        String raw = query != null && !query.isBlank() ? query.trim() : str(body, "bizDate");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            if (raw.length() == 8) {
                return LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZONE).toInstant().toEpochMilli();
            }
            return Long.parseLong(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("bizDate 需 epoch millis 或 yyyyMMdd: " + raw);
        }
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        return String.valueOf(body.get(key));
    }
}