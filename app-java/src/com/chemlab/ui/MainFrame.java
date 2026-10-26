package com.chemlab.ui;

import com.chemlab.core.NativeCore;
import com.chemlab.data.CalibrationDao;
import com.chemlab.data.RecordDao;
import com.chemlab.service.PythonBridge;
import com.chemlab.ui.panels.CalcPanel;
import com.chemlab.ui.panels.CalibrationPanel;
import com.chemlab.ui.panels.ChromPanel;
import com.chemlab.ui.panels.DesignPanel;
import com.chemlab.ui.panels.ImaPanel;
import com.chemlab.ui.panels.KnowledgePanel;
import com.chemlab.ui.panels.NotebookPanel;
import com.chemlab.ui.panels.ObsidianPanel;
import com.chemlab.ui.panels.SpectrumPanel;
import com.chemlab.ui.panels.StatsPanel;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * 主窗口：深色渐变侧边栏 + 右侧内容区（顶部标题条 + 卡片内容 + 底部状态栏）。
 *
 * <p>把各功能面板按模块顺序组装为卡片，切换时只显示对应卡片。顶部显示当前模块
 * 名称与说明，并给出内核 / 分析服务的状态胶囊；底部状态栏显示更详细的降级信息。
 */
public class MainFrame extends JFrame {

    /** 模块名称与一句话说明，顺序与侧边栏、卡片注册顺序一致。 */
    private static final String[][] MODULES = {
            {"实验记录本", "记录的增删改查、检索与归纳分析"},
            {"基础计算", "浓度 / pH / 滴定 / 光度"},
            {"统计分析", "描述统计、异常值检验、显著性检验"},
            {"标定曲线", "线性回归、LOD/LOQ、残差分析"},
            {"光谱分析", "谱图曲线提取、平滑、寻峰、积分"},
            {"色谱分析", "色谱峰检测、峰面积与分离度"},
            {"实验设计", "正交表生成与误差分配"},
            {"知识库", "本地参考书全文浏览与跨来源检索"},
            {"Obsidian 同步", "实验记录与标定曲线的双向同步"},
            {"ima 知识库", "连接 ima 知识库：凭据配置与连通测试"},
    };

    /** 侧边栏内次要文字的暗色。侧边栏在各主题下均为深色，故此处固定。 */
    private static final Color SIDEBAR_DIM = new Color(0x5A, 0x67, 0x7C);

    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final List<NavItem> navItems = new ArrayList<>();

    private final JLabel headerTitle = new JLabel();
    private final JLabel headerSub = new JLabel();

    private final RecordDao recordDao;
    private final CalibrationDao calibrationDao;
    private final PythonBridge bridge;
    /** 主题切换回调：接收目标主题索引；为 null 时切换器仅展示、不可点击。 */
    private final IntConsumer onThemeChange;

    public MainFrame(RecordDao recordDao, CalibrationDao calibrationDao, PythonBridge bridge) {
        this(recordDao, calibrationDao, bridge, null);
    }

    public MainFrame(RecordDao recordDao, CalibrationDao calibrationDao, PythonBridge bridge,
                     IntConsumer onThemeChange) {
        super("ChemLab Analyzer · 分析化学实验数据处理");
        this.recordDao = recordDao;
        this.calibrationDao = calibrationDao;
        this.bridge = bridge;
        this.onThemeChange = onThemeChange;

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout());
        getContentPane().setBackground(Theme.BG);

        add(buildSidebar(), BorderLayout.WEST);
        add(buildMain(), BorderLayout.CENTER);

        buildPanels();

        setSize(1300, 840);
        setMinimumSize(new Dimension(1060, 700));
        setLocationRelativeTo(null);
        select(0);
    }

    // ------------------------------------------------------------------
    // 侧边栏
    // ------------------------------------------------------------------

    private JPanel buildSidebar() {
        JPanel side = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 0, Theme.SIDEBAR_TOP,
                        0, getHeight(), Theme.SIDEBAR_BOTTOM));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setOpaque(false);
        side.setPreferredSize(new Dimension(238, 100));
        side.setBorder(BorderFactory.createEmptyBorder(20, 0, 14, 0));

        side.add(buildBrand());
        side.add(Box.createVerticalStrut(14));

        for (int i = 0; i < MODULES.length; i++) {
            final int index = i;
            NavItem item = new NavItem(MODULES[i][0], Icons.nav(i), () -> select(index));
            navItems.add(item);
            side.add(item);
            if (i < MODULES.length - 1) {
                side.add(vGlue());
            }
        }

        side.add(Box.createVerticalStrut(14));
        side.add(sidebarFooter());
        return side;
    }

    /** 垂直弹性空隙：在导航项之间均分剩余高度，使菜单铺满侧边栏。 */
    private static Component vGlue() {
        Component c = Box.createVerticalGlue();
        ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private JComponent buildBrand() {
        JComponent logoBox = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setPaint(new GradientPaint(0, 0, Theme.ACCENT_LIGHT,
                        getWidth(), getHeight(), Theme.ACCENT_DARK));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.dispose();
                Icons.logo(18).paintIcon(this, g, 6, 6);
            }
        };
        logoBox.setForeground(Color.WHITE);
        logoBox.setOpaque(false);
        logoBox.setPreferredSize(new Dimension(30, 30));
        logoBox.setMinimumSize(new Dimension(30, 30));
        logoBox.setMaximumSize(new Dimension(30, 30));

        JLabel name = new JLabel("ChemLab");
        name.setFont(Theme.font(Font.BOLD, 16));
        name.setForeground(Color.WHITE);

        JLabel accent = new JLabel(" Analyzer");
        accent.setFont(Theme.font(Font.PLAIN, 14));
        accent.setForeground(Theme.SIDEBAR_TEXT);

        JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        nameRow.setOpaque(false);
        nameRow.add(name);
        nameRow.add(accent);

        JPanel textCol = new JPanel();
        textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
        textCol.setOpaque(false);
        nameRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        textCol.add(nameRow);

        JLabel sub = new JLabel("分析化学实验数据处理");
        sub.setFont(Theme.font(Font.PLAIN, 11));
        sub.setForeground(SIDEBAR_DIM);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        sub.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        textCol.add(sub);

        JPanel row = new JPanel(new BorderLayout(12, 0)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 18));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(logoBox, BorderLayout.WEST);
        row.add(textCol, BorderLayout.CENTER);
        return row;
    }

    private JComponent sidebarFooter() {
        JPanel p = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 20));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);

        p.add(themeSwitcher());
        p.add(Box.createVerticalStrut(12));

        JLabel line = new JLabel("C++ 内核 · Python 分析 · Java 界面");
        line.setFont(Theme.font(Font.PLAIN, 10));
        line.setForeground(SIDEBAR_DIM);
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(line);
        return p;
    }

    /** 侧边栏底部主题切换器：标题 + 一排色板圆点，点击即时换肤。 */
    private JComponent themeSwitcher() {
        JLabel caption = new JLabel("主题");
        caption.setFont(Theme.font(Font.PLAIN, 10));
        caption.setForeground(SIDEBAR_DIM);
        caption.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (int i = 0; i < Theme.themeCount(); i++) {
            row.add(new ThemeSwatch(i, onThemeChange));
        }

        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(caption);
        col.add(Box.createVerticalStrut(7));
        col.add(row);
        return col;
    }

    // ------------------------------------------------------------------
    // 右侧主区
    // ------------------------------------------------------------------

    private JPanel buildMain() {
        JPanel main = new JPanel(new BorderLayout());
        main.setBackground(Theme.BG);
        main.add(buildHeader(), BorderLayout.NORTH);

        content.setBackground(Theme.BG);
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 4, 14));
        main.add(content, BorderLayout.CENTER);
        main.add(buildStatusBar(), BorderLayout.SOUTH);
        return main;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.CARD);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(14, 22, 14, 22)));

        headerTitle.setFont(Theme.title());
        headerTitle.setForeground(Theme.TEXT);
        headerSub.setFont(Theme.font(Font.PLAIN, 12));
        headerSub.setForeground(Theme.MUTED);

        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setOpaque(false);
        headerTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerSub.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(headerTitle);
        left.add(Box.createVerticalStrut(3));
        left.add(headerSub);

        JPanel chips = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        chips.setOpaque(false);
        chips.add(pill("计算内核", NativeCore.isAvailable() ? Theme.OK : Theme.DANGER));
        boolean pythonOk = bridge != null && bridge.isAvailable();
        chips.add(pill("分析服务", pythonOk
                ? (bridge.visionAvailable() ? Theme.OK : Theme.WARN) : Theme.MUTED));

        header.add(left, BorderLayout.WEST);
        header.add(chips, BorderLayout.EAST);
        return header;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Theme.CARD);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(8, 22, 8, 22)));

        JLabel left = new JLabel(coreSummary());
        left.setFont(Theme.font(Font.PLAIN, 12));
        left.setForeground(NativeCore.isAvailable() ? Theme.OK : Theme.DANGER);

        JLabel right = new JLabel(pythonSummary());
        right.setFont(Theme.font(Font.PLAIN, 12));
        if (bridge != null && bridge.isAvailable()) {
            right.setForeground(bridge.visionAvailable() ? Theme.OK : Theme.WARN);
        } else {
            right.setForeground(Theme.MUTED);
        }

        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private String coreSummary() {
        if (NativeCore.isAvailable()) {
            return "C++ 内核：已加载（" + NativeCore.version() + "）";
        }
        return "C++ 内核：未加载（数值计算不可用）";
    }

    private String pythonSummary() {
        if (bridge == null || !bridge.isAvailable()) {
            return "Python 分析服务：不可用（光谱与归纳分析已禁用）";
        }
        if (!bridge.visionAvailable()) {
            return "Python 分析服务：就绪，但缺少 OpenCV/NumPy（光谱分析已禁用）";
        }
        return "Python 分析服务：就绪（" + bridge.interpreter() + "）";
    }

    /** 状态胶囊：圆角浅底 + 彩色圆点 + 文字。 */
    private static JComponent pill(String text, Color dot) {
        Font f = Theme.font(Font.PLAIN, 12);
        int w = metrics(f).stringWidth(text) + 38;
        return new JComponent() {
            {
                setOpaque(false);
                setPreferredSize(new Dimension(w, 26));
                setMaximumSize(new Dimension(w, 26));
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int h = getHeight();
                g2.setColor(Theme.CARD_ALT);
                g2.fillRoundRect(0, 0, getWidth(), h, h, h);
                g2.setColor(Theme.BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, h - 1, h, h);
                g2.setColor(dot);
                g2.fillOval(11, h / 2 - 4, 8, 8);
                g2.setFont(f);
                g2.setColor(Theme.TEXT);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(text, 25, (h - fm.getHeight()) / 2 + fm.getAscent());
                g2.dispose();
            }
        };
    }

    private static FontMetrics metrics(Font f) {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        g.dispose();
        return fm;
    }

    // ------------------------------------------------------------------
    // 面板组装
    // ------------------------------------------------------------------

    private void buildPanels() {
        // 知识库面板的「ima 配置」按钮跳到本模块；ima 面板保存后回通知知识库刷新来源下拉
        KnowledgePanel kbPanel = new KnowledgePanel(() -> select(9));
        ImaPanel imaPanel = new ImaPanel(kbPanel::reloadImaConfig);

        content.add(new NotebookPanel(recordDao, bridge), MODULES[0][0]);
        content.add(new CalcPanel(), MODULES[1][0]);
        content.add(new StatsPanel(), MODULES[2][0]);
        content.add(new CalibrationPanel(calibrationDao), MODULES[3][0]);
        content.add(new SpectrumPanel(bridge), MODULES[4][0]);
        content.add(new ChromPanel(), MODULES[5][0]);
        content.add(new DesignPanel(), MODULES[6][0]);
        content.add(kbPanel, MODULES[7][0]);
        content.add(new ObsidianPanel(recordDao, calibrationDao), MODULES[8][0]);
        content.add(imaPanel, MODULES[9][0]);
    }

    private void select(int index) {
        if (index < 0 || index >= navItems.size()) {
            return;
        }
        for (int i = 0; i < navItems.size(); i++) {
            navItems.get(i).setActive(i == index);
        }
        headerTitle.setText(MODULES[index][0]);
        headerSub.setText(MODULES[index][1]);
        cards.show(content, MODULES[index][0]);
    }

    // ------------------------------------------------------------------

    /** 侧边栏导航项：自绘圆角高亮、强调条、图标与文字。 */
    private static final class NavItem extends JComponent {
        private final String text;
        private final Icon icon;
        private final Runnable onClick;
        private boolean active;
        private boolean hover;

        NavItem(String text, Icon icon, Runnable onClick) {
            this.text = text;
            this.icon = icon;
            this.onClick = onClick;
            setOpaque(false);
            setForeground(Theme.SIDEBAR_TEXT);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            setPreferredSize(new Dimension(238, 44));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    NavItem.this.onClick.run();
                }
            });
        }

        void setActive(boolean active) {
            this.active = active;
            setForeground(active ? Color.WHITE : Theme.SIDEBAR_TEXT);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int inset = 12;
            if (active) {
                g2.setColor(Theme.SIDEBAR_ACTIVE);
                g2.fillRoundRect(inset, 3, w - 2 * inset, h - 6, 10, 10);
                g2.setColor(Theme.ACCENT);
                g2.fillRoundRect(inset, h / 2 - 9, 3, 18, 3, 3);
            } else if (hover) {
                g2.setColor(Theme.SIDEBAR_HOVER);
                g2.fillRoundRect(inset, 3, w - 2 * inset, h - 6, 10, 10);
            }

            int ix = inset + 16;
            int iy = (h - icon.getIconHeight()) / 2;
            icon.paintIcon(this, g2, ix, iy);

            g2.setFont(active ? Theme.font(Font.BOLD, 14) : Theme.font(Font.PLAIN, 14));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, ix + icon.getIconWidth() + 12,
                    (h - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** 主题色板圆点：以该主题强调色填充，当前主题加白环与对勾，悬停时提示名称。 */
    private static final class ThemeSwatch extends JComponent {
        private static final int DOT = 15;
        private final int index;
        private final IntConsumer onChange;
        private boolean hover;

        ThemeSwatch(int index, IntConsumer onChange) {
            this.index = index;
            this.onChange = onChange;
            setOpaque(false);
            setPreferredSize(new Dimension(26, 26));
            setMinimumSize(new Dimension(26, 26));
            setMaximumSize(new Dimension(26, 26));
            setToolTipText(Theme.themeName(index));
            if (onChange == null) {
                return;
            }
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (Theme.currentIndex() != ThemeSwatch.this.index) {
                        ThemeSwatch.this.onChange.accept(ThemeSwatch.this.index);
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            boolean current = Theme.currentIndex() == index;
            int d = DOT;
            int x = (w - d) / 2;
            int y = (h - d) / 2;

            // 外圈：当前主题为实心白环，悬停为半透明浅环
            if (current || hover) {
                g2.setColor(current ? Color.WHITE : new Color(255, 255, 255, 70));
                g2.setStroke(new BasicStroke(current ? 2f : 1.4f));
                g2.drawOval(2, 2, w - 5, h - 5);
            }

            g2.setColor(Theme.themeAccent(index));
            g2.fillOval(x, y, d, d);

            if (current) {
                int cx = w / 2;
                int cy = h / 2;
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g2.drawLine(cx - 3, cy, cx - 1, cy + 2);
                g2.drawLine(cx - 1, cy + 2, cx + 3, cy - 3);
            }
            g2.dispose();
        }
    }
}