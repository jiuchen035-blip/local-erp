package com.local.erp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.entity.PeerStock;
import com.local.erp.entity.Product;
import com.local.erp.mapper.PeerStockMapper;
import com.local.erp.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** 编码生成：商品 SKU 与同行库存 SKU 的统一出口（商品管理/批量导入/快速建档/AI 建档共用） */
@Service
@RequiredArgsConstructor
public class ProductCodeService {

    private final ProductMapper productMapper;
    private final PeerStockMapper peerStockMapper;

    /** 商品 SKU：一级缩写-二级编号+三级编号+序号；无分类用 SP 前缀（例 XF-010001 / SP-000001） */
    public String nextProductSku(String category, String subCategory, String sub2Category) {
        String l1 = nz(category).isBlank() ? "SP" : initials(category);
        var all = productMapper.selectList(null);
        String l2code = "00";
        if (!nz(subCategory).isBlank()) {
            // 新分类也参与排序定位：保证首个商品拿到的编号与后续商品一致
            var set = new java.util.TreeSet<String>();
            all.stream().filter(p -> nz(p.getCategory()).equals(category) && !nz(p.getSubCategory()).isBlank())
                    .map(Product::getSubCategory).forEach(set::add);
            set.add(subCategory);
            int idx = new ArrayList<>(set).indexOf(subCategory) + 1;
            if (idx > 0) l2code = String.format("%02d", idx);
        }
        String l3code = "00";
        if (!nz(sub2Category).isBlank()) {
            var set = new java.util.TreeSet<String>();
            all.stream().filter(p -> nz(p.getCategory()).equals(category) && nz(p.getSubCategory()).equals(subCategory)
                            && !nz(p.getSub2Category()).isBlank())
                    .map(Product::getSub2Category).forEach(set::add);
            set.add(sub2Category);
            int idx = new ArrayList<>(set).indexOf(sub2Category) + 1;
            if (idx > 0) l3code = String.format("%02d", idx);
        }
        String prefix = l1 + "-" + l2code + l3code;
        int max = 0;
        for (Product p : all) {
            String sku = nz(p.getSku());
            if (sku.startsWith(prefix) && sku.length() > prefix.length()) {
                try { max = Math.max(max, Integer.parseInt(sku.substring(prefix.length()))); }
                catch (NumberFormatException ignored) {}
            }
        }
        return prefix + String.format("%02d", max + 1);
    }

    /** 同行库存 SKU：PS- + 已有最大序号 + 1 */
    public String nextPeerSku() {
        String prefix = "PS-";
        int max = 0;
        for (PeerStock s : peerStockMapper.selectList(null)) {
            String sku = nz(s.getSku());
            if (sku.startsWith(prefix) && sku.length() > prefix.length()) {
                try { max = Math.max(max, Integer.parseInt(sku.substring(prefix.length()))); }
                catch (NumberFormatException ignored) {}
            }
        }
        return prefix + String.format("%06d", max + 1);
    }

    /** 分类名 → 拼音/字母首字母大写缩写（汉字取拼音首字母，字母保留大写，数字符号跳过；空则 SP） */
    private String initials(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : nz(s).toCharArray()) {
            if (!Character.isLetter(c)) continue;
            String py = com.github.promeg.pinyinhelper.Pinyin.toPinyin(c);
            if (!py.isEmpty()) sb.append(Character.toUpperCase(py.charAt(0)));
        }
        return sb.length() > 0 ? sb.toString() : "SP";
    }

    private String nz(String s) { return s == null ? "" : s; }
}
