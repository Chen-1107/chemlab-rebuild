package com.chemlab.ui;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻量绘图组件：折线 / 散点 / 柱状，支持多序列、自动刻度、图例与竖向标记。
 *
 * <p>不使用任何第三方图表库——项目只依赖 SQLite JDBC，自绘可避免额外依赖，
 * 也便于统一视觉风格。按用户偏好，数据点上不标注数值，避免遮挡曲线。
 */
public class ChartPanel extends JPanel {

    public enum Type { LINE, SCATTER, BAR }

    /** 一条数据序列。x 与 y 长度需一致。 */
    public static class Series {
        public final String name;
        public final Color color;
        public final Type type;
        public final double[] x;
        public final double[] y;

        public Series(String name, Color color, Type type, double[] x, double[] y) {
            this.name = name;
            this.color = color;
            this.type = type;
            this.x = x;
            this.y = y;
        }
    }

    private static class Marker {
        final double x;
        final String label;

        Marker(double x, String label) {
            this.x = x;
            this.label = label;
        }
    }

    private final List<Series> series = new ArrayList<>();
    private final List<Marker> markers = new ArrayList<>();
    private String title = "";
    private String xLabel = "";
    private String yLabel = "";

    private static final int MARGIN_LEFT = 62;
    private static final int MARGIN_RIGHT = 18;
    private static final int MARGIN_TOP = 34;
    private static final int MARGIN_BOTTOM = 44;

    public ChartPanel() {
        setBackground(Theme.CARD);
        setFont(Theme.base());
    }

    public void clear() {
        series.clear();
        markers.clear();
        repaint();
    }

    public void addSeries(Series s) {
        if (s != null && s.x != null && s.y != null && s.x.length == s.y.length) {
            series.add(s);
        }
        repaint();
    }

    public void addMarker(double x, String label) {
        markers.add(new Marker(x, label));
        repaint();
    }

    public void setLabels(String title, String xLabel, String yLabel) {
        this.title = title == null ? "" : title;
        this.xLabel = xLabel == null ? "" : xLabel;
        this.yLabel = yLabel == null ? "" : yLabel;
        repaint();
    }

    public boolean hasData() {
        return !series.isEmpty();
    }

    // ------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth();
        int h = getHeight();

        if (series.isEmpty()) {
            g2.setColor(Theme.MUTED);
            g2.setFont(Theme.base());
            String msg = "暂无数据";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2);
            g2.dispose();
            return;
        }

        double[] range = computeRange();
        double xMin = range[0], xMax = range[1], yMin = range[2], yMax = range[3];

        int plotW = w - MARGIN_LEFT - MARGIN_RIGHT;
        int plotH = h - MARGIN_TOP - MARGIN_BOTTOM;
        if (plotW < 40 || plotH < 30) {
            g2.dispose();
            return;
        }

        if (title != null && !title.isEmpty()) {
            g2.setColor(Theme.TEXT);
            g2.setFont(Theme.bold(14));
            g2.drawString(title, MARGIN_LEFT, 20);
        }

        // 绘图区底色
        g2.setColor(Theme.CHART_PLOT);
        g2.fillRect(MARGIN_LEFT, MARGIN_TOP, plotW, plotH);

        g2.setFont(Theme.font(java.awt.Font.PLAIN, 11));
        g2.setColor(Theme.MUTED);

        double[] xTicks = niceTicks(xMin, xMax, Math.max(3, plotW / 90));
        double[] yTicks = niceTicks(yMin, yMax, Math.max(3, plotH / 55));

        // 网格
        g2.setColor(Theme.CHART_GRID);
        for (double t : xTicks) {
            int px = mapX(t, xMin, xMax, plotW);
            g2.drawLine(px, MARGIN_TOP, px, MARGIN_TOP + plotH);
        }
        for (double t : yTicks) {
            int py = mapY(t, yMin, yMax, plotH);
            g2.drawLine(MARGIN_LEFT, py, MARGIN_LEFT + plotW, py);
        }

        // 坐标轴
        g2.setColor(Theme.BORDER);
        g2.drawRect(MARGIN_LEFT, MARGIN_TOP, plotW, plotH);

        // 刻度文字
        g2.setColor(Theme.MUTED);
        FontMetrics fm = g2.getFontMetrics();
        for (double t : xTicks) {
            int px = mapX(t, xMin, xMax, plotW);
            String s = fmt(t);
            g2.drawString(s, px - fm.stringWidth(s) / 2, MARGIN_TOP + plotH + 16);
        }
        for (double t : yTicks) {
            int py = mapY(t, yMin, yMax, plotH);
            String s = fmt(t);
            g2.drawString(s, MARGIN_LEFT - 8 - fm.stringWidth(s), py + 4);
        }

        // 轴标题
        g2.setColor(Theme.MUTED);
        if (xLabel != null && !xLabel.isEmpty()) {
            g2.drawString(xLabel, MARGIN_LEFT + plotW / 2 - fm.stringWidth(xLabel) / 2,
                    h - 10);
        }
        if (yLabel != null && !yLabel.isEmpty()) {
            java.awt.geom.AffineTransform old = g2.getTransform();
            g2.rotate(-Math.PI / 2);
            g2.drawString(yLabel, -(MARGIN_TOP + plotH / 2 + fm.stringWidth(yLabel) / 2), 16);
            g2.setTransform(old);
        }

        // 竖向标记（如峰位）
        for (Marker m : markers) {
            int px = mapX(m.x, xMin, xMax, plotW);
            if (px < MARGIN_LEFT || px > MARGIN_LEFT + plotW) {
                continue;
            }
            g2.setColor(Theme.CHART_MARKER);
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    1f, new float[]{4f, 4f}, 0f));
            g2.drawLine(px, MARGIN_TOP, px, MARGIN_TOP + plotH);
            g2.setStroke(new BasicStroke(1f));
            if (m.label != null && !m.label.isEmpty()) {
                g2.setColor(Theme.DANGER);
                g2.drawString(m.label, px + 3, MARGIN_TOP + 12);
            }
        }

        // 数据序列
        for (Series s : series) {
            g2.setColor(s.color);
            if (s.type == Type.BAR) {
                drawBars(g2, s, xMin, xMax, yMin, yMax, plotW, plotH);
            } else if (s.type == Type.LINE) {
                drawLine(g2, s, xMin, xMax, yMin, yMax, plotW, plotH);
            } else {
                drawScatter(g2, s, xMin, xMax, yMin, yMax, plotW, plotH);
            }
        }

        drawLegend(g2, w);

        g2.dispose();
    }

    private void drawLine(Graphics2D g2, Series s, double xMin, double xMax,
                          double yMin, double yMax, int plotW, int plotH) {
        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Integer prevX = null, prevY = null;
        for (int i = 0; i < s.x.length; i++) {
            int px = mapX(s.x[i], xMin, xMax, plotW);
            int py = mapY(s.y[i], yMin, yMax, plotH);
            if (prevX != null) {
                g2.drawLine(prevX, prevY, px, py);
            }
            prevX = px;
            prevY = py;
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawScatter(Graphics2D g2, Series s, double xMin, double xMax,
                             double yMin, double yMax, int plotW, int plotH) {
        for (int i = 0; i < s.x.length; i++) {
            int px = mapX(s.x[i], xMin, xMax, plotW);
            int py = mapY(s.y[i], yMin, yMax, plotH);
            g2.fill(new Ellipse2D.Double(px - 3, py - 3, 6, 6));
        }
    }

    private void drawBars(Graphics2D g2, Series s, double xMin, double xMax,
                          double yMin, double yMax, int plotW, int plotH) {
        if (s.x.length == 0) {
            return;
        }
        double span = xMax - xMin;
        double barW = Math.max(3, Math.min(40, plotW / (double) s.x.length * 0.62));
        int baseY = mapY(Math.max(yMin, 0), yMin, yMax, plotH);
        for (int i = 0; i < s.x.length; i++) {
            int px = mapX(s.x[i], xMin, xMax, plotW);
            int py = mapY(s.y[i], yMin, yMax, plotH);
            int hgt = Math.abs(baseY - py);
            g2.fillRect((int) (px - barW / 2), Math.min(baseY, py), (int) barW, hgt);
        }
        if (span <= 0) {
            return;
        }
    }

    private void drawLegend(Graphics2D g2, int w) {
        if (series.size() <= 1 && (series.isEmpty() || series.get(0).name == null
                || series.get(0).name.isEmpty())) {
            return;
        }
        g2.setFont(Theme.font(java.awt.Font.PLAIN, 11));
        FontMetrics fm = g2.getFontMetrics();
        int pad = 8, lineH = 16;
        int maxW = 0;
        for (Series s : series) {
            maxW = Math.max(maxW, fm.stringWidth(s.name == null ? "" : s.name));
        }
        int boxW = maxW + 34;
        int boxH = series.size() * lineH + pad;
        int x = w - MARGIN_RIGHT - boxW;
        int y = MARGIN_TOP + 6;

        g2.setColor(Theme.CHART_LEGEND);
        g2.fillRoundRect(x, y, boxW, boxH, 6, 6);
        g2.setColor(Theme.BORDER);
        g2.drawRoundRect(x, y, boxW, boxH, 6, 6);

        int ty = y + pad + 2;
        for (Series s : series) {
            g2.setColor(s.color);
            if (s.type == Type.LINE) {
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new Line2D.Double(x + 8, ty + 5, x + 24, ty + 5));
                g2.setStroke(new BasicStroke(1f));
            } else if (s.type == Type.BAR) {
                g2.fillRect(x + 8, ty, 16, 9);
            } else {
                g2.fill(new Ellipse2D.Double(x + 12, ty + 1, 7, 7));
            }
            g2.setColor(Theme.TEXT);
            g2.drawString(s.name == null ? "" : s.name, x + 30, ty + 9);
            ty += lineH;
        }
    }

    // ------------------------------------------------------------------
    // 坐标换算与刻度
    // ------------------------------------------------------------------

    private double[] computeRange() {
        double xMin = Double.POSITIVE_INFINITY, xMax = Double.NEGATIVE_INFINITY;
        double yMin = Double.POSITIVE_INFINITY, yMax = Double.NEGATIVE_INFINITY;
        for (Series s : series) {
            for (int i = 0; i < s.x.length; i++) {
                if (Double.isFinite(s.x[i])) {
                    xMin = Math.min(xMin, s.x[i]);
                    xMax = Math.max(xMax, s.x[i]);
                }
                if (Double.isFinite(s.y[i])) {
                    yMin = Math.min(yMin, s.y[i]);
                    yMax = Math.max(yMax, s.y[i]);
                }
            }
        }
        for (Marker m : markers) {
            xMin = Math.min(xMin, m.x);
            xMax = Math.max(xMax, m.x);
        }
        if (!Double.isFinite(xMin) || !Double.isFinite(xMax)) {
            xMin = 0;
            xMax = 1;
        }
        if (!Double.isFinite(yMin) || !Double.isFinite(yMax)) {
            yMin = 0;
            yMax = 1;
        }
        if (xMax - xMin < 1e-12) {
            xMin -= 0.5;
            xMax += 0.5;
        }
        if (yMax - yMin < 1e-12) {
            yMin -= 0.5;
            yMax += 0.5;
        }
        double yPad = (yMax - yMin) * 0.08;
        yMin -= yPad;
        yMax += yPad;
        return new double[]{xMin, xMax, yMin, yMax};
    }

    private int mapX(double v, double xMin, double xMax, int plotW) {
        return MARGIN_LEFT + (int) Math.round((v - xMin) / (xMax - xMin) * plotW);
    }

    private int mapY(double v, double yMin, double yMax, int plotH) {
        return MARGIN_TOP + plotH - (int) Math.round((v - yMin) / (yMax - yMin) * plotH);
    }

    private static double[] niceTicks(double min, double max, int target) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || max <= min) {
            return new double[]{min, max};
        }
        double raw = (max - min) / Math.max(target, 1);
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double step;
        if (norm <= 1) {
            step = 1;
        } else if (norm <= 2) {
            step = 2;
        } else if (norm <= 5) {
            step = 5;
        } else {
            step = 10;
        }
        step *= mag;
        double start = Math.ceil(min / step) * step;
        int n = (int) Math.floor((max - start) / step) + 1;
        if (n <= 0) {
            return new double[]{min, max};
        }
        n = Math.min(n, 40);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = start + i * step;
        }
        return out;
    }

    private static String fmt(double v) {
        if (v == 0) {
            return "0";
        }
        double a = Math.abs(v);
        if (a >= 1e5 || a < 1e-3) {
            return String.format("%.1e", v);
        }
        String s = String.format("%.4f", v);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}