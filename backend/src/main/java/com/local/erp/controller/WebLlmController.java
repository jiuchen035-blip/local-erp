package com.local.erp.controller;

import com.local.erp.ai.AiRouter;
import com.local.erp.ai.WebLlmBridgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 网页版模型桥接管理：运行状态、启动、打开站点登录窗口。
 * 以及 /api/ai/source：当前实际生效的模型来源（供聊天页展示）。
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class WebLlmController {

    private final WebLlmBridgeService bridge;
    private final AiRouter router;

    /** 当前实际生效的模型来源（API / 本地 / 网页版）；只读解析，不触发桥接拉起 */
    @GetMapping("/source")
    public Map<String, Object> source() {
        try {
            AiRouter.LlmEndpoint ep = router.resolve(false);
            return Map.of("provider", ep.provider(), "label", ep.label(), "model", ep.model(),
                    "fallbackReason", router.lastFallbackReason());
        } catch (Exception e) {
            return Map.of("provider", "none", "label", "不可用", "model", "",
                    "fallbackReason", e.getMessage());
        }
    }

    @GetMapping("/webllm/status")
    public Map<String, Object> status() {
        return bridge.status();
    }

    @PostMapping("/webllm/start")
    public Map<String, Object> start() {
        try {
            bridge.ensureRunning();
            return Map.of("ok", true);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    /** 打开有头浏览器让用户登录指定网站（deepseek / miaoxiang），登录态持久化 */
    @PostMapping("/webllm/login")
    public Map<String, Object> login(@RequestParam String site) {
        if (!site.equals("deepseek") && !site.equals("miaoxiang")) {
            return Map.of("ok", false, "error", "未知站点：" + site);
        }
        bridge.openLogin(site);
        return Map.of("ok", true, "message", "登录窗口已打开（5分钟内完成登录），完成后此处可查询结果");
    }

    @GetMapping("/webllm/login/result")
    public Map<String, Object> loginResult(@RequestParam String site) {
        String r = bridge.pollLoginResult(site);
        return Map.of("done", r != null, "message", r == null ? "等待中" : r);
    }
}
