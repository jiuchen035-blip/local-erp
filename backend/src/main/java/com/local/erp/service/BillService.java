package com.local.erp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.dto.BillDto;
import com.local.erp.entity.*;
import com.local.erp.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 多行单据：草稿 → 过账（生成库存流水 + 移动加权成本）→ 可整单冲正。
 * 单据类型与库存方向：
 *   PURCHASE采购入库+  SALE销售出库-  PURCHASE_RETURN采购退货-  SALE_RETURN销售退货+
 *   LOSS报损-  GAIN盘盈+  TRANSFER调拨(出仓-入仓+)
 */
@Service
@RequiredArgsConstructor
public class BillService {

    private final BillMapper billMapper;
    private final BillItemMapper itemMapper;
    private final StockRecordMapper recordMapper;
    private final ProductMapper productMapper;
    private final WarehouseMapper warehouseMapper;
    private final PartnerMapper partnerMapper;
    private final com.local.erp.mapper.PeerStockMapper peerStockMapper;
    private final StockService stockService;
    private final VoucherService voucherService;
    private final FinanceSettingsService financeSettings;

    public static final List<String> TYPES = List.of(
            "PURCHASE", "SALE", "PURCHASE_RETURN", "SALE_RETURN", "LOSS", "GAIN", "TRANSFER");

    /** 挂账只对采购/销售有意义 */
    private static final Set<String> CREDITABLE = Set.of("PURCHASE", "SALE");
    private static final Map<String, String> REVERSE_TYPE = Map.of(
            "PURCHASE", "PURCHASE_RETURN", "PURCHASE_RETURN", "PURCHASE",
            "SALE", "SALE_RETURN", "SALE_RETURN", "SALE",
            "LOSS", "GAIN", "GAIN", "LOSS", "TRANSFER", "TRANSFER");

    public Bill createDraft(BillDto dto, String username) {
        if (dto.getType() == null || !TYPES.contains(dto.getType()))
            throw new IllegalArgumentException("非法单据类型: " + dto.getType());
        if (dto.getItems() == null || dto.getItems().isEmpty())
            throw new IllegalArgumentException("单据至少要有一行商品明细");
        if (dto.getWarehouseId() == null)
            throw new IllegalArgumentException("请选择仓库");
        if ("TRANSFER".equals(dto.getType())) {
            if (dto.getToWarehouseId() == null) throw new IllegalArgumentException("调拨单必须选择入仓仓库");
            if (dto.getToWarehouseId().equals(dto.getWarehouseId())) throw new IllegalArgumentException("调拨的出仓与入仓不能相同");
        }

        Bill bill = new Bill();
        bill.setBillNo(nextBillNo());
        bill.setType(dto.getType());
        bill.setPartnerId(dto.getPartnerId());
        bill.setWarehouseId(dto.getWarehouseId());
        bill.setToWarehouseId(dto.getToWarehouseId());
        bill.setStatus("DRAFT");
        bill.setPaid(CREDITABLE.contains(dto.getType()) && Integer.valueOf(0).equals(dto.getPaid()) ? 0 : 1);
        bill.setRemark(dto.getRemark());
        bill.setCreatedBy(username);
        // 整单折扣：1~100，空/非法按无折扣
        double disc = dto.getDiscount() == null ? 100 : dto.getDiscount();
        if (disc <= 0 || disc > 100) disc = 100;
        bill.setDiscount(disc);

        double total = 0;
        billMapper.insert(bill);
        for (BillDto.Item i : dto.getItems()) {
            if (i.getProductId() == null || i.getQuantity() == null || i.getQuantity() <= 0)
                throw new IllegalArgumentException("明细行数量必须大于0");
            Product p = productMapper.selectById(i.getProductId());
            if (p == null) throw new IllegalArgumentException("商品不存在: " + i.getProductId());
            double price = i.getPrice() != null ? i.getPrice()
                    : ("PURCHASE".equals(dto.getType()) || "PURCHASE_RETURN".equals(dto.getType())
                       ? p.getCostPrice() : p.getSalePrice());
            BillItem item = new BillItem();
            item.setBillId(bill.getId());
            item.setProductId(i.getProductId());
            item.setQuantity(i.getQuantity());
            item.setPrice(price);
            item.setBatchNo(i.getBatchNo());
            item.setProductionDate(i.getProductionDate());
            itemMapper.insert(item);
            total += i.getQuantity() * price * disc / 100;
        }
        bill.setTotalAmount(round2(total));
        billMapper.updateById(bill);
        return bill;
    }

    /**
     * 同行调货：创建两张关联草稿（同行采购入库 + 销售出库，销售单 peer_bill_id 关联采购单），不自动过账。
     * peerLines = [{stockId, quantity, salePrice, peerPrice}]（来自同行库存表）。
     * 同行行首次调货自动进入自己商品档案（安全库存 0 + 不监控预警）。人工确认后调 postWithPeer 一起过账。
     */
    @Transactional
    public Map<String, Object> createPeerDrafts(com.local.erp.dto.BillDto saleDto,
                                                com.local.erp.dto.BillDto purchaseDto,
                                                List<Map<String, Object>> peerLines, String username) {
        if (peerLines == null || peerLines.isEmpty())
            throw new IllegalArgumentException("同行调货必须至少有一行同行商品");
        if (purchaseDto.getPartnerId() == null)
            throw new IllegalArgumentException("请选择调货供应商（可直接输入新名称自动建档）");
        if (saleDto.getPartnerId() == null && purchaseDto.getPartnerId() != null) {
            // 销售未指定客户时沿用默认（可空）
        }
        List<BillDto.Item> purchaseItems = new ArrayList<>();
        List<BillDto.Item> salePeerItems = new ArrayList<>();
        List<Map<String, Object>> saleItemViews = new ArrayList<>();
        for (Map<String, Object> pl : peerLines) {
            long stockId = Long.parseLong(String.valueOf(pl.get("stockId")));
            int qty = (int) Double.parseDouble(String.valueOf(pl.get("quantity")));
            double salePrice = Double.parseDouble(String.valueOf(pl.get("salePrice")));
            double peerPrice = Double.parseDouble(String.valueOf(pl.get("peerPrice")));
            PeerStock ps = peerStockMapper.selectById(stockId);
            if (ps == null) throw new IllegalArgumentException("同行库存记录不存在: " + stockId);

            Product p = null;
            if (ps.getProductId() != null) {
                p = productMapper.selectById(ps.getProductId());
                // 关联商品被手动删除时不报错，降级为按名称复用/重新建档
            }
            if (p == null) {
                // 优先复用同名同单位的自有商品，避免档案里重复出现同名商品
                String peerUnit = ps.getUnit() == null ? "" : ps.getUnit();
                List<Product> same = productMapper.selectList(new LambdaQueryWrapper<Product>()
                        .eq(Product::getName, ps.getProductName())
                        .eq(Product::getEnabled, 1)
                        .orderByAsc(Product::getId));
                for (Product cand : same) {
                    String u = cand.getUnit() == null ? "" : cand.getUnit();
                    if (u.equals(peerUnit)) { p = cand; break; }
                }
            }
            if (p == null) {
                // 首次调货且没有可复用商品：建隐藏档案（enabled=0，商品库存页不展示，仅用于同行调货记账）
                p = new Product();
                p.setName(ps.getProductName());
                p.setSku(ps.getSku());
                p.setCategory(ps.getCategory());
                p.setUnit(ps.getUnit());
                p.setCostPrice(peerPrice);
                p.setSalePrice(salePrice);
                p.setSafeStock(0);
                p.setNoAlert(1);
                p.setEnabled(0);
                productMapper.insert(p);
            }
            ps.setProductId(p.getId());
            // 本次调货价写回同行库存，下次开单默认带出
            ps.setLastPrice(peerPrice);
            peerStockMapper.updateById(ps);

            BillDto.Item pi = new BillDto.Item();
            pi.setProductId(p.getId()); pi.setQuantity(qty); pi.setPrice(peerPrice);
            purchaseItems.add(pi);
            BillDto.Item si = new BillDto.Item();
            si.setProductId(p.getId()); si.setQuantity(qty); si.setPrice(salePrice);
            salePeerItems.add(si);
            Map<String, Object> view = new HashMap<>();
            view.put("productId", p.getId());
            view.put("productName", p.getName());
            view.put("quantity", qty);
            view.put("price", salePrice);
            saleItemViews.add(view);
        }

        purchaseDto.setItems(purchaseItems);
        Bill purchase = createDraft(purchaseDto, username);
        List<BillDto.Item> ownItems = saleDto.getItems() == null ? new ArrayList<>() : new ArrayList<>(saleDto.getItems());
        ownItems.addAll(salePeerItems);
        saleDto.setItems(ownItems);
        Bill sale = createDraft(saleDto, username);
        sale.setPeerBillId(purchase.getId());
        billMapper.updateById(sale);

        List<Map<String, Object>> ownItemViews = new ArrayList<>();
        for (BillDto.Item it : saleDto.getItems()) {
            Product pp = productMapper.selectById(it.getProductId());
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("productName", pp == null ? "" : pp.getName());
            v.put("quantity", it.getQuantity());
            v.put("price", it.getPrice() == null ? 0 : it.getPrice());
            ownItemViews.add(v);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("purchase", purchase);
        result.put("sale", sale);
        result.put("saleItems", saleItemViews);
        result.put("ownItems", ownItemViews);
        result.put("total", sale.getTotalAmount());
        return result;
    }

    /** 确认过账同行调货：先过账关联的同行采购草稿（补库存），再过账销售草稿（同一事务） */
    @Transactional
    public Map<String, Object> postWithPeer(Long saleBillId) {
        Bill sale = billMapper.selectById(saleBillId);
        if (sale == null) throw new IllegalArgumentException("单据不存在");
        if (sale.getPeerBillId() == null) throw new IllegalArgumentException("该销售单没有关联的同行采购草稿");
        Bill purchase = post(sale.getPeerBillId());
        Bill posted = post(saleBillId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("purchase", purchase);
        result.put("sale", posted);
        return result;
    }

    /** 过账：校验库存 → 写流水 → 采购重算移动加权成本 → 单据置为已过账 */
    @Transactional
    public Bill post(Long billId) {
        Bill bill = billMapper.selectById(billId);
        if (bill == null) throw new IllegalArgumentException("单据不存在");
        if ("POSTED".equals(bill.getStatus())) throw new IllegalArgumentException("单据已过账，不能重复过账");
        List<BillItem> items = itemMapper.selectList(new LambdaQueryWrapper<BillItem>().eq(BillItem::getBillId, billId));
        if (items.isEmpty()) throw new IllegalArgumentException("单据没有明细行");

        Map<Long, Map<Long, Integer>> byWh = stockService.stockByWarehouse();
        double disc = bill.getDiscount() == null ? 100 : bill.getDiscount();

        for (BillItem item : items) {
            Product p = productMapper.selectById(item.getProductId());
            if (p == null)
                throw new IllegalArgumentException("明细里的商品已被删除，无法过账：请删除该草稿后重新开单");
            double avgCost = p.getAvgCost() != null ? p.getAvgCost() : p.getCostPrice();
            String type = bill.getType();
            // 折扣按行分摊：流水/应收/成本一律用折后价，保证单据金额与账一致
            double effPrice = round2(item.getPrice() * disc / 100);

            if ("TRANSFER".equals(type)) {
                int outQty = byWh.getOrDefault(bill.getWarehouseId(), Map.of()).getOrDefault(item.getProductId(), 0);
                if (outQty < item.getQuantity())
                    throw new IllegalArgumentException("调拨库存不足：" + p.getName() + " 在出仓仓库仅剩 " + outQty);
                insertRecord(bill, item, effPrice, -item.getQuantity(), avgCost);
                insertRecord(bill, item, effPrice, item.getQuantity(), avgCost);
                byWh.getOrDefault(bill.getWarehouseId(), new HashMap<>()).merge(item.getProductId(), -item.getQuantity(), Integer::sum);
                byWh.getOrDefault(bill.getToWarehouseId(), new HashMap<>()).merge(item.getProductId(), item.getQuantity(), Integer::sum);
                continue;
            }

            int signed = switch (type) {
                case "PURCHASE", "SALE_RETURN", "GAIN" -> item.getQuantity();
                case "SALE", "PURCHASE_RETURN", "LOSS" -> -item.getQuantity();
                default -> throw new IllegalArgumentException("非法类型 " + type);
            };

            if (signed < 0) {
                int cur = byWh.getOrDefault(bill.getWarehouseId(), Map.of()).getOrDefault(item.getProductId(), 0);
                if (cur < item.getQuantity()) {
                    String whName = warehouseName(bill.getWarehouseId());
                    throw new IllegalArgumentException("库存不足：" + p.getName()
                            + (whName == null ? "" : "（仓库「" + whName + "」）") + " 仅剩 " + cur + "，请改仓或减量");
                }
            }

            // 采购入库：移动加权平均成本 = (原均价*原总库存 + 本次金额) / (原总库存+本次数量)，采购成本按折后价
            if ("PURCHASE".equals(type)) {
                // 计税模式：库存/成本按不含税价入账（effPrice 为含税价，先价税分离）
                double netUnit = effPrice;
                if (financeSettings.get().isTaxEnabled()) {
                    double rate = p.getTaxRate() != null ? p.getTaxRate()
                            : financeSettings.get().getDefaultTaxRate();
                    netUnit = round2(effPrice / (1 + rate / 100));
                }
                int curTotal = Math.max(stockService.currentStock().getOrDefault(item.getProductId(), 0), 0);
                double newAvg = (avgCost * curTotal + item.getQuantity() * netUnit)
                        / (curTotal + item.getQuantity());
                p.setAvgCost(Math.round(newAvg * 10000) / 10000.0);
                productMapper.updateById(p);
                avgCost = p.getAvgCost();
            }
            insertRecord(bill, item, effPrice, signed, avgCost);
            byWh.getOrDefault(bill.getWarehouseId(), new HashMap<>())
                    .merge(item.getProductId(), signed, Integer::sum);
        }

        bill.setStatus("POSTED");
        bill.setPostedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        billMapper.updateById(bill);
        // 财务联动：过账即自动生成记账凭证（同一事务，原子生效；调拨不生成，冲正单生成反向凭证）
        voucherService.createFromBill(bill);
        return bill;
    }

    private void insertRecord(Bill bill, BillItem item, double effPrice, int signedQty, double avgCost) {
        StockRecord r = new StockRecord();
        r.setProductId(item.getProductId());
        r.setType(signedQty > 0 ? "PURCHASE" : "SALE"); // 流水只有进出两种物理方向
        r.setQuantity(signedQty);
        r.setPrice(effPrice);
        r.setPartnerId(bill.getPartnerId());
        r.setWarehouseId("TRANSFER".equals(bill.getType()) && signedQty > 0 ? bill.getToWarehouseId() : bill.getWarehouseId());
        r.setPaid(bill.getPaid());
        r.setCostPrice(avgCost);
        r.setBillId(bill.getId());
        r.setBillItemId(item.getId());
        r.setRemark(bill.getRemark());
        recordMapper.insert(r);
    }

    /** 整单冲正：生成镜像反向单据并立即过账，原单保留 */
    @Transactional
    public Bill reverse(Long billId) {
        Bill origin = billMapper.selectById(billId);
        if (origin == null) throw new IllegalArgumentException("单据不存在");
        if (!"POSTED".equals(origin.getStatus())) throw new IllegalArgumentException("草稿直接删除即可，无需冲正");
        if (origin.getRemark() != null && origin.getRemark().startsWith("冲正#"))
            throw new IllegalArgumentException("冲正单不能再冲正");

        Bill rev = new Bill();
        rev.setBillNo(nextBillNo());
        rev.setType(REVERSE_TYPE.get(origin.getType()));
        rev.setPartnerId(origin.getPartnerId());
        rev.setWarehouseId("TRANSFER".equals(origin.getType()) ? origin.getToWarehouseId() : origin.getWarehouseId());
        rev.setToWarehouseId("TRANSFER".equals(origin.getType()) ? origin.getWarehouseId() : null);
        rev.setStatus("DRAFT");
        rev.setPaid(1);
        rev.setDiscount(origin.getDiscount());
        rev.setRemark("冲正#" + origin.getBillNo());
        rev.setCreatedBy(origin.getCreatedBy());
        billMapper.insert(rev);

        double total = 0;
        for (BillItem oi : itemMapper.selectList(new LambdaQueryWrapper<BillItem>().eq(BillItem::getBillId, billId))) {
            BillItem ni = new BillItem();
            ni.setBillId(rev.getId());
            ni.setProductId(oi.getProductId());
            ni.setQuantity(oi.getQuantity());
            ni.setPrice(oi.getPrice());
            itemMapper.insert(ni);
            total += oi.getQuantity() * oi.getPrice() * rev.getDiscount() / 100;
        }
        rev.setTotalAmount(round2(total));
        billMapper.updateById(rev);
        Bill posted = post(rev.getId());

        // 原单已冲销：其挂账随之结清（应收/应付归零），流水同步置为已结
        if (Integer.valueOf(0).equals(origin.getPaid())) {
            origin.setPaid(1);
            billMapper.updateById(origin);
            recordMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<StockRecord>()
                    .eq(StockRecord::getBillId, origin.getId())
                    .set(StockRecord::getPaid, 1));
        }
        return posted;
    }

    private String nextBillNo() {
        String prefix = "DJ" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        // 取当天最大单号+1（按计数生成会因删除单据而撞号）
        String max = billMapper.selectList(new LambdaQueryWrapper<Bill>()
                .likeRight(Bill::getBillNo, prefix)
                .orderByDesc(Bill::getBillNo)
                .last("limit 1"))
                .stream().findFirst().map(Bill::getBillNo).orElse(null);
        int seq = 1;
        if (max != null && max.length() > prefix.length()) {
            try { seq = Integer.parseInt(max.substring(prefix.length())) + 1; } catch (Exception ignored) {}
        }
        return prefix + String.format("%04d", seq);
    }

    public Map<String, Object> detail(Long billId) {
        Bill bill = billMapper.selectById(billId);
        if (bill == null) throw new IllegalArgumentException("单据不存在");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bill", bill);
        m.put("items", itemMapper.selectList(new LambdaQueryWrapper<BillItem>().eq(BillItem::getBillId, billId))
                .stream().map(i -> {
                    Product p = productMapper.selectById(i.getProductId());
                    Map<String, Object> im = new LinkedHashMap<String, Object>();
                    im.put("productId", i.getProductId());
                    im.put("productName", p != null ? p.getName() : "#" + i.getProductId());
                    im.put("quantity", i.getQuantity());
                    im.put("price", i.getPrice());
                    double disc = bill.getDiscount() == null ? 100 : bill.getDiscount();
                    im.put("effPrice", round2(i.getPrice() * disc / 100));
                    im.put("amount", round2(i.getQuantity() * i.getPrice() * disc / 100));
                    im.put("batchNo", i.getBatchNo());
                    im.put("productionDate", i.getProductionDate());
                    return im;
                }).toList());
        m.put("warehouseName", warehouseName(bill.getWarehouseId()));
        if (bill.getToWarehouseId() != null)
            m.put("toWarehouseName", warehouseName(bill.getToWarehouseId()));
        if (bill.getPartnerId() != null) {
            Partner pt = partnerMapper.selectById(bill.getPartnerId());
            m.put("partnerName", pt != null ? pt.getName() : null);
        }
        return m;
    }

    private double round2(double v) { return Math.round(v * 100) / 100.0; }

    private String warehouseName(Long id) {
        if (id == null) return null;
        Warehouse w = warehouseMapper.selectById(id);
        return w != null ? w.getName() : null;
    }
}
