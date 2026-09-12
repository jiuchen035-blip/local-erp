package com.local.erp.controller;

import com.local.erp.entity.Warehouse;
import com.local.erp.mapper.WarehouseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseMapper warehouseMapper;

    @GetMapping
    public List<Warehouse> list() {
        return warehouseMapper.selectList(null);
    }

    @PostMapping
    public Warehouse create(@RequestBody Warehouse w) {
        if (w.getEnabled() == null) w.setEnabled(1);
        warehouseMapper.insert(w);
        return w;
    }

    @PutMapping("/{id}")
    public Warehouse update(@PathVariable Long id, @RequestBody Warehouse w) {
        w.setId(id);
        warehouseMapper.updateById(w);
        return w;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        warehouseMapper.deleteById(id);
    }
}
