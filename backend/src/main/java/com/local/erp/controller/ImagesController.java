package com.local.erp.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;

/** 商品图片（本地只读，免登录便于 <img> 标签加载） */
@RestController
@RequestMapping("/api/images")
public class ImagesController {

    @GetMapping("/{id}")
    public ResponseEntity<FileSystemResource> image(@PathVariable Long id) {
        for (String ext : new String[]{".jpg", ".png"}) {
            File f = new File(com.local.erp.AppHome.dataDir(), "images/" + id + ext);
            if (f.exists()) {
                MediaType type = ext.equals(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
                return ResponseEntity.ok().contentType(type).body(new FileSystemResource(f));
            }
        }
        return ResponseEntity.notFound().build();
    }
}
