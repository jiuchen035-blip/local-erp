package com.local.erp.integration;

import com.local.erp.entity.IntegrationChannel;

import java.util.List;
import java.util.Map;

/**
 * 电商平台适配器插口：将来接入淘宝/拼多多/抖音/美团开放平台时，
 * 实现本接口并注册为 Spring 组件即可被 PlatformAdapters 收集，无需改动其他代码。
 */
public interface PlatformAdapter {

    /** 平台编码：TAOBAO / PDD / DOUYIN / MEITUAN */
    String platform();

    /** 平台显示名 */
    String displayName();

    /**
     * 测试通道连通性（校验 appKey/appSecret 是否有效）。
     * 返回 {"ok": true/false, "message": "..."}
     */
    Map<String, Object> testConnection(IntegrationChannel channel);

    /**
     * 拉取平台订单（真实接入后实现）。
     * 返回订单 Map：orderNo / orderTime / buyer / items[{productName, quantity, price}]
     */
    List<Map<String, Object>> fetchOrders(IntegrationChannel channel, String since);
}
