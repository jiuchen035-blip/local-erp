package com.local.erp.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议客户端：云端 API（GLM/DeepSeek/OpenAI）、本地 Ollama、
 * 网页版模型桥接（webllm，127.0.0.1:8317）同一套代码。
 * 每次请求先经 AiRouter 解析实际端点（支持 API→Ollama→网页版自动降级），支持 Function Calling。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelClient {

    private final AiProperties props;
    private final AiRouter router;

    private RestClient client(AiRouter.LlmEndpoint ep) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (ep.apiKey() != null && !ep.apiKey().isBlank() && !ep.apiKey().contains("填入")) {
            headers.setBearerAuth(ep.apiKey());
        }
        return RestClient.builder()
                .baseUrl(ep.baseUrl())
                // 默认的 JDK HttpClient 对 POST 用 chunked + h2c 升级头，uvicorn/FastAPI 会把它当空请求体（422）；
                // HttpURLConnection 缓冲 body、带 Content-Length、纯 HTTP/1.1，所有厂家都兼容
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
                .defaultHeaders(h -> h.addAll(headers))
                .build();
    }

    /** 便捷问答：system + user 一轮（自动路由） */
    public String chat(String systemPrompt, String userMessage) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userMessage));
        Map<String, Object> msg = chatRaw(messages, null);
        return String.valueOf(msg.get("content"));
    }

    /**
     * 原生对话：返回 assistant message（含 content 或 tool_calls）。
     * tools 为 OpenAI function calling 格式，可空。端点由 AiRouter 解析。
     */
    public Map<String, Object> chatRaw(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        return chatRaw(router.resolve(), messages, tools);
    }

    /** 指定端点的对话（供测试连接等场景绕过路由） */
    @SuppressWarnings("unchecked")
    public Map<String, Object> chatRaw(AiRouter.LlmEndpoint ep,
                                       List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", ep.model());
        body.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }

        try {
            Map<?, ?> resp = client(ep).post()
                    .uri("/chat/completions")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            List<?> choices = (List<?>) resp.get("choices");
            if (choices == null || choices.isEmpty()) return Map.of("role", "assistant", "content", "(模型无返回)");
            Map<?, ?> choice = (Map<?, ?>) choices.get(0);
            return (Map<String, Object>) choice.get("message");
        } catch (RestClientResponseException e) {
            // 上游错误信息透传（网页桥接的 503 未登录 / 502 站点改版等提示都在响应体里）
            String detail = e.getResponseBodyAsString();
            String msg = (detail != null && !detail.isBlank()) ? detail : e.getMessage();
            throw new IllegalStateException("[" + ep.label() + "] " + msg, e);
        }
    }
}
