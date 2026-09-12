package com.local.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.entity.Bill;
import com.local.erp.entity.BillItem;
import com.local.erp.entity.Product;
import com.local.erp.entity.StockRecord;
import com.local.erp.mapper.BillItemMapper;
import com.local.erp.mapper.BillMapper;
import com.local.erp.mapper.ProductMapper;
import com.local.erp.mapper.StockRecordMapper;
import com.local.erp.mapper.WarehouseMapper;
import com.local.erp.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stock")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;
    private final StockRecordMapper recordMapper;
    private final WarehouseMapper warehouseMapper;
    private final ProductMapper productMapper;
    private final BillMapper billMapper;
    private final BillItemMapper billItemMapper;

    @GetMapping("/records")
    public List<StockRecord> records(@RequestParam(required = false) Long productId) {
        if (productId != null) {
            return recordMapper.selectList(new LambdaQueryWrapper<StockRecord>()
                    .eq(StockRecord::getProductId, productId)
                    .orderByDesc(StockRecord::getId));
        }
        return recordMapper.selectList(new LambdaQueryWrapper<StockRecord>()
                .orderByDesc(StockRecord::getId).last("limit 200"));
    }

    /** 分仓库库存明细 */
    @GetMapping("/by-warehouse")
    public List<Map<String, Object>> byWarehouse() {
        Map<Long, Map<Long, Integer>> byWh = stockService.stockByWarehouse();
        Map<Long, String> whNames = new HashMap<>();
        warehouseMapper.selectList(null).forEach(w -> whNames.put(w.getId(), w.getName()));
        Map<Long, String> productNames = new HashMap<>();
        productMapper.selectList(null).forEach(p -> productNames.put(p.getId(), p.getName()));

        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        byWh.forEach((wid, products) -> products.forEach((pid, qty) -> {
            Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put("productId", pid);
            m.put("productName", productNames.getOrDefault(pid, "#" + pid));
            m.put("warehouseId", wid);
            m.put("warehouseName", whNames.getOrDefault(wid, "未分仓库"));
            m.put("quantity", qty);
            rows.add(m);
        }));
        return rows;
    }

    /** 低库存预警列表（含当前库存） */
    @GetMapping("/low-stock")
    public List<Map<String, Object>> lowStock() {
        Map<Long, Integer> stock = stockService.currentStock();
        return stockService.lowStock().stream().map(p -> {
            Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("sku", p.getSku());
            m.put("category", p.getCategory());
            m.put("spec", p.getSpec());
            m.put("stock", stock.getOrDefault(p.getId(), 0));
            m.put("safeStock", p.getSafeStock());
            return m;
        }).toList();
    }

    /** 同行货源台账：每个供应商的每个商品——最近进价/累计进购量/最近时间（可按供应商、商品关键词筛选） */
    @GetMapping("/peer-sources")
    public List<Map<String, Object>> peerSources(@RequestParam(required = false) Long partnerId,
                                                 @RequestParam(required = false) String keyword) {
        return stockService.peerSources(partnerId, keyword);
    }

    /** 批次临期查询：采购登记过生产日期且商品设置了保质期天数，days天内到期的批次 */
    @GetMapping("/expiring-batches")
    public List<Map<String, Object>> expiringBatches(@RequestParam(defaultValue = "60") int days) {
        Map<Long, String> billTypes = new HashMap<>();
        billMapper.selectList(null).forEach(b -> billTypes.put(b.getId(), b.getType()));
        Map<Long, Product> products = new HashMap<>();
        productMapper.selectList(null).forEach(p -> products.put(p.getId(), p));

        LocalDate today = LocalDate.now();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (BillItem item : billItemMapper.selectList(null)) {
            Bill bill = billMapper.selectById(item.getBillId());
            if (bill == null || !"PURCHASE".equals(bill.getType())
                    || !"POSTED".equals(bill.getStatus())) continue;
            if (item.getProductionDate() == null || item.getProductionDate().isBlank()) continue;
            Product p = products.get(item.getProductId());
            if (p == null || p.getShelfLifeDays() == null || p.getShelfLifeDays() <= 0) continue;
            LocalDate expiry;
            try {
                expiry = LocalDate.parse(item.getProductionDate()).plusDays(p.getShelfLifeDays());
            } catch (Exception e) { continue; }
            long left = expiry.toEpochDay() - today.toEpochDay();
            if (left > days) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productName", p.getName());
            m.put("unit", p.getUnit() == null || p.getUnit().isBlank() ? "个" : p.getUnit());
            m.put("batchNo", item.getBatchNo());
            m.put("quantity", item.getQuantity());
            m.put("productionDate", item.getProductionDate());
            m.put("expiryDate", expiry.toString());
            m.put("daysLeft", left);
            m.put("expired", left < 0);
            rows.add(m);
        }
        rows.sort((a, b) -> Long.compare((long) a.get("daysLeft"), (long) b.get("daysLeft")));
        return rows;
    }

    /** 经营汇总。注意：只有真正的采购/销售单计入进出金额，
     *  调拨/报损/盘盈及退货冲正不算经营流水；毛利按流水上的结转成本（移动加权）计算。 */
    @GetMapping("/report")
    public Map<String, Double> report() {
        Map<Long, String> billTypes = new HashMap<>();
        billMapper.selectList(null).forEach(b -> billTypes.put(b.getId(), b.getType()));

        double purchase = 0, sale = 0, saleCost = 0;
        for (StockRecord r : recordMapper.selectList(null)) {
            String billType = r.getBillId() != null ? billTypes.get(r.getBillId()) : null;
            boolean isPurchase = "PURCHASE".equals(r.getType())
                    && (billType == null || "PURCHASE".equals(billType));
            boolean isSale = "SALE".equals(r.getType())
                    && (billType == null || "SALE".equals(billType));
            double amt = Math.abs(r.getQuantity()) * r.getPrice();
            if (isPurchase) purchase += amt;
            if (isSale) {
                sale += amt;
                saleCost += Math.abs(r.getQuantity()) * (r.getCostPrice() == null ? 0 : r.getCostPrice());
            }
        }
        Map<String, Double> m = new java.util.LinkedHashMap<>();
        m.put("totalPurchase", purchase);
        m.put("totalSale", sale);
        m.put("saleCost", saleCost);
        m.put("grossProfit", sale - saleCost);
        return m;
    }
}
