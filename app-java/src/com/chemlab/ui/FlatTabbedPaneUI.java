package com.chemlab.ui;

import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * 扁平选项卡外观：白色标签条，选中项为浅色圆角药丸 + 底部强调条，
 * 未选中项为弱化文字，悬停时轻微加深。
 */
public class FlatTabbedPaneUI extends BasicTabbedPaneUI {

    public static ComponentUI createUI(JComponent c) {
        return new FlatTabbedPaneUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        tabPane.setOpaque(true);
        tabPane.setBackground(Theme.CARD);
        tabPane.setForeground(Theme.MUTED);
        tabPane.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 6, 0, 6));
    }

    @Override
    protected Insets getTabInsets(int tabPlacement, int tabIndex) {
        return new Insets(7, 16, 7, 16);
    }

    @Override
    protected Insets getSelectedTabPadInsets(int tabPlacement) {
        return new Insets(0, 0, 0, 0);
    }

    @Override
    protected void paintTabArea(Graphics g, int tabPlacement, int selectedIndex) {
        int height = calculateTabAreaHeight(tabPlacement, runCount, maxTabHeight);
        g.setColor(Theme.CARD);
        g.fillRect(0, 0, tabPane.getWidth(), height);
        super.paintTabArea(g, tabPlacement, selectedIndex);
    }

    @Override
    protected void paintTab(Graphics g, int tabPlacement, Rectangle[] rects, int tabIndex,
                            Rectangle iconRect, Rectangle textRect) {
        Rectangle r = rects[tabIndex];
        boolean selected = tabIndex == tabPane.getSelectedIndex();
        boolean hover = getRolloverTab() == tabIndex;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (selected) {
            g2.setColor(Theme.ACCENT_SOFT);
            g2.fillRoundRect(r.x + 3, r.y + 4, r.width - 6, r.height - 8, 9, 9);
            g2.setColor(Theme.ACCENT);
            g2.fillRoundRect(r.x + r.width / 2 - 13, r.y + r.height - 3, 26, 3, 3, 3);
        } else if (hover) {
            g2.setColor(Theme.CARD_ALT);
            g2.fillRoundRect(r.x + 3, r.y + 4, r.width - 6, r.height - 8, 9, 9);
        }

        String title = tabPane.getTitleAt(tabIndex);
        if (title != null && !title.isEmpty()) {
            g2.setFont(selected ? Theme.bold(13) : Theme.font(Font.PLAIN, 13));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(selected ? Theme.ACCENT : Theme.MUTED);
            int tx = r.x + (r.width - fm.stringWidth(title)) / 2;
            int ty = r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(title, tx, ty);
        }
        g2.dispose();
    }

    @Override
    protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex,
                                  int x, int y, int w, int h, boolean isSelected) {
        // 无描边
    }

    @Override
    protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
        // 无内容区描边
    }

    @Override
    protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects,
                                       int tabIndex, Rectangle iconRect, Rectangle textRect,
                                       boolean isSelected) {
        // 无焦点框
    }
}