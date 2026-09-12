package com.local.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.entity.Partner;
import com.local.erp.mapper.PartnerMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/partners")
@RequiredArgsConstructor
public class PartnerController {

    private final PartnerMapper partnerMapper;

    @GetMapping
    public List<Partner> list(@RequestParam(required = false) String type) {
        LambdaQueryWrapper<Partner> qw = new LambdaQueryWrapper<>();
        if (type != null && !type.isBlank()) qw.eq(Partner::getType, type);
        return partnerMapper.selectList(qw.orderByDesc(Partner::getId));
    }

    @PostMapping
    public Partner create(@RequestBody Partner p) {
        partnerMapper.insert(p);
        return p;
    }

    @PutMapping("/{id}")
    public Partner update(@PathVariable Long id, @RequestBody Partner p) {
        p.setId(id);
        partnerMapper.updateById(p);
        return p;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        partnerMapper.deleteById(id);
    }

    /** 结清期初余额：direction = RECEIVABLE / PAYABLE（建账后补收/补付完成时置零） */
    @PostMapping("/{id}/settle-opening")
    public Map<String, Object> settleOpening(@PathVariable Long id, @RequestParam String direction) {
        Partner p = partnerMapper.selectById(id);
        if (p == null) throw new IllegalArgumentException("往来单位不存在");
        if ("RECEIVABLE".equals(direction)) p.setOpeningReceivable(0.0);
        else if ("PAYABLE".equals(direction)) p.setOpeningPayable(0.0);
        else throw new IllegalArgumentException("direction 应为 RECEIVABLE 或 PAYABLE");
        partnerMapper.updateById(p);
        return java.util.Map.of("message", "期初余额已结清");
    }
}
