package com.local.erp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.erp.AppHome;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * 财务全局设置（计税方式等）：持久化到 data/finance-settings.json，运行时热生效。
 * taxEnabled=false（默认）：收入/成本全额入账，适合小规模纳税人和个体户；
 * taxEnabled=true：开单价按含税价录入，凭证自动价税分离（销项/进项走 2221 科目）。
 */
@Slf4j
@Service
public class FinanceSettingsService {

    private static final File FILE = new File(AppHome.dataDir(), "finance-settings.json");
    private final ObjectMapper om = new ObjectMapper();
    private Settings cache;

    @Data
    public static class Settings {
        private boolean taxEnabled = false;
        private Double defaultTaxRate = 13.0;   // 商品未单设税率时的默认税率%
    }

    public synchronized Settings get() {
        if (cache == null) {
            try {
                if (FILE.exists()) cache = om.readValue(FILE, Settings.class);
            } catch (Exception e) {
                log.warn("读取财务设置失败，用默认值: {}", e.getMessage());
            }
            if (cache == null) cache = new Settings();
        }
        return cache;
    }

    public synchronized Settings save(Settings s) {
        if (s.getDefaultTaxRate() == null) s.setDefaultTaxRate(13.0);
        cache = s;
        try {
            AppHome.dataDir().mkdirs();
            om.writerWithDefaultPrettyPrinter().writeValue(FILE, s);
        } catch (Exception e) {
            throw new IllegalArgumentException("保存财务设置失败: " + e.getMessage());
        }
        return s;
    }
}
