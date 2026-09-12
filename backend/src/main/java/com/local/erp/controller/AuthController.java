package com.local.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.config.AuthInterceptor;
import com.local.erp.config.TokenStore;
import com.local.erp.entity.SysUser;
import com.local.erp.mapper.SysUserMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserMapper userMapper;
    private final TokenStore tokenStore;
    private final PasswordEncoder passwordEncoder;

    @Data
    public static class LoginReq {
        private String username;
        private String password;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq req) {
        SysUser u = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, req.getUsername() == null ? "" : req.getUsername().trim()));
        if (u == null || u.getEnabled() == null || u.getEnabled() != 1
                || !passwordEncoder.matches(req.getPassword(), u.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        String token = tokenStore.create(u.getUsername(), u.getRole());
        return Map.of("token", token, "username", u.getUsername(), "role", u.getRole());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        tokenStore.remove(token);
        return Map.of("message", "已退出");
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        AuthInterceptor.SessionUser u = AuthInterceptor.currentUser();
        return Map.of("username", u.getUsername(), "role", u.getRole());
    }
}
