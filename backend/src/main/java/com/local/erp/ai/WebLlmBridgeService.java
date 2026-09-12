package com.local.erp.ai;

import com.local.erp.AppHome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * webllm 桥接服务管理：探测本地 Python 桥接（127.0.0.1:8317，OpenAI 兼容协议，
 * Playwright 驱动妙想/DeepSeek 网页版），未运行时自动用 ProcessBuilder 拉起。
 */
@Slf4j
@Service
public class WebLlmBridgeService {

    public static final String BASE_URL = "http://127.0.0.1:8317";
    public static final List<String> MODELS = List.of("deepseek-web", "miaoxiang-web");

    private final AtomicReference<Process> process = new AtomicReference<>();
    private final Map<String, String> loginResults = new ConcurrentHashMap<>();

    /** 桥接是否在运行 */
    public boolean isRunning() {
        try {
            String body = RestClient.builder().baseUrl(BASE_URL)
                    .build().get().uri("/health")
                    .retrieve().body(String.class);
            return body != null && body.contains("\"ok\":true");
        } catch (Exception e) {
            return false;
        }
    }

    /** 确保桥接运行：未运行则尝试自动拉起，最多等 20 秒 */
    public synchronized void ensureRunning() {
        if (isRunning()) return;
        File dir = findWebllmDir();
        if (dir == null) {
            throw new IllegalStateException("未找到 webllm 目录。网页版模型需要项目自带的 webllm 桥接服务（webllm/server.py）");
        }
        String python = findPython();
        if (python == null) {
            throw new IllegalStateException("未找到 Python。网页版模型需要安装 Python 3.10+，并执行：pip install -r webllm/requirements.txt 和 playwright install chromium");
        }
        try {
            ProcessBuilder pb;
            if (python.equals("py")) {
                pb = new ProcessBuilder("py", "-3", new File(dir, "server.py").getAbsolutePath());
            } else {
                pb = new ProcessBuilder(python, new File(dir, "server.py").getAbsolutePath());
            }
            pb.directory(dir);
            pb.redirectErrorStream(true);
            pb.redirectOutput(new File(dir, "bridge.log"));
            pb.environment().put("PYTHONIOENCODING", "utf-8");
            // 登录态放 data 目录：升级/重装程序不丢网页登录
            pb.environment().put("WEBLLM_PROFILE_DIR",
                    new File(com.local.erp.AppHome.dataDir(), "webllm-profiles").getAbsolutePath());
            Process old = process.getAndSet(pb.start());
            if (old != null && old.isAlive()) old.destroyForcibly();
            log.info("webllm 桥接已拉起：{}/server.py", dir);
        } catch (Exception e) {
            throw new IllegalStateException("启动 webllm 桥接失败：" + e.getMessage()
                    + "。可手动运行 webllm/start.bat");
        }
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) return;
            try { Thread.sleep(800); } catch (InterruptedException ie) { break; }
        }
        throw new IllegalStateException("webllm 桥接启动超时，请查看 webllm/bridge.log，"
                + "或确认已执行 pip install -r webllm/requirements.txt 和 playwright install chromium");
    }

    /** 查找 webllm 目录：开发态在 backend/../webllm，打包后在应用目录/webllm */
    private File findWebllmDir() {
        List<File> candidates = new ArrayList<>(List.of(
                new File(AppHome.dir(), "webllm"),
                new File(AppHome.dir(), "../webllm"),
                new File(System.getProperty("user.dir", ""), "webllm"),
                new File(System.getProperty("user.dir", ""), "../webllm")));
        for (File c : candidates) {
            if (new File(c, "server.py").isFile()) return c;
        }
        return null;
    }

    private String findPython() {
        for (String cmd : new String[]{"python", "python3", "py"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version").start();
                if (p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) return cmd;
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** 触发桥接打开有头浏览器登录指定站点；后台线程执行（桥接会阻塞等待用户完成登录），结果稍后可查 */
    public void openLogin(String site) {
        Thread t = new Thread(() -> {
            try {
                ensureRunning();
                Map<?, ?> resp = RestClient.builder().baseUrl(BASE_URL).build().post()
                        .uri("/login/" + site + "?minutes=5")
                        .retrieve().body(Map.class);
                loginResults.put(site, Boolean.TRUE.equals(resp.get("ok"))
                        ? "登录成功，登录态已保存"
                        : "等待登录超时，请重试");
            } catch (Exception e) {
                loginResults.put(site, "登录失败：" + e.getMessage());
            }
        }, "webllm-login-" + site);
        t.setDaemon(true);
        t.start();
    }

    /** 取最近一次登录结果并清除 */
    public String pollLoginResult(String site) {
        return loginResults.remove(site);
    }

    /** 状态总览（前端展示用） */
    public Map<String, Object> status() {
        File dir = findWebllmDir();
        boolean running = isRunning();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("running", running);
        m.put("dirFound", dir != null);
        m.put("pythonFound", findPython() != null);
        m.put("models", MODELS);
        Map<String, String> logins = new java.util.LinkedHashMap<>();
        for (String s : List.of("deepseek", "miaoxiang")) {
            String r = loginResults.get(s);
            if (r != null) logins.put(s, r);
        }
        m.put("loginResults", logins);
        return m;
    }
}
