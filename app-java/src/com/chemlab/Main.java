package com.chemlab;

import com.chemlab.core.NativeCore;
import com.chemlab.data.CalibrationDao;
import com.chemlab.data.Database;
import com.chemlab.data.RecordDao;
import com.chemlab.service.PythonBridge;
import com.chemlab.ui.MainFrame;
import com.chemlab.ui.Theme;
import com.chemlab.util.AppPaths;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

/**
 * 应用入口。
 *
 * <p>启动顺序：加载外观与字体 → 恢复上次主题 → 打开 SQLite 数据库 → 尝试启动
 * Python 分析服务（失败则降级，不影响主界面）→ 组装主窗口。任一层不可用时均给出
 * 明确提示，其余模块仍可正常使用。
 *
 * <p>主题切换：Swing 组件在构造期捕获颜色引用，无法就地刷新，因此切换主题后
 * 重建主窗口（数据库与分析服务为长生命周期对象，跨窗口复用）。所选主题索引
 * 持久化到 {@code data/theme.txt}，下次启动自动恢复。
 */
public final class Main {

    private static Database db;
    private static RecordDao recordDao;
    private static CalibrationDao calibrationDao;
    private static PythonBridge bridge;
    private static MainFrame frame;

    private Main() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Main::boot);
    }

    private static void boot() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // 系统外观不可用时使用默认外观
        }
        Theme.setTheme(loadThemeIndex());

        try {
            db = Database.open();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(null,
                    "无法打开数据库：\n" + e.getMessage()
                            + "\n\n请确认 lib/sqlite-jdbc-*.jar 已加入类路径。",
                    "启动失败", JOptionPane.ERROR_MESSAGE);
            return;
        }

        recordDao = new RecordDao(db);
        calibrationDao = new CalibrationDao(db);

        String pythonError = null;
        try {
            bridge = PythonBridge.start();
        } catch (RuntimeException e) {
            pythonError = e.getMessage();
        }

        frame = createFrame();
        frame.setVisible(true);

        warnIfDegraded(pythonError);
    }

    private static MainFrame createFrame() {
        MainFrame f = new MainFrame(recordDao, calibrationDao, bridge, Main::switchTheme);
        f.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (bridge != null) {
                    bridge.close();
                }
                db.close();
                f.dispose();
                System.exit(0);
            }
        });
        return f;
    }

    /** 切换主题：持久化索引 → 原地重建主窗口，保持窗口位置、大小与最大化状态。 */
    private static void switchTheme(int index) {
        Theme.setTheme(index);
        saveThemeIndex(index);

        Rectangle bounds = frame.getBounds();
        int state = frame.getExtendedState();
        MainFrame old = frame;
        frame = createFrame();
        frame.setBounds(bounds);
        if ((state & Frame.MAXIMIZED_BOTH) != 0) {
            frame.setExtendedState(Frame.MAXIMIZED_BOTH);
        }
        frame.setVisible(true);
        old.dispose();
    }

    // ------------------------------------------------------------------
    // 主题偏好持久化（data/theme.txt 仅存一个整数索引）
    // ------------------------------------------------------------------

    private static int loadThemeIndex() {
        try {
            Path p = AppPaths.dataDir().resolve("theme.txt");
            if (Files.isRegularFile(p)) {
                String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
                int i = Integer.parseInt(s);
                if (i >= 0 && i < Theme.themeCount()) {
                    return i;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // 偏好缺失或损坏时回退默认主题
        }
        return 0;
    }

    private static void saveThemeIndex(int index) {
        try {
            Files.write(AppPaths.dataDir().resolve("theme.txt"),
                    Integer.toString(index).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            System.err.println("[chemlab] 保存主题偏好失败：" + e.getMessage());
        }
    }

    private static void warnIfDegraded(String pythonError) {
        StringBuilder sb = new StringBuilder();
        if (!NativeCore.isAvailable()) {
            sb.append("· C++ 计算内核未加载：").append(NativeCore.loadError())
                    .append("\n  所有数值计算模块将不可用。\n");
        }
        if (pythonError != null) {
            sb.append("· Python 分析服务未启动：").append(pythonError)
                    .append("\n  光谱分析与记录本归纳分析将不可用。\n");
        }
        if (sb.length() == 0) {
            return;
        }
        JOptionPane.showMessageDialog(null,
                "部分组件降级运行：\n\n" + sb + "\n其余功能不受影响。",
                "降级提示", JOptionPane.WARNING_MESSAGE);
    }
}