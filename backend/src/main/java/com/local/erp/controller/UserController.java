package com.local.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.entity.SysUser;
import com.local.erp.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 操作员管理（仅 ADMIN，拦截器已做角色校验） */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final SysUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    public List<Map<String, Object>> list() {
        return userMapper.selectList(new LambdaQueryWrapper<SysUser>().orderByAsc(SysUser::getId))
                .stream().map(u -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", u.getId());
                    m.put("username", u.getUsername());
                    m.put("role", u.getRole());
                    m.put("enabled", u.getEnabled());
                    m.put("createdAt", u.getCreatedAt());
                    return m;
                }).toList();
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        String role = "ADMIN".equals(body.get("role")) ? "ADMIN" : "OPERATOR";
        if (username.isEmpty() || password.length() < 6)
            throw new IllegalArgumentException("用户名不能为空，密码至少6位");
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)) > 0)
            throw new IllegalArgumentException("用户名已存在");
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(password));
        u.setRole(role);
        u.setEnabled(1);
        userMapper.insert(u);
        return Map.of("message", "已创建 " + username);
    }

    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String password = body.getOrDefault("password", "");
        if (password.length() < 6) throw new IllegalArgumentException("密码至少6位");
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new IllegalArgumentException("用户不存在");
        u.setPasswordHash(passwordEncoder.encode(password));
        userMapper.updateById(u);
        return Map.of("message", "密码已重置");
    }

    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggle(@PathVariable Long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new IllegalArgumentException("用户不存在");
        if ("admin".equals(u.getUsername())) throw new IllegalArgumentException("不能停用内置管理员");
        u.setEnabled(u.getEnabled() != null && u.getEnabled() == 1 ? 0 : 1);
        userMapper.updateById(u);
        return Map.of("message", "已更新状态");
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new IllegalArgumentException("用户不存在");
        if ("admin".equals(u.getUsername())) throw new IllegalArgumentException("不能删除内置管理员");
        userMapper.deleteById(id);
        return Map.of("message", "已删除");
    }
}
