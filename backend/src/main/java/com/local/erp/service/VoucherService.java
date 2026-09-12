package com.local.erp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.entity.*;
import com.local.erp.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 记账凭证：单据过账时自动生成（简化不计税口径），也支持手工录入和期末结转损益。
 * 科目编码依赖 MigrationConfig 预置科目：1001现金 1122应收 1405库存商品 2202应付
 * 4103本年利润 6001主营收入 6301营业外收入 6401主营成本 6711营业外支出。
 * 原则：每张过账单据 → 一张凭证（调拨除外）；冲正单过账 → 自动生成反向凭证，原凭证保留。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherService {

    private final VoucherMapper voucherMapper;
    private final VoucherItemMapper itemMapper;
    private final AccountMapper accountMapper;
    private final StockRecordMapper recordMapper;
    private final PartnerMapper partnerMapper;
    private final ProductMapper productMapper;
    private final FinanceSettingsService financeSettings;
    private final JdbcTemplate jdbc;

    private static final Map<String, String> TYPE_NAMES = Map.of(
            "PURCHASE", "采购入库", "SALE", "销售出库", "PURCHASE_RETURN", "采购退货",
            "SALE_RETURN", "销售退货", "LOSS", "报损", "GAIN", "盘盈", "TRANSFER", "调拨");

    /** 单据过账后自动生成凭证（在 BillService.post 的事务内调用）。
     *  计税开启时按商品税率做价税分离：开单价视为含税价，收入/库存走不含税额，税额走 2221 销项/进项。 */
    @Transactional
    public void createFromBill(Bill bill) {
        if ("TRANSFER".equals(bill.getType())) return; // 调拨只是换仓，无价值移动
        List<StockRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<StockRecord>().eq(StockRecord::getBillId, bill.getId()));
        if (records.isEmpty()) return;

        FinanceSettingsService.Settings settings = financeSettings.get();
        boolean taxOn = settings.isTaxEnabled();
        double defRate = settings.getDefaultTaxRate() == null ? 13.0 : settings.getDefaultTaxRate();
        boolean onCredit = Integer.valueOf(0).equals(bill.getPaid());

        // 按税率聚合：rate -> [价税合计, 数量]
        Map<Double, double[]> byRate = new TreeMap<>();
        double costAmt = 0, totalAmt = 0, totalQty = 0;
        for (StockRecord r : records) {
            double qty = Math.abs(r.getQuantity());
            double amt = r2(qty * nz(r.getPrice()));
            if (amt <= 0 && qty <= 0) continue;
            double rate = 0.0;
            if (taxOn && !"LOSS".equals(bill.getType()) && !"GAIN".equals(bill.getType())) {
                Product p = productMapper.selectById(r.getProductId());
                rate = p != null && p.getTaxRate() != null ? p.getTaxRate() : defRate;
            }
            double[] agg = byRate.computeIfAbsent(rate, k -> new double[2]);
            agg[0] += amt;
            agg[1] += qty;
            totalAmt = r2(totalAmt + amt);
            totalQty += qty;
            if ("SALE".equals(bill.getType()) || "SALE_RETURN".equals(bill.getType()))
                costAmt += qty * nz(r.getCostPrice()); // 成本快照本身是不含税口径
        }
        costAmt = r2(costAmt);
        if (totalAmt <= 0 && costAmt <= 0) return;

        List<Map<String, Object>> items = new ArrayList<>();
        String cashCode = switch (bill.getType()) {
            case "SALE", "SALE_RETURN" -> onCredit ? "1122" : "1001";
            case "PURCHASE", "PURCHASE_RETURN" -> onCredit ? "2202" : "1001";
            default -> null;
        };

        switch (bill.getType()) {
            case "SALE" -> {
                if (taxOn) {
                    for (var e : byRate.entrySet()) {
                        double net = splitNet(e.getValue()[0], e.getKey());
                        addTaxItem(items, "6001", "CREDIT", net, "销售收入", e.getKey(), e.getValue()[1]);
                        addTaxItem(items, "2221", "CREDIT", r2(e.getValue()[0] - net), "销项税额", e.getKey(), null);
                    }
                } else {
                    addTaxItem(items, "6001", "CREDIT", totalAmt, "销售收入", null, totalQty);
                }
                addTaxItem(items, cashCode, "DEBIT", totalAmt, "销售收款", null, null);
                if (costAmt > 0) {
                    addTaxItem(items, "6401", "DEBIT", costAmt, "结转销售成本", null, totalQty);
                    addTaxItem(items, "1405", "CREDIT", costAmt, "库存商品出库", null, totalQty);
                }
            }
            case "PURCHASE" -> {
                if (taxOn) {
                    for (var e : byRate.entrySet()) {
                        double net = splitNet(e.getValue()[0], e.getKey());
                        addTaxItem(items, "1405", "DEBIT", net, "采购入库", e.getKey(), e.getValue()[1]);
                        addTaxItem(items, "2221", "DEBIT", r2(e.getValue()[0] - net), "进项税额", e.getKey(), null);
                    }
                } else {
                    addTaxItem(items, "1405", "DEBIT", totalAmt, "采购入库", null, totalQty);
                }
                addTaxItem(items, cashCode, "CREDIT", totalAmt, "采购付款", null, null);
            }
            case "SALE_RETURN" -> {
                if (taxOn) {
                    for (var e : byRate.entrySet()) {
                        double net = splitNet(e.getValue()[0], e.getKey());
                        addTaxItem(items, "6001", "DEBIT", net, "销售退回冲收入", e.getKey(), e.getValue()[1]);
                        addTaxItem(items, "2221", "DEBIT", r2(e.getValue()[0] - net), "冲减销项税额", e.getKey(), null);
                    }
                } else {
                    addTaxItem(items, "6001", "DEBIT", totalAmt, "销售退回冲收入", null, totalQty);
                }
                addTaxItem(items, cashCode, "CREDIT", totalAmt, "退回货款", null, null);
                if (costAmt > 0) {
                    addTaxItem(items, "1405", "DEBIT", costAmt, "退货入库", null, totalQty);
                    addTaxItem(items, "6401", "CREDIT", costAmt, "冲减销售成本", null, totalQty);
                }
            }
            case "PURCHASE_RETURN" -> {
                if (taxOn) {
                    for (var e : byRate.entrySet()) {
                        double net = splitNet(e.getValue()[0], e.getKey());
                        addTaxItem(items, "1405", "CREDIT", net, "采购退货", e.getKey(), e.getValue()[1]);
                        addTaxItem(items, "2221", "CREDIT", r2(e.getValue()[0] - net), "冲减进项税额", e.getKey(), null);
                    }
                } else {
                    addTaxItem(items, "1405", "CREDIT", totalAmt, "采购退货", null, totalQty);
                }
                addTaxItem(items, cashCode, "DEBIT", totalAmt, "退货收款", null, null);
            }
            case "LOSS" -> {
                addTaxItem(items, "6711", "DEBIT", totalAmt, "存货报损", null, totalQty);
                addTaxItem(items, "1405", "CREDIT", totalAmt, "库存商品报损出库", null, totalQty);
            }
            case "GAIN" -> {
                addTaxItem(items, "1405", "DEBIT", totalAmt, "存货盘盈入库", null, totalQty);
                addTaxItem(items, "6301", "CREDIT", totalAmt, "盘盈收入", null, totalQty);
            }
            default -> { return; }
        }
        if (items.isEmpty()) return;

        double total = r2(items.stream().filter(x -> "DEBIT".equals(x.get("direction")))
                .mapToDouble(i -> (Double) i.get("amount")).sum());
        if (total <= 0) return;
        saveVoucher(bill.getPostedAt() != null ? bill.getPostedAt().substring(0, 10) : bill.getCreatedAt().substring(0, 10),
                "BILL", bill.getId(),
                TYPE_NAMES.getOrDefault(bill.getType(), bill.getType()) + " " + bill.getBillNo() + partnerSuffix(bill.getPartnerId()),
                items, bill.getCreatedBy());
    }

    /** 价税分离：不含税额 = 含税额 / (1 + 税率%) */
    private double splitNet(double inclTax, double rate) {
        return r2(inclTax / (1 + rate / 100));
    }

    /** 手工凭证：强制借贷平衡 */
    @Transactional
    public Voucher createManual(String voucherDate, String summary, List<Map<String, Object>> items, String username) {
        if (items == null || items.size() < 2) throw new IllegalArgumentException("凭证至少需要一借一贷两行分录");
        double debit = 0, credit = 0;
        for (Map<String, Object> i : items) {
            double amt = r2(parseDouble(i.get("amount")));
            if (amt <= 0) throw new IllegalArgumentException("分录金额必须大于0");
            if (!"DEBIT".equals(i.get("direction")) && !"CREDIT".equals(i.get("direction")))
                throw new IllegalArgumentException("分录方向必须是 DEBIT/CREDIT");
            if (accountMapper.selectById(parseLong(i.get("accountId"))) == null)
                throw new IllegalArgumentException("分录科目不存在");
            i.put("amount", amt); // 规整为 Double：JSON 数字可能是 Integer，后续统一按 Double 读
            if ("DEBIT".equals(i.get("direction"))) debit += amt; else credit += amt;
        }
        debit = r2(debit); credit = r2(credit);
        if (Math.abs(debit - credit) > 0.005) throw new IllegalArgumentException(
                String.format("借贷不平衡：借方合计 %.2f，贷方合计 %.2f", debit, credit));
        return saveVoucher(voucherDate, "MANUAL", null, summary, items, username);
    }

    /** 期末结转损益：把损益类科目净额转入 4103 本年利润（同月只能结转一次） */
    @Transactional
    public Map<String, Object> closeProfit(String date, String username) {
        String month = date == null ? "" : date.substring(0, 7);
        Long exists = voucherMapper.selectCount(new LambdaQueryWrapper<Voucher>()
                .eq(Voucher::getSourceType, "CLOSE").apply("substr(voucher_date,1,7) = {0}", month));
        if (exists != null && exists > 0) throw new IllegalArgumentException(month + " 月已经结转过损益，不能重复结转");

        // 损益科目截至 date 的净额（贷-借）
        Map<Long, double[]> nets = new LinkedHashMap<>(); // accountId -> [借, 贷]
        for (Map<String, Object> row : itemsUpTo(date)) {
            Long accId = ((Number) row.get("accountId")).longValue();
            double[] d = nets.computeIfAbsent(accId, k -> new double[2]);
            if ("DEBIT".equals(row.get("direction"))) d[0] += ((Number) row.get("amount")).doubleValue();
            else d[1] += ((Number) row.get("amount")).doubleValue();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        double profit = 0;
        for (Map.Entry<Long, double[]> e : nets.entrySet()) {
            Account acc = accountMapper.selectById(e.getKey());
            if (acc == null || !"PROFIT".equals(acc.getCategory())) continue;
            double net = r2(e.getValue()[1] - e.getValue()[0]); // 贷-借
            if (Math.abs(net) < 0.005) continue;
            if (net > 0) { // 收入类净贷方：借科目，贷本年利润
                add(items, acc.getCode(), "DEBIT", net, "结转" + acc.getName());
                profit += net;
            } else {       // 成本费用净借方：借本年利润，贷科目
                add(items, acc.getCode(), "CREDIT", -net, "结转" + acc.getName());
                profit += net;
            }
        }
        if (items.isEmpty()) return Map.of("message", "没有需要结转的损益", "profit", 0.0);
        profit = r2(profit);
        double dSum = r2(items.stream().filter(i -> "DEBIT".equals(i.get("direction")))
                .mapToDouble(i -> (Double) i.get("amount")).sum());
        double cSum = r2(items.stream().filter(i -> "CREDIT".equals(i.get("direction")))
                .mapToDouble(i -> (Double) i.get("amount")).sum());
        if (dSum > cSum) add(items, "4103", "CREDIT", r2(dSum - cSum), "结转至本年利润");
        else if (cSum > dSum) add(items, "4103", "DEBIT", r2(cSum - dSum), "结转至本年利润（亏损）");
        Voucher v = saveVoucher(date, "CLOSE", null, "期末结转损益（" + month + "）", items, username);
        return Map.of("message", "已生成结转凭证 " + v.getVoucherNo(), "profit", profit);
    }

    /** 凭证删除：仅手工/期初凭证可删，单据生成的凭证随冲正走 */
    @Transactional
    public void deleteVoucher(Long id) {
        Voucher v = voucherMapper.selectById(id);
        if (v == null) throw new IllegalArgumentException("凭证不存在");
        if ("BILL".equals(v.getSourceType()))
            throw new IllegalArgumentException("单据自动生成的凭证不能删除，请对单据做冲正");
        itemMapper.delete(new LambdaQueryWrapper<VoucherItem>().eq(VoucherItem::getVoucherId, id));
        voucherMapper.deleteById(id);
    }

    /** 凭证分录余额：DEBIT 行 +，CREDIT 行 -（配合科目方向换算余额） */
    public double balance(List<Map<String, Object>> rows) {
        double bal = 0;
        for (Map<String, Object> r : rows) {
            double amt = ((Number) r.get("amount")).doubleValue();
            bal += "DEBIT".equals(r.get("direction")) ? amt : -amt;
        }
        return r2(bal);
    }

    /** 某日期（含）之前的全部分录 */
    public List<Map<String, Object>> itemsUpTo(String date) {
        return jdbc.queryForList("""
                SELECT vi.voucher_id AS voucherId, vi.account_id AS accountId, vi.direction, vi.amount, vi.summary AS itemSummary,
                       vi.quantity, vi.unit_price AS unitPrice, vi.tax_rate AS taxRate,
                       v.voucher_date AS voucherDate, v.voucher_no AS voucherNo, v.source_type AS sourceType
                FROM voucher_item vi JOIN voucher v ON v.id = vi.voucher_id
                WHERE v.voucher_date <= ? ORDER BY v.voucher_date, v.id, vi.id
                """, date);
    }

    /** [start, end] 闭区间内的全部分录 */
    public List<Map<String, Object>> itemsBetween(String start, String end) {
        return jdbc.queryForList("""
                SELECT vi.voucher_id AS voucherId, vi.account_id AS accountId, vi.direction, vi.amount, vi.summary AS itemSummary,
                       vi.quantity, vi.unit_price AS unitPrice, vi.tax_rate AS taxRate,
                       v.voucher_date AS voucherDate, v.voucher_no AS voucherNo, v.source_type AS sourceType
                FROM voucher_item vi JOIN voucher v ON v.id = vi.voucher_id
                WHERE v.voucher_date >= ? AND v.voucher_date <= ? ORDER BY v.voucher_date, v.id, vi.id
                """, start, end);
    }

    private void saveItems(Voucher v, List<Map<String, Object>> items) {
        for (Map<String, Object> i : items) {
            VoucherItem vi = new VoucherItem();
            vi.setVoucherId(v.getId());
            vi.setAccountId(parseLong(i.get("accountId")));
            if (vi.getAccountId() == null) {
                Account a = accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getCode, i.get("code")));
                vi.setAccountId(a.getId());
            }
            vi.setDirection((String) i.get("direction"));
            vi.setAmount(r2(parseDouble(i.get("amount"))));
            vi.setSummary((String) i.getOrDefault("summary", ""));
            vi.setPartnerId(i.get("partnerId") == null ? null : parseLong(i.get("partnerId")));
            vi.setQuantity(i.get("quantity") == null ? null : parseDouble(i.get("quantity")));
            vi.setUnitPrice(i.get("unitPrice") == null ? null : parseDouble(i.get("unitPrice")));
            vi.setTaxRate(i.get("taxRate") == null ? null : parseDouble(i.get("taxRate")));
            itemMapper.insert(vi);
        }
    }

    private Voucher saveVoucher(String date, String sourceType, Long sourceId, String summary,
                                List<Map<String, Object>> items, String username) {
        if (date == null || date.isBlank()) date = java.time.LocalDate.now().toString();
        double total = r2(items.stream().filter(i -> "DEBIT".equals(i.get("direction")))
                .mapToDouble(i -> (Double) i.get("amount")).sum());
        Voucher v = new Voucher();
        v.setVoucherNo(nextVoucherNo());
        v.setVoucherDate(date);
        v.setSourceType(sourceType);
        v.setSourceId(sourceId);
        v.setSummary(summary);
        v.setTotalAmount(total);
        v.setCreatedBy(username == null ? "" : username);
        voucherMapper.insert(v);
        saveItems(v, items);
        return v;
    }

    private void add(List<Map<String, Object>> items, String accountCode, String direction, double amount, String summary) {
        addTaxItem(items, accountCode, direction, amount, summary, null, null);
    }

    /** 带税率标记/数量的分录（数量>0 时自动算单价 = 金额/数量） */
    private void addTaxItem(List<Map<String, Object>> items, String accountCode, String direction,
                            double amount, String summary, Double taxRate, Double quantity) {
        if (amount <= 0) return;
        Account a = accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getCode, accountCode));
        if (a == null) throw new IllegalArgumentException("缺少会计科目 " + accountCode + "，请到科目设置中添加");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accountId", a.getId());
        m.put("code", a.getCode());
        m.put("direction", direction);
        m.put("amount", r2(amount));
        m.put("summary", summary);
        if (taxRate != null) m.put("taxRate", taxRate);
        if (quantity != null && quantity > 0) {
            m.put("quantity", r2(quantity));
            m.put("unitPrice", r2(amount / quantity));
        }
        items.add(m);
    }

    private String nextVoucherNo() {
        // id 为 AUTOINCREMENT 单调递增，用 maxId+1 生成序号永不撞号
        Voucher max = voucherMapper.selectList(
                new LambdaQueryWrapper<Voucher>().orderByDesc(Voucher::getId).last("limit 1"))
                .stream().findFirst().orElse(null);
        return String.format("记-%04d", (max == null ? 0 : max.getId()) + 1);
    }

    private String partnerSuffix(Long partnerId) {
        if (partnerId == null) return "";
        Partner p = partnerMapper.selectById(partnerId);
        return p != null ? "（" + p.getName() + "）" : "";
    }

    private double nz(Double d) { return d == null ? 0 : d; }
    private double r2(double v) { return Math.round(v * 100) / 100.0; }
    private double parseDouble(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0; }
    }
    private Long parseLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }
}
