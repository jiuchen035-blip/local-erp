package com.local.erp.service;

import com.local.erp.mapper.BillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 自动备份：启动时 + 每晚23点，用 SQLite VACUUM INTO 在线热备到 backend/backup/，
 * 保留最近30份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private final JdbcTemplate jdbc;
    private static final int KEEP = 30;
    private static final String DIR =
            new File(com.local.erp.AppHome.dir(), "backup").getPath();

    @PostConstruct
    public void onStartup() {
        try {
            backup("startup");
        } catch (Exception e) {
            log.warn("启动备份失败: {}", e.getMessage());
        }
    }

    @Scheduled(cron = "0 0 23 * * ?")
    public void nightly() {
        try {
            backup("daily");
        } catch (Exception e) {
            log.warn("定时备份失败: {}", e.getMessage());
        }
    }

    public synchronized String backup(String tag) {
        File dir = new File(DIR);
        if (!dir.exists()) dir.mkdirs();
        String name = "erp-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".db";
        jdbc.execute("VACUUM INTO '" + DIR + "/" + name + "'");
        prune();
        log.info("备份完成: {} ({})", name, tag);
        return name;
    }

    public List<Map<String, Object>> list() {
        File[] files = new File(DIR).listFiles((d, n) -> n.endsWith(".db"));
        List<Map<String, Object>> result = new ArrayList<>();
        if (files == null) return result;
        Arrays.stream(files).sorted((a, b) -> b.getName().compareTo(a.getName())).forEach(f -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", f.getName());
            m.put("sizeKb", f.length() / 1024);
            m.put("modified", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(f.lastModified())));
            result.add(m);
        });
        return result;
    }

    private File backupFile(String name) {
        // 防路径穿越：只允许字母数字下划线横线点，且必须是 .db
        if (name == null || !name.matches("[A-Za-z0-9_\\-.]+\\.db") || name.contains(".."))
            throw new IllegalArgumentException("非法备份文件名");
        File f = new File(DIR, name);
        if (!f.isFile()) throw new IllegalArgumentException("备份文件不存在: " + name);
        return f;
    }

    /** 准备恢复：把选中备份复制为 data/erp.db.restore-pending，重启应用时自动替换主库（重启前会再自动备份当前库） */
    public String prepareRestore(String name) throws Exception {
        File src = backupFile(name);
        File pending = new File(new File(com.local.erp.AppHome.dir(), "data"), "erp.db.restore-pending");
        java.nio.file.Files.copy(src.toPath(), pending.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        log.info("恢复已就绪: {} → restore-pending，重启后生效", name);
        return name;
    }

    /** 备份文件下载 */
    public File file(String name) {
        return backupFile(name);
    }

    /** 上传备份文件（跨机器恢复用）：重名自动加时间戳后缀 */
    public String saveUpload(String originalName, byte[] bytes) throws Exception {
        if (!originalName.endsWith(".db")) throw new IllegalArgumentException("仅支持 .db 备份文件");
        String name = new File(originalName).getName();
        if (new File(DIR, name).exists())
            name = "upload-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + "-" + name;
        File dir = new File(DIR);
        if (!dir.exists()) dir.mkdirs();
        java.nio.file.Files.write(new File(dir, name).toPath(), bytes);
        log.info("备份文件已导入: {}", name);
        return name;
    }

    public void delete(String name) {
        backupFile(name).delete();
    }

    private void prune() {
        File[] files = new File(DIR).listFiles((d, n) -> n.endsWith(".db"));
        if (files == null || files.length <= KEEP) return;
        Arrays.stream(files).sorted((a, b) -> b.getName().compareTo(a.getName()))
                .skip(KEEP).forEach(File::delete);
    }
}
