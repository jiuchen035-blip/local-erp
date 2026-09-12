package com.local.erp.controller;

import com.local.erp.ai.AgentService;
import com.local.erp.ai.AiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai/agent")
@RequiredArgsConstructor
public class AiAgentController {

    private final AgentService agentService;
    private final AiProperties aiProps;

    @PostMapping
    public Map<String, Object> run(@RequestBody Map<String, String> body) {
        if (!aiProps.chatConfigured()) {
            return Map.of("error", "请先在 application.yml 中配置 ai.api-key 才能使用智能开单");
        }
        return agentService.run(body.getOrDefault("message", ""));
    }
}
