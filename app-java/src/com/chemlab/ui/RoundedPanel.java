package com.chemlab.ui;

import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 圆角面板：以自身绘制代替直角背景，可选极浅投影，营造卡片层次。
 *
 * <p>投影绘制在组件内边缘预留的留白里，因此边框同时充当「外阴影 + 内边距」。
 */
public class RoundedPanel extends JPanel {

    private final Color fill;
    private final int arc;
    private final int margin;

    public RoundedPanel(Color fill, int arc, boolean shadow,
                        int padTop, int padLeft, int padBottom, int padRight) {
        this.fill = fill;
        this.arc = arc;
        this.margin = shadow ? 4 : 0;
        setOpaque(false);
        setBorder(new EmptyBorder(padTop + margin, padLeft + margin,
                padBottom + margin, padRight + margin));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int m = margin;
        int w = getWidth() - 2 * m;
        int h = getHeight() - 2 * m;
        if (w <= 0 || h <= 0) {
            g2.dispose();
            return;
        }

        if (margin > 0) {
            for (int i = margin - 1; i >= 0; i--) {
                g2.setColor(Theme.SHADOW);
                g2.fillRoundRect(m - i, m - i + 1, w + 2 * i, h + 2 * i,
                        arc + 2 * i, arc + 2 * i);
            }
        }

        g2.setColor(fill);
        g2.fillRoundRect(m, m, w, h, arc, arc);
        g2.setColor(Theme.BORDER);
        g2.drawRoundRect(m, m, w - 1, h - 1, arc, arc);
        g2.dispose();

        super.paintComponent(g);
    }
}