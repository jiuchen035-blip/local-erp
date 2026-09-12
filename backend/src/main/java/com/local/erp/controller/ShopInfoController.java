package com.local.erp.controller;

import com.local.erp.config.AuthInterceptor;
import com.local.erp.service.ShopInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 店铺信息：打印抬头用。查看所有登录用户，修改仅管理员。 */
@RestController
@RequestMapping("/api/shop")
@RequiredArgsConstructor
public class ShopInfoController {

    private final ShopInfoService shopInfoService;

    @GetMapping("/info")
    public ShopInfoService.ShopInfo get() {
        return shopInfoService.get();
    }

    @PostMapping("/info")
    public ShopInfoService.ShopInfo update(@RequestBody ShopInfoService.ShopInfo req) {
        AuthInterceptor.SessionUser u = AuthInterceptor.currentUser();
        if (u == null || !"ADMIN".equals(u.getRole())) {
            throw new SecurityException("仅管理员可修改店铺信息");
        }
        return shopInfoService.update(req);
    }
}
