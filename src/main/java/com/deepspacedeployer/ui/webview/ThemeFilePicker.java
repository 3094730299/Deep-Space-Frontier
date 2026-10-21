package com.deepspacedeployer.ui.webview;

import com.deepspacedeployer.DeepSpaceDeployer;

import java.awt.FileDialog;
import java.awt.Frame;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * <b>系统文件选择器</b>（给 UI 编辑器的「换图片」用）。
 *
 * <p>用 AWT 的 {@link FileDialog}（Windows 上是系统原生对话框），
 * 在<b>守护线程</b>里弹，避免卡住游戏主线程；结果放在静态字段里，
 * 由编辑器在下一帧 {@link #consume()} 取走。</p>
 *
 * <p>弹不出来（无图形环境 / 系统不支持）就什么都不做 —— 绝不能因为选图把游戏搞崩。</p>
 */
public final class ThemeFilePicker {

    private static volatile Path picked;
    private static volatile boolean open;

    private ThemeFilePicker() {
    }

    /** 弹一个"选图片"的对话框（同一个时间只允许一个） */
    public static void pickImage() {
        if (open) {
            return;
        }
        open = true;
        Thread t = new Thread(() -> {
            try {
                FileDialog dialog = new FileDialog((Frame) null, "选择图片", FileDialog.LOAD);
                dialog.setFilenameFilter((dir, name) -> {
                    String n = name.toLowerCase();
                    return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                            || n.endsWith(".gif");
                });
                dialog.setVisible(true);
                String file = dialog.getFile();
                String dir = dialog.getDirectory();
                if (file != null && dir != null) {
                    picked = Paths.get(dir, file);
                }
            } catch (Throwable t2) {
                DeepSpaceDeployer.LOGGER.warn("[深空铸造体][编辑器] 打不开文件选择器：{}", t2.toString());
            } finally {
                open = false;
            }
        }, "dsd-theme-file-picker");
        t.setDaemon(true);
        t.start();
    }

    /** 取走上次选中的文件（没有就返回 null） */
    public static Path consume() {
        Path p = picked;
        picked = null;
        return p;
    }
}
