package com.local.erp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.erp.dto.BillDto;
import com.local.erp.entity.Bill;
import com.local.erp.entity.IntegrationChannel;
import com.local.erp.entity.PlatformOrder;
import com.local.erp.entity.Product;
import com.local.erp.entity.Warehouse;
import com.local.erp.integration.PlatformAdapter;
import com.local.erp.mapper.IntegrationChannelMapper;
import com.local.erp.mapper.PlatformOrderMapper;
import com.local.erp.mapper.ProductMapper;
import com.local.erp.mapper.WarehouseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 电商对接：通道配置 + 平台订单导入。
 * 导入 = 逐单独立事务：商品按名称匹配（无则自动建档，成本=售价保证毛利不虚报）
 * → 创建并过账销售单（现结）→ 落 platform_order 防重。库存不足的单失败不中断整体。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationService {

    private final IntegrationChannelMapper channelMapper;
    private final PlatformOrderMapper orderMapper;
    private final ProductMapper productMapper;
    private final WarehouseMapper warehouseMapper;
    private final BillService billService;
    private final ObjectMapper om = new ObjectMapper();

    public Map<String, Object> testConnection(IntegrationChannel ch, List<PlatformAdapter> adapters) {
        return adapters.stream().filter(a -> a.platform().equals(ch.getPlatform()))
                .findFirst()
                .map(a -> a.testConnection(ch))
                .orElseThrow(() -> new IllegalArgumentException("未知平台: " + ch.getPlatform()));
    }

    /**
     * 导入单个平台订单（独立事务）。
     * order: { orderNo, orderTime, buyer, remark, items:[{productName, quantity, price}] }
     */
    @Transactional
    public Map<String, Object> importOne(String platform, Map<String, Object> order, String username) throws Exception {
        String orderNo = str(order.get("orderNo"));
        if (orderNo == null || orderNo.isBlank()) throw new IllegalArgumentException("平台单号必填");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("订单没有商品明细");

        // 防重：同平台同单号只导一次
        Long dup = orderMapper.selectCount(new LambdaQueryWrapper<PlatformOrder>()
                .eq(PlatformOrder::getPlatform, platform)
                .eq(PlatformOrder::getPlatformOrderNo, orderNo));
        if (dup != null && dup > 0) return Map.of("status", "DUPLICATE", "orderNo", orderNo);

        Warehouse wh = warehouseMapper.selectList(
                new LambdaQueryWrapper<Warehouse>().orderByAsc(Warehouse::getId).last("limit 1"))
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("请先在仓库管理中创建仓库"));

        // 商品匹配/自动建档（成本=售价：毛利按0计，不虚报；提示用户事后修正成本）
        BillDto dto = new BillDto();
        dto.setType("SALE");
        dto.setWarehouseId(wh.getId());
        dto.setPaid(1);
        dto.setDiscount(100.0);
        String buyer = str(order.get("buyer"));
        dto.setRemark("电商导入 单号:" + orderNo + (buyer != null && !buyer.isBlank() ? " 买家:" + buyer : "")
                + (str(order.get("remark")) != null ? " " + str(order.get("remark")) : ""));
        dto.setAutoPost(true);

        int newProducts = 0;
        var billItems = new java.util.ArrayList<BillDto.Item>();
        // 自动新建档的商品没有库存：先按订单数量自动采购入库（成本=售价，毛利按0计不虚报）
        var autoStock = new java.util.ArrayList<BillDto.Item>();
        for (Map<String, Object> i : items) {
            String name = str(i.get("productName"));
            double qty = parseDouble(i.get("quantity"));
            double price = parseDouble(i.get("price"));
            if (name == null || name.isBlank() || qty <= 0) throw new IllegalArgumentException("明细行缺少商品名或数量");
            Product p = productMapper.selectList(new LambdaQueryWrapper<Product>()
                    .eq(Product::getName, name).last("limit 1")).stream().findFirst().orElse(null);
            if (p == null) {
                p = new Product();
                p.setName(name);
                p.setSku(skuOf(platform, orderNo, billItems.size() + 1));
                p.setCategory("电商导入");
                p.setSalePrice(price);
                p.setCostPrice(price);
                p.setSafeStock(0);
                productMapper.insert(p);
                newProducts++;
                BillDto.Item in = new BillDto.Item();
                in.setProductId(p.getId());
                in.setQuantity((int) Math.round(qty));
                in.setPrice(price);
                autoStock.add(in);
            }
            BillDto.Item it = new BillDto.Item();
            it.setProductId(p.getId());
            it.setQuantity((int) Math.round(qty));
            it.setPrice(price);
            billItems.add(it);
        }
        if (!autoStock.isEmpty()) {
            BillDto purchase = new BillDto();
            purchase.setType("PURCHASE");
            purchase.setWarehouseId(wh.getId());
            purchase.setPaid(1);
            purchase.setDiscount(100.0);
            purchase.setItems(autoStock);
            purchase.setRemark("电商导入自动入库 单号:" + orderNo);
            Bill pb = billService.createDraft(purchase, username);
            billService.post(pb.getId());
        }
        dto.setItems(billItems);

        Bill bill = billService.createDraft(dto, username);
        billService.post(bill.getId());

        PlatformOrder po = new PlatformOrder();
        po.setPlatform(platform);
        po.setPlatformOrderNo(orderNo);
        po.setBuyer(buyer);
        po.setOrderTime(str(order.get("orderTime")));
        po.setRawJson(om.writeValueAsString(order));
        po.setBillId(bill.getId());
        po.setStatus("IMPORTED");
        orderMapper.insert(po);

        return Map.of("status", "IMPORTED", "orderNo", orderNo, "billNo", bill.getBillNo(),
                "newProducts", newProducts);
    }

    private String skuOf(String platform, String orderNo, int idx) {
        String prefix = switch (platform) {
            case "TAOBAO" -> "TB"; case "PDD" -> "PDD"; case "DOUYIN" -> "DY";
            case "MEITUAN" -> "MT"; default -> "EC";
        };
        // SKU 唯一且长度可控：平台前缀 + 日期 + 单号后8位 + 行号
        String tail = orderNo.length() > 8 ? orderNo.substring(orderNo.length() - 8) : orderNo;
        String base = prefix + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd")) + tail + "P" + idx;
        return base.length() > 40 ? base.substring(base.length() - 40) : base;
    }

    private String str(Object o) { return o == null ? null : o.toString().trim(); }
    private double parseDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString().replace(",", "")); } catch (Exception e) { return 0; }
    }
}
