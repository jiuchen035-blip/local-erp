package com.local.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.ai.AiProperties;
import com.local.erp.ai.ModelClient;
import com.local.erp.entity.KbChunk;
import com.local.erp.entity.KbDocument;
import com.local.erp.mapper.KbChunkMapper;
import com.local.erp.mapper.KbDocumentMapper;
import com.local.erp.service.KbService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库（RAG）接口：文档管理、检索测试、知识库问答。
 */
@RestController
@RequestMapping("/api/kb")
@RequiredArgsConstructor
public class KbController {

    private final KbService kbService;
    private final KbDocumentMapper docMapper;
    private final KbChunkMapper chunkMapper;
    private final AiProperties aiProps;
    private final ModelClient modelClient;

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vectorMode", aiProps.getEmbedding().isEnabled());
        m.put("embeddingModel", aiProps.getEmbedding().getModel());
        m.put("chatConfigured", aiProps.chatConfigured());
        m.put("docCount", docMapper.selectCount(null));
        m.put("chunkCount", chunkMapper.selectCount(null));
        return m;
    }

    @GetMapping("/documents")
    public List<Map<String, Object>> documents() {
        Map<Long, Long> counts = chunkMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(KbChunk::getDocId, Collectors.counting()));
        return docMapper.selectList(new LambdaQueryWrapper<KbDocument>().orderByDesc(KbDocument::getId))
                .stream().map(d -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", d.getId());
                    m.put("title", d.getTitle());
                    m.put("source", d.getSource());
                    m.put("chunks", counts.getOrDefault(d.getId(), 0L));
                    m.put("createdAt", d.getCreatedAt());
                    return m;
                }).toList();
    }

    /** 查看文档：标题信息 + 全部切片内容（可核对入库效果） */
    @GetMapping("/documents/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        KbDocument doc = docMapper.selectById(id);
        if (doc == null) throw new IllegalArgumentException("文档不存在");
        List<Map<String, Object>> chunks = new ArrayList<>();
        int idx = 1;
        for (KbChunk c : chunkMapper.selectList(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocId, id).orderByAsc(KbChunk::getId))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", idx++);
            m.put("content", c.getContent());
            m.put("length", c.getContent().length());
            m.put("vectorized", c.getEmbedding() != null);
            m.put("createdAt", c.getCreatedAt());
            chunks.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", doc.getId());
        result.put("title", doc.getTitle());
        result.put("source", doc.getSource());
        result.put("createdAt", doc.getCreatedAt());
        result.put("chunks", chunks);
        return result;
    }

    @PostMapping("/documents")
    public Map<String, Object> add(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "").trim();
        String text = body.getOrDefault("text", "").trim();
        if (title.isEmpty() || text.isEmpty()) return Map.of("error", "标题和内容不能为空");
        KbDocument doc = kbService.addDocument(title, text);
        long chunks = chunkMapper.selectCount(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocId, doc.getId()));
        return Map.of("message", "已入库《" + title + "》，切分为 " + chunks + " 个片段", "id", doc.getId());
    }

    /** 文件上传入库：txt/md/csv/json/log 直接读文本；docx/xlsx 由 POI、pdf 由 PDFBox 解析提取文字 */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "文档" : file.getOriginalFilename();
        String lower = name.toLowerCase();
        String text;
        try {
            byte[] bytes = file.getBytes();
            if (lower.endsWith(".docx")) text = extractDocx(bytes);
            else if (lower.endsWith(".pdf")) text = extractPdf(bytes);
            else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) text = extractExcel(bytes);
            else text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return Map.of("error", "解析文件失败：" + e.getMessage());
        }
        if (text == null || text.isBlank())
            return Map.of("error", "未能从文件中提取到文字（扫描件/图片型文件暂不支持）");
        String title = name.replaceAll("\\.[^.]+$", "");
        KbDocument doc = kbService.addDocument(title, text.trim());
        long chunks = chunkMapper.selectCount(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getDocId, doc.getId()));
        return Map.of("message", "已入库《" + title + "》，切分为 " + chunks + " 个片段", "id", doc.getId());
    }

    private String extractDocx(byte[] bytes) throws Exception {
        try (var is = new java.io.ByteArrayInputStream(bytes);
             var doc = new org.apache.poi.xwpf.usermodel.XWPFDocument(is);
             var ex = new org.apache.poi.xwpf.extractor.XWPFWordExtractor(doc)) {
            return ex.getText();
        }
    }

    private String extractPdf(byte[] bytes) throws Exception {
        try (var doc = org.apache.pdfbox.pdmodel.PDDocument.load(bytes)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
        }
    }

    private String extractExcel(byte[] bytes) throws Exception {
        var sb = new StringBuilder();
        var fmt = new org.apache.poi.ss.usermodel.DataFormatter();
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(bytes))) {
            for (var sheet : wb) {
                for (var row : sheet) {
                    var cells = new ArrayList<String>();
                    for (var c : row) cells.add(fmt.formatCellValue(c).trim());
                    if (cells.stream().anyMatch(s -> !s.isEmpty())) sb.append(String.join("\t", cells)).append("\n");
                }
            }
        }
        return sb.toString();
    }

    @DeleteMapping("/documents/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        kbService.deleteDocument(id);
        return Map.of("message", "已删除");
    }

    /** 切换/更新向量模型后全库重建向量 */
    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        int n = kbService.reembedAll();
        return Map.of("message", "已为 " + n + " 个文档重建向量索引");
    }

    /** 检索测试：直接看命中的片段 */
    @PostMapping("/search")
    public Map<String, Object> search(@RequestBody Map<String, Object> body) {
        String query = String.valueOf(body.getOrDefault("query", ""));
        int topK = body.get("topK") == null ? 4 : Integer.parseInt(String.valueOf(body.get("topK")));
        return Map.of("chunks", kbService.search(query, topK));
    }

    /** 知识库问答：检索 top 片段 → 大模型结合上下文回答 */
    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("message", "").trim();
        List<Map<String, Object>> hits = kbService.search(question, 4);

        List<String> sources = hits.stream().map(h -> String.valueOf(h.get("docTitle"))).distinct().toList();
        if (hits.isEmpty()) {
            return Map.of("answer", "知识库里没有找到相关内容。可以到“知识库”页面上传操作手册或常见问题。", "sources", sources);
        }

        if (!aiProps.chatConfigured()) {
            return Map.of(
                    "answer", "【未配置模型 API-KEY，仅返回检索结果】\n" +
                            hits.stream().map(h -> "▶ " + h.get("content")).collect(Collectors.joining("\n\n")),
                    "sources", sources);
        }

        String context = hits.stream()
                .map(h -> "【来源:" + h.get("docTitle") + "】\n" + h.get("content"))
                .collect(Collectors.joining("\n\n---\n\n"));

        String answer = modelClient.chat("""
                你是本地进销存系统的智能助手。请仅根据下面的知识库内容回答用户问题，
                答案后用（来源：xxx）标注引用的文档。知识库中没有的信息就明确说不知道，不要编造。

                知识库内容：
                %s
                """.formatted(context), question);

        return Map.of("answer", answer, "sources", sources);
    }
}
