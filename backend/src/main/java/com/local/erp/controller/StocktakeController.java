package com.local.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.dto.BillDto;
import com.local.erp.entity.Bill;
import com.local.erp.entity.BillItem;
import com.local.erp.entity.Product;
import com.local.erp.mapper.BillItemMapper;
import com.local.erp.mapper.BillMapper;
import com.local.erp.mapper.ProductMapper;
import com.local.erp.service.StockService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 库存盘点：按仓库列出账面库存，录实盘数后自动生成 盘盈(GAIN)/报损(LOSS) 单并过账。
 * 盘点单价一律按当时移动加权成本，不产生毛利污染。
 */
@RestController
@RequestMapping("/api/stocktake")
@RequiredArgsConstructor
public class StocktakeController {

    private final StockService stockService;
    private final ProductMapper productMapper;
    private final BillMapper billMapper;
    private final BillItemMapper itemMapper;
    private final com.local.erp.service.BillService billService;

    /** 某仓库的账面库存清单（盘点底表） */
    @GetMapping
    public List<Map<String, Object>> sheet(@RequestParam Long warehouseId) {
        Map<Long, Map<Long, Integer>> byWh = stockService.stockByWarehouse();
        Map<Long, Integer> whStock = byWh.getOrDefault(warehouseId, Map.of());
        return productMapper.selectList(new LambdaQueryWrapper<Product>().eq(Product::getEnabled, 1))
                .stream().map(p -> {
                    Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("productId", p.getId());
                    m.put("name", p.getName());
                    m.put("sku", p.getSku());
                    m.put("unit", p.getUnit() == null || p.getUnit().isBlank() ? "个" : p.getUnit());
                    m.put("bookQty", whStock.getOrDefault(p.getId(), 0));
                    return m;
                }).toList();
    }

    @Data
    public static class StocktakeReq {
        private Long warehouseId;
        private String remark;
        private List<Line> lines;
        @Data
        public static class Line {
            private Long productId;
            private Integer actualQty;
        }
    }

    /** 提交盘点结果：差异>0生成盘盈单，<0生成报损单（分别整张过账） */
    @PostMapping
    public Map<String, Object> submit(@RequestBody StocktakeReq req) {
        if (req.getWarehouseId() == null) throw new IllegalArgumentException("请选择仓库");
        if (req.getLines() == null || req.getLines().isEmpty()) throw new IllegalArgumentException("没有盘点数据");

        Map<Long, Map<Long, Integer>> byWh = stockService.stockByWarehouse();
        Map<Long, Integer> whStock = byWh.getOrDefault(req.getWarehouseId(), Map.of());

        List<BillDto.Item> gainLines = new ArrayList<>();
        List<BillDto.Item> lossLines = new ArrayList<>();
        StringBuilder trace = new StringBuilder();
        for (StocktakeReq.Line l : req.lines) {
            if (l.getProductId() == null || l.getActualQty() == null) continue;
            Product p = productMapper.selectById(l.getProductId());
            if (p == null) continue;
            int book = whStock.getOrDefault(l.getProductId(), 0);
            int diff = l.getActualQty() - book;
            if (diff == 0) continue;
            double avgCost = p.getAvgCost() != null ? p.getAvgCost() : p.getCostPrice();
            BillDto.Item item = new BillDto.Item();
            item.setProductId(l.getProductId());
            item.setQuantity(Math.abs(diff));
            item.setPrice(avgCost);
            (diff > 0 ? gainLines : lossLines).add(item);
            trace.append(String.format("%s 账面%d 实盘%d(%+d)；", p.getName(), book, l.getActualQty(), diff));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gainBillNo", null);
        result.put("lossBillNo", null);
        result.put("diffCount", gainLines.size() + lossLines.size());
        result.put("trace", trace.toString());
        if (gainLines.isEmpty() && lossLines.isEmpty()) {
            result.put("message", "账实相符，无差异");
            return result;
        }
        String remark = "盘点" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + (req.getRemark() == null || req.getRemark().isBlank() ? "" : " " + req.getRemark());
        if (!gainLines.isEmpty()) {
            BillDto dto = new BillDto();
            dto.setType("GAIN");
            dto.setWarehouseId(req.getWarehouseId());
            dto.setPaid(1);
            dto.setAutoPost(true);
            dto.setRemark(remark);
            dto.setItems(gainLines);
            Bill b = billService.createDraft(dto, user());
            billService.post(b.getId());
            result.put("gainBillNo", b.getBillNo());
        }
        if (!lossLines.isEmpty()) {
            BillDto dto = new BillDto();
            dto.setType("LOSS");
            dto.setWarehouseId(req.getWarehouseId());
            dto.setPaid(1);
            dto.setAutoPost(true);
            dto.setRemark(remark);
            dto.setItems(lossLines);
            Bill b = billService.createDraft(dto, user());
            billService.post(b.getId());
            result.put("lossBillNo", b.getBillNo());
        }
        return result;
    }

    private String user() {
        var u = com.local.erp.config.AuthInterceptor.currentUser();
        return u != null ? u.getUsername() : "";
    }
}
