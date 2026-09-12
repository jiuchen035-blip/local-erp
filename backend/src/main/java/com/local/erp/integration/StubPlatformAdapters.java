package com.local.erp.integration;

import com.local.erp.entity.IntegrationChannel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 预置四个平台的占位适配器（static 内部类 @Component 会被自动扫描注册）。
 * 真正接入时把 testConnection/fetchOrders 换成开放平台 API 实现即可，其他代码零改动。
 */
public class StubPlatformAdapters {

    private StubPlatformAdapters() {}

    @Component
    static class Taobao implements PlatformAdapter {
        public String platform() { return "TAOBAO"; }
        public String displayName() { return "淘宝"; }
        public Map<String, Object> testConnection(IntegrationChannel ch) {
            return stub("淘宝开放平台");
        }
        public List<Map<String, Object>> fetchOrders(IntegrationChannel ch, String since) { return List.of(); }
    }

    @Component
    static class Pinduoduo implements PlatformAdapter {
        public String platform() { return "PDD"; }
        public String displayName() { return "拼多多"; }
        public Map<String, Object> testConnection(IntegrationChannel ch) {
            return stub("拼多多开放平台");
        }
        public List<Map<String, Object>> fetchOrders(IntegrationChannel ch, String since) { return List.of(); }
    }

    @Component
    static class Douyin implements PlatformAdapter {
        public String platform() { return "DOUYIN"; }
        public String displayName() { return "抖音小店"; }
        public Map<String, Object> testConnection(IntegrationChannel ch) {
            return stub("抖音开放平台");
        }
        public List<Map<String, Object>> fetchOrders(IntegrationChannel ch, String since) { return List.of(); }
    }

    @Component
    static class Meituan implements PlatformAdapter {
        public String platform() { return "MEITUAN"; }
        public String displayName() { return "美团外卖"; }
        public Map<String, Object> testConnection(IntegrationChannel ch) {
            return stub("美团开放平台");
        }
        public List<Map<String, Object>> fetchOrders(IntegrationChannel ch, String since) { return List.of(); }
    }

    private static Map<String, Object> stub(String openPlatform) {
        return Map.of(
                "ok", false,
                "message", openPlatform + "的 API 适配器尚未开通：密钥已保存，拉单入口已预留（PlatformAdapter 接口），开通开放平台后即可自动拉单。现阶段可用下方“订单 Excel 导入”。");
    }
}
