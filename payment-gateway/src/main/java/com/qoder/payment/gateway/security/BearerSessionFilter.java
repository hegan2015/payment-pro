package com.qoder.payment.gateway.security;

import com.qoder.payment.facade.auth.ConsoleUserDTO;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Bearer 会话解析（S-2）：Authorization: Bearer <token> → SessionStore 取会话 →
 * SecurityContext（authorities=权限点 + ROLE_角色）。无效/过期不设置上下文，
 * 由授权层统一 401；/openapi/**、/actuator/**、/api/health 不受影响（permitAll）。
 */
public class BearerSessionFilter extends OncePerRequestFilter {

    public static final String TOKEN_ATTR = "pp.console.token";

    private final SessionStore sessionStore;

    public BearerSessionFilter(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            ConsoleUserDTO user = sessionStore.get(token);
            if (user != null && "ACTIVE".equals(user.getStatus())) {
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                if (user.getPermissions() != null) {
                    user.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                }
                if (user.getRoles() != null) {
                    user.getRoles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
                }
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(user, token, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
                request.setAttribute(TOKEN_ATTR, token);
            }
        }
        chain.doFilter(request, response);
    }
}