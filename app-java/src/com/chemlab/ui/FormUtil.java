package com.chemlab.ui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/** 表单与结果区构建辅助，避免各面板重复堆叠 GridBag 代码。 */
public final class FormUtil {

    private FormUtil() {
    }

    public static JPanel grid() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        return p;
    }

    private static GridBagConstraints gbc(int x, int y, double wx, int fill) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.weightx = wx;
        c.fill = fill;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(4, 4, 4, 4);
        return c;
    }

    /** 添加「标签 + 输入控件」一行。 */
    public static void row(JPanel p, int r, String label, JComponent field) {
        JLabel l = Theme.label(label);
        p.add(l, gbc(0, r, 0, GridBagConstraints.NONE));
        p.add(field, gbc(1, r, 1, GridBagConstraints.HORIZONTAL));
    }

    /** 添加带说明文字的一行。 */
    public static void row(JPanel p, int r, String label, JComponent field, String hint) {
        row(p, r, label, field);
        JLabel h = Theme.muted(hint);
        p.add(h, gbc(2, r, 0, GridBagConstraints.NONE));
    }

    /** 整行说明文字。 */
    public static void note(JPanel p, int r, String text) {
        GridBagConstraints c = gbc(0, r, 1, GridBagConstraints.HORIZONTAL);
        c.gridwidth = 3;
        p.add(Theme.muted(text), c);
    }

    /** 创建一个等宽字体的只读结果区。 */
    public static JTextArea output(int rows) {
        JTextArea ta = new JTextArea(rows, 40);
        ta.setEditable(false);
        ta.setFont(Theme.mono(13));
        ta.setBackground(Theme.CARD);
        ta.setForeground(Theme.TEXT);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10));
        return ta;
    }

    public static JScrollPane outputScroll(int rows) {
        JScrollPane sp = new JScrollPane(output(rows));
        sp.setBorder(javax.swing.BorderFactory.createLineBorder(Theme.BORDER));
        return sp;
    }

    /** 结果区面板：带标题的卡片，内部放只读文本区。 */
    public static JPanel resultCard(String title, JTextArea area) {
        JPanel p = Theme.titledCard(title);
        p.add(new JScrollPane(area), java.awt.BorderLayout.CENTER);
        return p;
    }

    /** 横向排列一组控件的容器。 */
    public static JPanel rowBox(JComponent... comps) {
        JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        for (JComponent c : comps) {
            p.add(c);
        }
        return p;
    }
}