package com.local.erp.config;

import com.local.erp.entity.OpLog;
import com.local.erp.mapper.OpLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录态拦截：X-Auth-Token 校验 + 关键操作写日志 + ADMIN 接口保护。
 * 用户信息放 ThreadLocal，业务代码用 currentUser() 取。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final TokenStore tokenStore;
    private final OpLogMapper opLogMapper;

    private static final ThreadLocal<SessionUser> CURRENT = new ThreadLocal<>();

    @Data
    public static class SessionUser {
        private String username;
        private String role;
        public SessionUser(String username, String role) { this.username = username; this.role = role; }
    }

    public static SessionUser currentUser() { return CURRENT.get(); }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        // 商品图片本地只读放行（<img> 标签无法带 token 头）
        if (request.getRequestURI().startsWith("/api/images/")) return true;

        SessionUser user = tokenStore.get(request.getHeader("X-Auth-Token"));
        if (user == null) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"未登录或登录已过期\"}");
            return false;
        }
        CURRENT.set(user);

        String uri = request.getRequestURI();
        // ADMIN 专属：用户管理、系统管理、财务记账、电商对接、所有删除操作
        if (!AccessRules.allow(user.getRole(), uri, request.getMethod())) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"需要管理员权限\"}");
            return false;
        }

        // 关键操作留痕
        String method = request.getMethod();
        if ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method)) {
            OpLog log = new OpLog();
            log.setUsername(user.getUsername());
            log.setAction(method + " " + uri);
            log.setDetail(request.getQueryString() == null ? "" : request.getQueryString());
            opLogMapper.insert(log);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        CURRENT.remove();
    }
}
