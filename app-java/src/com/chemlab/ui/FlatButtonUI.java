package com.chemlab.ui;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 扁平圆角按钮外观：自绘背景（含 hover / 按下 / 禁用态）与居中文字，
 * 不使用系统 L&F 的立体边框，保证与整体扁平风格一致。
 */
public class FlatButtonUI extends BasicButtonUI {

    private static final FlatButtonUI INSTANCE = new FlatButtonUI();

    public static ComponentUI createUI(JComponent c) {
        return INSTANCE;
    }

    @Override
    protected void installDefaults(AbstractButton b) {
        super.installDefaults(b);
        b.setOpaque(false);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setRolloverEnabled(true);
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        AbstractButton b = (AbstractButton) c;
        int w = b.getWidth();
        int h = b.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Color base = b.getBackground() == null ? Theme.CARD : b.getBackground();
        if (!b.isEnabled()) {
            base = mix(base, Theme.CARD, 0.55f);
        } else if (b.getModel().isPressed()) {
            base = shade(base, 0.86f);
        } else if (b.getModel().isRollover()) {
            base = shade(base, 0.93f);
        }

        g2.setColor(base);
        g2.fillRoundRect(0, 0, w, h, Theme.ARC_CTRL, Theme.ARC_CTRL);

        String text = b.getText();
        if (text != null && !text.isEmpty()) {
            g2.setFont(b.getFont());
            FontMetrics fm = g2.getFontMetrics();
            int tx = (w - fm.stringWidth(text)) / 2;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.setColor(b.isEnabled() ? b.getForeground() : Theme.FAINT);
            g2.drawString(text, tx, ty);
        }
        g2.dispose();
    }

    private static Color shade(Color c, float factor) {
        return new Color(
                clamp((int) (c.getRed() * factor)),
                clamp((int) (c.getGreen() * factor)),
                clamp((int) (c.getBlue() * factor)),
                c.getAlpha());
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(
                clamp((int) (a.getRed() + (b.getRed() - a.getRed()) * t)),
                clamp((int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t)),
                clamp((int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t)),
                a.getAlpha());
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}