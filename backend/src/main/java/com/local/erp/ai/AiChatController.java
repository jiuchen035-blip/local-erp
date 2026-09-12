package com.local.erp.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 助手入口：
 *  1. /api/ai/chat        智能助手（带全部工具：查商品/库存/往来、只读SQL统计、开单草稿——自由问答模式）
 *  2. /api/ai/query       Text2SQL 查经营数据 —— 强制只读，拦截任何写操作
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final ModelClient modelClient;
    private final AgentService agentService;
    private final JdbcTemplate jdbcTemplate;

    private static final String SCHEMA = """
            表结构（SQLite）：
            product(id, name 商品名, sku, category 类别, sale_price 售价, cost_price 成本价, safe_stock 安全库存, enabled, created_at)
            stock_record(id, product_id, type: PURCHASE采购入库/SALE销售出库/ADJUST盘整, quantity 数量(采购为正销售为负), price 单价, remark, created_at)
            库存 = stock_record.quantity 按 product_id 求和。
            """;

    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, String> body) {
        // 走 Agent 循环：需要数据时模型自己调工具查真实数据，需要开单时生成草稿
        Map<String, Object> r = agentService.run(body.getOrDefault("message", ""), AgentService.SYS_GENERAL);
        String answer = String.valueOf(r.get("reply"));
        Object draft = r.get("draft");
        if (draft instanceof Map<?, ?> d && d.get("billNo") != null) {
            answer += "\n\n📋 草稿单已生成，请在下方核对并确认过账：";
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("answer", answer);
        if (draft != null) result.put("draft", draft);
        return result;
    }

    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("message", "");
        String sql = modelClient.chat("""
                你是SQL生成器。根据以下SQLite表结构，把用户问题转成一条只读SELECT语句，直接输出SQL，不要任何解释和markdown代码块。
                只允许 SELECT。禁止 INSERT/UPDATE/DELETE/DROP/ALTER/ATTACH。
                %s
                """.formatted(SCHEMA), question).trim()
                .replaceAll("(?i)^```(sql)?|```$", "").trim();

        if (!sql.toUpperCase().startsWith("SELECT")) {
            return Map.of("sql", sql, "error", "仅允许只读查询");
        }
        var rows = jdbcTemplate.queryForList(sql);
        String summary = modelClient.chat("""
                你是经营分析助手。以下是SQL查询结果(JSON)，用两三句中文给商家解释结论，给出可执行建议。
                %s
                """.formatted(SCHEMA), rows.toString());
        return Map.of("sql", sql, "rows", rows, "summary", summary);
    }
}
