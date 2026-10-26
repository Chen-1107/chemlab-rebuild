package com.chemlab.ui;

import javax.swing.Icon;
import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * 导航与品牌图标：用 Java2D 手绘矢量字形，避免引入图标字体或图片资源，
 * 颜色取自所在组件的前景色，因此可随选中 / 悬停状态自动变化。
 */
public final class Icons {

    private Icons() {
    }

    private interface Painter {
        void paint(Graphics2D g);
    }

    private static Icon make(int size, Painter p) {
        return new Icon() {
            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                Color col = c instanceof JComponent && c.getForeground() != null
                        ? c.getForeground() : Theme.TEXT;
                g2.setColor(col);
                p.paint(g2);
                g2.dispose();
            }
        };
    }

    private static BasicStroke stroke(float w) {
        return new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    }

    /** 侧边栏第 index 个模块的图标（0..9）。 */
    public static Icon nav(int index) {
        switch (index) {
            case 0:
                return make(18, g -> {                 // 实验记录本
                    g.setStroke(stroke(1.6f));
                    g.drawRoundRect(3, 2, 12, 14, 3, 3);
                    g.drawLine(6, 2, 6, 16);
                    g.drawLine(8, 6, 13, 6);
                    g.drawLine(8, 9, 13, 9);
                    g.drawLine(8, 12, 11, 12);
                });
            case 1:
                return make(18, g -> {                 // 基础计算
                    g.setStroke(stroke(1.6f));
                    g.drawRoundRect(3, 2, 12, 14, 3, 3);
                    g.fillRoundRect(5, 4, 8, 3, 1, 1);
                    g.fillRect(5, 9, 2, 2);
                    g.fillRect(8, 9, 2, 2);
                    g.fillRect(11, 9, 2, 2);
                    g.fillRect(5, 12, 2, 2);
                    g.fillRect(8, 12, 2, 2);
                    g.fillRect(11, 12, 2, 2);
                });
            case 2:
                return make(18, g -> {                 // 统计分析
                    g.fillRoundRect(3, 10, 3, 6, 1, 1);
                    g.fillRoundRect(7, 5, 3, 11, 1, 1);
                    g.fillRoundRect(11, 8, 3, 8, 1, 1);
                });
            case 3:
                return make(18, g -> {                 // 标定曲线
                    g.setStroke(stroke(1.6f));
                    g.drawLine(3, 3, 3, 15);
                    g.drawLine(3, 15, 16, 15);
                    g.setStroke(stroke(1.8f));
                    g.drawPolyline(new int[]{4, 7, 10, 13, 15},
                            new int[]{12, 9, 10, 6, 4}, 5);
                    g.fillOval(2, 10, 3, 3);
                    g.fillOval(8, 7, 3, 3);
                    g.fillOval(13, 2, 3, 3);
                });
            case 4:
                return make(18, g -> {                 // 光谱分析
                    g.setStroke(stroke(1.8f));
                    g.drawPolyline(new int[]{2, 5, 7, 9, 11, 13, 16},
                            new int[]{13, 11, 4, 11, 8, 13, 10}, 7);
                });
            case 5:
                return make(18, g -> {                 // 色谱分析
                    g.setStroke(stroke(1.4f));
                    g.drawLine(2, 15, 16, 15);
                    g.drawPolyline(new int[]{3, 5, 6, 7, 8, 10, 11, 12, 13, 15},
                            new int[]{14, 13, 5, 13, 14, 13, 4, 12, 14, 14}, 10);
                });
            case 6:
                return make(18, g -> {                 // 实验设计
                    g.setStroke(stroke(1.5f));
                    g.drawRoundRect(2, 2, 14, 14, 2, 2);
                    g.drawLine(6, 2, 6, 16);
                    g.drawLine(11, 2, 11, 16);
                    g.drawLine(2, 6, 16, 6);
                    g.drawLine(2, 11, 16, 11);
                });
            case 7:
                return make(18, g -> {                 // 知识库
                    g.setStroke(stroke(1.6f));
                    g.drawRoundRect(3, 3, 12, 4, 1, 1);
                    g.drawRoundRect(3, 8, 12, 4, 1, 1);
                    g.drawRoundRect(3, 13, 12, 3, 1, 1);
                });
            case 8:
                return make(18, g -> {                 // Obsidian 同步
                    g.setStroke(stroke(1.7f));
                    g.drawLine(4, 6, 13, 6);
                    g.fillPolygon(new int[]{13, 9, 9}, new int[]{6, 3, 9}, 3);
                    g.drawLine(5, 12, 14, 12);
                    g.fillPolygon(new int[]{5, 9, 9}, new int[]{12, 9, 15}, 3);
                });
            default:
                return make(18, g -> {                 // ima 知识库（外部连接）
                    g.setStroke(stroke(1.5f));
                    g.drawLine(4, 12, 9, 5);
                    g.drawLine(9, 5, 14, 12);
                    g.drawLine(4, 12, 14, 12);
                    g.fillOval(2, 10, 4, 4);
                    g.fillOval(7, 3, 4, 4);
                    g.fillOval(12, 10, 4, 4);
                });
        }
    }

    /** 品牌图标：实验室烧瓶。 */
    public static Icon logo(int size) {
        return make(size, g -> {
            g.setStroke(stroke(1.7f));
            g.drawLine(7, 2, 7, 7);
            g.drawLine(11, 2, 11, 7);
            g.drawLine(7, 7, 3, 15);
            g.drawLine(11, 7, 15, 15);
            g.drawLine(3, 15, 15, 15);
            g.drawLine(5, 11, 13, 11);
        });
    }
}