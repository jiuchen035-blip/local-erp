package com.local.erp.config;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 登录令牌：内存存储（重启后需重新登录），足够本地单机场景 */
@Component
public class TokenStore {

    private final Map<String, AuthInterceptor.SessionUser> tokens = new ConcurrentHashMap<>();

    public String create(String username, String role) {
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, new AuthInterceptor.SessionUser(username, role));
        return token;
    }

    public AuthInterceptor.SessionUser get(String token) {
        return token == null ? null : tokens.get(token);
    }

    public void remove(String token) {
        if (token != null) tokens.remove(token);
    }
}
