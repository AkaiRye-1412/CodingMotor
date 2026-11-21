package com.motorsizer.app.report;

import com.motorsizer.app.common.BusinessException;
import com.motorsizer.app.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.EventQueue;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * 报告「另存为」：弹系统原生对话框让用户自己选保存位置，再把 PDF 写进去（D1 增强）。
 *
 * <p>为什么放在服务端而不是前端：打包形态是 jpackage + WebView2，后端与界面同机，
 * 用 {@link FileDialog} 得到的是 Windows 原生「另存为」窗口，支持输入任意路径、覆盖确认、
 * 网络盘与中文路径；浏览器的 {@code showSaveFilePicker} 在 WebView2 里并不可用，
 * 而 {@code <a download>} 只能落到默认下载目录，用户无法指定路径。
 *
 * <p>无图形环境（单元测试、headless 服务器）下不弹窗，直接抛
 * {@link ErrorCode#REPORT_SAVE_UNAVAILABLE} 让前端回退到浏览器下载 —— 绝不在无显示环境里卡住请求线程。
 */
@Component
public class ReportFileSaver {

    private static final Logger log = LoggerFactory.getLogger(ReportFileSaver.class);

    private static final String DIALOG_TITLE = "保存电机选型报告";

    /** 上次选择的目录：同一次会话里连续导出多个方案时不用每次重新找路径。 */
    private volatile String lastDirectory;

    /**
     * 弹出「另存为」对话框。
     *
     * @return 用户选定的完整路径；用户取消返回 {@code null}
     */
    public Path chooseSavePath(String suggestedName) {
        if (GraphicsEnvironment.isHeadless()) {
            log.warn("当前为 headless 环境，无法弹出保存对话框，改由前端下载");
            throw new BusinessException(ErrorCode.REPORT_SAVE_UNAVAILABLE,
                    ErrorCode.REPORT_SAVE_UNAVAILABLE.defaultMessage());
        }
        Path chosen = showDialog(suggestedName, defaultDirectory());
        if (chosen == null) {
            return null;
        }
        // 用户把文件名改成「报告」时替它补上 .pdf：AWT 的 FileDialog 不像原生 GetSaveFileName
        // 那样按过滤器自动补扩展名，缺后缀的文件双击打不开
        String name = chosen.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            chosen = chosen.resolveSibling(name + ".pdf");
        }
        Path parent = chosen.getParent();
        if (parent != null) {
            lastDirectory = parent.toString() + File.separator;
        }
        return chosen;
    }

    /** 写入 PDF；失败时只回传文件名，不把内部绝对路径暴露给前端。 */
    public void write(Path target, byte[] content) {
        try {
            Files.write(target, content);
        } catch (IOException ex) {
            log.error("报告写入失败：{}", target, ex);
            throw new BusinessException(ErrorCode.REPORT_SAVE_FAILED,
                    "报告写入失败：" + target.getFileName());
        }
    }

    /**
     * FileDialog 是模态窗口：在 EDT 上显示会阻塞 EDT 直到用户关闭，
     * 原生对话框自己有消息循环，不需要 AWT 事件泵，因此从请求线程切换过去是安全的。
     */
    private static Path showDialog(String suggestedName, String directory) {
        Path[] chosen = new Path[1];
        Runnable task = () -> {
            FileDialog dialog = new FileDialog((Frame) null, DIALOG_TITLE, FileDialog.SAVE);
            try {
                dialog.setFile(suggestedName);
                dialog.setDirectory(directory);
                dialog.setVisible(true);
                String dir = dialog.getDirectory();
                String name = dialog.getFile();
                if (dir != null && name != null && !name.isBlank()) {
                    chosen[0] = Paths.get(dir, name);
                }
            } finally {
                dialog.dispose();
            }
        };

        try {
            if (EventQueue.isDispatchThread()) {
                task.run();
            } else {
                EventQueue.invokeAndWait(task);
            }
        } catch (Exception ex) {
            // 无 AWT 权限 / 图形子系统异常：同样交给前端回退下载
            log.warn("保存对话框无法显示，改由前端下载", ex);
            throw new BusinessException(ErrorCode.REPORT_SAVE_UNAVAILABLE,
                    ErrorCode.REPORT_SAVE_UNAVAILABLE.defaultMessage());
        }
        return chosen[0];
    }

    /** 默认打开「桌面」，没有则「下载」，再退到用户目录。 */
    private String defaultDirectory() {
        String remembered = lastDirectory;
        if (remembered != null && Files.isDirectory(Paths.get(remembered))) {
            return remembered;
        }
        Path home = Paths.get(System.getProperty("user.home", "."));
        for (String name : List.of("Desktop", "Downloads")) {
            Path candidate = home.resolve(name);
            if (Files.isDirectory(candidate)) {
                lastDirectory = candidate.toString() + File.separator;
                return lastDirectory;
            }
        }
        lastDirectory = home.toString() + File.separator;
        return lastDirectory;
    }
}
