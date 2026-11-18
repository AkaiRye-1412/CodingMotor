package com.motorsizer.app.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动收尾（A-3 / A-4 / AR-11）：
 * <ul>
 *   <li>Web 服务器就绪 → 把实际端口写入单实例锁文件（随机端口场景下，后继实例才能定位本实例）；</li>
 *   <li>应用就绪 → 自动打开默认浏览器（{@code motorsizer.openBrowser} 可关闭，dev.bat 用 vite 时置 false）。</li>
 * </ul>
 *
 * <p>仅在真实 Web 服务器初始化后才开浏览器：{@code @SpringBootTest}（MOCK 环境）不触发
 * {@link WebServerInitializedEvent}，因此测试不会被弹浏览器干扰。
 */
@Component
public class AppLifecycleLauncher {

    private volatile int actualPort = -1;

    private final boolean openBrowser;

    public AppLifecycleLauncher(@Value("${motorsizer.openBrowser:true}") boolean openBrowser) {
        this.openBrowser = openBrowser;
    }

    @EventListener
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        actualPort = event.getWebServer().getPort();
        SingleInstanceLock.writePort(actualPort);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (actualPort <= 0 || !openBrowser) {
            return; // MOCK 测试环境 / 显式关闭
        }
        BrowserLauncher.open("http://127.0.0.1:" + actualPort);
    }
}
