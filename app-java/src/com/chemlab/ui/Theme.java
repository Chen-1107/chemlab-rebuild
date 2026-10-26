package com.chemlab.ui;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;

/**
 * 统一的配色、字体与控件工厂，保证各面板视觉一致。
 *
 * <p>所有外观常量与控件样式都集中在此：面板只调用工厂方法，不写死颜色，
 * 因此调整主题即可整体换肤。{@link #install()} 还会把自定义的扁平化
 * UI 委托注册到 {@link UIManager}，使面板中直接 new 出来的
 * JTabbedPane / JComboBox / JTable / JScrollPane 等也自动获得统一外观。
 */
public final class Theme {

    // ------------------------------------------------------------------
    // 主题色板
    //
    // 颜色字段全部可变：切换主题时 {@link #setTheme(int)} 整体替换为所选色板，
    // 随后重装全局外观、由调用方重建界面，即可完成整体换肤。面板只引用这些
    // 字段，不写死颜色，因此换肤不需要改动任何面板。
    // ------------------------------------------------------------------

    // ---- 基础 ----
    public static Color BG;
    public static Color CARD;
    public static Color CARD_ALT;
    public static Color BORDER;
    public static Color BORDER_STRONG;
    public static Color TEXT;
    public static Color MUTED;
    public static Color FAINT;

    // ---- 强调色 ----
    public static Color ACCENT;
    public static Color ACCENT_DARK;
    /** 品牌渐变的高亮端（比 ACCENT 更亮）。 */
    public static Color ACCENT_LIGHT;
    public static Color ACCENT_SOFT;
    /** 次级按钮底色。 */
    public static Color SECONDARY_BG;

    // ---- 侧边栏（各主题统一保持深色渐变，形成稳定对比） ----
    public static Color SIDEBAR;
    public static Color SIDEBAR_TOP;
    public static Color SIDEBAR_BOTTOM;
    public static Color SIDEBAR_TEXT;
    public static Color SIDEBAR_ACTIVE;
    public static Color SIDEBAR_HOVER;

    // ---- 语义色 ----
    public static Color OK;
    public static Color WARN;
    public static Color DANGER;
    public static Color OK_SOFT;
    public static Color WARN_SOFT;
    public static Color DANGER_SOFT;

    // ---- 图表 ----
    public static Color CHART_PLOT;
    public static Color CHART_GRID;
    /** 峰位等竖向标记线（半透明红）。 */
    public static Color CHART_MARKER;
    /** 图例浮层底色（半透明）。 */
    public static Color CHART_LEGEND;

    // ---- 滚动条 / 阴影 / 提示气泡 ----
    public static Color SCROLL_THUMB;
    public static Color SCROLL_THUMB_HOVER;
    public static Color SHADOW;
    public static Color TOOLTIP_BG;
    public static Color TOOLTIP_FG;

    public static Color[] SERIES = {};

    /** 卡片圆角半径。 */
    public static final int ARC_CARD = 12;
    /** 按钮 / 输入控件圆角半径。 */
    public static final int ARC_CTRL = 9;

    /** 一套主题的全部颜色；字段可变，便于各色板按需填写。 */
    public static final class Palette {
        public final String name;
        Color bg, card, cardAlt, border, borderStrong, text, muted, faint;
        Color accent, accentDark, accentLight, accentSoft, secondaryBg;
        Color sidebar, sidebarTop, sidebarBottom, sidebarText, sidebarActive, sidebarHover;
        Color ok, warn, danger, okSoft, warnSoft, dangerSoft;
        Color chartPlot, chartGrid, chartMarker, chartLegend;
        Color scrollThumb, scrollThumbHover, shadow, tooltipBg, tooltipFg;
        Color[] series;

        Palette(String name) {
            this.name = name;
        }
    }

    private static final Palette[] THEMES = {
            indigoLight(), inkDark(), tealLight(), amberLight(),
    };
    private static int currentIndex;

    static {
        applyColors(THEMES[0]);
    }

    public static int themeCount() {
        return THEMES.length;
    }

    public static int currentIndex() {
        return currentIndex;
    }

    public static String themeName(int index) {
        return THEMES[index].name;
    }

    /** 色板示意用的强调色（切换器绘制圆点）。 */
    public static Color themeAccent(int index) {
        return THEMES[index].accent;
    }

    /** 色板示意用的侧边栏底色（切换器绘制描边）。 */
    public static Color themeSidebar(int index) {
        return THEMES[index].sidebar;
    }

    /**
     * 切换主题：替换色板并重装全局外观。
     *
     * <p>颜色在面板构造期被捕获（背景、边框等），因此本方法只负责换色与
     * 更新 {@link UIManager}；界面重建由调用方负责（见 {@code Main}）。
     */
    public static void setTheme(int index) {
        if (index < 0 || index >= THEMES.length) {
            return;
        }
        currentIndex = index;
        applyColors(THEMES[index]);
        install();
    }

    private static void applyColors(Palette p) {
        BG = p.bg;
        CARD = p.card;
        CARD_ALT = p.cardAlt;
        BORDER = p.border;
        BORDER_STRONG = p.borderStrong;
        TEXT = p.text;
        MUTED = p.muted;
        FAINT = p.faint;
        ACCENT = p.accent;
        ACCENT_DARK = p.accentDark;
        ACCENT_LIGHT = p.accentLight;
        ACCENT_SOFT = p.accentSoft;
        SECONDARY_BG = p.secondaryBg;
        SIDEBAR = p.sidebar;
        SIDEBAR_TOP = p.sidebarTop;
        SIDEBAR_BOTTOM = p.sidebarBottom;
        SIDEBAR_TEXT = p.sidebarText;
        SIDEBAR_ACTIVE = p.sidebarActive;
        SIDEBAR_HOVER = p.sidebarHover;
        OK = p.ok;
        WARN = p.warn;
        DANGER = p.danger;
        OK_SOFT = p.okSoft;
        WARN_SOFT = p.warnSoft;
        DANGER_SOFT = p.dangerSoft;
        CHART_PLOT = p.chartPlot;
        CHART_GRID = p.chartGrid;
        CHART_MARKER = p.chartMarker;
        CHART_LEGEND = p.chartLegend;
        SCROLL_THUMB = p.scrollThumb;
        SCROLL_THUMB_HOVER = p.scrollThumbHover;
        SHADOW = p.shadow;
        TOOLTIP_BG = p.tooltipBg;
        TOOLTIP_FG = p.tooltipFg;
        SERIES = p.series.clone();
    }

    /** 靛蓝 · 浅色（默认）。 */
    private static Palette indigoLight() {
        Palette p = new Palette("靛蓝");
        p.bg = new Color(0xEE, 0xF1, 0xF7);
        p.card = Color.WHITE;
        p.cardAlt = new Color(0xF8, 0xFA, 0xFC);
        p.border = new Color(0xE3, 0xE8, 0xF0);
        p.borderStrong = new Color(0xD3, 0xDA, 0xE7);
        p.text = new Color(0x0F, 0x17, 0x2A);
        p.muted = new Color(0x64, 0x74, 0x8B);
        p.faint = new Color(0x94, 0xA3, 0xB8);
        p.accent = new Color(0x4F, 0x46, 0xE5);
        p.accentDark = new Color(0x43, 0x38, 0xCA);
        p.accentLight = new Color(0x6A, 0x6D, 0xF5);
        p.accentSoft = new Color(0xEE, 0xEF, 0xFF);
        p.secondaryBg = new Color(0xE9, 0xEC, 0xF3);
        p.sidebar = new Color(0x11, 0x18, 0x27);
        p.sidebarTop = new Color(0x13, 0x1B, 0x2B);
        p.sidebarBottom = new Color(0x0A, 0x10, 0x1C);
        p.sidebarText = new Color(0x9A, 0xA7, 0xBB);
        p.sidebarActive = new Color(0x25, 0x33, 0x4A);
        p.sidebarHover = new Color(0x1B, 0x25, 0x37);
        p.ok = new Color(0x10, 0xB9, 0x81);
        p.warn = new Color(0xF5, 0x9E, 0x0B);
        p.danger = new Color(0xEF, 0x44, 0x44);
        p.okSoft = new Color(0xE7, 0xF8, 0xF1);
        p.warnSoft = new Color(0xFE, 0xF4, 0xE2);
        p.dangerSoft = new Color(0xFE, 0xEC, 0xEC);
        p.chartPlot = new Color(0xFA, 0xFB, 0xFD);
        p.chartGrid = new Color(0xEC, 0xF0, 0xF5);
        p.chartMarker = new Color(0xDC, 0x26, 0x26, 120);
        p.chartLegend = new Color(255, 255, 255, 220);
        p.scrollThumb = new Color(0xC7, 0xCF, 0xDE);
        p.scrollThumbHover = new Color(0x9C, 0xA7, 0xBB);
        p.shadow = new Color(0x0F, 0x17, 0x2A, 7);
        p.tooltipBg = new Color(0x0F, 0x17, 0x2A);
        p.tooltipFg = Color.WHITE;
        p.series = new Color[]{
                new Color(0x4F, 0x46, 0xE5), new Color(0xEF, 0x44, 0x44),
                new Color(0x10, 0xB9, 0x81), new Color(0xF5, 0x9E, 0x0B),
                new Color(0x8B, 0x5C, 0xF6), new Color(0x06, 0xB6, 0xD4),
        };
        return p;
    }

    /** 墨夜 · 深色。 */
    private static Palette inkDark() {
        Palette p = new Palette("墨夜");
        p.bg = new Color(0x0E, 0x13, 0x1F);
        p.card = new Color(0x16, 0x1D, 0x2B);
        p.cardAlt = new Color(0x1B, 0x23, 0x33);
        p.border = new Color(0x25, 0x2F, 0x42);
        p.borderStrong = new Color(0x33, 0x40, 0x5A);
        p.text = new Color(0xE7, 0xEC, 0xF6);
        p.muted = new Color(0x9A, 0xA8, 0xBD);
        p.faint = new Color(0x6B, 0x79, 0x91);
        p.accent = new Color(0x6D, 0x7B, 0xFF);
        p.accentDark = new Color(0x55, 0x63, 0xE8);
        p.accentLight = new Color(0x8E, 0x97, 0xFF);
        p.accentSoft = new Color(0x23, 0x2A, 0x45);
        p.secondaryBg = new Color(0x24, 0x2D, 0x40);
        p.sidebar = new Color(0x0A, 0x0E, 0x17);
        p.sidebarTop = new Color(0x0F, 0x16, 0x24);
        p.sidebarBottom = new Color(0x06, 0x09, 0x11);
        p.sidebarText = new Color(0x8E, 0x9B, 0xB1);
        p.sidebarActive = new Color(0x1E, 0x2A, 0x40);
        p.sidebarHover = new Color(0x14, 0x1C, 0x2B);
        p.ok = new Color(0x2B, 0xD4, 0xA0);
        p.warn = new Color(0xF5, 0xB4, 0x3C);
        p.danger = new Color(0xFF, 0x6B, 0x6B);
        p.okSoft = new Color(0x15, 0x30, 0x2A);
        p.warnSoft = new Color(0x33, 0x2A, 0x16);
        p.dangerSoft = new Color(0x3A, 0x1F, 0x23);
        p.chartPlot = new Color(0x12, 0x19, 0x25);
        p.chartGrid = new Color(0x22, 0x2C, 0x3D);
        p.chartMarker = new Color(0xFF, 0x6E, 0x6E, 150);
        p.chartLegend = new Color(0x16, 0x1D, 0x2B, 228);
        p.scrollThumb = new Color(0x3A, 0x46, 0x5C);
        p.scrollThumbHover = new Color(0x4E, 0x5C, 0x76);
        p.shadow = new Color(0x00, 0x00, 0x00, 90);
        p.tooltipBg = new Color(0x2A, 0x35, 0x4A);
        p.tooltipFg = new Color(0xE7, 0xEC, 0xF6);
        p.series = new Color[]{
                new Color(0x7C, 0x88, 0xFF), new Color(0xFF, 0x6B, 0x6B),
                new Color(0x2B, 0xD4, 0xA0), new Color(0xF5, 0xB4, 0x3C),
                new Color(0xA9, 0x8B, 0xFF), new Color(0x35, 0xC8, 0xE8),
        };
        return p;
    }

    /** 青碧 · 浅色（青绿强调色）。 */
    private static Palette tealLight() {
        Palette p = new Palette("青碧");
        p.bg = new Color(0xEC, 0xF3, 0xF3);
        p.card = Color.WHITE;
        p.cardAlt = new Color(0xF6, 0xFB, 0xFA);
        p.border = new Color(0xDD, 0xE8, 0xE7);
        p.borderStrong = new Color(0xC9, 0xDA, 0xD8);
        p.text = new Color(0x0C, 0x1B, 0x1A);
        p.muted = new Color(0x55, 0x70, 0x6E);
        p.faint = new Color(0x88, 0xA0, 0x9E);
        p.accent = new Color(0x0E, 0x9F, 0x8E);
        p.accentDark = new Color(0x0A, 0x7F, 0x72);
        p.accentLight = new Color(0x33, 0xC0, 0xAE);
        p.accentSoft = new Color(0xE1, 0xF5, 0xF1);
        p.secondaryBg = new Color(0xE4, 0xEE, 0xED);
        p.sidebar = new Color(0x0A, 0x1E, 0x24);
        p.sidebarTop = new Color(0x0D, 0x28, 0x2F);
        p.sidebarBottom = new Color(0x06, 0x14, 0x19);
        p.sidebarText = new Color(0x92, 0xAF, 0xB2);
        p.sidebarActive = new Color(0x14, 0x36, 0x3C);
        p.sidebarHover = new Color(0x0F, 0x28, 0x2E);
        p.ok = new Color(0x0E, 0xB0, 0x77);
        p.warn = new Color(0xE8, 0x94, 0x0A);
        p.danger = new Color(0xE0, 0x3E, 0x3E);
        p.okSoft = new Color(0xE2, 0xF7, 0xEE);
        p.warnSoft = new Color(0xFC, 0xF2, 0xDF);
        p.dangerSoft = new Color(0xFC, 0xEA, 0xEA);
        p.chartPlot = new Color(0xF8, 0xFC, 0xFB);
        p.chartGrid = new Color(0xE6, 0xF0, 0xEE);
        p.chartMarker = new Color(0xD1, 0x2A, 0x2A, 120);
        p.chartLegend = new Color(255, 255, 255, 220);
        p.scrollThumb = new Color(0xBF, 0xD3, 0xD1);
        p.scrollThumbHover = new Color(0x93, 0xAE, 0xAC);
        p.shadow = new Color(0x0C, 0x1B, 0x1A, 7);
        p.tooltipBg = new Color(0x0C, 0x1B, 0x1A);
        p.tooltipFg = Color.WHITE;
        p.series = new Color[]{
                new Color(0x0E, 0x9F, 0x8E), new Color(0xE0, 0x3E, 0x3E),
                new Color(0x2F, 0x7D, 0xD1), new Color(0xE8, 0x94, 0x0A),
                new Color(0x7A, 0x5C, 0xD6), new Color(0x0F, 0x9E, 0xC4),
        };
        return p;
    }

    /** 暖阳 · 浅色（琥珀强调色）。 */
    private static Palette amberLight() {
        Palette p = new Palette("暖阳");
        p.bg = new Color(0xF6, 0xF1, 0xE9);
        p.card = Color.WHITE;
        p.cardAlt = new Color(0xFB, 0xF7, 0xF0);
        p.border = new Color(0xEB, 0xE1, 0xD2);
        p.borderStrong = new Color(0xDC, 0xCD, 0xB8);
        p.text = new Color(0x23, 0x1A, 0x12);
        p.muted = new Color(0x7A, 0x69, 0x56);
        p.faint = new Color(0xA3, 0x94, 0x80);
        p.accent = new Color(0xE0, 0x6B, 0x0A);
        p.accentDark = new Color(0xBE, 0x57, 0x06);
        p.accentLight = new Color(0xF2, 0x92, 0x3A);
        p.accentSoft = new Color(0xFD, 0xF0, 0xE1);
        p.secondaryBg = new Color(0xF1, 0xE9, 0xDD);
        p.sidebar = new Color(0x24, 0x19, 0x11);
        p.sidebarTop = new Color(0x2D, 0x20, 0x16);
        p.sidebarBottom = new Color(0x18, 0x10, 0x0A);
        p.sidebarText = new Color(0xBD, 0xA8, 0x92);
        p.sidebarActive = new Color(0x40, 0x2E, 0x1F);
        p.sidebarHover = new Color(0x30, 0x23, 0x18);
        p.ok = new Color(0x11, 0xA5, 0x6E);
        p.warn = new Color(0xD8, 0x8A, 0x08);
        p.danger = new Color(0xD8, 0x3A, 0x3A);
        p.okSoft = new Color(0xE6, 0xF6, 0xEE);
        p.warnSoft = new Color(0xFB, 0xF0, 0xDD);
        p.dangerSoft = new Color(0xFA, 0xEA, 0xE8);
        p.chartPlot = new Color(0xFD, 0xFA, 0xF5);
        p.chartGrid = new Color(0xF1, 0xE8, 0xDB);
        p.chartMarker = new Color(0xCC, 0x2A, 0x2A, 120);
        p.chartLegend = new Color(255, 255, 255, 222);
        p.scrollThumb = new Color(0xD6, 0xC7, 0xB2);
        p.scrollThumbHover = new Color(0xB5, 0xA1, 0x87);
        p.shadow = new Color(0x23, 0x1A, 0x12, 7);
        p.tooltipBg = new Color(0x23, 0x1A, 0x12);
        p.tooltipFg = Color.WHITE;
        p.series = new Color[]{
                new Color(0xE0, 0x6B, 0x0A), new Color(0x2F, 0x7D, 0xD1),
                new Color(0x11, 0xA5, 0x6E), new Color(0xD8, 0x3A, 0x3A),
                new Color(0x8A, 0x5C, 0xC9), new Color(0x0F, 0x9E, 0xC4),
        };
        return p;
    }

    /**
     * 逻辑字体族。物理字体（如 Microsoft YaHei UI、Consolas）缺失字形时不会回退，
     * 会导致下标（c₁、μ₀）、上下标（10⁻⁵）与箭头（◀ ▶）显示为豆腐块；
     * 逻辑字体族在 Windows 上以现代无衬线渲染中文，并自动为这些符号回退补字。
     */
    private static final String UI_FAMILY = "SansSerif";

    private Theme() {
    }

    public static Font font(int style, int size) {
        return new Font(UI_FAMILY, style, size);
    }

    public static Font base() {
        return font(Font.PLAIN, 13);
    }

    public static Font bold(int size) {
        return font(Font.BOLD, size);
    }

    public static Font title() {
        return font(Font.BOLD, 20);
    }

    public static Font section() {
        return font(Font.BOLD, 14);
    }

    /** 等宽字体：ASCII 等宽便于结果区对齐，逻辑字体族同时覆盖中文与上下标符号。 */
    public static Font mono(int size) {
        return new Font("Monospaced", Font.PLAIN, size);
    }

    // ------------------------------------------------------------------
    // 全局外观安装
    // ------------------------------------------------------------------

    public static void install() {
        Font base = base();

        String[] fontKeys = {
                "Label.font", "Button.font", "TextField.font", "TextArea.font",
                "ComboBox.font", "Table.font", "List.font", "TabbedPane.font",
                "CheckBox.font", "RadioButton.font", "ToggleButton.font",
                "OptionPane.messageFont", "OptionPane.buttonFont", "Menu.font",
                "MenuItem.font", "ToolTip.font", "Spinner.font",
                "FormattedTextField.font", "PasswordField.font", "EditorPane.font",
        };
        for (String k : fontKeys) {
            UIManager.put(k, base);
        }
        UIManager.put("TableHeader.font", bold(13));
        UIManager.put("TitledBorder.font", bold(13));
        UIManager.put("TitledBorder.titleColor", TEXT);

        // 基础面板与文字
        UIManager.put("Panel.background", BG);
        UIManager.put("OptionPane.background", CARD);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Separator.foreground", BORDER);

        // 自定义 UI 委托（面板中直接 new 的控件也会被覆盖）
        UIManager.put("ButtonUI", FlatButtonUI.class.getName());
        UIManager.put("ScrollBarUI", FlatScrollBarUI.class.getName());
        UIManager.put("TabbedPaneUI", FlatTabbedPaneUI.class.getName());
        UIManager.put("ComboBoxUI", FlatComboBoxUI.class.getName());
        UIManager.put("TableHeaderUI", "javax.swing.plaf.basic.BasicTableHeaderUI");
        UIManager.put("CheckBox.icon", new FlatCheckIcon());
        UIManager.put("RadioButton.icon", new FlatCheckIcon());

        // 按钮
        UIManager.put("Button.background", CARD);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.select", ACCENT_SOFT);
        UIManager.put("Button.focus", new Color(0, 0, 0, 0));
        UIManager.put("Button.defaultButtonFollowsFocus", Boolean.FALSE);

        // 文本输入
        String[] textKeys = {
                "TextField", "TextArea", "PasswordField",
                "FormattedTextField", "EditorPane", "TextPane",
        };
        for (String k : textKeys) {
            UIManager.put(k + ".background", CARD);
            UIManager.put(k + ".foreground", TEXT);
            UIManager.put(k + ".caretColor", ACCENT);
            UIManager.put(k + ".selectionColor", ACCENT);
            UIManager.put(k + ".selectedTextColor", Color.WHITE);
            UIManager.put(k + ".inactiveForeground", TEXT);
            UIManager.put(k + ".border", inputBorder());
        }

        // 表格
        UIManager.put("Table.background", CARD);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.gridColor", BORDER);
        UIManager.put("Table.rowHeight", 28);
        UIManager.put("Table.selectionBackground", ACCENT_SOFT);
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("Table.showHorizontalLines", Boolean.TRUE);
        UIManager.put("Table.showVerticalLines", Boolean.FALSE);
        UIManager.put("Table.intercellSpacing", new Dimension(0, 0));
        Border cellPad = new EmptyBorder(4, 10, 4, 10);
        UIManager.put("Table.cellNoFocusBorder", cellPad);
        UIManager.put("Table.focusCellHighlightBorder", cellPad);
        UIManager.put("TableHeader.background", CARD_ALT);
        UIManager.put("TableHeader.foreground", MUTED);
        UIManager.put("TableHeader.cellBorder", BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER),
                new EmptyBorder(7, 10, 7, 10)));

        // 列表
        UIManager.put("List.background", CARD);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("List.selectionBackground", ACCENT_SOFT);
        UIManager.put("List.selectionForeground", TEXT);
        UIManager.put("List.focusCellHighlightBorder", cellPad);

        // 下拉框
        UIManager.put("ComboBox.background", CARD);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", ACCENT_SOFT);
        UIManager.put("ComboBox.selectionForeground", TEXT);
        UIManager.put("ComboBox.buttonBackground", CARD);
        UIManager.put("ComboBox.border", inputBorder());

        // 选项卡
        UIManager.put("TabbedPane.background", CARD);
        UIManager.put("TabbedPane.foreground", MUTED);
        UIManager.put("TabbedPane.selected", ACCENT);
        UIManager.put("TabbedPane.contentAreaColor", CARD);
        UIManager.put("TabbedPane.tabAreaBackground", CARD);
        Color none = new Color(0, 0, 0, 0);
        for (String k : new String[]{"TabbedPane.focus", "TabbedPane.highlight",
                "TabbedPane.light", "TabbedPane.shadow", "TabbedPane.darkShadow",
                "TabbedPane.borderHightlightColor"}) {
            UIManager.put(k, none);
        }
        UIManager.put("TabbedPane.contentBorderInsets", new Insets(0, 0, 0, 0));

        // 滚动条
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("ScrollBar.thumb", SCROLL_THUMB);
        UIManager.put("ScrollBar.thumbDarkShadow", none);
        UIManager.put("ScrollBar.thumbHighlight", none);
        UIManager.put("ScrollBar.thumbShadow", none);
        UIManager.put("ScrollBar.track", none);
        UIManager.put("ScrollBar.trackHighlight", none);
        UIManager.put("ScrollBar.background", null);
        UIManager.put("ScrollPane.background", CARD);
        UIManager.put("ScrollPane.border", new EmptyBorder(0, 0, 0, 0));
        UIManager.put("Viewport.background", CARD);

        // 分隔条
        UIManager.put("SplitPane.background", BG);
        UIManager.put("SplitPane.dividerSize", 8);
        UIManager.put("SplitPane.border", new EmptyBorder(0, 0, 0, 0));
        UIManager.put("SplitPaneDivider.border", new EmptyBorder(0, 0, 0, 0));

        // 复选框
        UIManager.put("CheckBox.background", CARD);
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("CheckBox.focus", none);
        UIManager.put("RadioButton.background", CARD);
        UIManager.put("RadioButton.foreground", TEXT);
        UIManager.put("RadioButton.focus", none);

        // 提示气泡
        UIManager.put("ToolTip.background", TOOLTIP_BG);
        UIManager.put("ToolTip.foreground", TOOLTIP_FG);
        UIManager.put("ToolTip.border", new EmptyBorder(5, 9, 5, 9));
        UIManager.put("ToolTip.font", font(Font.PLAIN, 12));

        // 菜单
        UIManager.put("Menu.background", CARD);
        UIManager.put("Menu.foreground", TEXT);
        UIManager.put("MenuItem.background", CARD);
        UIManager.put("MenuItem.foreground", TEXT);
        UIManager.put("MenuItem.selectionBackground", ACCENT_SOFT);
        UIManager.put("MenuItem.selectionForeground", TEXT);
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(BORDER));
    }

    // ------------------------------------------------------------------
    // 控件工厂
    // ------------------------------------------------------------------

    /** 输入类控件的统一边框：细描边 + 内边距。 */
    public static Border inputBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_STRONG),
                new EmptyBorder(6, 10, 6, 10));
    }

    public static Border cardBorder() {
        return new EmptyBorder(14, 16, 14, 16);
    }

    /** 圆角白卡片（带极浅投影）。 */
    public static JPanel card() {
        return new RoundedPanel(CARD, ARC_CARD, true, 14, 16, 14, 16);
    }

    /** 带标题的圆角卡片，内容加到 CENTER。 */
    public static JPanel titledCard(String title) {
        JPanel p = card();
        p.setLayout(new java.awt.BorderLayout(0, 10));
        JLabel l = new JLabel(title);
        l.setFont(section());
        l.setForeground(TEXT);
        p.add(l, java.awt.BorderLayout.NORTH);
        return p;
    }

    public static JButton primary(String text) {
        return button(text, ACCENT, Color.WHITE);
    }

    public static JButton secondary(String text) {
        return button(text, SECONDARY_BG, TEXT);
    }

    public static JButton danger(String text) {
        return button(text, DANGER_SOFT, DANGER);
    }

    private static JButton button(String text, Color bg, Color fg) {
        JButton b = new JButton(text);
        b.setFont(font(Font.BOLD, 13));
        b.setBackground(bg);
        b.setForeground(fg);
        b.setFocusPainted(false);
        b.setBorderPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setBorder(new EmptyBorder(8, 18, 8, 18));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return b;
    }

    public static JTextField field(int columns) {
        JTextField f = new JTextField(columns);
        f.setFont(base());
        f.setBorder(inputBorder());
        return f;
    }

    public static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(base());
        l.setForeground(TEXT);
        return l;
    }

    public static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setFont(font(Font.PLAIN, 12));
        l.setForeground(MUTED);
        return l;
    }

    public static JScrollPane scroll(JComponent c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(new EmptyBorder(0, 0, 0, 0));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        return sp;
    }

    public static void fixHeight(JComponent c, int h) {
        Dimension d = c.getPreferredSize();
        c.setPreferredSize(new Dimension(d.width, h));
    }
}