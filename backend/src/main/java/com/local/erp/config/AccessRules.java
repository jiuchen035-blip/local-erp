package com.local.erp.config;

import java.util.List;

/**
 * 访问权限规则（唯一事实来源）：
 * 拦截器（HTTP 层）与 AI 网关（Agent 工具层）共用同一套规则，实现前端与 AI 操作平权。
 */
public final class AccessRules {

    /** 仅管理员可访问的 API 前缀 */
    public static final List<String> ADMIN_PREFIXES = List.of(
            "/api/users", "/api/system", "/api/finance", "/api/integrations");

    /** 仅管理员可执行的方法 */
    public static final List<String> ADMIN_METHODS = List.of("DELETE");

    /** 管理员可访问的菜单（与前端 App.vue 同源，供 AI 身份说明用） */
    public static final List<String> ADMIN_MENUS = List.of("财务记账", "电商对接", "系统设置");

    /** 全员可访问的菜单 */
    public static final List<String> COMMON_MENUS = List.of(
            "经营看板", "经营报表", "商品管理", "进货 / 销售", "同行库存", "调货记录",
            "库存预警", "供应商 / 客户", "应收应付", "仓库管理", "灭火器年检", "库存盘点",
            "AI 助手", "知识库");

    private AccessRules() {}

    /** URI + HTTP 方法是否允许该角色访问（HTTP 层规则） */
    public static boolean allow(String role, String uri, String method) {
        if ("ADMIN".equals(role)) return true;
        if (ADMIN_METHODS.contains(method.toUpperCase())) return false;
        return ADMIN_PREFIXES.stream().noneMatch(uri::startsWith);
    }

    /** AI 工具是否允许该角色执行（工具层规则）：requiredAdmin=true 的动作仅管理员 */
    public static boolean allowTool(String role, boolean requiredAdmin) {
        return !requiredAdmin || "ADMIN".equals(role);
    }

    /** 拒绝原因文案 */
    public static String denyReason(String role, boolean requiredAdmin) {
        return "当前角色（" + ("ADMIN".equals(role) ? "管理员" : "操作员")
                + "）无权执行此操作" + (requiredAdmin ? "，该操作仅管理员可用" : "");
    }
}
