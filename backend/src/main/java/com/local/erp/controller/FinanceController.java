package com.local.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.entity.Account;
import com.local.erp.entity.Voucher;
import com.local.erp.mapper.AccountMapper;
import com.local.erp.mapper.VoucherItemMapper;
import com.local.erp.mapper.VoucherMapper;
import com.local.erp.service.VoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 财务模块（仅管理员，见 AuthInterceptor 的 /api/finance 前缀规则）：
 * 科目管理 / 凭证管理 / 科目余额表（总账）/ 明细账 / 资产负债表+利润表 / 期末结转。
 * 记账口径：简化不计税（小规模/个体户），收入全额入账。
 */
@RestController
@RequestMapping("/api/finance")
@RequiredArgsConstructor
public class FinanceController {

    private final AccountMapper accountMapper;
    private final VoucherMapper voucherMapper;
    private final VoucherItemMapper itemMapper;
    private final VoucherService voucherService;
    private final com.local.erp.service.FinanceSettingsService financeSettings;
    private final com.local.erp.mapper.BillMapper billMapper;
    private final com.local.erp.mapper.BillItemMapper billItemMapper;
    private final com.local.erp.mapper.ProductMapper productMapper;
    private final com.local.erp.mapper.PartnerMapper partnerMapper;

    // ==================== 计税设置 ====================

    @GetMapping("/settings")
    public com.local.erp.service.FinanceSettingsService.Settings settings() {
        return financeSettings.get();
    }

    @PostMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody com.local.erp.service.FinanceSettingsService.Settings s) {
        financeSettings.save(s);
        return Map.of("message", s.isTaxEnabled()
                ? "已开启价税分离（税率 " + s.getDefaultTaxRate() + "%），新过账单据自动分离税额"
                : "已切换为不计税口径（新过账单据全额入账）");
    }

    /** 增值税辅助表：按税率分组 本期销售额(不含税)/销项/采购额(不含税)/进项/应纳税额（仅统计计税期间打了税率标记的分录） */
    @GetMapping("/vat")
    public Map<String, Object> vat(@RequestParam String start, @RequestParam String end) {
        Account vatAcc = accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getCode, "2221"));
        if (vatAcc == null) throw new IllegalArgumentException("缺少 2221 应交税费科目");
        Long vatId = vatAcc.getId();
        Long salesId = accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getCode, "6001")).getId();
        Long invId = accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getCode, "1405")).getId();

        // rate -> [销售净额, 销项, 采购净额, 进项]
        Map<Double, double[]> byRate = new TreeMap<>();
        for (Map<String, Object> r : voucherService.itemsBetween(start, end)) {
            if (r.get("taxRate") == null) continue; // 未打税率标记的分录不进增值税统计
            Long accId = ((Number) r.get("accountId")).longValue();
            double amt = ((Number) r.get("amount")).doubleValue();
            double rate = ((Number) r.get("taxRate")).doubleValue();
            double[] d = byRate.computeIfAbsent(rate, k -> new double[4]);
            if (accId.equals(salesId) && "CREDIT".equals(r.get("direction"))) d[0] += amt;
            else if (accId.equals(vatId) && "CREDIT".equals(r.get("direction"))) d[1] += amt;
            else if (accId.equals(invId) && "DEBIT".equals(r.get("direction"))) d[2] += amt;
            else if (accId.equals(vatId) && "DEBIT".equals(r.get("direction"))) d[3] += amt;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        double output = 0, input = 0;
        for (var e : byRate.entrySet()) {
            if (e.getValue()[0] == 0 && e.getValue()[1] == 0 && e.getValue()[2] == 0 && e.getValue()[3] == 0) continue;
            double payable = r2(e.getValue()[1] - e.getValue()[3]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rate", e.getKey());
            m.put("salesNet", r2(e.getValue()[0]));
            m.put("outputTax", r2(e.getValue()[1]));
            m.put("purchaseNet", r2(e.getValue()[2]));
            m.put("inputTax", r2(e.getValue()[3]));
            m.put("payable", payable);
            rows.add(m);
            output += e.getValue()[1];
            input += e.getValue()[3];
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("outputTaxTotal", r2(output));
        result.put("inputTaxTotal", r2(input));
        result.put("payableTotal", r2(output - input)); // 负数 = 留抵
        return result;
    }

    // ==================== 科目管理 ====================

    @GetMapping("/accounts")
    public List<Account> accounts() {
        return accountMapper.selectList(new LambdaQueryWrapper<Account>()
                .orderByAsc(Account::getCode));
    }

    @PostMapping("/accounts")
    public Account addAccount(@RequestBody Account a) {
        if (a.getCode() == null || a.getCode().isBlank()) throw new IllegalArgumentException("科目编码必填");
        if (a.getName() == null || a.getName().isBlank()) throw new IllegalArgumentException("科目名称必填");
        a.setCode(a.getCode().trim());
        if (!List.of("ASSET", "LIABILITY", "EQUITY", "COST", "PROFIT").contains(a.getCategory()))
            throw new IllegalArgumentException("科目类别必须是 ASSET/LIABILITY/EQUITY/COST/PROFIT");
        if (!"DEBIT".equals(a.getDirection()) && !"CREDIT".equals(a.getDirection()))
            a.setDirection("CREDIT".equals(a.getCategory()) || "PROFIT".equals(a.getCategory()) ? "CREDIT" : "DEBIT");
        if (accountMapper.selectCount(new LambdaQueryWrapper<Account>().eq(Account::getCode, a.getCode())) > 0)
            throw new IllegalArgumentException("科目编码 " + a.getCode() + " 已存在");
        a.setBuiltin(0);
        a.setId(null);
        accountMapper.insert(a);
        return a;
    }

    @PutMapping("/accounts/{id}")
    public Account updateAccount(@PathVariable Long id, @RequestBody Account a) {
        Account old = accountMapper.selectById(id);
        if (old == null) throw new IllegalArgumentException("科目不存在");
        old.setName(a.getName() != null ? a.getName() : old.getName());
        if (a.getCategory() != null) old.setCategory(a.getCategory());
        if (a.getDirection() != null) old.setDirection(a.getDirection());
        if (a.getEnabled() != null) old.setEnabled(a.getEnabled());
        accountMapper.updateById(old);
        return old;
    }

    @DeleteMapping("/accounts/{id}")
    public Map<String, Object> deleteAccount(@PathVariable Long id) {
        Account a = accountMapper.selectById(id);
        if (a == null) throw new IllegalArgumentException("科目不存在");
        if (Integer.valueOf(1).equals(a.getBuiltin())) throw new IllegalArgumentException("预置科目不能删除");
        if (itemMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.local.erp.entity.VoucherItem>()
                        .eq(com.local.erp.entity.VoucherItem::getAccountId, id)) > 0)
            throw new IllegalArgumentException("科目已有分录，不能删除（可改为停用）");
        accountMapper.deleteById(id);
        return Map.of("message", "已删除科目 " + a.getName());
    }

    // ==================== 凭证管理 ====================

    @GetMapping("/vouchers")
    public List<Map<String, Object>> vouchers(@RequestParam(required = false) String start,
                                              @RequestParam(required = false) String end,
                                              @RequestParam(required = false) String keyword) {
        LambdaQueryWrapper<Voucher> qw = new LambdaQueryWrapper<>();
        if (start != null && !start.isBlank()) qw.ge(Voucher::getVoucherDate, start);
        if (end != null && !end.isBlank()) qw.le(Voucher::getVoucherDate, end);
        if (keyword != null && !keyword.isBlank())
            qw.and(w -> w.like(Voucher::getSummary, keyword).or().like(Voucher::getVoucherNo, keyword));
        qw.orderByDesc(Voucher::getVoucherDate).orderByDesc(Voucher::getId);
        List<Voucher> list = voucherMapper.selectList(qw);
        return list.stream().map(v -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("voucherNo", v.getVoucherNo());
            m.put("voucherDate", v.getVoucherDate());
            m.put("sourceType", v.getSourceType());
            m.put("sourceTypeName", sourceName(v.getSourceType()));
            m.put("sourceId", v.getSourceId());
            m.put("summary", v.getSummary());
            m.put("totalAmount", v.getTotalAmount());
            m.put("createdBy", v.getCreatedBy());
            return m;
        }).toList();
    }

    @GetMapping("/vouchers/{id}")
    public Map<String, Object> voucherDetail(@PathVariable Long id) {
        Voucher v = voucherMapper.selectById(id);
        if (v == null) throw new IllegalArgumentException("凭证不存在");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voucher", v);
        List<Map<String, Object>> items = itemMapper.selectList(
                        new LambdaQueryWrapper<com.local.erp.entity.VoucherItem>()
                                .eq(com.local.erp.entity.VoucherItem::getVoucherId, id)
                                .orderByAsc(com.local.erp.entity.VoucherItem::getId))
                .stream().map(vi -> {
                    Account a = accountMapper.selectById(vi.getAccountId());
                    Map<String, Object> im = new LinkedHashMap<>();
                    im.put("direction", vi.getDirection());
                    im.put("amount", vi.getAmount());
                    im.put("summary", vi.getSummary());
                    im.put("accountCode", a != null ? a.getCode() : "?");
                    im.put("accountName", a != null ? a.getName() : "已删除科目");
                    im.put("quantity", vi.getQuantity());
                    im.put("unitPrice", vi.getUnitPrice());
                    im.put("taxRate", vi.getTaxRate());
                    return im;
                }).toList();
        m.put("items", items);
        double debit = items.stream().filter(i -> "DEBIT".equals(i.get("direction")))
                .mapToDouble(i -> (Double) i.get("amount")).sum();
        double credit = items.stream().filter(i -> "CREDIT".equals(i.get("direction")))
                .mapToDouble(i -> (Double) i.get("amount")).sum();
        m.put("debitTotal", r2(debit));
        m.put("creditTotal", r2(credit));
        return m;
    }

    /** 手工凭证：{ voucherDate, summary, items:[{accountId, direction, amount, summary}] } */
    @PostMapping("/vouchers")
    public Voucher createVoucher(@RequestBody Map<String, Object> body) {
        String username = com.local.erp.config.AuthInterceptor.currentUser().getUsername();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        return voucherService.createManual(
                (String) body.get("voucherDate"), (String) body.get("summary"), items, username);
    }

    @DeleteMapping("/vouchers/{id}")
    public Map<String, Object> deleteVoucher(@PathVariable Long id) {
        voucherService.deleteVoucher(id);
        return Map.of("message", "凭证已删除");
    }

    // ==================== 科目余额表（总账） ====================

    /** 每个科目：期初余额、本期借方、本期贷方、期末余额（按科目方向） */
    @GetMapping("/trial-balance")
    public Map<String, Object> trialBalance(@RequestParam String start, @RequestParam String end) {
        List<Account> accounts = accountMapper.selectList(new LambdaQueryWrapper<Account>().orderByAsc(Account::getCode));
        Map<Long, Account> accMap = new HashMap<>();
        accounts.forEach(a -> accMap.put(a.getId(), a));

        double dSum = 0, cSum = 0, oSum = 0, eSum = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<Long, double[]> opening = accumulate(voucherService.itemsUpTo(start));
        Map<Long, double[]> period = accumulate(voucherService.itemsBetween(start, end));

        for (Account a : accounts) {
            double[] o = opening.getOrDefault(a.getId(), new double[2]);
            double[] p = period.getOrDefault(a.getId(), new double[2]);
            double open = r2("CREDIT".equals(a.getDirection()) ? o[1] - o[0] : o[0] - o[1]);
            double endBal = r2(open + ("CREDIT".equals(a.getDirection()) ? p[1] - p[0] : p[0] - p[1]));
            if (open == 0 && p[0] == 0 && p[1] == 0) continue; // 无发生额且无余额的科目不显示
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", a.getCode());
            m.put("name", a.getName());
            m.put("direction", a.getDirection());
            m.put("category", a.getCategory());
            m.put("opening", open);
            m.put("periodDebit", r2(p[0]));
            m.put("periodCredit", r2(p[1]));
            m.put("ending", endBal);
            rows.add(m);
            dSum += p[0];
            cSum += p[1];
            oSum += "CREDIT".equals(a.getDirection()) ? -open : open;
            eSum += "CREDIT".equals(a.getDirection()) ? -endBal : endBal;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("openingDebitCreditDiff", r2(oSum));  // 期初借方-贷方（应≈0，账平标志）
        totals.put("periodDebit", r2(dSum));
        totals.put("periodCredit", r2(cSum));            // 本期借方合计=贷方合计 → 账平
        totals.put("endingDebitCreditDiff", r2(eSum));
        result.put("totals", totals);
        result.put("balanced", Math.abs(dSum - cSum) < 0.01);
        return result;
    }

    // ==================== 明细账 ====================

    @GetMapping("/ledger-detail")
    public Map<String, Object> ledgerDetail(@RequestParam Long accountId,
                                            @RequestParam String start, @RequestParam String end) {
        Account a = accountMapper.selectById(accountId);
        if (a == null) throw new IllegalArgumentException("科目不存在");
        boolean creditDir = "CREDIT".equals(a.getDirection());

        double opening = 0;
        for (Map<String, Object> r : voucherService.itemsUpTo(start)) {
            if (((Number) r.get("accountId")).longValue() != accountId) continue;
            double amt = ((Number) r.get("amount")).doubleValue();
            opening += "DEBIT".equals(r.get("direction")) == !creditDir ? amt : -amt;
        }
        double running = opening;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : voucherService.itemsBetween(start, end)) {
            if (((Number) r.get("accountId")).longValue() != accountId) continue;
            double amt = ((Number) r.get("amount")).doubleValue();
            double debit = "DEBIT".equals(r.get("direction")) ? amt : 0;
            double credit = "CREDIT".equals(r.get("direction")) ? amt : 0;
            running += ("DEBIT".equals(r.get("direction")) == !creditDir) ? amt : -amt;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", r.get("voucherDate"));
            m.put("voucherNo", r.get("voucherNo"));
            m.put("summary", r.get("itemSummary"));
            m.put("debit", r2(debit));
            m.put("credit", r2(credit));
            m.put("quantity", r.get("quantity"));
            m.put("unitPrice", r.get("unitPrice"));
            m.put("balance", r2(running));
            rows.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("account", a);
        result.put("opening", r2(opening));
        result.put("rows", rows);
        result.put("ending", r2(running));
        return result;
    }

    // ==================== 财务报表：资产负债表 + 利润表 ====================

    /**
     * 资产负债表截至 end；利润表为 [start, end] 区间。
     * 平衡口径：资产 = 负债 + 权益（含本年利润科目）+ 未结转损益。
     */
    @GetMapping("/statements")
    public Map<String, Object> statements(@RequestParam String start, @RequestParam String end) {
        List<Account> accounts = accountMapper.selectList(null);
        Map<Long, Account> accMap = new HashMap<>();
        accounts.forEach(a -> accMap.put(a.getId(), a));

        Map<Long, double[]> upto = accumulate(voucherService.itemsUpTo(end));
        double assets = 0, liabilities = 0, equity = 0, unclosed = 0;
        List<Map<String, Object>> assetRows = new ArrayList<>(), liabRows = new ArrayList<>(), equityRows = new ArrayList<>();
        for (Account a : accounts) {
            double[] d = upto.getOrDefault(a.getId(), new double[2]);
            double bal = r2("CREDIT".equals(a.getDirection()) ? d[1] - d[0] : d[0] - d[1]);
            if (Math.abs(bal) < 0.005) continue;
            if ("ASSET".equals(a.getCategory())) {
                assetRows.add(row(a, bal, "DEBIT"));
                assets += bal;
            } else if ("LIABILITY".equals(a.getCategory())) {
                liabRows.add(row(a, bal, "CREDIT"));
                liabilities += bal;
            } else if ("EQUITY".equals(a.getCategory())) {
                equityRows.add(row(a, bal, "CREDIT"));
                equity += bal;
            } else if ("PROFIT".equals(a.getCategory())) {
                unclosed += (d[1] - d[0]); // 损益科目统一按 贷-借 口径：收入为正、成本费用为负
            }
        }
        assets = r2(assets);
        double liabEquity = r2(liabilities + equity + unclosed);

        // 利润表（区间发生额）
        Map<Long, double[]> period = accumulate(voucherService.itemsBetween(start, end));
        double netProfit = 0;
        List<Map<String, Object>> incomeRows = new ArrayList<>();
        for (Account a : accounts) {
            if (!"PROFIT".equals(a.getCategory())) continue;
            double[] d = period.getOrDefault(a.getId(), new double[2]);
            if (d[0] == 0 && d[1] == 0) continue;
            double net = r2(d[1] - d[0]); // 贷-借：收入为正、成本费用为负
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", a.getCode());
            m.put("name", a.getName());
            m.put("debit", r2(d[0]));
            m.put("credit", r2(d[1]));
            m.put("net", net);
            incomeRows.add(m);
            netProfit += net;
        }
        netProfit = r2(netProfit);

        Map<String, Object> balanceSheet = new LinkedHashMap<>();
        balanceSheet.put("assets", assetRows);
        balanceSheet.put("liabilities", liabRows);
        balanceSheet.put("equity", equityRows);
        balanceSheet.put("unclosedProfit", r2(unclosed)); // 未结转损益（并入权益）
        balanceSheet.put("assetsTotal", assets);
        balanceSheet.put("liabEquityTotal", liabEquity);
        balanceSheet.put("balanced", Math.abs(assets - liabEquity) < 0.01);

        Map<String, Object> income = new LinkedHashMap<>();
        income.put("rows", incomeRows);
        income.put("netProfit", netProfit);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("start", start);
        result.put("end", end);
        result.put("balanceSheet", balanceSheet);
        result.put("income", income);
        return result;
    }

    // ==================== 期末结转 ====================

    @PostMapping("/close-profit")
    public Map<String, Object> closeProfit(@RequestParam String date) {
        String username = com.local.erp.config.AuthInterceptor.currentUser().getUsername();
        return voucherService.closeProfit(date, username);
    }

    // ==================== 现金流量表（直接法，按对方科目自动分类） ====================

    private static final Map<String, String[]> CASH_FLOW_MAP = Map.ofEntries(
            Map.entry("6001", new String[]{"经营", "销售商品、提供劳务收到的现金"}),
            Map.entry("6051", new String[]{"经营", "销售商品、提供劳务收到的现金"}),
            Map.entry("1122", new String[]{"经营", "销售商品、提供劳务收到的现金"}),
            Map.entry("1405", new String[]{"经营", "购买商品、接受劳务支付的现金"}),
            Map.entry("2202", new String[]{"经营", "购买商品、接受劳务支付的现金"}),
            Map.entry("1123", new String[]{"经营", "购买商品、接受劳务支付的现金"}),
            Map.entry("6601", new String[]{"经营", "支付的其他与经营活动有关的现金"}),
            Map.entry("6602", new String[]{"经营", "支付的其他与经营活动有关的现金"}),
            Map.entry("6301", new String[]{"经营", "收到其他与经营活动有关的现金"}),
            Map.entry("6711", new String[]{"经营", "支付的其他与经营活动有关的现金"}),
            Map.entry("1601", new String[]{"投资", "购建固定资产等长期资产支付的现金"}),
            Map.entry("4001", new String[]{"筹资", "吸收投资收到的现金"}));

    /** 现金流量表：现金/银行存款(1001/1002)分录按同凭证对方科目自动归类（简化直接法） */
    @GetMapping("/cash-flow")
    public Map<String, Object> cashFlow(@RequestParam String start, @RequestParam String end) {
        Set<Long> cashIds = new HashSet<>();
        for (String code : new String[]{"1001", "1002"}) {
            Account a = accountMapper.selectOne(new LambdaQueryWrapper<Account>().eq(Account::getCode, code));
            if (a != null) cashIds.add(a.getId());
        }
        if (cashIds.isEmpty()) throw new IllegalArgumentException("缺少现金/银行存款科目(1001/1002)");

        double opening = 0, ending = 0;
        Map<String, double[]> flows = new LinkedHashMap<>(); // "类别|项目" -> [流入, 流出]
        Map<Long, List<Map<String, Object>>> byVoucher = new LinkedHashMap<>();
        for (Map<String, Object> r : voucherService.itemsUpTo(end)) {
            Long accId = ((Number) r.get("accountId")).longValue();
            if (cashIds.contains(accId)) {
                double amt = ((Number) r.get("amount")).doubleValue();
                if (r.get("voucherDate").toString().compareTo(start) < 0) opening += "DEBIT".equals(r.get("direction")) ? amt : -amt;
            }
            byVoucher.computeIfAbsent(((Number) r.get("voucherId")).longValue(), k -> new ArrayList<>()).add(r);
        }
        for (List<Map<String, Object>> items : byVoucher.values()) {
            List<Map<String, Object>> cashLines = items.stream()
                    .filter(i -> cashIds.contains(((Number) i.get("accountId")).longValue())).toList();
            if (cashLines.isEmpty()) continue;
            List<Map<String, Object>> counters = items.stream()
                    .filter(i -> !cashIds.contains(((Number) i.get("accountId")).longValue())).toList();
            double counterTotal = counters.stream().mapToDouble(i -> ((Number) i.get("amount")).doubleValue()).sum();
            for (Map<String, Object> cash : cashLines) {
                double amt = ((Number) cash.get("amount")).doubleValue();
                boolean inflow = "DEBIT".equals(cash.get("direction"));
                ending += inflow ? amt : -amt;
                if (cash.get("voucherDate").toString().compareTo(start) < 0) continue; // 期初前的分录不进流量表
                // 按对方科目金额占比分摊到流量项目（2221 增值税按现金方向归类：流入=销售收现的税、流出=购进支付的税）
                for (Map<String, Object> c : counters) {
                    double share = counterTotal > 0 ? amt * (((Number) c.get("amount")).doubleValue() / counterTotal) : amt / Math.max(counters.size(), 1);
                    Account counterAcc = accOf(c);
                    String cCode = counterAcc == null ? "" : counterAcc.getCode();
                    String[] cf;
                    if ("2221".equals(cCode)) {
                        cf = inflow
                                ? new String[]{"经营", "销售商品、提供劳务收到的现金"}
                                : new String[]{"经营", "购买商品、接受劳务支付的现金"};
                    } else {
                        cf = CASH_FLOW_MAP.getOrDefault(cCode, new String[]{"其他", "其他经营活动现金流"});
                    }
                    double[] d = flows.computeIfAbsent(cf[0] + "|" + cf[1], k -> new double[2]);
                    if (inflow) d[0] += r2(share); else d[1] += r2(share);
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        double in = 0, out = 0;
        for (var e : flows.entrySet()) {
            if (e.getValue()[0] == 0 && e.getValue()[1] == 0) continue;
            String[] parts = e.getKey().split("\\|", 2);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", parts[0]);
            m.put("item", parts[1]);
            m.put("inflow", r2(e.getValue()[0]));
            m.put("outflow", r2(e.getValue()[1]));
            m.put("net", r2(e.getValue()[0] - e.getValue()[1]));
            rows.add(m);
            in += e.getValue()[0];
            out += e.getValue()[1];
        }
        opening = r2(opening);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("start", start);
        result.put("end", end);
        result.put("rows", rows);
        result.put("opening", opening);
        result.put("inflowTotal", r2(in));
        result.put("outflowTotal", r2(out));
        result.put("netTotal", r2(in - out));
        result.put("ending", r2(opening + in - out));
        result.put("cashAccountEnding", r2(ending)); // 与期末余额勾稽
        return result;
    }

    private Account accOf(Map<String, Object> item) {
        Long id = ((Number) item.get("accountId")).longValue();
        for (Account a : accountMapper.selectList(null)) {
            if (a.getId().equals(id)) return a;
        }
        return null;
    }

    // ==================== 开票清单 ====================

    /** 已过账销售单的开票辅助清单：客户/金额；计税模式下带不含税额和税额（开票走税控体系，本清单供人工录入参考） */
    @GetMapping("/invoice-list")
    public List<Map<String, Object>> invoiceList(@RequestParam String start, @RequestParam String end) {
        boolean taxOn = financeSettings.get().isTaxEnabled();
        double defRate = financeSettings.get().getDefaultTaxRate() == null ? 13.0 : financeSettings.get().getDefaultTaxRate();
        var qw = new LambdaQueryWrapper<com.local.erp.entity.Bill>()
                .eq(com.local.erp.entity.Bill::getType, "SALE")
                .eq(com.local.erp.entity.Bill::getStatus, "POSTED")
                .apply("substr(posted_at,1,10) between {0} and {1}", start, end)
                .orderByAsc(com.local.erp.entity.Bill::getPostedAt);
        List<Map<String, Object>> result = new ArrayList<>();
        for (com.local.erp.entity.Bill b : billMapper.selectList(qw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", b.getPostedAt() == null ? "" : b.getPostedAt().substring(0, 10));
            m.put("billNo", b.getBillNo());
            com.local.erp.entity.Partner pt = b.getPartnerId() == null ? null : partnerMapper.selectById(b.getPartnerId());
            m.put("partner", pt != null ? pt.getName() : "");
            m.put("amount", b.getTotalAmount());
            if (taxOn) {
                double disc = b.getDiscount() == null ? 100 : b.getDiscount();
                double net = 0, tax = 0;
                for (com.local.erp.entity.BillItem it : billItemMapper.selectList(
                        new LambdaQueryWrapper<com.local.erp.entity.BillItem>()
                                .eq(com.local.erp.entity.BillItem::getBillId, b.getId()))) {
                    com.local.erp.entity.Product p = productMapper.selectById(it.getProductId());
                    double rate = p != null && p.getTaxRate() != null ? p.getTaxRate() : defRate;
                    double incl = it.getQuantity() * it.getPrice() * disc / 100;
                    double n = incl / (1 + rate / 100);
                    net += n;
                    tax += incl - n;
                }
                m.put("net", r2(net));
                m.put("tax", r2(tax));
            }
            m.put("remark", b.getRemark());
            result.add(m);
        }
        return result;
    }

    private Map<Long, double[]> accumulate(List<Map<String, Object>> rows) {
        Map<Long, double[]> acc = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Long accId = ((Number) r.get("accountId")).longValue();
            double[] d = acc.computeIfAbsent(accId, k -> new double[2]);
            if ("DEBIT".equals(r.get("direction"))) d[0] += ((Number) r.get("amount")).doubleValue();
            else d[1] += ((Number) r.get("amount")).doubleValue();
        }
        return acc;
    }

    private Map<String, Object> row(Account a, double balance, String normal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", a.getCode());
        m.put("name", a.getName());
        m.put("balance", balance); // 已按科目正常方向计算，正数=正常方向余额
        return m;
    }

    private String sourceName(String t) {
        return switch (t == null ? "" : t) {
            case "BILL" -> "单据自动";
            case "MANUAL" -> "手工录入";
            case "OPENING" -> "期初建账";
            case "CLOSE" -> "期末结转";
            default -> t;
        };
    }

    private double r2(double v) { return Math.round(v * 100) / 100.0; }
}
