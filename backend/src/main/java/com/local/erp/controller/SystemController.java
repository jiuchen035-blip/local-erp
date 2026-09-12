package com.local.erp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.local.erp.entity.OpLog;
import com.local.erp.mapper.OpLogMapper;
import com.local.erp.service.BackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;
import java.util.Map;

/** 系统管理（备份/恢复/日志），仅 ADMIN */
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemController {

    private final BackupService backupService;
    private final OpLogMapper opLogMapper;

    @GetMapping("/backups")
    public List<Map<String, Object>> backups() {
        return backupService.list();
    }

    @PostMapping("/backup")
    public Map<String, Object> backupNow() {
        String name = backupService.backup("manual");
        return Map.of("message", "备份完成：" + name, "name", name);
    }

    /** 准备恢复：重启应用后自动用选中备份覆盖主库（重启前会先自动备份当前库） */
    @PostMapping("/backups/restore")
    public Map<String, Object> restore(@RequestBody Map<String, String> body) throws Exception {
        String name = backupService.prepareRestore(body.get("name"));
        return Map.of("message", "恢复已就绪（" + name + "）。重启账管卫士后自动生效，"
                + "重启时当前数据会先自动备份为 pre-restore-*.db。");
    }

    /** 备份文件下载（跨机器迁移用） */
    @GetMapping("/backups/download")
    public ResponseEntity<org.springframework.core.io.Resource> download(@RequestParam String name) {
        File f = backupService.file(name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new org.springframework.core.io.FileSystemResource(f));
    }

    /** 上传备份文件（把别处导出的 .db 放进备份列表，之后可执行恢复） */
    @PostMapping("/backups/upload")
    public Map<String, Object> uploadBackup(@RequestParam("file") MultipartFile file) throws Exception {
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!original.endsWith(".db")) return Map.of("error", "仅支持 .db 备份文件");
        String saved = backupService.saveUpload(original, file.getBytes());
        return Map.of("message", "备份文件已导入：" + saved, "name", saved);
    }

    @DeleteMapping("/backups/{name}")
    public Map<String, Object> deleteBackup(@PathVariable String name) {
        backupService.delete(name);
        return Map.of("message", "已删除备份 " + name);
    }

    @GetMapping("/logs")
    public List<OpLog> logs() {
        return opLogMapper.selectList(
                new LambdaQueryWrapper<OpLog>().orderByDesc(OpLog::getId).last("limit 200"));
    }
}
