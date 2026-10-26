package com.chemlab.ui;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 复选框 / 单选框的圆角图标：选中为实心强调色方块 + 对勾，未选中为细描边空框。
 */
public class FlatCheckIcon implements Icon {

    private static final int SIZE = 18;

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        boolean selected = c instanceof AbstractButton && ((AbstractButton) c).isSelected();
        boolean enabled = c == null || c.isEnabled();

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int s = SIZE - 2;

        if (selected) {
            g2.setColor(enabled ? Theme.ACCENT : Theme.FAINT);
            g2.fillRoundRect(x + 1, y + 1, s, s, 6, 6);
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawPolyline(new int[]{x + 5, x + 8, x + 13},
                    new int[]{y + 9, y + 12, y + 6}, 3);
        } else {
            g2.setColor(Theme.CARD);
            g2.fillRoundRect(x + 1, y + 1, s, s, 6, 6);
            g2.setColor(enabled ? Theme.BORDER_STRONG : Theme.BORDER);
            g2.drawRoundRect(x + 1, y + 1, s - 1, s - 1, 6, 6);
        }
        g2.dispose();
    }
}