package com.local.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.local.erp.entity.Bill;
import com.local.erp.entity.Partner;
import com.local.erp.entity.Product;
import com.local.erp.entity.StockRecord;
import com.local.erp.mapper.BillMapper;
import com.local.erp.mapper.PartnerMapper;
import com.local.erp.mapper.ProductMapper;
import com.local.erp.mapper.StockRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应收应付对账：挂账单据（paid=0）按往来单位聚合。
 * SALE 挂账 = 应收（客户），PURCHASE 挂账 = 应付（供应商）。
 */
@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final JdbcTemplate jdbc;
    private final StockRecordMapper recordMapper;
    private final ProductMapper productMapper;
    private final BillMapper billMapper;
    private final PartnerMapper partnerMapper;

    private static final String SUMMARY_SQL = """
            SELECT p.id AS partnerId, p.name AS name, p.phone AS phone,
                   p.opening_receivable AS openingReceivable, p.opening_payable AS openingPayable,
                   COUNT(r.id) AS cnt, SUM(ABS(r.quantity) * r.price) AS amount
            FROM partner p
            LEFT JOIN stock_record r ON r.partner_id = p.id AND r.paid = 0 AND r.type = ?
            GROUP BY p.id, p.name, p.phone, p.opening_receivable, p.opening_payable
            HAVING COUNT(r.id) > 0 OR p.opening_receivable > 0 OR p.opening_payable > 0
            """;

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        List<Map<String, Object>> receivables = jdbc.queryForList(SUMMARY_SQL, "SALE");
        List<Map<String, Object>> payables = jdbc.queryForList(SUMMARY_SQL, "PURCHASE");
        // 每个单位：合计 = 期初余额 + 挂账流水
        for (Map<String, Object> m : receivables) {
            double opening = m.get("openingReceivable") == null ? 0 : ((Number) m.get("openingReceivable")).doubleValue();
            double amount = m.get("amount") == null ? 0 : ((Number) m.get("amount")).doubleValue();
            m.put("amount", Math.round((opening + amount) * 100) / 100.0);
            m.put("opening", opening);
        }
        for (Map<String, Object> m : payables) {
            double opening = m.get("openingPayable") == null ? 0 : ((Number) m.get("openingPayable")).doubleValue();
            double amount = m.get("amount") == null ? 0 : ((Number) m.get("amount")).doubleValue();
            m.put("amount", Math.round((opening + amount) * 100) / 100.0);
            m.put("opening", opening);
        }
        double recvTotal = receivables.stream()
                .mapToDouble(m -> ((Number) m.get("amount")).doubleValue()).sum();
        double payTotal = payables.stream()
                .mapToDouble(m -> ((Number) m.get("amount")).doubleValue()).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("receivables", receivables);
        result.put("payables", payables);
        result.put("receivableTotal", recvTotal);
        result.put("payableTotal", payTotal);
        return result;
    }

    /** 某往来单位的未结清单据明细（带商品名/单据号，用于明细展示与对账单打印） */
    @GetMapping("/unpaid")
    public List<Map<String, Object>> unpaid(@RequestParam Long partnerId) {
        List<StockRecord> records = recordMapper.selectList(new LambdaQueryWrapper<StockRecord>()
                .eq(StockRecord::getPartnerId, partnerId)
                .eq(StockRecord::getPaid, 0)
                .orderByDesc(StockRecord::getId));

        Map<Long, Product> products = new HashMap<>();
        productMapper.selectList(null).forEach(p -> products.put(p.getId(), p));
        Map<Long, Bill> bills = new HashMap<>();
        billMapper.selectList(null).forEach(b -> bills.put(b.getId(), b));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (StockRecord r : records) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("productId", r.getProductId());
            Product p = products.get(r.getProductId());
            m.put("productName", p != null ? p.getName() : "未知商品#" + r.getProductId());
            Bill b = r.getBillId() != null ? bills.get(r.getBillId()) : null;
            m.put("billNo", b != null ? b.getBillNo() : null);
            // 备注：优先取单据备注；无单据的旧流水才用流水自身备注
            m.put("remark", b != null ? nz(b.getRemark()) : nz(r.getRemark()));
            m.put("createdAt", r.getCreatedAt());
            m.put("quantity", Math.abs(r.getQuantity()));
            m.put("price", r.getPrice());
            m.put("amount", Math.round(Math.abs(r.getQuantity()) * r.getPrice() * 100) / 100.0);
            rows.add(m);
        }
        return rows;
    }

    private String nz(String s) { return s == null ? "" : s; }

    /** 修改备注：作用于整张单据（多行流水共享同一单据备注） */
    @PostMapping("/update-remark")
    public Map<String, Object> updateRemark(@RequestBody Map<String, Object> body) {
        Long recordId = Long.valueOf(String.valueOf(body.get("recordId")));
        String remark = body.get("remark") == null ? "" : String.valueOf(body.get("remark")).trim();
        StockRecord r = recordMapper.selectById(recordId);
        if (r == null) throw new IllegalArgumentException("记录不存在");
        if (r.getBillId() != null) {
            Bill b = billMapper.selectById(r.getBillId());
            if (b == null) throw new IllegalArgumentException("关联单据不存在");
            b.setRemark(remark);
            billMapper.updateById(b);
            r.setRemark(remark);
            recordMapper.updateById(r);
        } else {
            r.setRemark(remark);
            recordMapper.updateById(r);
        }
        return Map.of("message", "备注已更新");
    }

    /** 核销单笔 */
    @PostMapping("/settle/{recordId}")
    public Map<String, Object> settle(@PathVariable Long recordId) {
        recordMapper.update(null, new LambdaUpdateWrapper<StockRecord>()
                .eq(StockRecord::getId, recordId)
                .set(StockRecord::getPaid, 1));
        return Map.of("message", "已核销单据 #" + recordId);
    }

    /** 结清某往来单位全部挂账：核销未结流水，并清零期初余额（direction=RECEIVABLE/PAYABLE，缺省按往来单位类型推断） */
    @PostMapping("/settle-partner/{partnerId}")
    @Transactional
    public Map<String, Object> settlePartner(@PathVariable Long partnerId,
                                             @RequestParam(required = false) String direction) {
        recordMapper.update(null, new LambdaUpdateWrapper<StockRecord>()
                .eq(StockRecord::getPartnerId, partnerId)
                .eq(StockRecord::getPaid, 0)
                .set(StockRecord::getPaid, 1));
        Partner p = partnerMapper.selectById(partnerId);
        if (p != null) {
            boolean receivable = "RECEIVABLE".equalsIgnoreCase(direction)
                    || ((direction == null || direction.isBlank()) && "CUSTOMER".equals(p.getType()));
            LambdaUpdateWrapper<Partner> uw = new LambdaUpdateWrapper<Partner>()
                    .eq(Partner::getId, partnerId);
            if (receivable) uw.set(Partner::getOpeningReceivable, 0.0);
            else uw.set(Partner::getOpeningPayable, 0.0);
            partnerMapper.update(null, uw);
        }
        return Map.of("message", "已结清该往来单位全部挂账及期初余额");
    }
}
