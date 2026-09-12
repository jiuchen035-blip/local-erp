package com.local.erp.controller;

import com.local.erp.ai.AiProperties;
import com.local.erp.ai.ModelClient;
import com.local.erp.entity.Bill;
import com.local.erp.entity.Partner;
import com.local.erp.entity.Product;
import com.local.erp.entity.StockRecord;
import com.local.erp.mapper.BillMapper;
import com.local.erp.mapper.PartnerMapper;
import com.local.erp.mapper.ProductMapper;
import com.local.erp.mapper.StockRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 经营报表：月度/季度/年度销售情况聚合 + AI 经营分析报告生成。
 * 统计口径与看板一致：只有真正的采购/销售单计入经营流水，毛利按结转成本（移动加权）。
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportsController {

    private final StockRecordMapper recordMapper;
    private final ProductMapper productMapper;
    private final PartnerMapper partnerMapper;
    private final BillMapper billMapper;
    private final AiProperties aiProps;
    private final ModelClient modelClient;

    /** 解析周期范围 */
    private LocalDate[] range(String period, LocalDate d) {
        return switch (period.toUpperCase()) {
            case "MONTH" -> new LocalDate[]{YearMonth.from(d).atDay(1), YearMonth.from(d).atEndOfMonth()};
            case "QUARTER" -> {
                int q = (d.getMonthValue() - 1) / 3 + 1;
                LocalDate start = LocalDate.of(d.getYear(), (q - 1) * 3 + 1, 1);
                yield new LocalDate[]{start, start.plusMonths(3).minusDays(1)};
            }
            case "YEAR" -> new LocalDate[]{LocalDate.of(d.getYear(), 1, 1), LocalDate.of(d.getYear(), 12, 31)};
            default -> throw new IllegalArgumentException("period 应为 MONTH/QUARTER/YEAR");
        };
    }

    private String label(String period, LocalDate d) {
        return switch (period.toUpperCase()) {
            case "MONTH" -> d.getYear() + "年" + d.getMonthValue() + "月";
            case "QUARTER" -> d.getYear() + "年第" + ((d.getMonthValue() - 1) / 3 + 1) + "季度";
            default -> d.getYear() + "年度";
        };
    }

    @GetMapping("/sales")
    public Map<String, Object> sales(@RequestParam String period, @RequestParam String date) {
        LocalDate d = LocalDate.parse(date);
        LocalDate[] se = range(period, d);
        String start = se[0].toString(), end = se[1].toString();
        String fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(d);

        Map<Long, String> billTypes = new HashMap<>();
        billMapper.selectList(null).forEach(b -> billTypes.put(b.getId(), b.getType()));
        Map<Long, Product> products = new HashMap<>();
        productMapper.selectList(null).forEach(p -> products.put(p.getId(), p));
        Map<Long, Partner> partners = new HashMap<>();
        partnerMapper.selectList(null).forEach(p -> partners.put(p.getId(), p));

        // 趋势桶：月报按天，季报/年报按月
        boolean byDay = "MONTH".equalsIgnoreCase(period);
        LinkedHashMap<String, double[]> trend = new LinkedHashMap<>();
        if (byDay) {
            for (LocalDate x = se[0]; !x.isAfter(se[1]); x = x.plusDays(1))
                trend.put(x.toString().substring(5), new double[3]); // [销售额, 毛利, 销量]
        } else {
            for (LocalDate x = se[0]; !x.isAfter(se[1]); x = x.plusMonths(1))
                trend.put(x.getMonthValue() + "月", new double[3]);
        }

        double saleAmount = 0, saleCost = 0, purchaseAmount = 0;
        long saleQty = 0;
        Set<Long> saleBillIds = new HashSet<>();
        Map<String, double[]> byProduct = new HashMap<>();     // 商品 -> [数量, 金额, 毛利]
        Map<String, double[]> byCategory = new HashMap<>();    // 分类 -> [金额, 毛利]
        Map<String, double[]> byPartner = new HashMap<>();     // 客户 -> [金额, 数量]

        for (StockRecord r : recordMapper.selectList(null)) {
            String day = r.getCreatedAt() == null ? "" : r.getCreatedAt().substring(0, 10);
            boolean inRange = day.compareTo(start) >= 0 && day.compareTo(end) <= 0;
            double amt = Math.abs(r.getQuantity()) * r.getPrice();
            double cost = Math.abs(r.getQuantity()) * (r.getCostPrice() == null ? 0 : r.getCostPrice());
            String billType = r.getBillId() != null ? billTypes.get(r.getBillId()) : null;

            boolean isSale = "SALE".equals(r.getType()) && inRange
                    && (billType == null || "SALE".equals(billType));
            boolean isPurchase = "PURCHASE".equals(r.getType()) && inRange
                    && (billType == null || "PURCHASE".equals(billType));
            if (!isSale && !isPurchase) continue;

            if (isPurchase) { purchaseAmount += amt; continue; }

            saleAmount += amt;
            saleCost += cost;
            saleQty += Math.abs(r.getQuantity());
            if (r.getBillId() != null) saleBillIds.add(r.getBillId());

            String trendKey = byDay ? day.substring(5) : (day.substring(0, 7).substring(5).replaceFirst("^0", "") + "月");
            double[] tb = trend.computeIfAbsent(trendKey, k -> new double[3]);
            tb[0] += amt; tb[1] += amt - cost; tb[2] += Math.abs(r.getQuantity());

            Product p = products.get(r.getProductId());
            String pname = p != null ? p.getName() : "未知商品";
            byProduct.computeIfAbsent(pname, k -> new double[3]);
            byProduct.get(pname)[0] += Math.abs(r.getQuantity());
            byProduct.get(pname)[1] += amt;
            byProduct.get(pname)[2] += amt - cost;

            String cat = p != null && p.getCategory() != null && !p.getCategory().isBlank() ? p.getCategory() : "未分类";
            byCategory.computeIfAbsent(cat, k -> new double[2]);
            byCategory.get(cat)[0] += amt;
            byCategory.get(cat)[1] += amt - cost;

            String partnerName = r.getPartnerId() != null && partners.containsKey(r.getPartnerId())
                    ? partners.get(r.getPartnerId()).getName() : "散客/未登记";
            byPartner.computeIfAbsent(partnerName, k -> new double[2]);
            byPartner.get(partnerName)[0] += amt;
            byPartner.get(partnerName)[1] += Math.abs(r.getQuantity());
        }

        double profit = saleAmount - saleCost;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("saleAmount", r2(saleAmount));
        summary.put("saleCost", r2(saleCost));
        summary.put("profit", r2(profit));
        summary.put("profitRate", saleAmount > 0 ? r2(profit / saleAmount * 100) : 0);
        summary.put("saleQty", saleQty);
        summary.put("billCount", saleBillIds.size());
        summary.put("purchaseAmount", r2(purchaseAmount));

        List<Map<String, Object>> trendRows = new ArrayList<>();
        trend.forEach((k, v) -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("label", k);
            m.put("amount", r2(v[0]));
            m.put("profit", r2(v[1]));
            m.put("qty", (long) v[2]);
            trendRows.add(m);
        });

        List<Map<String, Object>> topProducts = byProduct.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]))
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("name", e.getKey());
                    m.put("qty", (long) e.getValue()[0]);
                    m.put("amount", r2(e.getValue()[1]));
                    m.put("profit", r2(e.getValue()[2]));
                    return m;
                }).toList();

        List<Map<String, Object>> categoryDist = byCategory.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("category", e.getKey());
                    m.put("amount", r2(e.getValue()[0]));
                    m.put("profit", r2(e.getValue()[1]));
                    return m;
                }).toList();

        List<Map<String, Object>> partnerTop = byPartner.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(10)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("name", e.getKey());
                    m.put("amount", r2(e.getValue()[0]));
                    m.put("qty", (long) e.getValue()[1]);
                    return m;
                }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label(period, d));
        result.put("start", start);
        result.put("end", end);
        result.put("summary", summary);
        result.put("trend", trendRows);
        result.put("topProducts", topProducts);
        result.put("categoryDist", categoryDist);
        result.put("partnerTop", partnerTop);
        return result;
    }

    /** AI 经营分析报告：数据 → 大模型生成 Markdown 报告 */
    @PostMapping("/ai-summary")
    public Map<String, Object> aiSummary(@RequestBody Map<String, String> body) {
        if (!aiProps.chatConfigured()) {
            return Map.of("error", "请先在 application.yml 配置 ai.api-key，才能生成 AI 报告（可直接导出 Excel 版）");
        }
        String period = body.getOrDefault("period", "MONTH");
        Map<String, Object> data = sales(period, body.getOrDefault("date", LocalDate.now().toString()));

        String prompt = """
                你是资深经营分析师。请根据下面的进销存经营数据，为店主写一份%s经营分析报告。
                要求：
                1. 输出 Markdown 格式，中文，标题一级。
                2. 结构：一、总体概况（用数据说话）；二、销售趋势解读；三、明星商品与重点客户；四、商品结构分析；五、存在的问题与建议（至少3条具体可执行的建议）；六、下期展望。
                3. 语气务实接地气，避免空话套话；数字直接引用给出的数据，不要编造数据。
                4. 金额单位为元。
                
                报表数据JSON：
                %s
                """.formatted(String.valueOf(data.get("label")), data.toString());

        String markdown = modelClient.chat("你是中小商家信赖的经营分析师。", prompt);
        return Map.of("markdown", markdown, "label", data.get("label"));
    }

    private double r2(double v) { return Math.round(v * 100) / 100.0; }
}
