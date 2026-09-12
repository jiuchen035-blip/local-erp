package com.local.erp.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 模型端点路由：按当前配置解析本次请求实际使用的模型来源。
 * 优先级（provider=auto 或当前配置不可用时）：
 *   ① 云端 API（已填有效 Key）→ ② 本地 Ollama（在线且有模型）→ ③ 网页版模型桥接（免 API）。
 * 解析结果只作用于本次请求，不污染全局 ai-settings 配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRouter {

    private final AiProperties props;
    private final WebLlmBridgeService bridge;

    /** 一次请求实际使用的模型端点 */
    public record LlmEndpoint(String provider, String baseUrl, String apiKey, String model, String label) {}

    /** 上游错误信息缓存（供 /api/ai/source 展示降级原因） */
    private volatile String lastFallbackReason = "";

    public String lastFallbackReason() { return lastFallbackReason; }

    public synchronized LlmEndpoint resolve() {
        return resolve(true);
    }

    /** @param ensureBridge false 时只读解析（状态展示用），不触发桥接拉起 */
    public synchronized LlmEndpoint resolve(boolean ensureBridge) {
        String provider = props.getProvider();
        boolean auto = "auto".equals(provider);
        lastFallbackReason = "";

        // 1. 显式选择网页版：直接走桥接
        if ("webchat".equals(provider)) {
            return webEndpoint(ensureBridge);
        }

        // 2. API 配置可用（有真实 Key 且 baseUrl/model 齐全；占位 Key 不算）
        boolean hasKey = props.getApiKey() != null && !props.getApiKey().isBlank()
                && !props.getApiKey().contains("填入");
        boolean apiUsable = hasKey
                && props.getBaseUrl() != null && !props.getBaseUrl().isBlank()
                && props.getModel() != null && !props.getModel().isBlank();
        if (apiUsable) {
            return new LlmEndpoint(provider, props.getBaseUrl(), props.getApiKey(),
                    props.getModel(), providerLabel(provider));
        }
        if (!auto && !"ollama".equals(provider)) {
            lastFallbackReason = "当前厂家（" + providerLabel(provider) + "）未配置有效 API Key";
        }

        // 3. 本地 Ollama
        String ollamaModel = probeOllama();
        if (ollamaModel != null) {
            if (!auto) lastFallbackReason += "，已自动降级到本地 Ollama";
            return new LlmEndpoint("ollama", "http://localhost:11434/v1", "",
                    ollamaModel, "本地模型（Ollama）");
        }

        // 4. 网页版桥接
        if (!auto) lastFallbackReason += "，本地 Ollama 不在线，已自动降级到网页版模型";
        return webEndpoint(ensureBridge);
    }

    private LlmEndpoint webEndpoint(boolean ensureBridge) {
        if (ensureBridge) {
            bridge.ensureRunning();
        }
        String model = (props.getModel() != null && props.getModel().endsWith("-web"))
                ? props.getModel() : WebLlmBridgeService.MODELS.get(0);
        return new LlmEndpoint("webchat", WebLlmBridgeService.BASE_URL + "/v1", "",
                model, "网页版模型（免API）");
    }

    /** Ollama 在线且有已安装模型时返回模型名，否则 null。带 30s 结果缓存避免每请求都探测。 */
    private volatile long ollamaProbeAt = 0;
    private volatile String ollamaProbeResult;

    private String probeOllama() {
        long now = System.currentTimeMillis();
        if (now - ollamaProbeAt < 30_000) return ollamaProbeResult;
        String result = null;
        try {
            String tags = RestClient.create().get()
                    .uri("http://localhost:11434/api/tags")
                    .retrieve().body(String.class);
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<?, ?> map = om.readValue(tags, Map.class);
            if (map.get("models") instanceof List<?> list && !list.isEmpty()) {
                result = String.valueOf(((Map<?, ?>) list.get(0)).get("name"));
                // 用户指定过 ollama 模型且已安装则优先
                if ("ollama".equals(props.getProvider()) && props.getModel() != null && !props.getModel().isBlank()) {
                    for (Object o : list) {
                        if (props.getModel().equals(String.valueOf(((Map<?, ?>) o).get("name")))) {
                            result = props.getModel();
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) { }
        ollamaProbeAt = now;
        ollamaProbeResult = result;
        return result;
    }

    private String providerLabel(String provider) {
        AiSettingsService.Preset p = AiSettingsService.PRESETS.get(provider);
        return p != null ? p.name() : provider;
    }
}
