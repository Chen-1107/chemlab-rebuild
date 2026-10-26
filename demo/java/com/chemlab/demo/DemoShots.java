package com.chemlab.demo;

import com.chemlab.data.CalibrationDao;
import com.chemlab.data.Database;
import com.chemlab.data.RecordDao;
import com.chemlab.model.Record;
import com.chemlab.service.PythonBridge;
import com.chemlab.ui.MainFrame;
import com.chemlab.ui.Theme;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次性演示驱动：用课件/习题中的真实例题数据填充各模块并逐屏截图。
 *
 * <p>不参与正式构建，仅供生成界面演示图。运行前需先执行 build-all.ps1。
 * 用法：java ... com.chemlab.demo.DemoShots &lt;输出目录&gt;
 */
public final class DemoShots {

    private static final String[] MODULE_TITLES = {
            "实验记录本", "基础计算", "统计分析", "标定曲线",
            "光谱分析", "实验设计", "知识库", "Obsidian同步",
    };

    private static Path outDir;
    private static MainFrame frame;

    private DemoShots() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(System.getProperty("chemlab.home", ".")).toAbsolutePath().normalize();
        outDir = Paths.get(args.length > 0 ? args[0] : root.resolve("demo/shots").toString())
                .toAbsolutePath().normalize();
        Files.createDirectories(outDir);

        Theme.install();

        // ---- 演示数据库（独立于正式库，避免污染用户数据） ----
        Path dbFile = root.resolve("demo/demo.db");
        Files.deleteIfExists(dbFile);
        Files.deleteIfExists(Paths.get(dbFile + "-wal"));
        Files.deleteIfExists(Paths.get(dbFile + "-shm"));
        Database db = Database.open(dbFile);
        RecordDao recordDao = new RecordDao(db);
        CalibrationDao calibrationDao = new CalibrationDao(db);
        seedRecords(recordDao);

        // ---- Python 分析服务 ----
        PythonBridge bridge = null;
        try {
            bridge = PythonBridge.start();
            System.out.println("[demo] Python 服务就绪：" + bridge.interpreter()
                    + " vision=" + bridge.visionAvailable());
        } catch (RuntimeException e) {
            System.err.println("[demo] Python 服务不可用：" + e.getMessage());
        }

        // ---- 备份用户 Obsidian 配置，演示结束后还原 ----
        Path obsidianCfg = root.resolve("data/obsidian.json");
        String obsidianBackup = Files.isRegularFile(obsidianCfg)
                ? new String(Files.readAllBytes(obsidianCfg), StandardCharsets.UTF_8) : null;

        final PythonBridge finalBridge = bridge;
        SwingUtilities.invokeAndWait(() -> {
            frame = new MainFrame(recordDao, calibrationDao, finalBridge);
            frame.setSize(1520, 1040);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
        pause(1200);

        try {
            shot(0, "01_实验记录本.png", () -> {
                JComponent p = modulePanel(0);
                click(p, "归纳分析");
                pause(4000);
            });

            shot(1, "02_基础计算.png", () -> {
                JComponent p = modulePanel(1);
                JComponent tab = tabAt(p, 0);
                List<JTextField> fs = find(tab, JTextField.class);
                set(fs, 0, "0.5120");   // m(KHP) / g
                set(fs, 1, "204.22");   // M(KHP) / g·mol⁻¹
                set(fs, 2, "0.2500");   // V / L
                set(fs, 3, "0.1000");   // c1
                set(fs, 4, "25.00");    // V1 / mL
                set(fs, 5, "250.0");    // V2 / mL
                click(tab, "计算");
            });

            shot(2, "03_统计分析.png", () -> {
                JComponent p = modulePanel(2);
                JComponent tab = tabAt(p, 0);
                List<JTextArea> tas = find(tab, JTextArea.class);
                if (!tas.isEmpty()) {
                    tas.get(0).setText("10.48 10.37 10.47 10.43 10.40");
                }
                List<JTextField> fs = find(tab, JTextField.class);
                if (!fs.isEmpty()) {
                    fs.get(0).setText("10.42");
                }
                click(tab, "计算");
            });

            shot(3, "04_标定曲线.png", () -> {
                JComponent p = modulePanel(3);
                click(p, "回归");
            });

            shot(4, "05_光谱分析.png", () -> {
                JComponent p = modulePanel(4);
                String img = Paths.get(System.getProperty("chemlab.home", "."))
                        .resolve("demo/media/第二章 数据及误差的处理-48(1)/image15.png")
                        .toAbsolutePath().normalize().toString();
                setField(p, "imageFile", new File(img));
                ((JTextField) field(p, "pathField")).setText(img);
                ((JTextField) field(p, "curvesField")).setText("1");
                ((JTextField) field(p, "smoothField")).setText("9");
                ((JTextField) field(p, "minHeightField")).setText("0.15");
                ((JTextField) field(p, "minDistField")).setText("18");
                ((JComboBox<?>) field(p, "modeBox")).setSelectedIndex(1); // 深色曲线
                ((JCheckBox) field(p, "baselineBox")).setSelected(true);
                click(p, "开始分析");
                pause(9000);
            });

            shot(5, "06_实验设计.png", () -> {
                JComponent p = modulePanel(5);
                ((JTextArea) field(p, "factorNamesArea"))
                        .setText("反应温度\n体系 pH\n反应时间\n催化剂用量");
                ((JTextField) field(p, "factorCountField")).setText("4");
                click(p, "生成正交表");
            });

            shot(6, "07_知识库.png", () -> {
                JComponent p = modulePanel(6);
                List<JList> lists = find(p, JList.class);
                if (lists.size() >= 2) {
                    JList<?> chapters = lists.get(0);
                    JList<?> items = lists.get(1);
                    for (int i = 0; i < chapters.getModel().getSize(); i++) {
                        chapters.setSelectedIndex(i);
                        if (items.getModel().getSize() > 0) {
                            items.setSelectedIndex(0);
                            break;
                        }
                    }
                }
            });

            shot(7, "08_Obsidian同步.png", () -> {
                JComponent p = modulePanel(7);
                Path vault = Paths.get(System.getProperty("chemlab.home", "."))
                        .resolve("demo/obsidian-vault").toAbsolutePath().normalize();
                try {
                    Files.createDirectories(vault);
                } catch (Exception ignored) {
                    // 目录创建失败时仍保留面板默认外观
                }
                ((JTextField) field(p, "vaultField")).setText(vault.toString());
                click(p, "导出到库");
                pause(5000);
            });
        } finally {
            // 还原用户 Obsidian 配置
            try {
                if (obsidianBackup != null) {
                    Files.write(obsidianCfg, obsidianBackup.getBytes(StandardCharsets.UTF_8));
                } else {
                    Files.deleteIfExists(obsidianCfg);
                }
            } catch (Exception e) {
                System.err.println("[demo] 还原 obsidian.json 失败：" + e.getMessage());
            }
            if (finalBridge != null) {
                finalBridge.close();
            }
            db.close();
            SwingUtilities.invokeLater(() -> frame.dispose());
        }
        System.out.println("[demo] 全部截图已输出到：" + outDir);
        System.exit(0);
    }

    // ------------------------------------------------------------------

    private static void seedRecords(RecordDao dao) throws Exception {
        add(dao, "titration", "2026-09-08", "NaOH 标准溶液的标定",
                "用基准邻苯二甲酸氢钾标定 NaOH 溶液浓度。",
                "m(KHP)=0.5120 g；M=204.22 g/mol；V(NaOH)=25.10 mL",
                "c(NaOH)=0.09986 mol/L，平行三次 RSD=0.12%",
                "滴定,标定,NaOH", 0.09986);
        add(dao, "titration", "2026-09-15", "未知盐酸溶液浓度的测定",
                "以标定好的 NaOH 滴定未知浓度 HCl。",
                "V(HCl)=25.00 mL；V(NaOH)=25.30 mL",
                "c(HCl)=0.1011 mol/L",
                "滴定,酸碱", 0.1011);
        add(dao, "spectro", "2026-09-22", "邻二氮菲光度法测定微量铁",
                "绘制标准曲线并测定未知样中铁含量。",
                "λ=510 nm；b=1 cm；A=0.386",
                "回归方程 A=0.384c+0.0016，未知样 ρ(Fe)=0.5004 mg/L",
                "光度,铁,标准曲线", 0.5004);
        add(dao, "ph", "2026-09-29", "醋酸解离常数 Ka 的测定",
                "pH 法测定醋酸的解离常数。",
                "c(HAc)=0.100 mol/L；pH=2.87",
                "Ka=1.76×10⁻⁵，pKa=4.75",
                "pH,解离常数", 1.76e-5);
        add(dao, "calib", "2026-10-06", "分光光度法标准曲线",
                "建立浓度-吸光度标准曲线。",
                "6 个标定点，线性范围 0~1.0",
                "y=0.384x+0.0016，R²=0.9993，LOD=0.008 mmol/L",
                "标定,回归", 0.9993);
        add(dao, "complex", "2026-10-10", "EDTA 标准溶液的标定",
                "以 CaCO₃ 基准物标定 EDTA 标准溶液。",
                "m(CaCO₃)=0.2502 g；V(EDTA)=25.00 mL",
                "c(EDTA)=0.01002 mol/L",
                "络合,EDTA", 0.01002);
    }

    private static void add(RecordDao dao, String type, String date, String title,
                            String purpose, String data, String conclusion,
                            String tags, double result) throws Exception {
        Record r = new Record();
        r.type = type;
        r.date = date;
        r.title = title;
        r.purpose = purpose;
        r.dataText = data;
        r.conclusion = conclusion;
        r.tags = tags;
        r.resultValue = result;
        dao.insert(r);
    }

    // ------------------------------------------------------------------
    // 截图
    // ------------------------------------------------------------------

    private static void shot(int index, String fileName, Runnable prep) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            select(index);
            prep.run();
        });
        pause(500);
        final BufferedImage[] img = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            frame.validate();
            JComponent c = (JComponent) frame.getContentPane();
            BufferedImage bi = new BufferedImage(c.getWidth(), c.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bi.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            c.printAll(g);
            g.dispose();
            img[0] = bi;
        });
        Path out = outDir.resolve(fileName);
        ImageIO.write(img[0], "png", out.toFile());
        System.out.println("[demo] 已保存 " + out + "  (" + img[0].getWidth()
                + "x" + img[0].getHeight() + ")");
    }

    private static void select(int index) {
        try {
            Method m = MainFrame.class.getDeclaredMethod("select", int.class);
            m.setAccessible(true);
            m.invoke(frame, index);
        } catch (Exception e) {
            throw new RuntimeException("切换模块失败：" + index, e);
        }
    }

    private static JComponent modulePanel(int index) {
        JPanel content = (JPanel) field(frame, "content");
        return (JComponent) content.getComponent(index);
    }

    private static JComponent tabAt(Container root, int index) {
        List<JTabbedPane> tabs = find(root, JTabbedPane.class);
        if (tabs.isEmpty()) {
            return (JComponent) root;
        }
        JTabbedPane tp = tabs.get(0);
        tp.setSelectedIndex(index);
        return (JComponent) tp.getComponentAt(index);
    }

    // ------------------------------------------------------------------
    // 组件树工具
    // ------------------------------------------------------------------

    private static <T> List<T> find(Container root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T> void collect(Container c, Class<T> type, List<T> out) {
        for (Component comp : c.getComponents()) {
            if (type.isInstance(comp)) {
                out.add(type.cast(comp));
            }
            if (comp instanceof Container) {
                collect((Container) comp, type, out);
            }
        }
    }

    private static void click(Container root, String text) {
        for (JButton b : find(root, JButton.class)) {
            if (text.equals(b.getText())) {
                b.doClick();
                return;
            }
        }
        System.err.println("[demo] 未找到按钮：" + text);
    }

    private static void set(List<JTextField> fields, int i, String value) {
        if (i < fields.size()) {
            fields.get(i).setText(value);
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new RuntimeException("读取字段失败：" + name, e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("写入字段失败：" + name, e);
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}