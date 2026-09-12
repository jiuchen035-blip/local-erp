package com.local.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.dto.BillDto;
import com.local.erp.entity.Bill;
import com.local.erp.entity.StockRecord;
import com.local.erp.mapper.BillMapper;
import com.local.erp.mapper.BillItemMapper;
import com.local.erp.mapper.StockRecordMapper;
import com.local.erp.config.AuthInterceptor;
import com.local.erp.service.BillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bills")
@RequiredArgsConstructor
public class BillController {

    private final BillService billService;
    private final BillMapper billMapper;
    private final BillItemMapper itemMapper;
    private final StockRecordMapper recordMapper;
    private final com.local.erp.mapper.PartnerMapper partnerMapper;

    @GetMapping
    public List<Bill> list(@RequestParam(required = false) String status,
                           @RequestParam(required = false) String type) {
        LambdaQueryWrapper<Bill> qw = new LambdaQueryWrapper<Bill>().orderByDesc(Bill::getId).last("limit 200");
        if (status != null && !status.isBlank()) qw.eq(Bill::getStatus, status);
        if (type != null && !type.isBlank()) qw.eq(Bill::getType, type);
        return billMapper.selectList(qw);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return billService.detail(id);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody BillDto dto) {
        String user = AuthInterceptor.currentUser() != null ? AuthInterceptor.currentUser().getUsername() : "";
        Bill bill = billService.createDraft(dto, user);
        if (Boolean.TRUE.equals(dto.getAutoPost())) billService.post(bill.getId());
        return Map.of("id", bill.getId(), "billNo", bill.getBillNo(), "total", bill.getTotalAmount());
    }

    /**
     * 同行调货一键单（同一事务）。
     * body: { sale: BillDto(自有行 items), purchase: BillDto(供应商/仓库/挂账), peerLines: [{stockId, quantity, salePrice, peerPrice}] }
     */
    /** 同行调货：创建两张关联草稿（同行采购入库 + 销售出库），人工确认后调 post-with-peer 一起过账 */
    @PostMapping("/peer-sale")
    public Map<String, Object> peerSale(@RequestBody Map<String, Object> body) {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        BillDto sale = om.convertValue(body.get("sale"), BillDto.class);
        BillDto purchase = om.convertValue(body.get("purchase"), BillDto.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> peerLines = (List<Map<String, Object>>) body.get("peerLines");
        String user = AuthInterceptor.currentUser() != null ? AuthInterceptor.currentUser().getUsername() : "";

        // 同行供应商：purchase.partnerId 缺失时用 partnerName 自动查/建供应商档案
        if (purchase.getPartnerId() == null) {
            String pname = body.get("partnerName") == null ? "" : String.valueOf(body.get("partnerName")).trim();
            if (pname.isEmpty()) throw new IllegalArgumentException("请填写调货供应商（新名称自动建档）");
            com.local.erp.entity.Partner exist = partnerMapper.selectList(
                    new LambdaQueryWrapper<com.local.erp.entity.Partner>()
                            .eq(com.local.erp.entity.Partner::getName, pname)
                            .eq(com.local.erp.entity.Partner::getType, "SUPPLIER"))
                    .stream().findFirst().orElse(null);
            if (exist != null) {
                purchase.setPartnerId(exist.getId());
            } else {
                com.local.erp.entity.Partner np = new com.local.erp.entity.Partner();
                np.setName(pname);
                np.setType("SUPPLIER");
                partnerMapper.insert(np);
                purchase.setPartnerId(np.getId());
            }
        }

        Map<String, Object> r = billService.createPeerDrafts(sale, purchase, peerLines, user);
        Bill pur = (Bill) r.get("purchase");
        Bill sal = (Bill) r.get("sale");
        return Map.of("message", "已生成同行调货草稿：采购 " + pur.getBillNo() + " + 销售 " + sal.getBillNo()
                        + "（确认过账时两张一起生效）",
                "saleId", sal.getId(), "saleNo", sal.getBillNo(),
                "purchaseId", pur.getId(), "purchaseNo", pur.getBillNo());
    }

    /** 确认过账同行调货销售单：同一事务内先过账关联的同行采购草稿，再过账销售 */
    @PostMapping("/{id}/post-with-peer")
    public Map<String, Object> postWithPeer(@PathVariable Long id) {
        Map<String, Object> r = billService.postWithPeer(id);
        Bill pur = (Bill) r.get("purchase");
        Bill sal = (Bill) r.get("sale");
        return Map.of("message", "同行调货已过账：采购 " + pur.getBillNo() + " / 销售 " + sal.getBillNo(),
                "purchaseNo", pur.getBillNo(), "saleNo", sal.getBillNo());
    }

    @PostMapping("/{id}/post")
    public Map<String, Object> post(@PathVariable Long id) {
        Bill bill = billService.post(id);
        return Map.of("message", "单据 " + bill.getBillNo() + " 已过账", "billNo", bill.getBillNo());
    }

    @PostMapping("/{id}/reverse")
    public Map<String, Object> reverse(@PathVariable Long id) {
        Bill rev = billService.reverse(id);
        return Map.of("message", "已生成冲正单 " + rev.getBillNo() + " 并过账", "billNo", rev.getBillNo());
    }

    /** 仅草稿可删除 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        Bill bill = billMapper.selectById(id);
        if (bill == null) throw new IllegalArgumentException("单据不存在");
        if ("POSTED".equals(bill.getStatus())) throw new IllegalArgumentException("已过账单据请用冲正，不能删除");
        itemMapper.delete(new LambdaQueryWrapper<com.local.erp.entity.BillItem>().eq(com.local.erp.entity.BillItem::getBillId, id));
        // 清理草稿可能残留的流水引用（理论上草稿无流水，防御性处理）
        recordMapper.delete(new LambdaQueryWrapper<StockRecord>().eq(StockRecord::getBillId, id));
        billMapper.deleteById(id);
        return Map.of("message", "草稿已删除");
    }
}
