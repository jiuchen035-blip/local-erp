package com.local.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.config.AuthInterceptor;
import com.local.erp.entity.Inspection;
import com.local.erp.mapper.InspectionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 灭火器年检管理：
 *  - 本次年检后默认 next_date = inspect_date + 12 个月，可人工修改
 *  - 状态动态计算：OVERDUE已到期(今日期限已过) / DUE即将到期(默认30天内) / NORMAL正常
 */
@RestController
@RequestMapping("/api/inspections")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionMapper inspectionMapper;
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final List<String> PURPOSE_PRESETS =
            List.of("工地", "商场", "仓库", "学校", "工厂", "酒店", "办公楼", "居民楼", "其他");

    /** 计算到期状态：OVERDUE / DUE / NORMAL */
    public static String statusOf(Inspection i, int dueDays) {
        LocalDate next;
        try {
            next = LocalDate.parse(i.getNextDate(), D);
        } catch (Exception e) {
            return "NORMAL";
        }
        LocalDate today = LocalDate.now();
        if (next.isBefore(today)) return "OVERDUE";
        if (!next.isAfter(today.plusDays(dueDays))) return "DUE";
        return "NORMAL";
    }

    @GetMapping("/purposes")
    public List<String> purposes() {
        Set<String> set = new LinkedHashSet<>(PURPOSE_PRESETS);
        inspectionMapper.selectList(null).forEach(i -> {
            if (i.getPurpose() != null && !i.getPurpose().isBlank()) set.add(i.getPurpose());
        });
        return new ArrayList<>(set);
    }

    /** 列表：status/purpose/keyword 筛选，dueDays 提醒提前期（默认30天），按最紧急排序 */
    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String purpose,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(defaultValue = "30") int dueDays) {
        List<Inspection> all = inspectionMapper.selectList(
                new LambdaQueryWrapper<Inspection>().orderByAsc(Inspection::getNextDate))
                // 已被续检取代的历史记录不参与列表与统计
                .stream().filter(i -> i.getArchived() == null || i.getArchived() == 0).toList();
        String kw = keyword == null ? "" : keyword.trim();

        List<Map<String, Object>> rows = new ArrayList<>();
        int overdue = 0, due = 0, normal = 0;
        for (Inspection i : all) {
            String st = statusOf(i, dueDays);
            switch (st) { case "OVERDUE" -> overdue++; case "DUE" -> due++; default -> normal++; }
            if (status != null && !status.isBlank() && !status.equals(st)) continue;
            if (purpose != null && !purpose.isBlank() && !purpose.equals(i.getPurpose())) continue;
            if (!kw.isEmpty() && !(nz(i.getCustomerName()).contains(kw) || nz(i.getPhone()).contains(kw))) continue;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", i.getId());
            m.put("customerName", i.getCustomerName());
            m.put("phone", i.getPhone());
            m.put("address", i.getAddress());
            m.put("purpose", i.getPurpose());
            m.put("spec", i.getSpec());
            m.put("quantity", i.getQuantity());
            m.put("price", i.getPrice());
            m.put("totalAmount", i.getTotalAmount());
            m.put("inspectDate", i.getInspectDate());
            m.put("nextDate", i.getNextDate());
            m.put("status", st);
            m.put("remark", i.getRemark());
            rows.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("overdue", overdue);
        result.put("due", due);
        result.put("normal", normal);
        result.put("total", all.size());
        return result;
    }

    /** 马上要打电话的名单：已到期 + dueDays 内到期 */
    @GetMapping("/due-list")
    public List<Map<String, Object>> dueList(@RequestParam(defaultValue = "30") int dueDays) {
        List<Inspection> all = inspectionMapper.selectList(
                new LambdaQueryWrapper<Inspection>().orderByAsc(Inspection::getNextDate))
                // 已被续检取代的历史记录不进催检名单
                .stream().filter(i -> i.getArchived() == null || i.getArchived() == 0).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Inspection i : all) {
            String st = statusOf(i, dueDays);
            if ("NORMAL".equals(st)) continue;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", i.getId());
            m.put("customerName", i.getCustomerName());
            m.put("phone", i.getPhone());
            m.put("address", i.getAddress());
            m.put("purpose", i.getPurpose());
            m.put("quantity", i.getQuantity());
            m.put("nextDate", i.getNextDate());
            m.put("status", st);
            m.put("daysLeft", LocalDate.parse(i.getNextDate(), D).toEpochDay() - LocalDate.now().toEpochDay());
            rows.add(m);
        }
        return rows;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Inspection i) {
        validate(i);
        if (i.getNextDate() == null || i.getNextDate().isBlank()) {
            i.setNextDate(defaultNext(i.getInspectDate()));
        }
        i.setTotalAmount(round2(nzInt(i.getQuantity(), 1) * nzD(i.getPrice())));
        i.setCreatedBy(user());
        inspectionMapper.insert(i);
        return Map.of("message", "已登记年检记录", "id", i.getId());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Inspection i) {
        Inspection old = inspectionMapper.selectById(id);
        if (old == null) throw new IllegalArgumentException("记录不存在");
        validate(i);
        i.setId(id);
        i.setTotalAmount(round2(nzInt(i.getQuantity(), 1) * nzD(i.getPrice())));
        if (i.getNextDate() == null || i.getNextDate().isBlank()) i.setNextDate(defaultNext(i.getInspectDate()));
        inspectionMapper.updateById(i);
        return Map.of("message", "已保存");
    }

    /** 续检：复制原记录生成新一条，本次年检日期默认今天，周期+12个月 */
    @PostMapping("/{id}/renew")
    public Map<String, Object> renew(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        Inspection old = inspectionMapper.selectById(id);
        if (old == null) throw new IllegalArgumentException("记录不存在");
        String date = (body != null && body.get("date") != null && !body.get("date").isBlank())
                ? body.get("date") : LocalDate.now().format(D);

        Inspection next = new Inspection();
        next.setCustomerName(old.getCustomerName());
        next.setPhone(old.getPhone());
        next.setAddress(old.getAddress());
        next.setPurpose(old.getPurpose());
        next.setSpec(old.getSpec());
        next.setProductId(old.getProductId());
        next.setQuantity(old.getQuantity());
        next.setPrice(old.getPrice());
        next.setInspectDate(date);
        next.setNextDate(defaultNext(date));
        next.setRemark("续检自#" + old.getId());
        next.setCreatedBy(user());
        next.setTotalAmount(round2(nzInt(next.getQuantity(), 1) * nzD(next.getPrice())));
        inspectionMapper.insert(next);
        // 旧记录转为历史留档（archived=1）：列表默认不再显示，
        // 避免同一客户出现"一条已到期 + 一条正常"两条让人误以为续检没生效
        old.setArchived(1);
        inspectionMapper.updateById(old);
        return Map.of("message", "已续检，新记录下次年检 " + next.getNextDate(), "id", next.getId());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        inspectionMapper.deleteById(id);
        return Map.of("message", "已删除");
    }

    private String defaultNext(String inspectDate) {
        try {
            return LocalDate.parse(inspectDate, D).plusMonths(12).format(D);
        } catch (Exception e) {
            return LocalDate.now().plusMonths(12).format(D);
        }
    }

    private void validate(Inspection i) {
        if (i.getCustomerName() == null || i.getCustomerName().isBlank())
            throw new IllegalArgumentException("客户名称必填");
        if (i.getInspectDate() == null || i.getInspectDate().isBlank())
            throw new IllegalArgumentException("本次年检日期必填");
        try { LocalDate.parse(i.getInspectDate(), D); } catch (Exception e) {
            throw new IllegalArgumentException("日期格式应为 YYYY-MM-DD");
        }
        if (i.getQuantity() == null || i.getQuantity() < 1) i.setQuantity(1);
        if (i.getPrice() == null) i.setPrice(0.0);
    }

    private String user() {
        AuthInterceptor.SessionUser u = AuthInterceptor.currentUser();
        return u != null ? u.getUsername() : "";
    }

    private String nz(String s) { return s == null ? "" : s; }
    private int nzInt(Integer v, int d) { return v == null ? d : v; }
    private double nzD(Double v) { return v == null ? 0 : v; }
    private double round2(double v) { return Math.round(v * 100) / 100.0; }
}
