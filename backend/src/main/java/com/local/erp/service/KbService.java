package com.local.erp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.erp.ai.EmbeddingClient;
import com.local.erp.entity.KbChunk;
import com.local.erp.entity.KbDocument;
import com.local.erp.mapper.KbChunkMapper;
import com.local.erp.mapper.KbDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 知识库（RAG）：
 *  - 入库：长文本按段落切块（~500字），可向量化存 JSON
 *  - 检索：向量模式=余弦相似度暴力检索（小规模够用）；未配置向量模型自动降级为关键词检索
 *  - 后续量大可换 sqlite-vss，接口不变
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbService {

    private final KbDocumentMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final EmbeddingClient embeddingClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final int CHUNK_SIZE = 500;

    public KbDocument addDocument(String title, String text) {
        return addDocument(title, text, "manual");
    }

    public KbDocument addDocument(String title, String text, String source) {
        KbDocument doc = new KbDocument();
        doc.setTitle(title);
        doc.setSource(source);
        docMapper.insert(doc);

        for (String piece : chunk(text)) {
            KbChunk chunk = new KbChunk();
            chunk.setDocId(doc.getId());
            chunk.setContent(piece);
            chunkMapper.insert(chunk);
        }
        if (embeddingClient.isVectorMode()) reembedDoc(doc.getId());
        return doc;
    }

    /** 按段落聚合切块，超长段落硬切 */
    List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : text.split("\n+")) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            while (p.length() > CHUNK_SIZE) {
                chunks.add(p.substring(0, CHUNK_SIZE));
                p = p.substring(CHUNK_SIZE);
            }
            if (cur.length() + p.length() > CHUNK_SIZE && cur.length() > 0) {
                chunks.add(cur.toString().trim());
                cur = new StringBuilder();
            }
            cur.append(p).append('\n');
        }
        if (cur.length() > 0) chunks.add(cur.toString().trim());
        return chunks;
    }

    /** 为单个文档的全部切片重建向量 */
    public void reembedDoc(Long docId) {
        if (!embeddingClient.isVectorMode()) return;
        List<KbChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocId, docId));
        if (chunks.isEmpty()) return;
        try {
            List<double[]> vectors = embeddingClient.embed(chunks.stream().map(KbChunk::getContent).toList());
            for (int i = 0; i < chunks.size(); i++) {
                chunks.get(i).setEmbedding(objectMapper.writeValueAsString(vectors.get(i)));
                chunkMapper.updateById(chunks.get(i));
            }
        } catch (Exception e) {
            log.warn("向量化失败，降级关键词检索: {}", e.getMessage());
        }
    }

    /** 全库重建向量（切换向量模型后调用） */
    public int reembedAll() {
        List<Long> docIds = docMapper.selectList(null).stream().map(KbDocument::getId).toList();
        docIds.forEach(this::reembedDoc);
        return docIds.size();
    }

    /** 检索：返回 [{docId, docTitle, content, score}] */
    public List<Map<String, Object>> search(String query, int topK) {
        List<KbChunk> all = chunkMapper.selectList(null);
        if (all.isEmpty()) return List.of();

        Map<Long, String> titles = new HashMap<>();
        docMapper.selectList(null).forEach(d -> titles.put(d.getId(), d.getTitle()));

        List<Map<String, Object>> scored = new ArrayList<>();
        if (embeddingClient.isVectorMode()) {
            try {
                double[] qv = embeddingClient.embed(List.of(query)).get(0);
                for (KbChunk c : all) {
                    if (c.getEmbedding() == null) continue;
                    double[] cv = EmbeddingClient.parseEmbedding(objectMapper, c.getEmbedding());
                    if (cv == null) continue;
                    scored.add(buildHit(titles, c, EmbeddingClient.cosine(qv, cv)));
                }
            } catch (Exception e) {
                log.warn("向量检索失败，降级关键词: {}", e.getMessage());
                keywordScore(scored, titles, all, query);
            }
        } else {
            keywordScore(scored, titles, all, query);
        }

        scored.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    /**
     * 关键词检索（中文友好）：整词命中权重高，同时用2字滑窗切词兜底，
     * 如“怎么冲正单据”能通过 2-gram“冲正”命中内容。
     */
    private void keywordScore(List<Map<String, Object>> scored, Map<Long, String> titles, List<KbChunk> all, String query) {
        Set<String> grams = new LinkedHashSet<>();
        for (String term : query.split("[\\s，。？！、,.?!:：]+")) {
            if (term.isEmpty()) continue;
            grams.add(term);
            for (int i = 0; i + 2 <= term.length(); i++) grams.add(term.substring(i, i + 2));
        }
        for (KbChunk c : all) {
            double score = 0;
            for (String g : grams) {
                int n = countOccurrences(c.getContent(), g);
                if (n > 0) score += Math.min(g.length(), 6) * n;
            }
            if (score > 0) scored.add(buildHit(titles, c, score));
        }
    }

    private Map<String, Object> buildHit(Map<Long, String> titles, KbChunk c, double score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docId", c.getDocId());
        m.put("docTitle", titles.getOrDefault(c.getDocId(), "未命名"));
        m.put("content", c.getContent());
        m.put("score", Math.round(score * 1000) / 1000.0);
        return m;
    }

    private int countOccurrences(String text, String term) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(term, idx)) >= 0) { count++; idx += term.length(); }
        return count;
    }

    public void deleteDocument(Long docId) {
        chunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocId, docId));
        docMapper.deleteById(docId);
    }
}
