package com.local.erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

@SpringBootApplication
public class ErpApplication {
    public static void main(String[] args) {
        // 数据根目录（打包 exe → 程序包内；开发态 → 工作目录），
        // 必须在 Spring 启动前定好，yml 里 ${erp.data.root} 的占位符取这里
        System.setProperty("erp.data.root", AppHome.dir().getAbsolutePath());

        // SQLite 不会自动创建父目录，必须在数据源初始化前建好
        java.io.File dataDir = AppHome.dataDir();
        if (!dataDir.exists()) dataDir.mkdirs();

        // 恢复挂起的备份：数据源尚未初始化，直接替换文件零冲突。
        // 当前库先自动备份为 backup/pre-restore-<时间戳>.db，防止误恢复丢数据。
        try {
            java.io.File pending = new java.io.File(dataDir, "erp.db.restore-pending");
            if (pending.isFile()) {
                java.io.File db = new java.io.File(dataDir, "erp.db");
                java.io.File backupDir = new java.io.File(AppHome.dir(), "backup");
                if (!backupDir.exists()) backupDir.mkdirs();
                if (db.isFile()) {
                    String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                    java.nio.file.Files.copy(db.toPath(),
                            new java.io.File(backupDir, "pre-restore-" + ts + ".db").toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                java.nio.file.Files.copy(pending.toPath(), db.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                pending.delete();
                System.out.println("[数据恢复] 已从备份恢复数据库（原库自动备份为 backup/pre-restore-*.db）");
            }
        } catch (Exception e) {
            System.err.println("[数据恢复] 失败：" + e.getMessage());
        }

        // 8080 被其他程序占用时自动换备用端口（exe 发给别人，对方电脑可能占着 8080）
        boolean hasExplicitPort = args != null && java.util.Arrays.stream(args)
                .anyMatch(a -> a.toLowerCase().startsWith("--server.port"));
        if (!hasExplicitPort) {
            String port = pickFreePort("8080", "8081", "8082", "18080");
            if (port != null) System.setProperty("server.port", port);
        }
        SpringApplication.run(ErpApplication.class, args);
    }

    /** 依次探测，返回第一个空闲端口；全忙返回 null（按默认 8080 启动，日志里可定位失败原因） */
    private static String pickFreePort(String... candidates) {
        for (String p : candidates) {
            try (ServerSocket ss = new ServerSocket()) {
                ss.bind(new InetSocketAddress("127.0.0.1", Integer.parseInt(p)));
                return p;
            } catch (Exception ignored) {
                // 端口被占用，试下一个
            }
        }
        return null;
    }
}
