package com.chemlab.ui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.LookAndFeel;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 扁平下拉框外观：细描边圆角框 + 自绘的下拉箭头，去掉系统 L&F 的立体箭头按钮。
 */
public class FlatComboBoxUI extends BasicComboBoxUI {

    public static ComponentUI createUI(JComponent c) {
        return new FlatComboBoxUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        comboBox.setBorder(Theme.inputBorder());
        comboBox.setFont(Theme.base());
        LookAndFeel.installColors(comboBox, "ComboBox.background", "ComboBox.foreground");
    }

    @Override
    protected JButton createArrowButton() {
        return new ArrowButton();
    }

    private static final class ArrowButton extends JButton {
        ArrowButton() {
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setPreferredSize(new Dimension(22, 22));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.MUTED);
            g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            g2.drawLine(cx - 4, cy - 1, cx, cy + 3);
            g2.drawLine(cx, cy + 3, cx + 4, cy - 1);
            g2.dispose();
        }
    }
}