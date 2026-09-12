package com.local.erp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.entity.Partner;
import com.local.erp.entity.Product;
import com.local.erp.entity.StockRecord;
import com.local.erp.mapper.PartnerMapper;
import com.local.erp.mapper.ProductMapper;
import com.local.erp.mapper.StockRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存=流水聚合（流水的唯一入口是单据过账 BillService）。
 * 多仓库：库存按 仓库×商品 维度聚合。
 */
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockRecordMapper recordMapper;
    private final ProductMapper productMapper;
    private final PartnerMapper partnerMapper;

    /** 当前总库存（跨仓库合计）：productId -> 数量 */
    public Map<Long, Integer> currentStock() {
        Map<Long, Integer> stock = new HashMap<>();
        for (StockRecord r : recordMapper.selectList(null)) {
            stock.merge(r.getProductId(), r.getQuantity(), Integer::sum);
        }
        return stock;
    }

    /** 分仓库库存：warehouseId -> (productId -> 数量) */
    public Map<Long, Map<Long, Integer>> stockByWarehouse() {
        Map<Long, Map<Long, Integer>> result = new HashMap<>();
        for (StockRecord r : recordMapper.selectList(null)) {
            Long wid = r.getWarehouseId() == null ? 0L : r.getWarehouseId();
            result.computeIfAbsent(wid, k -> new HashMap<>())
                  .merge(r.getProductId(), r.getQuantity(), Integer::sum);
        }
        return result;
    }

    /**
     * 库存预警：no_alert=1 的商品（同行调货等）不监控。
     * 安全库存 >0：低于安全库存预警；安全库存 =0：缺货（库存≤0）预警。
     */
    public List<Product> lowStock() {
        Map<Long, Integer> stock = currentStock();
        return productMapper.selectList(new LambdaQueryWrapper<Product>().eq(Product::getEnabled, 1))
                .stream()
                .filter(p -> nzI(p.getNoAlert()) == 0)
                .filter(p -> {
                    int cur = stock.getOrDefault(p.getId(), 0);
                    int safe = nzI(p.getSafeStock());
                    return safe > 0 ? cur < safe : cur <= 0;
                })
                .toList();
    }

    private int nzI(Integer i) { return i == null ? 0 : i; }

    /**
     * 同行货源台账：所有带供应商的采购流水按 供应商×商品 聚合，
     * 输出每个同行的每个商品：最近一次进价、最近进购时间、累计进购数量。
     * partnerId 传 null 查全部；keyword 匹配商品名/SKU/条码。按最近时间倒序。
     */
    public List<Map<String, Object>> peerSources(Long partnerId, String keyword) {
        Map<Long, Product> products = productMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.toMap(Product::getId, p -> p, (a, b) -> a));
        Map<Long, String> partnerNames = new HashMap<>();
        for (Partner pt : partnerMapper.selectList(null)) partnerNames.put(pt.getId(), pt.getName());

        String kw = keyword == null ? "" : keyword.trim().toLowerCase();

        // 按 供应商×商品 聚合：最近价/最近时间（created_at 大者）/累计数量
        class Agg {
            double lastPrice; String lastTime = ""; int totalQty;
        }
        Map<String, Agg> agg = new HashMap<>();
        Map<String, Long> keyPartner = new HashMap<>();
        Map<String, Long> keyProduct = new HashMap<>();

        List<StockRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<StockRecord>().eq(StockRecord::getType, "PURCHASE"));
        for (StockRecord r : records) {
            if (r.getPartnerId() == null) continue;              // 无供应商的入库（盘盈等）不算货源
            if (partnerId != null && !partnerId.equals(r.getPartnerId())) continue;
            Product p = products.get(r.getProductId());
            if (p == null) continue;
            if (!kw.isEmpty() && !(nz(p.getName()) + " " + nz(p.getSku()) + " " + nz(p.getBarcode()))
                    .toLowerCase().contains(kw)) continue;

            String key = r.getPartnerId() + ":" + r.getProductId();
            Agg a = agg.get(key);
            if (a == null) { a = new Agg(); agg.put(key, a); keyPartner.put(key, r.getPartnerId()); keyProduct.put(key, r.getProductId()); }
            String cur = nz(r.getCreatedAt());
            if (cur.compareTo(a.lastTime) > 0) { a.lastTime = cur; a.lastPrice = r.getPrice() == null ? 0 : r.getPrice(); }
            a.totalQty += r.getQuantity();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (var e : agg.entrySet()) {
            Agg a = e.getValue();
            Long ptId = keyPartner.get(e.getKey());
            Product p = products.get(keyProduct.get(e.getKey()));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("partnerId", ptId);
            m.put("partnerName", partnerNames.getOrDefault(ptId, "未知供应商"));
            m.put("productId", p.getId());
            m.put("productName", p.getName());
            m.put("category", nz(p.getCategory()));
            m.put("lastPrice", a.lastPrice);
            m.put("totalQty", a.totalQty);
            m.put("lastTime", a.lastTime);
            m.put("salePrice", p.getSalePrice());
            result.add(m);
        }
        result.sort((a, b) -> String.valueOf(b.get("lastTime")).compareTo(String.valueOf(a.get("lastTime"))));
        return result;
    }

    private String nz(String s) { return s == null ? "" : s; }
}
