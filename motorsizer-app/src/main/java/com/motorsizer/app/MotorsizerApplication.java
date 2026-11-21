package com.motorsizer.app;

import com.motorsizer.app.bootstrap.BrowserLauncher;
import com.motorsizer.app.bootstrap.SingleInstanceLock;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.net.InetAddress;
import java.net.ServerSocket;

@SpringBootApplication
@MapperScan("com.motorsizer.app.mapper")
public class MotorsizerApplication {

    /** 默认端口（与前端 vite 代理、application.yml 一致）。 */
    static final int DEFAULT_PORT = 8080;

    public static void main(String[] args) {
        if (!SingleInstanceLock.acquire()) {
            int existingPort = SingleInstanceLock.readExistingPort();
            if (existingPort > 0) {
                System.out.println("MotorSizer 已在运行（端口 " + existingPort + "），正在打开界面…");
                BrowserLauncher.open("http://127.0.0.1:" + existingPort);
            } else {
                System.out.println("MotorSizer 已在运行，但暂未取到其端口，请稍候手动打开。");
            }
            return;
        }

        // 8080 空闲则固定使用（dev 友好）；被占用则交由系统分配随机空闲端口（AR-11）
        System.setProperty("server.port", String.valueOf(pickPort()));

        // 报告「另存为」要弹 AWT 原生对话框，而 Spring Boot 默认把 java.awt.headless 置为 true，
        // 会让 FileDialog 抛 HeadlessException。只在正式启动进程里关掉它：测试走 @SpringBootTest，
        // 不经过 main，仍保持 headless —— 所以保存接口在单测里只会返回 REPORT_SAVE_UNAVAILABLE，不会弹窗卡住。
        SpringApplication application = new SpringApplication(MotorsizerApplication.class);
        application.setHeadless(false);
        application.run(args);
    }

    private static int pickPort() {
        try (ServerSocket probe = new ServerSocket(DEFAULT_PORT, 1, InetAddress.getByName("127.0.0.1"))) {
            return DEFAULT_PORT;
        } catch (Exception portBusy) {
            return 0; // 随机空闲端口
        }
    }
}
