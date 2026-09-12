package com.local.erp.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.util.*;

/**
 * AI 厂家设置：主流厂家预设（baseUrl + 内置模型清单兜底），
 * 支持在线拉取 /v1/models 模型列表（Ollama 走 /api/tags），
 * 配置持久化到 data/ai-settings.json，运行时热生效（无需重启）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSettingsService {

    private final AiProperties props;
    private final WebLlmBridgeService bridge;
    private final AiRouter router;
    private final ObjectMapper om = new ObjectMapper();
    private static final File FILE = new File(com.local.erp.AppHome.dataDir(), "ai-settings.json");

    public record Preset(String name, String baseUrl, List<String> models) {}

    public static final Map<String, Preset> PRESETS = new LinkedHashMap<>();
    static {
        PRESETS.put("deepseek", new Preset("DeepSeek", "https://api.deepseek.com/v1",
                List.of("deepseek-chat", "deepseek-reasoner")));
        PRESETS.put("zhipu", new Preset("智谱GLM", "https://open.bigmodel.cn/api/paas/v4",
                List.of("glm-4-flash", "glm-4-flash-250414", "glm-4-air-250414", "glm-4-plus",
                        "glm-4.5", "glm-4.5-air", "glm-4.5-flash", "glm-4v-flash", "embedding-3")));
        PRESETS.put("qwen", new Preset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                List.of("qwen-plus", "qwen-max", "qwen-turbo", "qwen3-max",
                        "qwen2.5-72b-instruct", "qwen-long")));
        PRESETS.put("moonshot", new Preset("Kimi月之暗面", "https://api.moonshot.cn/v1",
                List.of("kimi-k2-0711-preview", "moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k")));
        PRESETS.put("siliconflow", new Preset("硅基流动", "https://api.siliconflow.cn/v1",
                List.of("deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-V3.1", "Qwen/Qwen2.5-72B-Instruct",
                        "Qwen/Qwen3-32B", "BAAI/bge-m3")));
        PRESETS.put("openai", new Preset("OpenAI", "https://api.openai.com/v1",
                List.of("gpt-4o", "gpt-4o-mini")));
        PRESETS.put("ollama", new Preset("Ollama本地", "http://localhost:11434/v1", List.of()));
        PRESETS.put("webchat", new Preset("网页版模型（免API）", "http://127.0.0.1:8317/v1",
                List.of("deepseek-web", "miaoxiang-web")));
        PRESETS.put("auto", new Preset("自动（推荐）", "", List.of()));
        PRESETS.put("custom", new Preset("自定义", "", List.of()));
    }

    /** 启动时加载持久化的设置（覆盖 application.yml 默认值） */
    @PostConstruct
    public void load() {
        if (!FILE.exists()) return;
        try {
            Map<String, Object> m = om.readValue(FILE, Map.class);
            props.setProvider(str(m.get("provider"), props.getProvider()));
            if (m.get("baseUrl") != null && !String.valueOf(m.get("baseUrl")).isBlank())
                props.setBaseUrl(String.valueOf(m.get("baseUrl")));
            if (m.get("apiKey") != null && !String.valueOf(m.get("apiKey")).isBlank())
                props.setApiKey(String.valueOf(m.get("apiKey")));
            if (m.get("model") != null && !String.valueOf(m.get("model")).isBlank())
                props.setModel(String.valueOf(m.get("model")));
            Map<String, Object> emb = (Map<String, Object>) m.get("embedding");
            if (emb != null) {
                props.getEmbedding().setEnabled(Boolean.TRUE.equals(emb.get("enabled")));
                if (emb.get("model") != null) props.getEmbedding().setModel(String.valueOf(emb.get("model")));
                if (emb.get("baseUrl") != null && !String.valueOf(emb.get("baseUrl")).isBlank())
                    props.getEmbedding().setBaseUrl(String.valueOf(emb.get("baseUrl")));
            }
            log.info("已加载 AI 设置: provider={} model={}", props.getProvider(), props.getModel());
        } catch (Exception e) {
            log.warn("读取 ai-settings.json 失败: {}", e.getMessage());
        }
    }

    /** 应用并持久化；apiKey 传空=保留原值。auto/webchat 无需 base-url */
    public synchronized Map<String, Object> apply(String provider, String baseUrl, String apiKey,
                                                  String model, boolean embEnabled, String embModel) {
        Preset preset = PRESETS.get(provider);
        boolean needsBaseUrl = !("auto".equals(provider) || "webchat".equals(provider) || "custom".equals(provider));
        String effectiveBase = "custom".equals(provider)
                ? baseUrl : (preset != null ? preset.baseUrl : baseUrl);
        if (needsBaseUrl && (effectiveBase == null || effectiveBase.isBlank()))
            throw new IllegalArgumentException("请填写 base-url");

        props.setProvider(provider);
        // auto 的 base-url 为空：不覆盖已保存的云端地址（切回 API 模式时无需重填）
        boolean keepBaseUrl = "auto".equals(provider) && (effectiveBase == null || effectiveBase.isBlank());
        if (!keepBaseUrl && effectiveBase != null)
            props.setBaseUrl(effectiveBase);
        if (apiKey != null && !apiKey.isBlank() && !apiKey.contains("填入")) {
            props.setApiKey(apiKey.trim());
        }
        props.setModel(model);
        props.getEmbedding().setEnabled(embEnabled);
        if (embModel != null && !embModel.isBlank()) props.getEmbedding().setModel(embModel.trim());
        props.getEmbedding().setBaseUrl(effectiveBase);
        props.getEmbedding().setApiKey(props.getApiKey());

        save();
        return status();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", props.getProvider());
        m.put("providerName", presetName(props.getProvider()));
        m.put("baseUrl", props.getBaseUrl());
        m.put("model", props.getModel());
        boolean hasKey = props.getApiKey() != null && !props.getApiKey().isBlank()
                && !props.getApiKey().contains("填入");
        m.put("hasKey", hasKey);
        m.put("keyMasked", hasKey ? "••••" + props.getApiKey().substring(Math.max(0, props.getApiKey().length() - 4)) : "");
        m.put("chatConfigured", props.chatConfigured());
        Map<String, Object> emb = new LinkedHashMap<>();
        emb.put("enabled", props.getEmbedding().isEnabled());
        emb.put("model", props.getEmbedding().getModel());
        m.put("embedding", emb);
        List<Map<String, Object>> presets = new ArrayList<>();
        PRESETS.forEach((k, v) -> presets.add(Map.of("key", k, "name", v.name())));
        m.put("presets", presets);
        return m;
    }

    /** 拉取模型列表：网页版直接返回桥接模型清单；auto 无固定模型；其余在线 /models 优先，失败回落内置清单 */
    public Map<String, Object> fetchModels(String provider, String apiKey, String baseUrlOverride) {
        if ("webchat".equals(provider)) {
            return Map.of("models", WebLlmBridgeService.MODELS, "source", "preset");
        }
        if ("auto".equals(provider)) {
            return Map.of("models", List.of(), "source", "none",
                    "hint", "自动模式无需选择模型：有 Key 走 API，Ollama 在线走本地，否则用网页版模型");
        }
        Preset preset = PRESETS.get(provider);
        String baseUrl = "custom".equals(provider)
                ? baseUrlOverride : (preset != null ? preset.baseUrl : baseUrlOverride);
        if (baseUrl == null || baseUrl.isBlank())
            return Map.of("models", List.of(), "source", "none");

        String key = (apiKey != null && !apiKey.isBlank()) ? apiKey.trim()
                : (Objects.equals(provider, props.getProvider()) ? props.getApiKey() : "");

        // 1. 标准 OpenAI 兼容 /models
        try {
            List<String> models = fetchOpenAiModels(baseUrl, key);
            if (!models.isEmpty()) return Map.of("models", models, "source", "live");
        } catch (Exception ignored) { }

        // 2. Ollama 本地 /api/tags
        if ("ollama".equals(provider)) {
            try {
                String tags = RestClient.create().get()
                        .uri("http://localhost:11434/api/tags").retrieve().body(String.class);
                Map<?, ?> map = om.readValue(tags, Map.class);
                List<String> models = new ArrayList<>();
                if (map.get("models") instanceof List<?> list) {
                    for (Object o : list) models.add(String.valueOf(((Map<?, ?>) o).get("name")));
                }
                Collections.sort(models);
                return Map.of("models", models, "source", "live");
            } catch (Exception e) {
                return Map.of("models", List.of(), "source", "offline",
                        "hint", "本地 Ollama 未运行或无模型，请先执行 ollama serve 并 ollama pull 模型");
            }
        }

        // 3. 内置清单兜底
        return Map.of("models", preset != null ? preset.models() : List.of(), "source", "preset");
    }

    @SuppressWarnings("unchecked")
    private List<String> fetchOpenAiModels(String baseUrl, String key) throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(baseUrl);
        if (key != null && !key.isBlank()) {
            builder.defaultHeaders(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + key));
        }
        String body = builder.build().get().uri("/models").retrieve().body(String.class);
        Map<?, ?> map = om.readValue(body, Map.class);
        List<String> models = new ArrayList<>();
        if (map.get("data") instanceof List<?> list) {
            for (Object o : list) models.add(String.valueOf(((Map<?, ?>) o).get("id")));
        }
        Collections.sort(models);
        return models;
    }

    /** 测试连接：webchat 探测桥接服务并检查网页登录状态；auto 输出当前实际路由；其余发一条最小补全请求 */
    public Map<String, Object> test(String provider, String baseUrl, String apiKey, String model) {
        if ("webchat".equals(provider)) {
            boolean running = bridge.isRunning();
            if (!running) {
                try {
                    bridge.ensureRunning();
                    running = true;
                } catch (Exception e) {
                    return Map.of("ok", false, "error", e.getMessage());
                }
            }
            // 检查所选模型对应网站的登录状态
            String site = model != null && model.startsWith("miaoxiang") ? "miaoxiang"
                    : model != null && model.startsWith("deepseek") ? "deepseek"
                    : WebLlmBridgeService.MODELS.get(0).split("-")[0];
            try {
                Map<?, ?> r = RestClient.builder().baseUrl(WebLlmBridgeService.BASE_URL).build()
                        .get().uri("/logged-in/" + site).retrieve().body(Map.class);
                boolean loggedIn = Boolean.TRUE.equals(r.get("loggedIn"));
                String siteName = "miaoxiang".equals(site) ? "妙想" : "DeepSeek";
                if (loggedIn) {
                    return Map.of("ok", true, "reply", siteName + "网页已登录，可以开始对话");
                }
                return Map.of("ok", false, "error", siteName + "网页未登录，请先点上方「" + siteName + " 登录」按钮完成登录");
            } catch (Exception e) {
                return Map.of("ok", running, "reply", "桥接服务运行中，但登录状态检测失败：" + e.getMessage());
            }
        }
        if ("auto".equals(provider)) {
            try {
                var ep = router.resolve();
                return Map.of("ok", true, "reply", "当前实际使用：" + ep.label() + "（" + ep.model() + "）"
                        + (router.lastFallbackReason().isEmpty() ? "" : "，" + router.lastFallbackReason()));
            } catch (Exception e) {
                return Map.of("ok", false, "error", e.getMessage());
            }
        }
        Preset preset = PRESETS.get(provider);
        String effectiveBase = "custom".equals(provider) ? baseUrl
                : (preset != null ? preset.baseUrl : baseUrl);
        try {
            RestClient.Builder builder = RestClient.builder().baseUrl(effectiveBase);
            if (apiKey != null && !apiKey.isBlank()) {
                builder.defaultHeaders(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim()));
            }
            Map<?, ?> resp = builder.build().post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", model, "max_tokens", 16,
                            "messages", List.of(Map.of("role", "user", "content", "请只回复两个字母：OK"))))
                    .retrieve().body(Map.class);
            List<?> choices = (List<?>) resp.get("choices");
            String reply = choices != null && !choices.isEmpty()
                    ? String.valueOf(((Map<?, ?>) ((List<?>) choices).get(0)).get("message")) : "";
            reply = reply.replaceAll(".*content=([^,}]+).*", "$1");
            return Map.of("ok", true, "reply", reply);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("401")) msg = "API Key 无效（401）";
            else if (msg.contains("404")) msg = "地址或模型不存在（404），请检查 base-url 和模型名";
            else if (msg.contains("Connect")) msg = "连不上服务器，请检查网络或本地服务是否启动";
            return Map.of("ok", false, "error", msg);
        }
    }

    private void save() {
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", props.getProvider());
            m.put("baseUrl", props.getBaseUrl());
            m.put("apiKey", props.getApiKey());
            m.put("model", props.getModel());
            Map<String, Object> emb = new LinkedHashMap<>();
            emb.put("enabled", props.getEmbedding().isEnabled());
            emb.put("model", props.getEmbedding().getModel());
            emb.put("baseUrl", props.getEmbedding().getBaseUrl());
            m.put("embedding", emb);
            om.writerWithDefaultPrettyPrinter().writeValue(FILE, m);
        } catch (Exception e) {
            log.warn("保存 ai-settings.json 失败: {}", e.getMessage());
        }
    }

    private String presetName(String key) {
        Preset p = PRESETS.get(key);
        return p != null ? p.name() : key;
    }

    private String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }
}
