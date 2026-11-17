package com.qoder.payment.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

import java.io.File;

/** OpenAPI HTTPS 接入端口（与 HTTP 控制台端口并存）；keystore 缺失时跳过，不影响 standalone 运行。 */
@Slf4j
@Component
public class HttpsConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Value("${payment.tls.keystore:config/payment-pro.p12}")
    private String keystore;

    @Value("${payment.tls.keystore-password:changeit}")
    private String keystorePassword;

    @Value("${payment.tls.port:8443}")
    private int port;

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        File ks = new File(keystore);
        if (!ks.isFile()) {
            log.warn("未找到 TLS keystore {}，HTTPS 端口未启用（run.sh 会自动生成自签证书）", ks.getAbsolutePath());
            return;
        }
        Connector connector = new Connector(Http11NioProtocol.class.getName());
        connector.setPort(port);
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setProperty("SSLEnabled", "true");
        SSLHostConfig ssl = new SSLHostConfig();
        SSLHostConfigCertificate cert = new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.UNDEFINED);
        cert.setCertificateKeystoreFile(ks.getAbsolutePath());
        cert.setCertificateKeystorePassword(keystorePassword);
        cert.setCertificateKeystoreType("PKCS12");
        ssl.addCertificate(cert);
        connector.addSslHostConfig(ssl);
        factory.addAdditionalTomcatConnectors(connector);
        log.info("OpenAPI HTTPS 端口启用 port={} keystore={}", port, ks.getAbsolutePath());
    }
}