package com.local.erp.controller;

import com.local.erp.entity.Product;
import com.local.erp.entity.StockRecord;
import com.local.erp.mapper.ProductMapper;
import com.local.erp.mapper.StockRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.Random;

/**
 * 演示数据：一键生成 30 天模拟经营流水，方便看图表效果。
 * 仅供体验，reset 可一键清空。
 */
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {

    private final ProductMapper productMapper;
    private final StockRecordMapper recordMapper;
    private final com.local.erp.mapper.WarehouseMapper warehouseMapper;
    private final Random random = new Random(42);

    private static final String[][] PRODUCTS = {
            {"农夫山泉550ml", "YS-001", "饮品", "2.0", "1.2", "50"},
            {"可口可乐330ml", "YS-002", "饮品", "3.0", "1.8", "40"},
            {"味全酸奶100g", "YS-003", "饮品", "5.5", "3.2", "30"},
            {"乐事薯片原味", "LS-001", "零食", "6.5", "4.0", "20"},
            {"奥利奥夹心饼干", "LS-002", "零食", "8.9", "5.5", "15"},
            {"蓝月亮洗衣液1kg", "RY-001", "日用", "19.9", "12.0", "10"},
            {"心相印抽纸", "RY-002", "日用", "12.5", "7.8", "25"},
            {"海飞丝洗发水", "RY-003", "日用", "39.9", "26.0", "8"}
    };

    @PostMapping("/seed")
    public Map<String, Object> seed() {
        if (productMapper.selectCount(null) > 0) {
            return Map.of("message", "已有数据，未生成。如需重新演示请先点“清空数据”");
        }
        int days = 30;
        var warehouses = warehouseMapper.selectList(null);
        Long whId = warehouses.isEmpty() ? null : warehouses.get(0).getId();
        for (String[] row : PRODUCTS) {
            Product p = new Product();
            p.setName(row[0]);
            p.setSku(row[1]);
            p.setCategory(row[2]);
            p.setSalePrice(Double.parseDouble(row[3]));
            p.setCostPrice(Double.parseDouble(row[4]));
            p.setSafeStock(Integer.parseInt(row[5]));
            p.setEnabled(1);
            productMapper.insert(p);

            // 首次大批量采购（第30天前）
            StockRecord purchase = new StockRecord();
            purchase.setProductId(p.getId());
            purchase.setType("PURCHASE");
            purchase.setQuantity(200 + random.nextInt(200));
            purchase.setPrice(p.getCostPrice());
            purchase.setCreatedAt(LocalDate.now().minusDays(days).toString() + " 09:00:00");
            purchase.setWarehouseId(whId);
            recordMapper.insert(purchase);

            // 每天 0~15 单销售
            for (int i = days - 1; i >= 0; i--) {
                int orders = random.nextInt(p.getSalePrice() < 10 ? 16 : 6);
                for (int o = 0; o < orders; o++) {
                    StockRecord sale = new StockRecord();
                    sale.setProductId(p.getId());
                    sale.setType("SALE");
                    sale.setQuantity(random.nextInt(p.getSalePrice() < 10 ? 6 : 3) + 1);
                    sale.setPrice(p.getSalePrice());
                    sale.setCreatedAt(LocalDate.now().minusDays(i).toString()
                            + String.format(" %02d:%02d:00", 8 + random.nextInt(12), random.nextInt(60)));
                    sale.setWarehouseId(whId);
                    recordMapper.insert(sale);
                }
            }
            // 中途补货一次
            StockRecord restock = new StockRecord();
            restock.setProductId(p.getId());
            restock.setType("PURCHASE");
            restock.setQuantity(100 + random.nextInt(100));
            restock.setPrice(p.getCostPrice());
            restock.setCreatedAt(LocalDate.now().minusDays(10).toString() + " 10:00:00");
            restock.setWarehouseId(whId);
            recordMapper.insert(restock);
        }
        return Map.of("message", "已生成 " + PRODUCTS.length + " 个商品、近30天模拟流水");
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        recordMapper.delete(null);
        productMapper.delete(null);
        return Map.of("message", "已清空全部数据");
    }
}
