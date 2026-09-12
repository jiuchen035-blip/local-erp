package com.local.erp.controller;

import com.local.erp.entity.Product;
import com.local.erp.mapper.ProductMapper;
import com.local.erp.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.*;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductMapper productMapper;
    private final StockService stockService;
    private final com.local.erp.mapper.StockRecordMapper stockRecordMapper;
    private final com.local.erp.mapper.WarehouseMapper warehouseMapper;
    private final com.local.erp.service.ProductCodeService productCodeService;
    private static final String IMG_DIR =
            new File(com.local.erp.AppHome.dataDir(), "images").getPath();

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String category,
                                          @RequestParam(required = false) Boolean includeDisabled) {
        Map<Long, Integer> stock = stockService.currentStock();
        return productMapper.selectList(null).stream()
                // 默认不返回停用档案（同行调货自动生成的隐藏档案 enabled=0 不进商品库存/选品器）
                .filter(p -> Boolean.TRUE.equals(includeDisabled)
                        || p.getEnabled() == null || p.getEnabled() == 1)
                .filter(p -> matchKeyword(p, keyword))
                .filter(p -> category == null || category.isBlank()
                        || category.equals(p.getCategory()) || category.equals(p.getSubCategory())
                        || category.equals(p.getSub2Category()))
                .map(p -> toMap(p, stock.getOrDefault(p.getId(), 0)))
                .toList();
    }

    private boolean matchKeyword(Product p, String keyword) {
        if (keyword == null || keyword.isBlank()) return true;
        return nz(p.getName()).contains(keyword) || nz(p.getSku()).contains(keyword)
                || nz(p.getBarcode()).contains(keyword) || nz(p.getSubCategory()).contains(keyword);
    }

    private Map<String, Object> toMap(Product p, int stock) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("sku", p.getSku());
        m.put("barcode", p.getBarcode());
        m.put("category", p.getCategory());
        m.put("spec", p.getSpec());
        m.put("subCategory", p.getSubCategory());
        m.put("sub2Category", p.getSub2Category());
        m.put("noAlert", p.getNoAlert() != null && p.getNoAlert() == 1);
        m.put("costPrice", p.getCostPrice());
        m.put("salePrice", p.getSalePrice());
        m.put("wholesalePrice", p.getWholesalePrice());
        m.put("memberPrice", p.getMemberPrice());
        m.put("unit", p.getUnit());
        m.put("bigUnit", p.getBigUnit());
        m.put("bigUnitRate", p.getBigUnitRate());
        m.put("shelfLifeDays", p.getShelfLifeDays());
        m.put("taxRate", p.getTaxRate());
        m.put("safeStock", p.getSafeStock());
        m.put("avgCost", p.getAvgCost() != null ? p.getAvgCost() : p.getCostPrice());
        m.put("stock", stock);
        m.put("hasImage", new File(IMG_DIR, p.getId() + ".jpg").exists()
                || new File(IMG_DIR, p.getId() + ".png").exists());
        return m;
    }

    /** 分类字典（三级树）：categories=一级，subOf[一级]=[二级]，sub2Of["一级/二级"]=[三级] */
    @GetMapping("/categories")
    public Map<String, Object> categories() {
        Set<String> cats = new LinkedHashSet<>();
        Map<String, Set<String>> subs = new LinkedHashMap<>();
        Map<String, Set<String>> subs2 = new LinkedHashMap<>();
        for (Product p : productMapper.selectList(null)) {
            if (nz(p.getCategory()).isBlank()) continue;
            cats.add(p.getCategory());
            if (!nz(p.getSubCategory()).isBlank()) {
                subs.computeIfAbsent(p.getCategory(), k -> new LinkedHashSet<>()).add(p.getSubCategory());
                if (!nz(p.getSub2Category()).isBlank()) {
                    subs2.computeIfAbsent(p.getCategory() + "/" + p.getSubCategory(), k -> new LinkedHashSet<>())
                            .add(p.getSub2Category());
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("categories", cats);
        result.put("subOf", subs);
        result.put("sub2Of", subs2);
        return result;
    }

    /**
     * SKU 自动编号：一级分类拼音首字母缩写-二级编号+三级编号+商品序号。
     * 例：消防/灭火器/无三级 → XF-010001（XF=消防，01=第一个二级分类，00=无三级，01=该范围第1个商品）。
     * 编号 = 该级分类在同级内按名称排序的序号（两位）；序号 = 同前缀已有最大序号 + 1。
     */
    @GetMapping("/next-sku")
    public Map<String, Object> nextSku(@RequestParam(required = false) String category,
                                       @RequestParam(required = false) String subCategory,
                                       @RequestParam(required = false) String sub2Category) {
        return Map.of("sku", productCodeService.nextProductSku(nz(category), nz(subCategory), nz(sub2Category)));
    }

    /** 分类管理：重命名/删除（删除=清空引用该分类的商品的对应字段；一级连带清二三级） */
    @PostMapping("/categories/apply")
    public Map<String, Object> applyCategory(@RequestBody Map<String, Object> body) {
        int level = Integer.parseInt(String.valueOf(body.getOrDefault("level", "1")));
        String l1 = nz((String) body.get("l1"));
        String l2 = nz((String) body.get("l2"));
        String oldName = nz((String) body.get("oldName"));
        String newName = nz((String) body.get("newName")).trim();
        if (oldName.isBlank()) return Map.of("error", "分类名不能为空");
        boolean delete = newName.isBlank();
        long affected;
        if (level == 1) {
            affected = delete
                    ? productMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Product>()
                            .eq(Product::getCategory, oldName)
                            .set(Product::getCategory, "").set(Product::getSubCategory, "").set(Product::getSub2Category, ""))
                    : productMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Product>()
                            .eq(Product::getCategory, oldName).set(Product::getCategory, newName));
        } else if (level == 2) {
            if (l1.isBlank()) return Map.of("error", "缺少一级分类");
            affected = delete
                    ? productMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Product>()
                            .eq(Product::getCategory, l1).eq(Product::getSubCategory, oldName)
                            .set(Product::getSubCategory, "").set(Product::getSub2Category, ""))
                    : productMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Product>()
                            .eq(Product::getCategory, l1).eq(Product::getSubCategory, oldName).set(Product::getSubCategory, newName));
        } else {
            if (l1.isBlank() || l2.isBlank()) return Map.of("error", "缺少上级分类");
            affected = delete
                    ? productMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Product>()
                            .eq(Product::getCategory, l1).eq(Product::getSubCategory, l2).eq(Product::getSub2Category, oldName)
                            .set(Product::getSub2Category, ""))
                    : productMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Product>()
                            .eq(Product::getCategory, l1).eq(Product::getSubCategory, l2).eq(Product::getSub2Category, oldName)
                            .set(Product::getSub2Category, newName));
        }
        String msg = (delete ? "已删除分类「" + oldName + "」" : "已重命名为「" + newName + "」")
                + "，涉及 " + affected + " 个商品";
        return Map.of("affected", affected, "message", msg);
    }

    /** 分类名 → 拼音/字母首字母大写缩写（汉字取拼音首字母，字母保留并大写，数字与符号跳过；空则 SP） */
    /**
     * 批量导入（前端 SheetJS 解析任意 ERP 导出表 + 列映射后的 JSON 行）。
     * rows 字段: name必填, sku, barcode, spec, category(可含"/"自动拆三级), subCategory, sub2Category,
     *           unit, costPrice, salePrice, wholesalePrice, memberPrice, safeStock, bigUnit, bigUnitRate, stock(期初库存)
     * 选项: skuRegen=全部重新生成SKU; dedup=skip|overwrite（按SKU/条码匹配已存在商品）
     */
    @PostMapping("/import/batch")
    public Map<String, Object> importBatch(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
        if (rows == null || rows.isEmpty()) return Map.of("error", "没有可导入的数据");
        boolean skuRegen = Boolean.TRUE.equals(body.get("skuRegen"));
        String dedup = String.valueOf(body.getOrDefault("dedup", "skip"));

        Map<Long, Integer> stockMap = stockService.currentStock();
        java.util.Set<String> batchSkus = new java.util.HashSet<>();
        int imported = 0, updated = 0, skipped = 0, failed = 0;
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            try {
                String name = strVal(r.get("name"));
                if (name.isEmpty()) throw new IllegalArgumentException("商品名称为空");
                String sku = strVal(r.get("sku"));
                String barcode = strVal(r.get("barcode"));
                String category = strVal(r.get("category"));
                String spec = strVal(r.get("spec"));
                String sub = strVal(r.get("subCategory"));
                String sub2 = strVal(r.get("sub2Category"));
                if (category.contains("/")) {   // "饮料/碳酸饮料/可乐" 整串自动拆三级
                    String[] parts = category.split("/");
                    category = parts[0].trim();
                    if (parts.length > 1 && sub.isEmpty()) sub = parts[1].trim();
                    if (parts.length > 2 && sub2.isEmpty()) sub2 = parts[2].trim();
                }

                Product exist = null;
                if (!sku.isBlank())
                    exist = productMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product>()
                            .eq(Product::getSku, sku).last("LIMIT 1"));
                if (exist == null && !barcode.isBlank())
                    exist = productMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product>()
                            .eq(Product::getBarcode, barcode).last("LIMIT 1"));

                if (exist != null) {
                    if ("overwrite".equals(dedup)) {
                        exist.setName(name);
                        if (!category.isBlank()) exist.setCategory(category);
                        if (!spec.isBlank()) exist.setSpec(spec);
                        if (!sub.isBlank()) exist.setSubCategory(sub);
                        if (!sub2.isBlank()) exist.setSub2Category(sub2);
                        if (!barcode.isBlank()) exist.setBarcode(barcode);
                        if (r.get("costPrice") != null && !strVal(r.get("costPrice")).isEmpty()) exist.setCostPrice(dblVal(r.get("costPrice")));
                        if (r.get("salePrice") != null && !strVal(r.get("salePrice")).isEmpty()) exist.setSalePrice(dblVal(r.get("salePrice")));
                        if (strVal(r.get("unit")).isEmpty() == false) exist.setUnit(strVal(r.get("unit")));
                        productMapper.updateById(exist);
                        applyInitialStock(exist, r, stockMap);
                        updated++;
                    } else skipped++;
                    continue;
                }

                if (sku.isBlank() || skuRegen) sku = productCodeService.nextProductSku(category, sub, sub2);
                if (!batchSkus.add(sku)) throw new IllegalArgumentException("批内 SKU 重复: " + sku);
                Product dup = productMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Product>()
                        .eq(Product::getSku, sku).last("LIMIT 1"));
                if (dup != null) throw new IllegalArgumentException("SKU 已存在: " + sku + "（重试会自动顺延）");

                Product p = new Product();
                p.setName(name);
                p.setSku(sku);
                p.setBarcode(barcode.isBlank() ? null : barcode);
                p.setCategory(category.isBlank() ? null : category);
                p.setSpec(spec.isBlank() ? null : spec);
                p.setSubCategory(sub.isBlank() ? null : sub);
                p.setSub2Category(sub2.isBlank() ? null : sub2);
                p.setUnit(strVal(r.get("unit")).isBlank() ? "个" : strVal(r.get("unit")));
                p.setCostPrice(dblOr(r.get("costPrice"), 0.0));
                p.setSalePrice(dblOr(r.get("salePrice"), 0.0));
                if (notBlank(r.get("wholesalePrice"))) p.setWholesalePrice(dblVal(r.get("wholesalePrice")));
                if (notBlank(r.get("memberPrice"))) p.setMemberPrice(dblVal(r.get("memberPrice")));
                p.setSafeStock(notBlank(r.get("safeStock")) ? intVal(r.get("safeStock")) : 10);   // 未映射/为空默认 10
                if (notBlank(r.get("bigUnit"))) p.setBigUnit(strVal(r.get("bigUnit")));
                if (notBlank(r.get("bigUnitRate"))) p.setBigUnitRate(intVal(r.get("bigUnitRate")));
                p.setEnabled(1);
                productMapper.insert(p);
                applyInitialStock(p, r, stockMap);
                imported++;
            } catch (Exception e) {
                failed++;
                if (errors.size() < 20) errors.add("第 " + (i + 1) + " 条（" + strVal(r.get("name")) + "）: " + e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("updated", updated);
        result.put("skipped", skipped);
        result.put("failed", failed);
        result.put("errors", errors);
        result.put("message", "导入完成：新增 " + imported + "，更新 " + updated + "，跳过 " + skipped + "，失败 " + failed);
        return result;
    }

    /** 期初库存：目标数量与当前库存有差额时补一条 ADJUST 盘整流水（不污染毛利）；归入第一个仓库，导入后即可开单 */
    private void applyInitialStock(Product p, Map<String, Object> r, Map<Long, Integer> stockMap) {
        if (!notBlank(r.get("stock"))) return;
        int target = intVal(r.get("stock"));
        int diff = target - stockMap.getOrDefault(p.getId(), 0);
        if (diff == 0) return;
        var sr = new com.local.erp.entity.StockRecord();
        sr.setProductId(p.getId());
        sr.setType("ADJUST");
        sr.setQuantity(diff);
        sr.setPrice(p.getCostPrice() == null ? 0 : p.getCostPrice());
        sr.setRemark("批量导入期初库存");
        warehouseMapper.selectList(null).stream()
                .map(w -> w.getId()).min(Long::compare)
                .ifPresent(sr::setWarehouseId);
        sr.setCreatedAt(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        stockRecordMapper.insert(sr);
        stockMap.put(p.getId(), target);
    }

    private boolean notBlank(Object o) { return o != null && !String.valueOf(o).isBlank(); }
    private String strVal(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private Double dblVal(Object o) { return Double.parseDouble(String.valueOf(o).toString().trim()); }
    private Double dblOr(Object o, Double def) { try { return dblVal(o); } catch (Exception e) { return def; } }
    private Integer intVal(Object o) { return (int) (double) dblVal(o); }


    @PostMapping
    public Product create(@RequestBody Product p) {
        if (p.getEnabled() == null) p.setEnabled(1);
        if (p.getSafeStock() == null) p.setSafeStock(10);   // 默认安全库存 10（低于预警；设 0=仅缺货提醒）
        // SKU 未填时按规则自动生成（无分类用 SP 前缀），与批量导入一致
        if (nz(p.getSku()).isBlank())
            p.setSku(productCodeService.nextProductSku(nz(p.getCategory()), nz(p.getSubCategory()), nz(p.getSub2Category())));
        productMapper.insert(p);
        return p;
    }

    @PutMapping("/{id}")
    public Product update(@PathVariable Long id, @RequestBody Product p) {
        p.setId(id);
        productMapper.updateById(p);
        return p;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        productMapper.deleteById(id);
        new File(IMG_DIR, id + ".jpg").delete();
        new File(IMG_DIR, id + ".png").delete();
    }

    /** 商品图片上传（覆盖式） */
    @PostMapping("/{id}/image")
    public Map<String, Object> uploadImage(@PathVariable Long id,
                                           @RequestParam("file") MultipartFile file) throws Exception {
        Product p = productMapper.selectById(id);
        if (p == null) throw new IllegalArgumentException("商品不存在");
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = original.toLowerCase().endsWith(".png") ? ".png" : ".jpg";
        File dir = new File(IMG_DIR);
        if (!dir.exists()) dir.mkdirs();
        // 先清掉另一种扩展名的旧图
        new File(IMG_DIR, id + ".jpg").delete();
        new File(IMG_DIR, id + ".png").delete();
        file.transferTo(new File(dir, id + ext).getAbsoluteFile());
        return Map.of("message", "图片已上传", "url", "/api/images/" + id);
    }

    private String nz(String s) { return s == null ? "" : s; }
}
