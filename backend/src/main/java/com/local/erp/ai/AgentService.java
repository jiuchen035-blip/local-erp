package com.local.erp.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.dto.BillDto;
import com.local.erp.entity.Bill;
import com.local.erp.entity.Partner;
import com.local.erp.entity.Product;
import com.local.erp.entity.Warehouse;
import com.local.erp.mapper.PartnerMapper;
import com.local.erp.mapper.BillMapper;
import com.local.erp.mapper.PeerStockMapper;
import com.local.erp.mapper.BillItemMapper;
import com.local.erp.mapper.StockRecordMapper;
import com.local.erp.mapper.InspectionMapper;
import com.local.erp.entity.BillItem;
import com.local.erp.entity.PeerStock;
import com.local.erp.entity.Inspection;
import com.local.erp.entity.StockRecord;
import com.local.erp.service.ProductCodeService;
import com.local.erp.config.AccessRules;
import com.local.erp.config.AuthInterceptor;
import com.local.erp.config.AuthInterceptor.SessionUser;
import com.local.erp.entity.OpLog;
import com.local.erp.mapper.OpLogMapper;
import com.local.erp.mapper.ProductMapper;
import com.local.erp.mapper.WarehouseMapper;
import com.local.erp.service.BillService;
import com.local.erp.service.StockService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 草稿开单：Function Calling 工具编排。
 * 大模型自主调用 查商品/查预警/查仓库/查往来单位 工具收集信息，
 * 最终调用 create_draft_bill 生成草稿单据，人工确认后才过账落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final ModelClient modelClient;
    private final ProductMapper productMapper;
    private final WarehouseMapper warehouseMapper;
    private final PartnerMapper partnerMapper;
    private final com.local.erp.mapper.PeerStockMapper peerStockMapper;
    private final com.local.erp.mapper.BillMapper billMapper;
    private final com.local.erp.mapper.BillItemMapper billItemMapper;
    private final com.local.erp.mapper.StockRecordMapper recordMapper;
    private final com.local.erp.mapper.InspectionMapper inspectionMapper;
    private final com.local.erp.service.ProductCodeService productCodeService;
    private final OpLogMapper opLogMapper;
    private final AiAuditService aiAudit;
    private final StockService stockService;
    private final BillService billService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final ObjectMapper om = new ObjectMapper();

    private static final String SYS = """
            你是本地进销存系统的智能开单助手。帮助商家把自然语言转成单据草稿。
            工作流程：
            1. 信息不全时先用工具查询：search_products 搜商品（可按名称或SKU），warehouses 查仓库，partners 查往来单位，low_stock 查库存预警。
            2. 商品价格：采购用商品成本价，销售用商品售价，除非用户明确指定。
            3. 往来单位：销售类单据（SALE/SALE_RETURN）对应 CUSTOMER 客户，采购类单据（PURCHASE/PURCHASE_RETURN）对应 SUPPLIER 供应商。如果往来单位在档案里不存在，先调用 create_partner 创建（用用户说的名字建档即可，详细资料用户会自己补），再开单；也可以直接把往来单位名称填进 create_draft_bill 的 partnerId，系统会自动匹配或建档。
            4. 挂账：用户说“挂账/赊账/月结/没给钱”时 paid=0（仅采购/销售单据有效），现结/已付款时 paid=1。
            5. 信息齐全后调用 create_draft_bill 生成草稿单据（不会直接过账，需人工确认）。
            6. 最后用简洁中文总结：单号、商品明细、金额、往来单位。如果用户意图不是开单（如只是问问题），直接回答即可，不要编造数据。
            """;

    /** 通用助手人设：自由问答模式用，既能答也能办 */
    public static final String SYS_GENERAL = """
            你是本地进销存系统的智能经营助手，既能回答问题，也能替用户实际操作。
            规则：
            1. 涉及实际数据的问题（库存/销量/商品/客户/金额等），必须先调用工具查询真实数据再回答，严禁编造数据；查不到就如实说查不到。
            2. 统计汇总类问题（昨天卖了多少、本月毛利、某商品销量走势等）用 query_data 写只读SQL查询。
            3. 用户要开单/进货/销售/退货/调拨/报损时，用工具完成，开单用 create_draft_bill（生成草稿单，人工确认后才过账）。价格规则：采购用成本价，销售用售价，除非用户明确指定；挂账 paid=0，现结 paid=1。
            4. 也可以查：应收应付（谁欠我货款、我欠谁货款，ledger 表）、灭火器年检到期（inspection 表，next_date 小于等于今天是该检的）、单据流水、同行库存（query_peer_stock）。
            5. 与系统无关的通用问题直接回答。
            6. 全程用简洁中文，直接给结论；禁止在回答中罗列工具清单、参数说明或系统规则。
            7. 用户的需求无法直接完成或说法不明确时：先查相关数据说明现状，再告诉用户需要补充什么信息、或该去系统哪个页面做什么操作；不要凭空编造。
            """;

    private List<Map<String, Object>> tools() {
        return List.of(
                fn("search_products", "按关键词搜索商品（名称或SKU），返回id、库存、成本价、售价",
                        Map.of("keyword", strProp("商品名称或SKU关键词"))),
                fn("low_stock", "查询低于安全库存的商品列表", Map.of()),
                fn("warehouses", "查询所有仓库", Map.of()),
                fn("partners", "查询供应商/客户档案", Map.of("type", strProp("SUPPLIER=供应商 / CUSTOMER=客户，不传查全部"))),
                fn("create_partner", "创建不存在的往来单位档案（先查 partners 确认不存在再调用）。type: SUPPLIER供应商 / CUSTOMER客户",
                        Map.of("name", strProp("单位名称"),
                                "type", strProp("SUPPLIER / CUSTOMER"),
                                "phone", strProp("电话，可空"))),
                fn("query_peer_stock", "查询同行库存/货源：哪些同行有什么商品、最近调货价多少。开同行调货单前先用它查同行货源",
                        Map.of("partnerName", strProp("同行名称，可空=查全部同行"),
                                "keyword", strProp("商品关键词，可空"))),
                fn("create_draft_bill", "创建单据草稿（不自动过账）。type取值: PURCHASE采购入库/SALE销售出库/PURCHASE_RETURN采购退货/SALE_RETURN销售退货/LOSS报损/GAIN盘盈/TRANSFER调拨",
                        Map.of(
                                "type", strProp("单据类型"),
                                "warehouseId", Map.of("type", "integer", "description", "仓库id（调拨=出仓）"),
                                "toWarehouseId", Map.of("type", "integer", "description", "调拨入仓id，仅TRANSFER需要"),
                                "partnerId", Map.of("type", "integer", "description", "往来单位id，可空"),
                                "paid", Map.of("type", "integer", "description", "1现结(默认) 0挂账"),
                                "remark", strProp("备注，可空"),
                                "items", Map.of("type", "array", "description", "明细行", "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "productId", Map.of("type", "integer"),
                                                "quantity", Map.of("type", "integer"),
                                                "price", Map.of("type", "number")))))),
                fn("who_am_i", "查当前登录身份与权限范围：我是谁、什么角色、能访问哪些模块和操作。用户问'我是谁/我能做什么/我有什么权限'时用它",
                        Map.of()),
                fn("get_business_flow", "查业务流程说明：采购入库、销售出库、退货冲正、同行调货、灭火器年检、应收应付等完整链路。用户问'流程怎么走/怎么操作'时用它",
                        Map.of("flow", strProp("流程名：采购 / 销售 / 退货 / 同行调货 / 灭火器年检 / 应收应付，可空=全部"))),
                fn("find_risks", "跨模块风险扫描：聚合低库存、批次临期、年检过期、挂账欠款、滞留草稿等风险并给处理建议。用户问'有什么风险/需要注意什么'时用它",
                        Map.of()),
                fn("get_dashboard", "经营概况：今日/本月销售额与毛利、库存价值、低库存商品数、销量Top3。用户问今天卖了多少、这个月毛利多少时用它",
                        Map.of()),
                fn("query_bills", "查单据流水（最近50张）：单号/类型/金额/挂账/时间。可按状态(DRAFT草稿/POSTED已过账)和类型(PURCHASE采购/SALE销售/PURCHASE_RETURN采购退货/SALE_RETURN销售退货/LOSS报损/GAIN盘盈/TRANSFER调拨)筛选",
                        Map.of("status", strProp("DRAFT 或 POSTED，可空"),
                                "type", strProp("单据类型，可空"))),
                fn("query_bill_detail", "查单据明细：某张单据的完整商品明细行（商品/数量/单价/金额）",
                        Map.of("billId", Map.of("type", "integer", "description", "单据id（先 query_bills 拿到）"))),
                fn("query_stock_detail", "查商品分仓库库存明细：某商品在各仓库各有多少",
                        Map.of("keyword", strProp("商品名称/SKU 关键词，可空=全部"))),
                fn("query_receivables", "应收应付台账汇总：各往来单位欠款金额+合计。用户问应收多少钱、应付多少钱、谁欠款时用它。direction: RECEIVABLE=应收 / PAYABLE=应付 / 不传=两者都查",
                        Map.of("direction", strProp("RECEIVABLE / PAYABLE，可空"),
                                "partnerId", Map.of("type", "integer", "description", "只看某往来单位，可空"))),
                fn("query_inspections", "灭火器年检记录与到期名单：返回超期(OVERDUE)和即将到期(DUE)的客户/规格/下次年检日期。用户问哪些灭火器该年检了时用它",
                        Map.of("dueDays", Map.of("type", "integer", "description", "未来多少天内到期算'即将到期'，默认30"),
                                "keyword", strProp("客户名称关键词，可空"))),
                fn("create_inspection", "登记灭火器年检记录（next_date 自动=本次年检日期+12个月，总价自动=数量×单价）",
                        Map.of("customerName", strProp("客户名称，必填"),
                                "inspectDate", strProp("本次年检日期 yyyy-MM-dd，默认今天"),
                                "spec", strProp("灭火器规格，如 4kg干粉"),
                                "quantity", Map.of("type", "integer", "description", "数量，默认1"),
                                "price", Map.of("type", "number", "description", "年检单价，默认0"),
                                "phone", strProp("联系电话，可空"))),
                fn("query_peer_history", "同行调货历史流水：某同行（或全部）调过哪些商品、每次数量/价格/时间/单号",
                        Map.of("partnerName", strProp("同行名称，可空"),
                                "keyword", strProp("商品关键词，可空"))),
                fn("create_product", "商品建档：新增一个商品到商品档案（SKU 自动生成，安全库存默认10）",
                        Map.of("name", strProp("商品名称，必填"),
                                "salePrice", Map.of("type", "number", "description", "零售价"),
                                "costPrice", Map.of("type", "number", "description", "成本价"),
                                "category", strProp("一级分类，可空"),
                                "unit", strProp("单位，默认个"),
                                "barcode", strProp("条码，可空"))),
                fn("query_expiring_batches", "批次临期查询：N 天内到期的采购批次（需商品设保质期、采购登记生产日期）",
                        Map.of("days", Map.of("type", "integer", "description", "未来多少天内到期，默认60"))),
                fn("delete_draft", "删除开错的草稿单（只能删 DRAFT 草稿，已过账单不可删）",
                        Map.of("billId", Map.of("type", "integer", "description", "草稿单id（先 query_bills 查到）"))),
                fn("create_peer_draft", "同行调货开单（生成两张关联草稿：同行采购入库+销售出库，用户确认后一起过账）。peerItems 是从同行调货的商品行（先 query_peer_stock 查同行货源拿 stockId 和调货价）；ownItems 是自己库存的商品行（先 search_products 拿 productId）。支持混合单",
                        Map.of("peerName", strProp("调货同行名称，必填"),
                                "peerItems", Map.of("type", "array", "description", "同行调货商品行", "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "stockId", Map.of("type", "integer", "description", "同行库存记录id（query_peer_stock 返回）"),
                                                "quantity", Map.of("type", "integer"),
                                                "peerPrice", Map.of("type", "number", "description", "调货价"),
                                                "salePrice", Map.of("type", "number", "description", "销售单价")))),
                                "ownItems", Map.of("type", "array", "description", "自己库存的商品行，可空", "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "productId", Map.of("type", "integer"),
                                                "quantity", Map.of("type", "integer"),
                                                "price", Map.of("type", "number", "description", "销售单价")))),
                                "warehouseId", Map.of("type", "integer", "description", "仓库id，可空=默认第一个仓库"))),
                fn("query_data", "执行只读SQL查询经营数据（仅允许单条SELECT语句）。可查表：product(id, name商品名, sku, barcode条码, category一级分类, sub_category二级分类, sub2_category三级分类, sale_price售价, cost_price成本价, avg_cost加权均价, safe_stock安全库存[0=仅缺货提醒], no_alert 1=不监控, enabled)、stock_record(id, product_id, partner_id供应商或客户, type PURCHASE采购入库/SALE销售出库/ADJUST盘整, quantity数量采购为正销售为负, price单价, cost_price结转成本, paid 1现结0挂账, created_at时间。库存=quantity按product_id求和，毛利=销售额-结转成本)、bill(id, bill_no单号, type类型, partner_id, status DRAFT/POSTED, paid, total_amount, created_at, posted_at)、bill_item(bill_id, product_id, quantity, price, batch_no批次号, production_date生产日期)、partner(id, type SUPPLIER供应商/CUSTOMER客户, name, phone, opening_receivable期初应收, opening_payable期初应付)、ledger(应收应付台账: party_name往来单位, direction RECEIVABLE应收/PAYABLE应付, amount金额, settled 1已结清0未结清, created_at)、warehouse(id, name仓库名)、inspection(灭火器年检: customer_name客户, phone, spec规格, quantity, price, total_amount, inspect_date本次年检, next_date下次年检[小于等于今天=该检了])、peer_stock(同行库存: partner_id同行, product_name商品, sku, category, last_price最近调货价)",
                        Map.of("sql", strProp("单条SQLite SELECT语句"))));
    }

    /** 同行库存/货源查询（query_peer_stock 工具）：按同行名、商品关键词筛选 */
    private Object queryPeerStock(String partnerName, String keyword) {
        Map<Long, String> partnerNames = new HashMap<>();
        for (var pt : partnerMapper.selectList(null)) partnerNames.put(pt.getId(), pt.getName());
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var s : peerStockMapper.selectList(null)) {
            String pn = partnerNames.getOrDefault(s.getPartnerId(), "");
            if (partnerName != null && !partnerName.isBlank()
                    && !pn.toLowerCase().contains(partnerName.trim().toLowerCase())) continue;
            if (!kw.isEmpty() && !(nz2(s.getProductName()) + " " + nz2(s.getSku())).toLowerCase().contains(kw)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("同行", pn);
            m.put("商品名称", s.getProductName());
            m.put("SKU", s.getSku());
            m.put("分类", nz2(s.getCategory()));
            m.put("最近调货价", s.getLastPrice());
            result.add(m);
        }
        result.sort((a, b) -> String.valueOf(b.get("最近调货价")).compareTo(String.valueOf(a.get("最近调货价"))));
        return result;
    }

    private String nz2(String s) { return s == null ? "" : s; }

    // ==================== 扩充工具实现 ====================

    /** 经营概况（口径与 DashboardController 一致：仅 SALE 单计入经营流水） */
    private Object dashboardTool() {
        List<StockRecord> records = recordMapper.selectList(null);
        Map<Long, Product> products = productMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.toMap(Product::getId, p -> p, (a, b) -> a));
        Map<Long, String> billTypes = new HashMap<>();
        billMapper.selectList(null).forEach(b -> billTypes.put(b.getId(), b.getType()));

        String today = java.time.LocalDate.now().toString();
        String month = today.substring(0, 7);
        double todaySale = 0, monthSale = 0, todayProfit = 0, monthProfit = 0, inventoryValue = 0;
        Map<String, double[]> byProduct = new HashMap<>();
        for (StockRecord r : records) {
            String billType = r.getBillId() != null ? billTypes.get(r.getBillId()) : null;
            if (!"SALE".equals(r.getType()) || (billType != null && !"SALE".equals(billType))) continue;
            double amt = Math.abs(r.getQuantity()) * r.getPrice();
            double cost = Math.abs(r.getQuantity()) * (r.getCostPrice() == null ? 0 : r.getCostPrice());
            String d = r.getCreatedAt() == null ? "" : r.getCreatedAt().substring(0, 10);
            if (today.equals(d)) { todaySale += amt; todayProfit += amt - cost; }
            if (d.startsWith(month)) { monthSale += amt; monthProfit += amt - cost; }
            Product p = products.get(r.getProductId());
            if (p != null) {
                byProduct.computeIfAbsent(p.getName(), k -> new double[2]);
                byProduct.get(p.getName())[0] += Math.abs(r.getQuantity());
                byProduct.get(p.getName())[1] += amt;
            }
        }
        Map<Long, Integer> stock = stockService.currentStock();
        for (Product p : products.values()) {
            double unitCost = p.getAvgCost() != null ? p.getAvgCost() : p.getCostPrice();
            inventoryValue += stock.getOrDefault(p.getId(), 0) * unitCost;
        }
        List<Map<String, Object>> top = byProduct.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]))
                .limit(3)
                .map(e -> Map.<String, Object>of("商品", e.getKey(), "销量", e.getValue()[0],
                        "销售额", Math.round(e.getValue()[1] * 100) / 100.0))
                .collect(java.util.stream.Collectors.toList());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("今日销售额", round2(todaySale));
        m.put("今日毛利", round2(todayProfit));
        m.put("本月销售额", round2(monthSale));
        m.put("本月毛利", round2(monthProfit));
        m.put("库存价值", round2(inventoryValue));
        m.put("低库存商品数", stockService.lowStock().size());
        m.put("销量Top3", top);
        return m;
    }

    private Object queryBills(String status, String type) {
        LambdaQueryWrapper<com.local.erp.entity.Bill> qw = new LambdaQueryWrapper<com.local.erp.entity.Bill>()
                .orderByDesc(com.local.erp.entity.Bill::getId).last("limit 50");
        if (status != null && !status.isBlank()) qw.eq(com.local.erp.entity.Bill::getStatus, status);
        if (type != null && !type.isBlank()) qw.eq(com.local.erp.entity.Bill::getType, type);
        Map<Long, String> partnerNames = new HashMap<>();
        for (var pt : partnerMapper.selectList(null)) partnerNames.put(pt.getId(), pt.getName());
        List<Map<String, Object>> result = new ArrayList<>();
        for (var b : billMapper.selectList(qw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("单号", b.getBillNo());
            m.put("类型", b.getType());
            m.put("往来单位", partnerNames.getOrDefault(b.getPartnerId(), "-"));
            m.put("金额", b.getTotalAmount());
            m.put("状态", b.getStatus());
            m.put("挂账", b.getPaid() != null && b.getPaid() == 0 ? "是" : "否");
            m.put("备注", b.getRemark());
            m.put("时间", b.getCreatedAt());
            result.add(m);
        }
        return result;
    }

    private Object queryStockDetail(String keyword) {
        String k = keyword == null ? "" : keyword.trim().toLowerCase();
        Map<Long, Product> products = productMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.toMap(Product::getId, p -> p, (a, b) -> a));
        Map<Long, String> whNames = new HashMap<>();
        for (var w : warehouseMapper.selectList(null)) whNames.put(w.getId(), w.getName());
        List<Map<String, Object>> result = new ArrayList<>();
        stockService.stockByWarehouse().forEach((wid, byProduct) -> {
            byProduct.forEach((pid, qty) -> {
                Product p = products.get(pid);
                if (p == null) return;
                if (!k.isEmpty() && !(nz2(p.getName()) + " " + nz2(p.getSku())).toLowerCase().contains(k)) return;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("商品", p.getName());
                m.put("SKU", p.getSku());
                m.put("仓库", whNames.getOrDefault(wid, "未分仓库"));
                m.put("数量", qty);
                result.add(m);
            });
        });
        return result;
    }

    /** 应收应付汇总（口径与 LedgerController.SUMMARY_SQL 一致：期初 + 挂账流水） */
    private Object queryReceivables(String direction, Object partnerId) {
        String sql = "SELECT p.id AS partnerId, p.name AS name, p.phone AS phone, "
                + "p.opening_receivable AS openingReceivable, p.opening_payable AS openingPayable, "
                + "COUNT(r.id) AS cnt, SUM(ABS(r.quantity) * r.price) AS amount "
                + "FROM partner p "
                + "LEFT JOIN stock_record r ON r.partner_id = p.id AND r.paid = 0 AND r.type = ? "
                + "GROUP BY p.id, p.name, p.phone, p.opening_receivable, p.opening_payable "
                + "HAVING COUNT(r.id) > 0 OR p.opening_receivable > 0 OR p.opening_payable > 0";
        boolean wantRecv = direction == null || direction.isBlank() || "RECEIVABLE".equals(direction);
        boolean wantPay = direction == null || direction.isBlank() || "PAYABLE".equals(direction);
        Long pid = null;
        if (partnerId != null && !str(partnerId).isEmpty()) pid = Long.parseLong(str(partnerId));

        Map<String, Object> out = new LinkedHashMap<>();
        double recvTotal = 0, payTotal = 0;
        List<Map<String, Object>> detail = new ArrayList<>();
        if (wantRecv) {
            double total = 0;
            for (var row : jdbcTemplate.queryForList(sql, "SALE")) {
                double opening = row.get("openingReceivable") == null ? 0 : Double.parseDouble(String.valueOf(row.get("openingReceivable")));
                double amt = row.get("amount") == null ? 0 : Double.parseDouble(String.valueOf(row.get("amount")));
                if (opening <= 0 && amt <= 0) continue;
                if (pid != null && row.get("partnerId") != null
                        && Long.parseLong(String.valueOf(row.get("partnerId"))) != pid) continue;
                Map<String, Object> m = new LinkedHashMap<>(row);
                m.put("方向", "应收");
                m.put("当前欠款合计", round2(opening + amt));
                detail.add(m);
                total += opening + amt;
            }
            recvTotal = total;
        }
        if (wantPay) {
            double total = 0;
            for (var row : jdbcTemplate.queryForList(sql, "PURCHASE")) {
                double opening = row.get("openingPayable") == null ? 0 : Double.parseDouble(String.valueOf(row.get("openingPayable")));
                double amt = row.get("amount") == null ? 0 : Double.parseDouble(String.valueOf(row.get("amount")));
                if (opening <= 0 && amt <= 0) continue;
                if (pid != null && row.get("partnerId") != null
                        && Long.parseLong(String.valueOf(row.get("partnerId"))) != pid) continue;
                Map<String, Object> m = new LinkedHashMap<>(row);
                m.put("方向", "应付");
                m.put("当前欠款合计", round2(opening + amt));
                detail.add(m);
                total += opening + amt;
            }
            payTotal = total;
        }
        out.put("应收合计", round2(recvTotal));
        out.put("应付合计", round2(payTotal));
        out.put("明细", detail);
        return out;
    }

    /** 灭火器年检：到期名单（复用 InspectionController.statusOf 的判定口径） */
    private Object queryInspections(int dueDays, String keyword) {
        List<Map<String, Object>> due = new ArrayList<>();
        int overdue = 0, dueCnt = 0, normal = 0;
        for (Inspection i : inspectionMapper.selectList(null)) {
            String st = com.local.erp.controller.InspectionController.statusOf(i, dueDays);
            if (st.equals("OVERDUE")) overdue++;
            else if (st.equals("DUE")) dueCnt++;
            else normal++;
            if (st.equals("NORMAL")) continue;
            if (keyword != null && !keyword.isBlank()
                    && !nz2(i.getCustomerName()).toLowerCase().contains(keyword.toLowerCase())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("客户", i.getCustomerName());
            m.put("电话", i.getPhone());
            m.put("规格", i.getSpec());
            m.put("数量", i.getQuantity());
            m.put("下次年检日期", i.getNextDate());
            m.put("状态", st.equals("OVERDUE") ? "已过期" : "即将到期");
            due.add(m);
        }
        due.sort((a, b) -> String.valueOf(a.get("下次年检日期")).compareTo(String.valueOf(b.get("下次年检日期"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("已过期数", overdue);
        out.put("即将到期数", dueCnt);
        out.put("正常数", normal);
        out.put("到期名单", due);
        return out;
    }

    /** 登记灭火器年检（next_date 自动 +12 个月，总价自动算） */
    private Object createInspectionTool(Map<String, Object> args) {
        String customerName = str(args.get("customerName"));
        if (customerName.isBlank()) throw new IllegalArgumentException("客户名称必填");
        Inspection i = new Inspection();
        i.setCustomerName(customerName.trim());
        String inspectDate = str(args.get("inspectDate"));
        if (inspectDate.isBlank()) inspectDate = java.time.LocalDate.now().toString();
        i.setInspectDate(inspectDate);
        i.setNextDate(java.time.LocalDate.parse(inspectDate).plusMonths(12).toString());
        i.setSpec(str(args.get("spec")));
        i.setPhone(str(args.get("phone")));
        i.setQuantity(args.get("quantity") == null ? 1 : (int) Double.parseDouble(str(args.get("quantity"))));
        double price = args.get("price") == null ? 0 : Double.parseDouble(str(args.get("price")));
        i.setPrice(price);
        i.setTotalAmount(Math.round(i.getQuantity() * price * 100) / 100.0);
        inspectionMapper.insert(i);
        return Map.of("message", "年检已登记：客户 " + i.getCustomerName() + "，下次年检 " + i.getNextDate()
                + "，金额 " + i.getTotalAmount(), "id", i.getId());
    }

    /** 同行调货历史流水（带供应商的采购流水，按时间倒序取 30 条） */
    private Object queryPeerHistory(String partnerName, String keyword) {
        Map<Long, String> partnerNames = new HashMap<>();
        for (var pt : partnerMapper.selectList(null)) partnerNames.put(pt.getId(), pt.getName());
        Map<Long, Product> products = productMapper.selectList(null).stream()
                .collect(java.util.stream.Collectors.toMap(Product::getId, p -> p, (a, b) -> a));
        Map<Long, com.local.erp.entity.Bill> bills = new HashMap<>();
        billMapper.selectList(null).forEach(b -> bills.put(b.getId(), b));
        List<Map<String, Object>> result = new ArrayList<>();
        for (StockRecord r : recordMapper.selectList(null)) {
            if (!"PURCHASE".equals(r.getType()) || r.getPartnerId() == null) continue;
            String pn = partnerNames.getOrDefault(r.getPartnerId(), "");
            Product p = products.get(r.getProductId());
            if (p == null) continue;
            if (partnerName != null && !partnerName.isBlank()
                    && !pn.toLowerCase().contains(partnerName.trim().toLowerCase())) continue;
            if (keyword != null && !keyword.isBlank()
                    && !nz2(p.getName()).toLowerCase().contains(keyword.trim().toLowerCase())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("同行", pn);
            m.put("商品", p.getName());
            m.put("数量", Math.abs(r.getQuantity()));
            m.put("单价", r.getPrice());
            m.put("时间", r.getCreatedAt());
            var bill = bills.get(r.getBillId());
            m.put("单号", bill == null ? "" : bill.getBillNo());
            m.put("挂账", bill != null && bill.getPaid() != null && bill.getPaid() == 0 ? "是" : "否");
            result.add(m);
        }
        result.sort((a, b) -> String.valueOf(b.get("时间")).compareTo(String.valueOf(a.get("时间"))));
        return result.size() > 30 ? result.subList(0, 30) : result;
    }

    /** 商品建档（create_product 工具）：SKU 自动生成，安全库存默认 10 */
    private Object createProductTool(Map<String, Object> args) {
        String name = str(args.get("name"));
        if (name.isBlank()) throw new IllegalArgumentException("商品名称必填");
        Product p = new Product();
        p.setName(name.trim());
        p.setSku(productCodeService.nextProductSku(nz2(str(args.get("category"))), "", ""));
        p.setSalePrice(args.get("salePrice") == null ? 0 : Double.parseDouble(str(args.get("salePrice"))));
        p.setCostPrice(args.get("costPrice") == null ? 0 : Double.parseDouble(str(args.get("costPrice"))));
        p.setCategory(str(args.get("category")));
        p.setUnit(str(args.get("unit")).isBlank() ? "个" : str(args.get("unit")));
        p.setBarcode(str(args.get("barcode")).isBlank() ? null : str(args.get("barcode")));
        p.setSafeStock(10);
        p.setEnabled(1);
        productMapper.insert(p);
        return Map.of("message", "商品已建档：" + p.getName() + "（SKU " + p.getSku() + "）",
                "id", p.getId(), "sku", p.getSku());
    }

    private Object queryExpiringBatches(int days) {
        Map<Long, String> billTypes = new HashMap<>();
        billMapper.selectList(null).forEach(b -> billTypes.put(b.getId(), b.getType()));
        Map<Long, Product> products = new HashMap<>();
        productMapper.selectList(null).forEach(p -> products.put(p.getId(), p));

        java.time.LocalDate today = java.time.LocalDate.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BillItem item : billItemMapper.selectList(null)) {
            var bill = billMapper.selectById(item.getBillId());
            if (bill == null || !"PURCHASE".equals(bill.getType()) || !"POSTED".equals(bill.getStatus())) continue;
            if (item.getProductionDate() == null || item.getProductionDate().isBlank()) continue;
            Product p = products.get(item.getProductId());
            if (p == null || p.getShelfLifeDays() == null || p.getShelfLifeDays() <= 0) continue;
            java.time.LocalDate expiry;
            try { expiry = java.time.LocalDate.parse(item.getProductionDate()).plusDays(p.getShelfLifeDays()); }
            catch (Exception e) { continue; }
            long left = expiry.toEpochDay() - today.toEpochDay();
            if (left > days) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("商品", p.getName());
            m.put("批次号", item.getBatchNo());
            m.put("数量", item.getQuantity());
            m.put("到期日", expiry.toString());
            m.put("剩余天数", left < 0 ? "已过期" + (-left) + "天" : left + "天");
            rows.add(m);
        }
        rows.sort((a, b) -> String.valueOf(a.get("剩余天数")).compareTo(String.valueOf(b.get("剩余天数"))));
        return rows;
    }

    /** 删除草稿单（仅 DRAFT；连明细一起删） */
    private Object deleteDraft(Long billId) {
        if (billId == null) throw new IllegalArgumentException("请提供草稿单 id");
        var bill = billMapper.selectById(billId);
        if (bill == null) throw new IllegalArgumentException("单据不存在");
        if (!"DRAFT".equals(bill.getStatus())) throw new IllegalArgumentException("已过账单据不能删除（可走冲正流程）");
        billItemMapper.delete(new LambdaQueryWrapper<BillItem>().eq(BillItem::getBillId, billId));
        billMapper.deleteById(billId);
        return Map.of("message", "草稿单 " + bill.getBillNo() + " 已删除");
    }

    /** 同行调货开单（create_peer_draft 工具）：自动登记同行库存/建档，生成两张关联草稿 */
    private Object createPeerDraftTool(Map<String, Object> args) {
        String peerName = str(args.get("peerName"));
        if (peerName.isBlank()) throw new IllegalArgumentException("调货同行名称必填");
        Map<String, Object> peerArgs = new HashMap<>();
        peerArgs.put("name", peerName);
        peerArgs.put("type", "SUPPLIER");
        Map<?, ?> peerMap = (Map<?, ?>) createPartner(peerArgs);
        Long peerId = Long.parseLong(String.valueOf(peerMap.get("id")));

        List<Map<String, Object>> peerItems = (List<Map<String, Object>>) args.get("peerItems");
        if (peerItems == null || peerItems.isEmpty()) throw new IllegalArgumentException("缺少同行调货商品行（peerItems）");

        com.local.erp.dto.BillDto purchaseDto = new com.local.erp.dto.BillDto();
        purchaseDto.setType("PURCHASE");
        purchaseDto.setPartnerId(peerId);
        purchaseDto.setPaid(0);
        purchaseDto.setRemark("同行调货（AI 开单）");

        com.local.erp.dto.BillDto saleDto = new com.local.erp.dto.BillDto();
        saleDto.setType("SALE");
        saleDto.setPaid(1);
        saleDto.setRemark("同行调货（AI 开单）");
        if (args.get("warehouseId") != null && !str(args.get("warehouseId")).isEmpty()) {
            saleDto.setWarehouseId(Long.parseLong(str(args.get("warehouseId"))));
        } else {
            var whs = warehouseMapper.selectList(new LambdaQueryWrapper<Warehouse>().last("limit 1"));
            if (!whs.isEmpty()) saleDto.setWarehouseId(whs.get(0).getId());
        }
        purchaseDto.setWarehouseId(saleDto.getWarehouseId());

        List<Map<String, Object>> peerLines = new ArrayList<>();
        for (var it : peerItems) {
            String productName = str(it.get("productName"));
            if (productName.isBlank()) throw new IllegalArgumentException("同行商品行缺商品名称");
            double peerPrice = it.get("peerPrice") == null ? 0 : Double.parseDouble(str(it.get("peerPrice")));
            // 同行库存表登记（同名同行的同商品已存在则复用并更新价格）
            PeerStock ps = peerStockMapper.selectList(new LambdaQueryWrapper<PeerStock>()
                    .eq(PeerStock::getPartnerId, peerId)
                    .eq(PeerStock::getProductName, productName)).stream().findFirst().orElse(null);
            if (ps == null) {
                ps = new PeerStock();
                ps.setPartnerId(peerId);
                ps.setProductName(productName);
                ps.setSku(productCodeService.nextPeerSku());
                ps.setUnit("个");
            }
            ps.setLastPrice(peerPrice);
            if (ps.getId() == null) peerStockMapper.insert(ps); else peerStockMapper.updateById(ps);

            int qty = it.get("quantity") == null ? 1 : (int) Double.parseDouble(str(it.get("quantity")));
            double salePrice = it.get("salePrice") == null ? 0 : Double.parseDouble(str(it.get("salePrice")));
            Map<String, Object> line = new HashMap<>();
            line.put("stockId", ps.getId());
            line.put("quantity", qty);
            line.put("salePrice", salePrice);
            line.put("peerPrice", peerPrice);
            peerLines.add(line);
        }

        Map<String, Object> r = billService.createPeerDrafts(saleDto, purchaseDto, peerLines, "AI助手");
        com.local.erp.entity.Bill sale = (com.local.erp.entity.Bill) r.get("sale");
        com.local.erp.entity.Bill purchase = (com.local.erp.entity.Bill) r.get("purchase");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saleId", sale.getId());
        out.put("saleNo", sale.getBillNo());
        out.put("purchaseNo", purchase.getBillNo());
        out.put("purchaseId", purchase.getId());
        out.put("total", sale.getTotalAmount());
        List<Map<String, Object>> views = new ArrayList<>();
        views.addAll((List<Map<String, Object>>) r.get("saleItems"));
        views.addAll((List<Map<String, Object>>) r.get("ownItems"));
        out.put("items", views);
        out.put("hint", "已生成两张草稿单（同行采购入库+销售出库），用户确认过账后两张一起生效");
        return out;
    }

    /** 当前登录身份与权限范围（who_am_i 工具；权限规则与拦截器/前端菜单同源） */
    private Object whoAmI(String username, String role) {
        boolean admin = "ADMIN".equals(role);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("当前用户", username == null ? "AI助手（无登录会话）" : username);
        m.put("角色", admin ? "管理员" : "操作员");
        var menus = new ArrayList<String>(AccessRules.COMMON_MENUS);
        if (admin) menus.addAll(AccessRules.ADMIN_MENUS);
        m.put("可访问模块", menus);
        m.put("可执行操作", admin
                ? "全部业务查询与新增/修改/删除（含财务记账、系统设置、用户管理）"
                : "全部业务的查询与新增/修改；删除操作和管理模块（财务记账/电商对接/系统设置）需要管理员权限");
        m.put("AI 边界", "AI 只能创建草稿单，过账/删除等影响库存与账务的操作需你在页面上人工确认");
        return m;
    }

    /** 业务流程知识（get_business_flow 工具）：结构化链路，返回文字版流程图 */
    private Object businessFlow(String flow) {
        Map<String, String> flows = new LinkedHashMap<>();
        flows.put("采购", "选仓库 → 采购入库单（供应商/商品/数量/进价，可登记批次号与生产日期）→ 过账 → 库存增加、按移动加权更新成本 → 自动生成会计凭证 → 挂账进应付");
        flows.put("销售", "选仓库 → 销售出库单（客户/商品/数量/售价，可选零售/批发/会员价、整单折扣）→ 过账 → 库存减少、结转成本 → 自动生成凭证 → 挂账进应收；商品可勾选同行调货（自动生成同行采购入库+销售出库两张关联单）");
        flows.put("退货", "销售退货=客户退回（库存回加）；采购退货=退给供应商（库存再减）；都在进货/销售页按原单信息开退货单过账");
        flows.put("冲正", "已过账单据发现错误 → 单据列表点「冲正」→ 生成镜像反向单并过账还原库存，原单保留可追溯；挂账单冲正自动结清应收应付");
        flows.put("同行调货", "同行库存页登记货源（同行/商品/调货价）→ 开销售单时点「同行商品」选择 → 过账自动生成同行采购入库+销售出库两张关联单（净库存0）→ 调货记录页可查历史");
        flows.put("灭火器年检", "灭火器年检页登记（客户/规格/数量/单价/年检日期）→ 下次年检日自动+12个月 → 到期名单提醒 → 联系客户续检");
        flows.put("应收应付", "挂账单过账自动进应收（客户）/应付（供应商）→ 应收应付页看汇总与欠款明细 → 收款后点结清核销");
        flows.put("库存预警", "安全库存>0：低于即预警；=0：缺货才预警；同行调货商品可在商品档案开「不监控」");
        if (flow == null || flow.isBlank()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("流程清单", flows.keySet());
            m.put("提示", "告诉我流程名（如：采购/销售/同行调货/灭火器年检），我给你完整的链路说明");
            return m;
        }
        String key = flows.keySet().stream().filter(flow::contains).findFirst().orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        if (key == null) {
            m.put("提示", "没有找到该流程，可查的流程：" + flows.keySet());
            m.put("全部流程", flows);
            return m;
        }
        m.put("流程", key);
        m.put("链路", flows.get(key));
        return m;
    }

    /** 跨模块风险扫描（find_risks 工具）：可组织能力——聚合低库存/临期/年检过期/欠款/滞留草稿 */
    private Object findRisks() {
        List<Map<String, Object>> risks = new ArrayList<>();
        var low = stockService.lowStock();
        if (!low.isEmpty())
            risks.add(Map.of("类型", "低库存", "数量", low.size(),
                    "明细", low.stream().limit(5).map(p -> p.getName() + "（剩" +
                            stockService.currentStock().getOrDefault(p.getId(), 0) + "）").toList(),
                    "建议", "库存预警页一键补货，或同行调货"));
        int over = 0;
        List<String> dueCustomers = new ArrayList<>();
        for (Inspection i : inspectionMapper.selectList(null)) {
            String st = com.local.erp.controller.InspectionController.statusOf(i, 0);
            if (st.equals("OVERDUE")) { over++; if (dueCustomers.size() < 5) dueCustomers.add(i.getCustomerName()); }
        }
        if (over > 0)
            risks.add(Map.of("类型", "灭火器年检过期", "数量", over, "明细", dueCustomers,
                    "建议", "灭火器年检页联系客户续检"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exp = (List<Map<String, Object>>) queryExpiringBatches(60);
        if (!exp.isEmpty())
            risks.add(Map.of("类型", "批次临期", "数量", exp.size(),
                    "明细", exp.stream().limit(5).map(x -> x.get("商品") + "（" + x.get("剩余天数") + "）").toList(),
                    "建议", "促销清仓或与供应商换货"));
        String sql = "SELECT p.name AS name, SUM(ABS(r.quantity) * r.price) AS amount "
                + "FROM stock_record r JOIN partner p ON p.id = r.partner_id "
                + "WHERE r.paid = 0 AND r.type = 'SALE' AND r.created_at <= datetime('now', '-30 day') "
                + "GROUP BY p.name HAVING SUM(ABS(r.quantity) * r.price) > 0 ORDER BY 2 DESC";
        var debts = jdbcTemplate.queryForList(sql);
        if (!debts.isEmpty())
            risks.add(Map.of("类型", "逾期应收（挂账超30天）", "数量", debts.size(),
                    "明细", debts.stream().limit(5).map(x -> x.get("name") + " 欠 ￥" + x.get("amount")).toList(),
                    "建议", "应收应付页对账催款"));
        LambdaQueryWrapper<com.local.erp.entity.Bill> qw = new LambdaQueryWrapper<com.local.erp.entity.Bill>()
                .eq(com.local.erp.entity.Bill::getStatus, "DRAFT").last("limit 200");
        long stale = billMapper.selectList(qw).stream()
                .filter(b -> b.getCreatedAt() != null
                        && b.getCreatedAt().compareTo(java.time.LocalDate.now().minusDays(7).toString()) < 0)
                .count();
        if (stale > 0)
            risks.add(Map.of("类型", "滞留草稿", "数量", stale, "明细", List.of(),
                    "建议", "进货/销售页处理或删除过期草稿"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("风险总数", risks.size());
        out.put("风险清单", risks);
        out.put("提示", risks.isEmpty() ? "暂未发现明显风险" : "低风险操作我可以直接帮你做，高风险需你在页面确认");
        return out;
    }

    private double round2(double v) { return Math.round(v * 100) / 100.0; }


    private Map<String, Object> strProp(String desc) {
        return Map.of("type", "string", "description", desc);
    }

    private Map<String, Object> fn(String name, String desc, Map<String, Object> props) {
        Map<String, Object> f = new HashMap<>();
        f.put("name", name);
        f.put("description", desc);
        f.put("parameters", Map.of("type", "object", "properties", props));
        Map<String, Object> tool = new HashMap<>();
        tool.put("type", "function");
        tool.put("function", f);
        return tool;
    }

    public Map<String, Object> run(String message) {
        SessionUser u = AuthInterceptor.currentUser();
        String user = u == null ? null : u.getUsername();
        String role = u == null ? "OPERATOR" : u.getRole();
        return run(message, identityBlock(user, role) + SYS, user, role);
    }

    /** 指定人设运行（自由问答=通用助手，智能开单=开单助手） */
    public Map<String, Object> run(String message, String sysPrompt) {
        SessionUser u = AuthInterceptor.currentUser();
        String username = u == null ? null : u.getUsername();
        String role = u == null ? "OPERATOR" : u.getRole();
        return run(message, sysPrompt + identityBlock(username, role), username, role);
    }

    /** 身份与权限说明（注入系统提示，AI 原生"可理解"） */
    private String identityBlock(String username, String role) {
        boolean admin = "ADMIN".equals(role);
        return "\n\n【当前身份】你是以登录用户「" + (username == null ? "AI助手" : username)
                + "」（角色：" + (admin ? "管理员" : "操作员") + "）的身份在操作本系统，你的权限与该用户完全一致。"
                + (admin ? "可访问全部模块（含财务记账、电商对接、系统设置）与删除操作。"
                         : "不可访问财务记账、电商对接、系统设置，不可执行删除操作；用户要求这些时如实告知需管理员权限。")
                + "\n";
    }

    private Map<String, Object> run(String message, String sysPrompt, String username, String role) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", sysPrompt));
        messages.add(Map.of("role", "user", "content", message));

        Map<String, Object> draft = null;
        for (int round = 0; round < 8; round++) {
            Map<String, Object> msg = modelClient.chatRaw(messages, tools());
            messages.add(new LinkedHashMap<>(msg));

            Object callsObj = msg.get("tool_calls");
            if (!(callsObj instanceof List<?> calls) || calls.isEmpty()) {
                Map<String, Object> result = new HashMap<>();
                result.put("reply", String.valueOf(msg.getOrDefault("content", "(无回复)")));
                result.put("draft", draft);
                return result;
            }

            for (Object o : calls) {
                Map<?, ?> call = (Map<?, ?>) o;
                Map<?, ?> fnObj = (Map<?, ?>) call.get("function");
                String name = String.valueOf(fnObj.get("name"));
                Map<String, Object> args;
                try {
                    args = om.readValue(String.valueOf(fnObj.get("arguments")),
                            new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    args = new HashMap<>();
                }
                String result;
                try {
                    result = om.writeValueAsString(dispatch(name, args));
                } catch (Exception e) {
                    log.warn("Agent工具执行失败 {}: {}", name, e.getMessage());
                    result = "{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
                }
                if ("create_peer_draft".equals(name) && result.contains("\"saleId\"")) {
                    try {
                        Map<String, Object> parsed = om.readValue(result,
                                new TypeReference<Map<String, Object>>() {});
                        Map<String, Object> d = new HashMap<>();
                        d.put("id", parsed.get("saleId"));
                        d.put("billNo", parsed.get("saleNo"));
                        d.put("total", parsed.get("total"));
                        d.put("type", "SALE");
                        d.put("items", parsed.get("items"));
                        d.put("peerPurchaseNo", parsed.get("purchaseNo"));
                        d.put("peerPurchaseId", parsed.get("purchaseId"));
                        draft = d;
                    } catch (Exception ignore) { }
                }
                if ("create_draft_bill".equals(name) && result.contains("\"id\"")) {
                    try {
                        Map<String, Object> parsed = om.readValue(result,
                                new TypeReference<Map<String, Object>>() {});
                        Map<String, Object> d = new HashMap<>();
                        d.put("id", parsed.get("id"));
                        d.put("billNo", parsed.get("billNo"));
                        d.put("total", parsed.get("total"));
                        d.put("type", args.get("type"));
                        d.put("items", parsed.get("items"));
                        draft = d;
                    } catch (Exception ignore) { }
                }
                Map<String, Object> toolMsg = new HashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", String.valueOf(call.get("id")));
                toolMsg.put("content", result);
                messages.add(toolMsg);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("reply", "工具调用轮次过多，已停止。请换个说法或分步操作。");
        result.put("draft", draft);
        return result;
    }

    /** 需要管理员权限的工具（与 AccessRules 的 DELETE/管理前缀规则同语义） */
    private static final java.util.Set<String> ADMIN_TOOLS = java.util.Set.of("delete_draft");
    /** 写操作工具（执行后统一记审计日志） */
    private static final java.util.Set<String> WRITE_TOOLS = java.util.Set.of(
            "create_draft_bill", "create_peer_draft", "create_inspection",
            "create_product", "create_partner", "delete_draft");

    private Object dispatch(String name, Map<String,Object> args) {
        // AI 网关：权限平权（AI 无法绕过前端同款权限规则）
        SessionUser u = AuthInterceptor.currentUser();
        String role = u == null ? "OPERATOR" : u.getRole();
        String username = u == null ? null : u.getUsername();
        if (ADMIN_TOOLS.contains(name) && !AccessRules.allowTool(role, true))
            throw new IllegalArgumentException(AccessRules.denyReason(role, true));

        Object result = dispatchTool(name, args, username, role);
        // 审计平权：AI 写操作全量记录（操作人=真实用户）
        if (WRITE_TOOLS.contains(name)) {
            String d = String.valueOf(result);
            aiAudit.record(username, name, d.length() > 300 ? d.substring(0, 300) : d);
        }
        return result;
    }

    private Object dispatchTool(String name, Map<String, Object> args, String username, String role) {
        return switch (name) {
            case "search_products" -> searchProducts(str(args.get("keyword")));
            case "low_stock" -> lowStock();
            case "query_peer_stock" -> queryPeerStock(str(args.get("partnerName")), str(args.get("keyword")));
            case "query_data" -> queryData(str(args.get("sql")));
            case "get_dashboard" -> dashboardTool();
            case "who_am_i" -> whoAmI(username, role);
            case "get_business_flow" -> businessFlow(str(args.get("flow")));
            case "find_risks" -> findRisks();
            case "query_bills" -> queryBills(str(args.get("status")), str(args.get("type")));
            case "query_bill_detail" -> billService.detail(Long.parseLong(str(args.get("billId"))));
            case "query_stock_detail" -> queryStockDetail(str(args.get("keyword")));
            case "query_receivables" -> queryReceivables(str(args.get("direction")), args.get("partnerId"));
            case "query_inspections" -> queryInspections(args.get("dueDays") == null ? 30 : (int) Double.parseDouble(String.valueOf(args.get("dueDays"))), str(args.get("keyword")));
            case "create_inspection" -> createInspectionTool(args);
            case "query_peer_history" -> queryPeerHistory(str(args.get("partnerName")), str(args.get("keyword")));
            case "create_product" -> createProductTool(args);
            case "query_expiring_batches" -> queryExpiringBatches(args.get("days") == null ? 60 : (int) Double.parseDouble(String.valueOf(args.get("days"))));
            case "delete_draft" -> deleteDraft(args.get("billId") == null ? null : Long.parseLong(str(args.get("billId"))));
            case "create_peer_draft" -> createPeerDraftTool(args);
            case "warehouses" -> warehouseMapper.selectList(null).stream()
                    .map(w -> Map.of("id", w.getId(), "name", w.getName())).toList();
            case "partners" -> {
                String type = str(args.get("type"));
                List<Partner> list = partnerMapper.selectList(null);
                yield list.stream()
                        .filter(p -> type.isEmpty() || p.getType().equals(type))
                        .map(p -> Map.of("id", p.getId(), "name", p.getName(),
                                "type", p.getType(), "phone", p.getPhone() == null ? "" : p.getPhone()))
                        .toList();
            }
            case "create_partner" -> createPartner(args);
            case "create_draft_bill" -> createDraft(args);
            default -> Map.of("error", "未知工具: " + name);
        };
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private Object searchProducts(String keyword) {
        Map<Long, Integer> stock = stockService.currentStock();
        return productMapper.selectList(null).stream()
                .filter(p -> keyword.isBlank()
                        || p.getName().contains(keyword) || p.getSku().contains(keyword))
                .limit(10)
                .map(p -> Map.of("id", p.getId(), "name", p.getName(), "sku", p.getSku(),
                        "stock", stock.getOrDefault(p.getId(), 0),
                        "costPrice", p.getCostPrice(), "salePrice", p.getSalePrice()))
                .toList();
    }

    private Object lowStock() {
        Map<Long, Integer> stock = stockService.currentStock();
        return stockService.lowStock().stream()
                .map(p -> Map.of("id", p.getId(), "name", p.getName(),
                        "stock", stock.getOrDefault(p.getId(), 0), "safeStock", p.getSafeStock(),
                        "costPrice", p.getCostPrice()))
                .toList();
    }

    /** 只读 SQL 查询（query_data 工具）：仅单条 SELECT，黑名单关键词，最多返回 200 行 */
    private Object queryData(String sql) {
        String s = sql.trim().replaceAll("(?i)^```(sql)?|```$", "").trim();
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        if (!s.toUpperCase().startsWith("SELECT")) throw new IllegalArgumentException("仅允许 SELECT 查询");
        String up = s.toUpperCase();
        for (String bad : new String[]{"INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "ATTACH",
                "DETACH", "PRAGMA", "CREATE", "REPLACE", "VACUUM", "REINDEX"}) {
            if (java.util.regex.Pattern.compile("\\b" + bad + "\\b").matcher(up).find()) {
                throw new IllegalArgumentException("只读查询，禁止关键词: " + bad);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>(jdbcTemplate.queryForList(s));
        if (rows.size() > 200) rows = rows.subList(0, 200);
        return rows;
    }

    /** 创建往来单位：已存在同名档案时直接复用 */
    private Object createPartner(Map<String, Object> args) {
        String name = str(args.get("name")).trim();
        if (name.isEmpty()) throw new IllegalArgumentException("往来单位名称不能为空");
        String type = "SUPPLIER".equals(str(args.get("type"))) ? "SUPPLIER" : "CUSTOMER";
        Partner exist = partnerMapper.selectList(new LambdaQueryWrapper<Partner>()
                .eq(Partner::getName, name)).stream().findFirst().orElse(null);
        if (exist != null) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", exist.getId());
            r.put("name", exist.getName());
            r.put("type", exist.getType());
            r.put("message", "该往来单位已存在，直接使用");
            return r;
        }
        Partner p = new Partner();
        p.setName(name);
        p.setType(type);
        p.setPhone(str(args.get("phone")));
        partnerMapper.insert(p);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", p.getId());
        r.put("name", p.getName());
        r.put("type", p.getType());
        r.put("message", "已创建" + ("SUPPLIER".equals(type) ? "供应商" : "客户") + "「" + name + "」，详细资料可到供应商/客户页补充");
        return r;
    }

    /**
     * partnerId 兼容两种传法：数字 id 直接用；名称字符串先精确/模糊匹配已有档案，
     * 匹配不到则按单据类型自动建档（采购类→SUPPLIER，销售类→CUSTOMER）。
     */
    private Long resolvePartnerId(Object raw, String billType) {
        String s = str(raw).trim();
        if (s.isEmpty()) return null;
        if (s.matches("\\d+(\\.0+)?")) return (long) Double.parseDouble(s);
        Partner exist = partnerMapper.selectList(new LambdaQueryWrapper<Partner>()
                .eq(Partner::getName, s)).stream().findFirst()
                .orElseGet(() -> partnerMapper.selectList(new LambdaQueryWrapper<Partner>()
                        .like(Partner::getName, s)).stream().findFirst().orElse(null));
        if (exist != null) return exist.getId();
        Partner p = new Partner();
        p.setName(s);
        p.setType(billType != null && billType.startsWith("PURCHASE") ? "SUPPLIER" : "CUSTOMER");
        partnerMapper.insert(p);
        return p.getId();
    }

    private Object createDraft(Map<String, Object> args) {
        BillDto dto = new BillDto();
        dto.setType(str(args.get("type")));
        dto.setWarehouseId(toLong(args.get("warehouseId")));
        dto.setToWarehouseId(toLong(args.get("toWarehouseId")));
        dto.setPartnerId(resolvePartnerId(args.get("partnerId"), dto.getType()));
        Object paid = args.get("paid");
        dto.setPaid(paid == null ? 1 : (int) Double.parseDouble(String.valueOf(paid)));
        dto.setRemark(str(args.get("remark")));

        List<Map<String, Object>> itemViews = new ArrayList<>();
        List<BillDto.Item> items = new ArrayList<>();
        Object itemsObj = args.get("items");
        if (itemsObj instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> m;
                try {
                    m = om.readValue(om.writeValueAsString(o), new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) { continue; }
                BillDto.Item item = new BillDto.Item();
                item.setProductId(toLong(m.get("productId")));
                item.setQuantity((int) Double.parseDouble(String.valueOf(m.get("quantity"))));
                if (m.get("price") != null) item.setPrice(Double.parseDouble(String.valueOf(m.get("price"))));
                items.add(item);
                Map<String, Object> view = new HashMap<>();
                view.put("productId", item.getProductId());
                view.put("quantity", item.getQuantity());
                view.put("price", item.getPrice() == null ? 0 : item.getPrice());
                itemViews.add(view);
            }
        }
        dto.setItems(items);
        if (dto.getItems().isEmpty()) throw new IllegalArgumentException("明细不能为空");

        Bill bill = billService.createDraft(dto, "AI助手");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", bill.getId());
        result.put("billNo", bill.getBillNo());
        result.put("total", bill.getTotalAmount());
        result.put("items", itemViews);
        result.put("paid", bill.getPaid());
        if (bill.getPartnerId() != null) {
            Partner pt = partnerMapper.selectById(bill.getPartnerId());
            if (pt != null) result.put("partnerName", pt.getName());
        }
        result.put("message", "草稿已创建，等待人工确认过账");
        return result;
    }

    private Long toLong(Object o) {
        String s = str(o);
        if (s.isEmpty()) return null;
        return (long) Double.parseDouble(s);
    }
}
