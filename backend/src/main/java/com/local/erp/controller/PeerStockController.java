package com.local.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.entity.PeerStock;
import com.local.erp.entity.Partner;
import com.local.erp.mapper.PeerStockMapper;
import com.local.erp.mapper.PartnerMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/** 同行库存：同行可调货源目录（开单勾选同行行时从这里选），支持 Excel 批量导入 */
@RestController
@RequestMapping("/api/peer-stock")
@RequiredArgsConstructor
public class PeerStockController {

    private final PeerStockMapper peerStockMapper;
    private final com.local.erp.service.ProductCodeService productCodeService;
    private final PartnerMapper partnerMapper;

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long partnerId,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) String keyword) {
        return peerStockMapper.selectList(new LambdaQueryWrapper<PeerStock>()
                        .orderByDesc(PeerStock::getId)).stream()
                .filter(s -> partnerId == null || partnerId.equals(s.getPartnerId()))
                .filter(s -> category == null || category.isBlank() || category.equals(s.getCategory()))
                .filter(s -> matchKeyword(s, keyword))
                .map(this::toMap)
                .toList();
    }

    private boolean matchKeyword(PeerStock s, String keyword) {
        if (keyword == null || keyword.isBlank()) return true;
        String k = keyword.toLowerCase();
        return nz(s.getProductName()).toLowerCase().contains(k) || nz(s.getSku()).toLowerCase().contains(k);
    }

    private Map<String, Object> toMap(PeerStock s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("partnerId", s.getPartnerId());
        m.put("partnerName", partnerName(s.getPartnerId()));
        m.put("productName", s.getProductName());
        m.put("sku", s.getSku());
        m.put("category", s.getCategory());
        m.put("lastPrice", s.getLastPrice());
        m.put("unit", s.getUnit());
        m.put("productId", s.getProductId());
        m.put("createdAt", s.getCreatedAt());
        return m;
    }

    private String partnerName(Long partnerId) {
        if (partnerId == null) return "";
        Partner p = partnerMapper.selectById(partnerId);
        return p == null ? "" : p.getName();
    }

    /** 同行名 → 档案 id（不存在自动创建供应商档案） */
    private Long ensurePartner(String name) {
        String n = nz(name).trim();
        if (n.isEmpty()) return null;
        Partner exist = partnerMapper.selectList(new LambdaQueryWrapper<Partner>()
                .eq(Partner::getName, n).eq(Partner::getType, "SUPPLIER")).stream().findFirst().orElse(null);
        if (exist != null) return exist.getId();
        Partner p = new Partner();
        p.setName(n);
        p.setType("SUPPLIER");
        partnerMapper.insert(p);
        return p.getId();
    }

    /** 创建：partnerId（已有同行）或 partnerName（新同行，自动建档）二选一；sku 留空自动生成 */
    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Long partnerId = resolvePartner(body);
        String productName = str(body.get("productName"));
        if (productName.isEmpty()) return Map.of("error", "商品名称必填");
        PeerStock s = new PeerStock();
        s.setPartnerId(partnerId);
        s.setProductName(productName);
        String sku = str(body.get("sku"));
        if (sku.isBlank()) s.setSku(productCodeService.nextPeerSku());
        else {
            if (peerStockMapper.selectCount(new LambdaQueryWrapper<PeerStock>().eq(PeerStock::getSku, sku)) > 0)
                return Map.of("error", "SKU 已存在: " + sku);
            s.setSku(sku);
        }
        s.setCategory(str(body.get("category")));
        s.setLastPrice(dblOr(body.get("lastPrice"), 0.0));
        s.setUnit(str(body.get("unit")).isBlank() ? "个" : str(body.get("unit")));
        peerStockMapper.insert(s);
        return toMap(s);
    }

    /** 编辑：同行/商品名/分类/调货价/单位（SKU 不变） */
    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        PeerStock s = peerStockMapper.selectById(id);
        if (s == null) return Map.of("error", "记录不存在");
        Long partnerId = resolvePartner(body);
        if (partnerId != null) s.setPartnerId(partnerId);
        if (notBlank(body.get("productName"))) s.setProductName(str(body.get("productName")));
        if (body.containsKey("category")) s.setCategory(str(body.get("category")));
        if (notBlank(body.get("lastPrice"))) s.setLastPrice(dblVal(body.get("lastPrice")));
        if (notBlank(body.get("unit"))) s.setUnit(str(body.get("unit")));
        peerStockMapper.updateById(s);
        return toMap(s);
    }

    /** body 里的同行：优先 partnerId；否则 partnerName 自动建档 */
    private Long resolvePartner(Map<String, Object> body) {
        Object pid = body.get("partnerId");
        if (pid != null && !str(pid).isEmpty()) {
            try { return Long.parseLong(str(pid)); } catch (NumberFormatException e) { /* 走名称建档 */ }
        }
        String name = str(body.get("partnerName"));
        return name.isEmpty() ? null : ensurePartner(name);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        peerStockMapper.deleteById(id);
        return Map.of("message", "已删除");
    }

    /** Excel 批量导入（固定列：同行名称 / 商品名称 / 调货价 / 分类 / 单位；SKU 留空自动生成） */
    @PostMapping("/import")
    public Map<String, Object> importBatch(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        if (rows == null || rows.isEmpty()) return Map.of("error", "没有可导入的数据");
        int imported = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        Set<String> batchSkus = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            try {
                String partnerName = str(r.get("partnerName"));
                String productName = str(r.get("productName"));
                if (productName.isEmpty()) throw new IllegalArgumentException("商品名称为空");
                PeerStock s = new PeerStock();
                s.setPartnerId(ensurePartner(partnerName));
                s.setProductName(productName);
                String sku = str(r.get("sku"));
                if (sku.isBlank()) s.setSku(productCodeService.nextPeerSku());
                else {
                    if (!batchSkus.add(sku)) throw new IllegalArgumentException("批内 SKU 重复: " + sku);
                    s.setSku(sku);
                }
                s.setCategory(str(r.get("category")));
                s.setLastPrice(r.get("lastPrice") == null || str(r.get("lastPrice")).isEmpty()
                        ? 0.0 : Double.parseDouble(str(r.get("lastPrice"))));
                s.setUnit(str(r.get("unit")).isBlank() ? "个" : str(r.get("unit")));
                peerStockMapper.insert(s);
                imported++;
            } catch (Exception e) {
                failed++;
                if (errors.size() < 20) errors.add("第 " + (i + 1) + " 条（" + str(r.get("productName")) + "）: " + e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("failed", failed);
        result.put("errors", errors);
        result.put("message", "导入完成：新增 " + imported + "，失败 " + failed);
        return result;
    }

    private String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private String nz(String s) { return s == null ? "" : s; }
    private Double dblOr(Object o, Double def) { try { return Double.parseDouble(str(o)); } catch (Exception e) { return def; } }
    private Double dblVal(Object o) { return Double.parseDouble(str(o)); }
    private boolean notBlank(Object o) { return o != null && !str(o).isEmpty(); }
}
