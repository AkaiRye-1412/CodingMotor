package com.motorsizer.app.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用系统默认浏览器打开地址（打包形态下启动后自动唤起，02 §2.3.3）。
 *
 * <p>Windows 用 {@code rundll32 url.dll,FileProtocolHandler}：不依赖 AWT/Desktop，
 * 在 jpackage 无控制台启动器下同样可用；失败静默（只影响便利性，不影响功能）。
 */
public final class BrowserLauncher {

    private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

    private BrowserLauncher() {
    }

    public static void open(String url) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            ProcessBuilder builder;
            if (os.contains("win")) {
                builder = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else if (os.contains("mac")) {
                builder = new ProcessBuilder("open", url);
            } else {
                builder = new ProcessBuilder("xdg-open", url);
            }
            builder.start();
            log.info("已在系统浏览器打开 {}", url);
        } catch (Exception ex) {
            log.warn("自动打开浏览器失败（{}），请手动访问 {}", ex.getMessage(), url);
        }
    }
}
