package com.local.erp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 店铺信息（店名/电话/地址）：持久化到 data/shop.json，
 * 打印单据、对账单的抬头统一从这里取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopInfoService {

    private final ObjectMapper om = new ObjectMapper();
    private static final File FILE = new File(com.local.erp.AppHome.dataDir(), "shop.json");

    @Data
    public static class ShopInfo {
        private String shopName = "";
        private String phone = "";
        private String address = "";
    }

    private ShopInfo info = new ShopInfo();

    @PostConstruct
    public void load() {
        if (!FILE.exists()) return;
        try {
            info = om.readValue(FILE, ShopInfo.class);
            log.info("已加载店铺信息: {}", info.getShopName());
        } catch (Exception e) {
            log.warn("读取 shop.json 失败: {}", e.getMessage());
        }
    }

    public ShopInfo get() {
        return info;
    }

    public synchronized ShopInfo update(ShopInfo req) {
        if (req.getShopName() == null) req.setShopName("");
        info = req;
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shopName", nz(req.getShopName()));
            m.put("phone", nz(req.getPhone()));
            m.put("address", nz(req.getAddress()));
            om.writerWithDefaultPrettyPrinter().writeValue(FILE, m);
        } catch (Exception e) {
            log.warn("保存 shop.json 失败: {}", e.getMessage());
        }
        return info;
    }

    private String nz(String s) { return s == null ? "" : s; }
}
