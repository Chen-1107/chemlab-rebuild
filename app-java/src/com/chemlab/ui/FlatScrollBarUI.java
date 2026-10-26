package com.chemlab.ui;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * 细长扁平的滚动条外观：隐藏箭头按钮，仅保留圆角滑块，轨道透明。
 *
 * <p>滑块颜色在绘制时从 {@link Theme} 读取，因此切换主题后自动适配。
 */
public class FlatScrollBarUI extends BasicScrollBarUI {

    public static ComponentUI createUI(JComponent c) {
        return new FlatScrollBarUI();
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
        return zeroButton();
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        return zeroButton();
    }

    private JButton zeroButton() {
        JButton b = new JButton();
        Dimension zero = new Dimension(0, 0);
        b.setPreferredSize(zero);
        b.setMinimumSize(zero);
        b.setMaximumSize(zero);
        b.setFocusable(false);
        b.setOpaque(false);
        return b;
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
        // 轨道透明，让父容器底色透出
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
        if (r.width <= 0 || r.height <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(isThumbRollover() || isDragging
                ? Theme.SCROLL_THUMB_HOVER : Theme.SCROLL_THUMB);

        if (scrollbar.getOrientation() == javax.swing.JScrollBar.VERTICAL) {
            int w = Math.min(r.width, 8);
            int x = r.x + (r.width - w) / 2;
            g2.fillRoundRect(x, r.y + 1, w, Math.max(0, r.height - 2), w, w);
        } else {
            int h = Math.min(r.height, 8);
            int y = r.y + (r.height - h) / 2;
            g2.fillRoundRect(r.x + 1, y, Math.max(0, r.width - 2), h, h, h);
        }
        g2.dispose();
    }
}