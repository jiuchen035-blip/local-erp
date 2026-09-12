package com.local.erp.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 模型配置：统一 OpenAI 兼容协议。
 * 云端测试填 base-url + api-key；本地切换 Ollama 时 api-key 留空、
 * base-url 改为 http://localhost:11434/v1，model 如 qwen2.5:7b。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
    /** 厂家标识：deepseek/zhipu/qwen/moonshot/siliconflow/openai/ollama/custom */
    private String provider = "deepseek";
    private String baseUrl;
    private String apiKey;
    private String model;
    /** RAG 用向量库类型（预留）：sqlite-vss / chroma */
    private Rag rag = new Rag();
    /** 向量模型（embedding）：不启用时知识库自动降级为关键词检索 */
    private Embedding embedding = new Embedding();

    @Data
    public static class Rag {
        private boolean enabled;
    }

    @Data
    public static class Embedding {
        private boolean enabled;
        private String baseUrl;
        private String apiKey = "";
        private String model;
    }

    /** 是否已可用于对话：Ollama 本地无需 Key，网页版/自动模式无需 Key，其余需配置有效 Key */
    public boolean chatConfigured() {
        if ("webchat".equals(provider) || "auto".equals(provider)) return true;
        if (baseUrl == null || baseUrl.isBlank() || model == null || model.isBlank()) return false;
        if ("ollama".equals(provider)) return true;
        return apiKey != null && !apiKey.isBlank() && !apiKey.contains("填入");
    }
}
