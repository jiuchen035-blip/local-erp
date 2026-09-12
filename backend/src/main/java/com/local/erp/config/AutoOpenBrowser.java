package com.local.erp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;

/**
 * 启动完成后自动用系统默认浏览器打开管理界面。
 * 双击 exe 一键启动时无需手动输入网址；可通过 app.auto-open-browser=false 关闭。
 */
@Component
public class AutoOpenBrowser implements ApplicationRunner {

    @Value("${app.auto-open-browser:true}")
    private boolean autoOpen;

    private final org.springframework.core.env.Environment env;

    public AutoOpenBrowser(org.springframework.core.env.Environment env) {
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoOpen) return;
        new Thread(() -> {
            try {
                Thread.sleep(1500); // 留出 Web 服务器最后收尾的时间
                int port = env.getProperty("server.port", Integer.class, 8080);
                String url = "http://localhost:" + port;
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(url));
                } else {
                    Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", "", url});
                }
            } catch (Exception ignored) {
                // 打不开浏览器不影响服务本身
            }
        }, "auto-open-browser").start();
    }
}
