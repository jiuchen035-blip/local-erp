package com.local.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.local.erp.config.AuthInterceptor;
import com.local.erp.entity.Bill;
import com.local.erp.entity.IntegrationChannel;
import com.local.erp.entity.PlatformOrder;
import com.local.erp.integration.PlatformAdapter;
import com.local.erp.mapper.BillMapper;
import com.local.erp.mapper.IntegrationChannelMapper;
import com.local.erp.mapper.PlatformOrderMapper;
import com.local.erp.service.IntegrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 电商对接（仅管理员，见 AuthInterceptor 的 /api/integrations 前缀规则）：
 * 平台通道配置（淘宝/拼多多/抖音/美团，API 拉单入口已预留）+ 订单 Excel 导入 + 导入记录。
 */
@Slf4j
@RestController
@RequestMapping("/api/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationChannelMapper channelMapper;
    private final PlatformOrderMapper orderMapper;
    private final BillMapper billMapper;
    private final IntegrationService integrationService;
    private final List<PlatformAdapter> adapters; // Spring 自动收集所有 PlatformAdapter 实现
    private final ObjectMapper om = new ObjectMapper();

    /** 平台清单 + 各自通道状态 */
    @GetMapping("/platforms")
    public List<Map<String, Object>> platforms() {
        Map<String, IntegrationChannel> chMap = new HashMap<>();
        channelMapper.selectList(null).forEach(c -> chMap.put(c.getPlatform(), c));
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlatformAdapter a : adapters) {
            IntegrationChannel ch = chMap.get(a.platform());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("platform", a.platform());
            m.put("name", a.displayName());
            m.put("adapterReady", false); // 真实 API 适配器实现后自动为 true 的判定可加在此处
            m.put("channel", ch);
            result.add(m);
        }
        return result;
    }

    /** 保存通道配置（按平台 upsert；密钥保存在本机数据库 data/erp.db，不出本机） */
    @PostMapping("/channels")
    public Map<String, Object> saveChannel(@RequestBody Map<String, Object> body) throws Exception {
        String platform = (String) body.get("platform");
        adapters.stream().filter(a -> a.platform().equals(platform))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("未知平台: " + platform));
        IntegrationChannel ch = channelMapper.selectList(
                        new LambdaQueryWrapper<IntegrationChannel>().eq(IntegrationChannel::getPlatform, platform))
                .stream().findFirst().orElseGet(() -> {
                    IntegrationChannel c = new IntegrationChannel();
                    c.setPlatform(platform);
                    channelMapper.insert(c);
                    return c;
                });
        ch.setShopName((String) body.getOrDefault("shopName", ""));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("appKey", body.getOrDefault("appKey", ""));
        config.put("appSecret", body.getOrDefault("appSecret", ""));
        config.put("callback", body.getOrDefault("callback", ""));
        ch.setConfigJson(om.writeValueAsString(config));
        ch.setStatus(body.get("appKey") != null && !body.get("appKey").toString().isBlank()
                ? "ENABLED" : "NOT_CONFIGURED");
        channelMapper.updateById(ch);
        return Map.of("message", "已保存 " + platform + " 通道配置", "channel", ch);
    }

    /** 测试通道：当前为占位实现，返回明确的“入口已预留”提示 */
    @PostMapping("/channels/{id}/test")
    public Map<String, Object> testChannel(@PathVariable Long id) {
        IntegrationChannel ch = channelMapper.selectById(id);
        if (ch == null) throw new IllegalArgumentException("通道不存在");
        return integrationService.testConnection(ch, adapters);
    }

    /**
     * 订单 Excel 导入（前端解析 xlsx 后传结构化 JSON）：
     * { platform, orders: [{ orderNo, orderTime, buyer, remark, items:[{productName, quantity, price}] }] }
     * 逐单独立事务：失败不中断整体，返回成功/重复/新建商品/失败明细。
     */
    @PostMapping("/orders/import")
    public Map<String, Object> importOrders(@RequestBody Map<String, Object> body) {
        String platform = (String) body.get("platform");
        adapters.stream().filter(a -> a.platform().equals(platform))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("未知平台: " + platform));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> orders = (List<Map<String, Object>>) body.get("orders");
        if (orders == null || orders.isEmpty()) throw new IllegalArgumentException("没有可导入的订单");
        String username = AuthInterceptor.currentUser().getUsername();

        int imported = 0, duplicated = 0, newProducts = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Map<String, Object> o : orders) {
            String orderNo = o.get("orderNo") == null ? "?" : o.get("orderNo").toString();
            try {
                Map<String, Object> r = integrationService.importOne(platform, o, username);
                if ("DUPLICATE".equals(r.get("status"))) duplicated++;
                else {
                    imported++;
                    newProducts += ((Number) r.getOrDefault("newProducts", 0)).intValue();
                }
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("orderNo", orderNo);
                err.put("error", e.getMessage() == null ? "导入失败" : e.getMessage());
                errors.add(err);
                log.error("平台订单导入失败 {}: {}", orderNo, e.getMessage());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", imported);
        result.put("duplicated", duplicated);
        result.put("failed", errors.size());
        result.put("newProducts", newProducts);
        result.put("errors", errors);
        result.put("message", String.format("导入完成：成功 %d 单，重复跳过 %d 单，失败 %d 单，新建商品 %d 个",
                imported, duplicated, errors.size(), newProducts));
        return result;
    }

    /** 已导入的平台订单（含映射的本地销售单号） */
    @GetMapping("/orders")
    public List<Map<String, Object>> orders(@RequestParam(required = false) String platform) {
        LambdaQueryWrapper<PlatformOrder> qw = new LambdaQueryWrapper<>();
        if (platform != null && !platform.isBlank()) qw.eq(PlatformOrder::getPlatform, platform);
        qw.orderByDesc(PlatformOrder::getId).last("limit 200");
        return orderMapper.selectList(qw).stream().map(po -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", po.getId());
            m.put("platform", po.getPlatform());
            m.put("platformOrderNo", po.getPlatformOrderNo());
            m.put("buyer", po.getBuyer());
            m.put("orderTime", po.getOrderTime());
            m.put("billId", po.getBillId());
            if (po.getBillId() != null) {
                Bill b = billMapper.selectById(po.getBillId());
                m.put("billNo", b != null ? b.getBillNo() : null);
                m.put("billAmount", b != null ? b.getTotalAmount() : null);
            }
            m.put("createdAt", po.getCreatedAt());
            return m;
        }).toList();
    }
}
