package com.local.erp.controller;

import com.local.erp.ai.AiSettingsService;
import com.local.erp.config.AuthInterceptor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** AI 模型设置：查看/保存（管理员）、拉取厂家模型列表、测试连接 */
@RestController
@RequestMapping("/api/ai/settings")
@RequiredArgsConstructor
public class AiSettingsController {

    private final AiSettingsService settingsService;

    @GetMapping
    public Map<String, Object> get() {
        return settingsService.status();
    }

    @Data
    public static class SaveReq {
        private String provider;
        private String baseUrl;
        private String apiKey;      // 空 = 保留原 Key
        private String model;
        private Boolean embeddingEnabled;
        private String embeddingModel;
    }

    @PostMapping
    public Map<String, Object> save(@RequestBody SaveReq req) {
        requireAdmin();
        return settingsService.apply(req.getProvider(), req.getBaseUrl(), req.getApiKey(),
                req.getModel(),
                Boolean.TRUE.equals(req.getEmbeddingEnabled()),
                req.getEmbeddingModel());
    }

    /** 拉取模型列表（在线优先，失败回落内置清单） */
    @GetMapping("/models")
    public Map<String, Object> models(@RequestParam String provider,
                                      @RequestParam(required = false) String apiKey,
                                      @RequestParam(required = false) String baseUrl) {
        return settingsService.fetchModels(provider, apiKey, baseUrl);
    }

    /** 测试连接 */
    @Data
    public static class TestReq {
        private String provider;
        private String baseUrl;
        private String apiKey;
        private String model;
    }

    @PostMapping("/test")
    public Map<String, Object> test(@RequestBody TestReq req) {
        return settingsService.test(req.getProvider(), req.getBaseUrl(), req.getApiKey(), req.getModel());
    }

    private void requireAdmin() {
        AuthInterceptor.SessionUser u = AuthInterceptor.currentUser();
        if (u == null) throw new SecurityException("未登录");
        if (!"ADMIN".equals(u.getRole())) throw new SecurityException("仅管理员可修改 AI 模型设置");
    }
}
