package com.local.erp.controller;

import com.local.erp.entity.Product;
import com.local.erp.entity.StockRecord;
import com.local.erp.mapper.BillMapper;
import com.local.erp.mapper.ProductMapper;
import com.local.erp.mapper.StockRecordMapper;
import com.local.erp.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final StockRecordMapper recordMapper;
    private final ProductMapper productMapper;
    private final StockService stockService;
    private final BillMapper billMapper;

    @GetMapping
    public Map<String, Object> dashboard() {
        List<StockRecord> records = recordMapper.selectList(null);
        Map<Long, Product> products = productMapper.selectList(null).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        // 按单据类型过滤：调拨/报损/盘盈/退货不计入经营流水
        Map<Long, String> billTypes = new HashMap<>();
        billMapper.selectList(null).forEach(b -> billTypes.put(b.getId(), b.getType()));

        String today = LocalDate.now().toString();
        String month = today.substring(0, 7);
        double todaySale = 0, monthSale = 0, todayProfit = 0, monthProfit = 0, inventoryValue = 0;
        Map<String, double[]> trend = new TreeMap<>();          // 日期 -> [销售额, 销量]
        Map<String, double[]> byProduct = new HashMap<>();      // 商品 -> [销量, 销售额]

        for (StockRecord r : records) {
            String billType = r.getBillId() != null ? billTypes.get(r.getBillId()) : null;
            boolean isSale = "SALE".equals(r.getType()) && (billType == null || "SALE".equals(billType));
            if (!isSale) continue;
            double amt = Math.abs(r.getQuantity()) * r.getPrice();
            double cost = Math.abs(r.getQuantity()) * (r.getCostPrice() == null ? 0 : r.getCostPrice());
            String d = r.getCreatedAt() == null ? "" : r.getCreatedAt().substring(0, 10);
            trend.computeIfAbsent(d, k -> new double[2]);
            trend.get(d)[0] += amt;
            trend.get(d)[1] += Math.abs(r.getQuantity());
            if (today.equals(d)) { todaySale += amt; todayProfit += amt - cost; }
            if (d.startsWith(month)) { monthSale += amt; monthProfit += amt - cost; }
            Product p = products.get(r.getProductId());
            if (p != null) {
                byProduct.computeIfAbsent(p.getName(), k -> new double[2]);
                byProduct.get(p.getName())[0] += Math.abs(r.getQuantity());
                byProduct.get(p.getName())[1] += amt;
            }
        }

        Map<Long, Integer> stock = stockService.currentStock();
        int totalStock = 0;
        for (Product p : products.values()) {
            int q = stock.getOrDefault(p.getId(), 0);
            totalStock += q;
            double unitCost = p.getAvgCost() != null ? p.getAvgCost() : p.getCostPrice();
            inventoryValue += q * unitCost;
        }

        // 近14天销量趋势（补零，保证曲线连续）
        List<Map<String, Object>> saleTrend = new ArrayList<>();
        for (int i = 13; i >= 0; i--) {
            String d = LocalDate.now().minusDays(i).toString();
            double[] v = trend.getOrDefault(d, new double[2]);
            saleTrend.add(Map.of("date", d.substring(5), "amount", v[0], "qty", v[1]));
        }

        List<Map<String, Object>> topProducts = byProduct.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]))
                .limit(5)
                .map(e -> Map.<String, Object>of("name", e.getKey(),
                        "qty", e.getValue()[0], "amount", e.getValue()[1]))
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("todaySale", todaySale);
        result.put("monthSale", monthSale);
        result.put("todayProfit", todayProfit);
        result.put("monthProfit", monthProfit);
        result.put("inventoryValue", inventoryValue);
        result.put("totalStock", totalStock);
        result.put("productCount", products.size());
        result.put("lowStockCount", stockService.lowStock().size());
        result.put("saleTrend", saleTrend);
        result.put("topProducts", topProducts);
        return result;
    }
}
