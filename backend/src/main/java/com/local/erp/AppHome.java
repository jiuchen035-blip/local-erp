package com.local.erp;

import java.io.File;

/**
 * 运行数据根目录。
 * jpackage 打包出的 exe（绿色版/安装版）里，jar 旁边必有 <应用名>.cfg 启动配置——
 * 以此识别"打包运行"模式，数据固定落在程序包目录内（删掉程序文件夹 = 彻底卸载）；
 * 开发态（IDE / java -jar）没有这些特征，退回工作目录，行为与以前一致（backend/data）。
 */
public final class AppHome {

    private static volatile File home;

    private AppHome() {}

    public static File dir() {
        File h = home;
        if (h == null) {
            synchronized (AppHome.class) {
                if (home == null) {
                    home = resolve();
                }
            }
            h = home;
        }
        return h;
    }

    /** data 子目录：数据库、商品图片、AI 设置、店铺信息、日志都在里面 */
    public static File dataDir() {
        return new File(dir(), "data");
    }

    private static File resolve() {
        // 1) jpackage 部分平台/版本会注入 app.home、app.dir，直接信任
        String prop = System.getProperty("app.home");
        if (isBlank(prop)) prop = System.getProperty("app.dir");
        if (!isBlank(prop)) return new File(prop);

        // 2) classpath 里第一个可执行 jar 旁边有 .cfg（jpackage 启动配置）→ 打包模式
        File packaged = packagedJarDir();
        if (packaged != null) return packaged;

        // 3) 开发态：沿用工作目录
        return new File("").getAbsoluteFile();
    }

    /** jpackage 模式返回 app 目录（含 jar 与 .cfg），否则返回 null */
    private static File packagedJarDir() {
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(File.pathSeparator)) {
            File f = new File(entry).getAbsoluteFile();
            if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                File dir = f.getParentFile();
                File[] cfg = dir == null ? null
                        : dir.listFiles((d, n) -> n.toLowerCase().endsWith(".cfg"));
                return (cfg != null && cfg.length > 0) ? dir : null;
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
