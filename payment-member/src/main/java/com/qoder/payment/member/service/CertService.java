package com.qoder.payment.member.service;

import com.qoder.payment.common.exception.BizException;
import com.qoder.payment.common.metrics.MetricsSupport;
import com.qoder.payment.common.result.ResultCode;
import com.qoder.payment.facade.member.CertAlertDTO;
import com.qoder.payment.facade.member.CertDTO;
import com.qoder.payment.member.dataobject.MemberCertDO;
import com.qoder.payment.member.mapper.MemberCertMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;

/**
 * 商户应用公钥证书管理（M-1/S-1 RSA2）：上传解析有效期/公钥/序列号；
 * 到期扫描（@Scheduled，双 profile 同构）→ 剩余天数低于阈值 ERROR 日志 + 指标告警。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertService {

    private static final long DAY_MS = 86_400_000L;

    private final MemberCertMapper memberCertMapper;

    @Value("${payment.member.cert.warn-days:30}")
    private int warnDays;

    public CertDTO upload(String memberId, String certPem) {
        if (certPem == null || certPem.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "certPem 必填");
        }
        X509Certificate cert;
        try {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certPem.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BizException(ResultCode.PARAM_ERROR, "证书解析失败，需 X509 PEM 格式");
        }
        MemberCertDO row = new MemberCertDO();
        row.setId("MC" + memberCertMapper.nextId());
        row.setMemberId(memberId);
        row.setCertSn(cert.getSerialNumber().toString(16));
        row.setCertPem(certPem);
        row.setPublicKey(Base64.getEncoder().encodeToString(cert.getPublicKey().getEncoded()));
        row.setNotBefore(cert.getNotBefore().getTime());
        row.setNotAfter(cert.getNotAfter().getTime());
        row.setStatus("ACTIVE");
        row.setCreatedAt(System.currentTimeMillis());
        memberCertMapper.insert(row);
        log.info("商户证书上传 memberId={} certId={} certSn={} notAfter={}",
                memberId, row.getId(), row.getCertSn(), row.getNotAfter());
        return toDTO(row);
    }

    public List<CertDTO> list(String memberId) {
        return memberCertMapper.selectByMember(memberId).stream().map(this::toDTO).toList();
    }

    /** 最新 ACTIVE 证书公钥（getByAppId 内部验签链路用），无则 null。 */
    public String latestActivePublicKey(String memberId) {
        String id = memberCertMapper.selectLatestActiveId(memberId);
        if (id == null) {
            return null;
        }
        MemberCertDO cert = memberCertMapper.selectById(id);
        return cert == null ? null : cert.getPublicKey();
    }

    /** 到期扫描：剩余天数 < warnDays → ERROR 日志 + 指标（payment_member_cert_alert_total / cert_expiring gauge）。 */
    public List<CertAlertDTO> scan(Integer days) {
        int threshold = days == null || days < 0 ? warnDays : days;
        List<MemberCertDO> expiring = memberCertMapper.selectExpiring(System.currentTimeMillis() + threshold * DAY_MS);
        List<CertAlertDTO> alerts = expiring.stream().map(cert -> {
            long daysLeft = (cert.getNotAfter() - System.currentTimeMillis()) / DAY_MS;
            log.error("证书到期告警 memberId={} certId={} certSn={} 剩余{}天 notAfter={}（阈值{}天）",
                    cert.getMemberId(), cert.getId(), cert.getCertSn(), daysLeft, cert.getNotAfter(), threshold);
            MetricsSupport.count("cert_alert");
            CertAlertDTO alert = new CertAlertDTO();
            alert.setMemberId(cert.getMemberId());
            alert.setCertId(cert.getId());
            alert.setCertSn(cert.getCertSn());
            alert.setNotAfter(cert.getNotAfter());
            alert.setDaysLeft(daysLeft);
            return alert;
        }).toList();
        MetricsSupport.gauge("cert_expiring", alerts::size);
        return alerts;
    }

    /** 只读查询（不告警）。 */
    public List<CertAlertDTO> expiring(Integer days) {
        int threshold = days == null || days < 0 ? warnDays : days;
        return memberCertMapper.selectExpiring(System.currentTimeMillis() + threshold * DAY_MS).stream()
                .map(cert -> {
                    CertAlertDTO alert = new CertAlertDTO();
                    alert.setMemberId(cert.getMemberId());
                    alert.setCertId(cert.getId());
                    alert.setCertSn(cert.getCertSn());
                    alert.setNotAfter(cert.getNotAfter());
                    alert.setDaysLeft((cert.getNotAfter() - System.currentTimeMillis()) / DAY_MS);
                    return alert;
                }).toList();
    }

    /** 定时到期扫描（I-3 调度接入前的 @Scheduled 兜底，双 profile 同构）。 */
    @Scheduled(fixedDelayString = "${payment.member.cert.scan-interval-ms:60000}", initialDelay = 15000)
    public void scheduledScan() {
        try {
            scan(warnDays);
        } catch (Exception e) {
            log.warn("证书到期扫描失败: {}", e.getMessage());
        }
    }

    private CertDTO toDTO(MemberCertDO row) {
        CertDTO dto = new CertDTO();
        dto.setId(row.getId());
        dto.setMemberId(row.getMemberId());
        dto.setCertSn(row.getCertSn());
        dto.setPublicKey(row.getPublicKey());
        dto.setNotBefore(row.getNotBefore());
        dto.setNotAfter(row.getNotAfter());
        dto.setStatus(row.getStatus());
        dto.setCreatedAt(row.getCreatedAt());
        dto.setDaysLeft(row.getNotAfter() == null ? null
                : (row.getNotAfter() - System.currentTimeMillis()) / DAY_MS);
        return dto;
    }
}