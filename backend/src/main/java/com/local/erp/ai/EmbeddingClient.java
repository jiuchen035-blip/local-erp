package com.local.erp.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 向量模型客户端：OpenAI 兼容 /embeddings 协议。
 * 兼容 智谱(embedding-3)、硅基流动(bge-m3)、本地 Ollama(bge-m3) 等。
 */
@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    private final AiProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isVectorMode() {
        AiProperties.Embedding e = props.getEmbedding();
        return e.isEnabled() && e.getBaseUrl() != null && e.getModel() != null;
    }

    /** 批量向量化，返回与输入顺序一致的向量列表 */
    public List<double[]> embed(List<String> texts) {
        AiProperties.Embedding e = props.getEmbedding();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (e.getApiKey() != null && !e.getApiKey().isBlank()) headers.setBearerAuth(e.getApiKey());

        RestClient client = RestClient.builder()
                .baseUrl(e.getBaseUrl())
                .defaultHeaders(h -> h.addAll(headers))
                .build();

        Map<?, ?> resp = client.post()
                .uri("/embeddings")
                .body(Map.of("model", e.getModel(), "input", texts))
                .retrieve()
                .body(Map.class);

        List<?> data = (List<?>) resp.get("data");
        return data.stream()
                .map(d -> {
                    List<?> vec = (List<?>) ((Map<?, ?>) d).get("embedding");
                    return vec.stream().mapToDouble(v -> ((Number) v).doubleValue()).toArray();
                })
                .toList();
    }

    public static double[] parseEmbedding(ObjectMapper om, String json) {
        try {
            double[] v = om.readValue(json, double[].class);
            return v;
        } catch (Exception ex) {
            return null;
        }
    }

    public static double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }
}
