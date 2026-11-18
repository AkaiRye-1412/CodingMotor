package com.motorsizer.app.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 单实例锁（A-4）：以 {@code %APPDATA%\MotorSizer\app.lock} 上的文件锁保证同一时刻只有一个实例，
 * 防止两个进程写同一个 H2 库文件。
 *
 * <p><b>锁文件与端口文件分离</b>：Windows 的独占 FileLock 会让其他进程连「读」都被拒绝，
 * 因此端口不能写进锁文件（否则第二个实例读不到、无法定位既有实例）。
 * <ul>
 *   <li>{@code app.lock} —— 仅持有 FileLock，内容无意义；</li>
 *   <li>{@code app.port} —— 不加锁，记录当前实例实际监听端口，任意进程可读。</li>
 * </ul>
 *
 * <p>流程：
 * <ol>
 *   <li>{@link #acquire()} 在 main 里先于 Spring 启动调用；失败即已有实例在运行。</li>
 *   <li>首个实例在 Web 服务器就绪后把实际端口写入 {@code app.port}（随机端口场景下第二个实例才知道地址）。</li>
 *   <li>第二个实例读 {@code app.port} → 打开浏览器指向既有实例 → 退出。</li>
 *   <li>JVM 退出钩子清空 {@code app.port} 并释放锁；进程异常退出时操作系统亦会释放文件锁。</li>
 * </ol>
 *
 * <p>纯静态工具类，不作为 Spring Bean——{@code @SpringBootTest} 不经过 main，因此测试不受影响。
 */
public final class SingleInstanceLock {

    private static final Logger log = LoggerFactory.getLogger(SingleInstanceLock.class);

    /** 第二个实例等待既有实例写出端口的最长时间（毫秒）。 */
    private static final long PORT_READ_TIMEOUT_MS = 5_000;
    private static final long PORT_READ_INTERVAL_MS = 200;

    private static Path lockFile;
    private static Path portFile;
    private static FileChannel channel;
    private static FileLock lock;

    private SingleInstanceLock() {
    }

    /**
     * 尝试获取单实例锁。
     *
     * @return true = 本进程是首个实例；false = 已有实例在运行
     */
    public static synchronized boolean acquire() {
        lockFile = resolveBase().resolve("app.lock");
        portFile = resolveBase().resolve("app.port");
        try {
            Files.createDirectories(lockFile.getParent());
            channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            lock = channel.tryLock();
            if (lock == null) {
                log.info("检测到 MotorSizer 已在运行（{}）", lockFile);
                return false;
            }
            // 首个实例：清掉上一代进程可能残留的端口
            writePortFile(-1);
            Runtime.getRuntime().addShutdownHook(new Thread(SingleInstanceLock::release, "instance-lock"));
            return true;
        } catch (IOException | OverlappingFileLockException ex) {
            log.warn("单实例锁不可用（{}），继续启动：{}", lockFile, ex.getMessage());
            // 锁机制失效不阻断启动（例如锁目录无写权限），退化为可多开
            return true;
        }
    }

    /**
     * Web 服务器就绪后记录实际端口到 {@code app.port}，供后继实例 / 冒烟脚本读取。
     */
    public static synchronized void writePort(int port) {
        if (portFile == null) {
            return;
        }
        try {
            writePortFile(port);
        } catch (IOException ex) {
            log.warn("写入实例端口失败：{}", ex.getMessage());
        }
    }

    /**
     * 供“第二个实例”读取既有实例的端口；等待既有实例完成启动（最长 {@value #PORT_READ_TIMEOUT_MS} ms）。
     *
     * @return 端口；读不到返回 -1
     */
    public static int readExistingPort() {
        if (portFile == null) {
            portFile = resolveBase().resolve("app.port");
        }
        long deadline = System.currentTimeMillis() + PORT_READ_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                int port = parsePortFile();
                if (port > 0) {
                    return port;
                }
                Thread.sleep(PORT_READ_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return -1;
            } catch (IOException ex) {
                return -1;
            }
        }
        return -1;
    }

    /** 进程退出时清空端口文件并释放锁。 */
    public static synchronized void release() {
        try {
            if (lock != null) {
                writePortFile(-1);
                lock.release();
                lock = null;
            }
        } catch (IOException ex) {
            log.debug("清空端口文件失败：{}", ex.getMessage());
        } finally {
            try {
                if (channel != null) {
                    channel.close();
                    channel = null;
                }
            } catch (IOException ex) {
                log.debug("关闭锁文件失败：{}", ex.getMessage());
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 端口文件不加锁，任意进程可读写。 */
    private static void writePortFile(int port) throws IOException {
        String payload = port > 0 ? String.valueOf(port) : "";
        Files.writeString(portFile, payload, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static int parsePortFile() throws IOException {
        if (!Files.exists(portFile)) {
            return -1;
        }
        String content = Files.readString(portFile, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(content);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static Path resolveBase() {
        String appData = System.getenv("APPDATA");
        Path base;
        if (appData != null && !appData.isBlank()) {
            base = Path.of(appData);
        } else {
            // 非 Windows / 无 APPDATA 的退化位置（仅开发环境）
            base = Path.of(System.getProperty("user.home"), ".motorsizer");
        }
        return base.resolve("MotorSizer");
    }
}
