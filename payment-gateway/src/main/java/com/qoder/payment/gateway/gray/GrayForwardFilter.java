package com.qoder.payment.gateway.gray;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * I-9 灰度转发过滤器（order -200，早于 Spring Security -100）。
 *
 * <p>命中灰度（{@link GrayRouter#toGray}）时把请求<b>原样</b>转发到灰度实例/版本
 * （method + URI + query + 头 + body 字节），由目标实例自行完成 Bearer/HMAC 鉴权与业务，
 * 响应状态/头/体原样回传。转发请求带 {@code X-Gray-Hop: 1} 防二次转发成环。
 *
 * <p><b>资金不中断</b>：转发失败（目标宕机/超时）→ fail-open 用缓存体在<b>本实例</b>继续处理，
 * 绝不因灰度目标不可用而丢请求。健康/actuator/文档/静态资源一律本地处理（监控与回滚通道不受灰度影响）。
 *
 * <p>每个响应都带 {@code X-Instance-Id}（本实例标识）与 {@code X-Gray-Decision}（gray/stable），
 * 便于实测分流与回滚。
 */
@Slf4j
public class GrayForwardFilter extends OncePerRequestFilter {

    /** 逐跳头/受限头：不转发（HttpClient 亦禁止设置）。 */
    private static final Set<String> SKIP_REQUEST_HEADERS = Set.of(
            "host", "content-length", "connection", "transfer-encoding", "upgrade", "expect",
            "http2-settings", "keep-alive", "proxy-connection", "te", "trailer");
    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of(
            "transfer-encoding", "content-length", "connection", "keep-alive",
            GrayRouter.DECISION_HEADER.toLowerCase(Locale.ROOT));

    private final GrayRouter router;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public GrayForwardFilter(GrayRouter router) {
        this.router = router;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(GrayRouter.INSTANCE_HEADER, router.instanceId());
        String path = request.getRequestURI();
        boolean routable = (path.startsWith("/api/") || path.startsWith("/openapi/"))
                && !path.equals("/api/health")
                && request.getHeader(GrayRouter.HOP_HEADER) == null;
        if (!routable) {
            chain.doFilter(request, response);
            return;
        }
        String shardKey = shardKey(request);
        boolean gray = router.toGray(request.getHeader(GrayRouter.TAG_HEADER), shardKey);
        if (!gray) {
            response.setHeader(GrayRouter.DECISION_HEADER, "stable");
            chain.doFilter(request, response);
            return;
        }
        String target = router.state().target();
        byte[] body = request.getInputStream().readAllBytes();
        // 决策头必须在写响应体（提交响应）之前设置，否则 setHeader 失效
        response.setHeader(GrayRouter.DECISION_HEADER, "gray");
        try {
            forward(request, response, target, body);
            return;
        } catch (Exception e) {
            log.warn("灰度转发失败，fail-open 本实例处理 target={} path={}: {}", target, path, e.toString());
            response.setHeader(GrayRouter.DECISION_HEADER, "stable-failopen");
            chain.doFilter(new BufferedRequest(request, body), response);
        }
    }

    private void forward(HttpServletRequest request, HttpServletResponse response, String target, byte[] body)
            throws IOException, InterruptedException {
        String query = request.getQueryString();
        URI uri = URI.create(target.replaceAll("/+$", "") + request.getRequestURI() + (query == null ? "" : "?" + query));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20));
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (SKIP_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                builder.header(name, values.nextElement());
            }
        }
        builder.header(GrayRouter.HOP_HEADER, "1");
        builder.header(GrayRouter.INSTANCE_HEADER, router.instanceId());
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        // GET/HEAD 等无体方法用 noBody（JDK HttpClient 不允许 GET 带体）；有体则原样字节转发
        HttpRequest.BodyPublisher publisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        builder.method(method, publisher);
        HttpResponse<byte[]> upstream = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        response.setStatus(upstream.statusCode());
        for (Map.Entry<String, List<String>> entry : upstream.headers().map().entrySet()) {
            String name = entry.getKey();
            if (name == null || SKIP_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))
                    || name.equalsIgnoreCase(GrayRouter.INSTANCE_HEADER)) {
                continue;
            }
            for (String value : entry.getValue()) {
                response.addHeader(name, value);
            }
        }
        // 灰度实例标识回传（便于实测「哪个实例受理」）
        upstream.headers().firstValue(GrayRouter.INSTANCE_HEADER)
                .ifPresent(id -> response.setHeader("X-Gray-Upstream-Instance", id));
        response.setHeader(GrayRouter.INSTANCE_HEADER, router.instanceId());
        byte[] payload = upstream.body() == null ? new byte[0] : upstream.body();
        response.setContentLength(payload.length);
        response.getOutputStream().write(payload);
        response.getOutputStream().flush();
    }

    /** 分流键：Bearer token 优先（同一会话稳定落侧），否则客户端 IP。 */
    private static String shardKey(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        String ip = request.getHeader("X-Forwarded-For");
        return ip != null && !ip.isBlank() ? ip.split(",")[0].trim() : request.getRemoteAddr();
    }

    /** 转发失败 fail-open 用的缓存体请求包装（字节原样，可重复读）。 */
    private static final class BufferedRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private BufferedRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buffer = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buffer.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return buffer.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}