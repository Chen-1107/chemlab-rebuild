package com.chemlab.ui.panels;

import com.chemlab.core.NativeCore;
import com.chemlab.ui.ChartPanel;
import com.chemlab.ui.FormUtil;
import com.chemlab.ui.ImportDialog;
import com.chemlab.ui.Theme;
import com.chemlab.util.DataParse;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Random;

/**
 * 色谱峰分析：粘贴或从文件导入「时间–信号」序列，由 C++ 内核完成
 * 平滑、基线扣除与寻峰，给出保留时间、峰面积、面积归一化、塔板数、
 * 拖尾因子与相邻峰分离度。
 */
public class ChromPanel extends JPanel {

    private final JTextArea dataArea = FormUtil.output(10);
    private final JTextField minHeightF = Theme.field(8);
    private final JTextField minDistF = Theme.field(8);
    private final JTextField smoothF = Theme.field(8);
    private final ChartPanel chart = new ChartPanel();
    private final JTable peakTable = new JTable();

    public ChromPanel() {
        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        add(CalcPanel.coreBanner(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
    }

    private JComponent buildBody() {
        minHeightF.setText("0.05");
        minDistF.setText("5");
        smoothF.setText("7");

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "峰高阈值", minHeightF, "相对最大信号，0~1");
        FormUtil.row(form, 1, "最小峰间距", minDistF, "采样点数");
        FormUtil.row(form, 2, "平滑窗口", smoothF, "奇数，0 不平滑");

        dataArea.setToolTipText("每行「时间 信号」两列，以空格或制表符分隔");

        JScrollPane dataScroll = Theme.scroll(dataArea);
        dataScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));

        JButton sample = Theme.secondary("示例数据");
        JButton imp = Theme.secondary("导入数据…");
        JButton run = Theme.primary("分析");
        sample.addActionListener(e -> loadSample());
        imp.addActionListener(e -> importData());
        run.addActionListener(e -> analyze());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(sample);
        buttons.add(imp);
        buttons.add(run);

        JPanel left = Theme.titledCard("色谱数据");
        JPanel leftBody = new JPanel(new BorderLayout(0, 8));
        leftBody.setOpaque(false);
        leftBody.add(form, BorderLayout.NORTH);
        leftBody.add(dataScroll, BorderLayout.CENTER);
        leftBody.add(buttons, BorderLayout.SOUTH);
        left.add(leftBody, BorderLayout.CENTER);
        left.setPreferredSize(new Dimension(360, 100));

        peakTable.setRowHeight(24);
        peakTable.setFont(Theme.mono(12));
        peakTable.getTableHeader().setFont(Theme.base());
        peakTable.setModel(new PeakTableModel(new double[0][8]));
        JScrollPane tableScroll = Theme.scroll(peakTable);
        tableScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        tableScroll.setPreferredSize(new Dimension(100, 190));

        JPanel right = Theme.titledCard("色谱图与峰表");
        right.add(chart, BorderLayout.CENTER);
        right.add(tableScroll, BorderLayout.SOUTH);

        JPanel body = new JPanel(new BorderLayout(12, 0));
        body.setOpaque(false);
        body.add(left, BorderLayout.WEST);
        body.add(right, BorderLayout.CENTER);
        return body;
    }

    private void importData() {
        double[][] p = ImportDialog.pickColumns(this, new String[]{"时间列", "信号列"});
        if (p == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p[0].length; i++) {
            sb.append(DataParse.fmt(p[0][i])).append('\t')
                    .append(DataParse.fmt(p[1][i])).append('\n');
        }
        dataArea.setText(sb.toString());
    }

    /** 合成双峰示例：窄高斯峰 + 缓漂移基线 + 微小噪声，模拟典型 HPLC 图。 */
    private void loadSample() {
        StringBuilder sb = new StringBuilder();
        Random rnd = new Random(42);
        for (double t = 0.0; t <= 10.0; t += 0.01) {
            double base = 0.05 + 0.01 * t;
            double p1 = 1.00 * Math.exp(-0.5 * sq((t - 2.0) / 0.06));
            double p2 = 0.60 * Math.exp(-0.5 * sq((t - 3.5) / 0.08));
            double noise = 0.002 * rnd.nextGaussian();
            sb.append(String.format("%.2f\t%.4f%n", t, base + p1 + p2 + noise));
        }
        dataArea.setText(sb.toString());
        analyze();
    }

    private static double sq(double v) {
        return v * v;
    }

    private void analyze() {
        try {
            double[][] d = DataParse.pairs(dataArea.getText());
            int n = d[0].length;
            if (n < 5) {
                throw new IllegalArgumentException("至少需要 5 个数据点（每行：时间 信号）");
            }
            double minHeight = parse(minHeightF, 0.05);
            int minDist = (int) parse(minDistF, 5);
            int smooth = (int) parse(smoothF, 7);
            if (minHeight <= 0 || minHeight >= 1) {
                throw new IllegalArgumentException("峰高阈值需在 0 与 1 之间");
            }
            double[] res = NativeCore.chromPeaks(d[0], d[1], minHeight, minDist, smooth);
            if (res == null) {
                throw new IllegalStateException("内核分析失败：请检查数据与参数");
            }
            int nPk = (int) res[0];
            double[][] pk = new double[nPk][8];
            for (int p = 0; p < nPk; p++) {
                System.arraycopy(res, 1 + p * 8, pk[p], 0, 8);
            }
            peakTable.setModel(new PeakTableModel(pk));

            chart.clear();
            chart.setLabels("色谱图（" + nPk + " 个峰）", "保留时间", "信号");
            chart.addSeries(new ChartPanel.Series("原始信号", Theme.MUTED,
                    ChartPanel.Type.LINE, d[0], d[1]));
            double[] sm = smooth >= 3 ? NativeCore.savgol(d[1], smooth, 2) : d[1];
            double[] corr = NativeCore.baselinePoly(sm, 2, 8);
            chart.addSeries(new ChartPanel.Series("基线扣除", Theme.ACCENT,
                    ChartPanel.Type.LINE, d[0], corr));
            for (double[] peak : pk) {
                chart.addMarker(peak[0], null);
            }
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException ex) {
            clearToMessage(ex.getMessage());
        }
    }

    private void clearToMessage(String msg) {
        peakTable.setModel(new PeakTableModel(new double[0][8]));
        chart.clear();
        chart.setLabels("分析失败：" + msg, "", "");
    }

    private static double parse(JTextField f, double dft) {
        String s = f.getText().trim();
        if (s.isEmpty()) {
            return dft;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("参数不是合法数值：" + s);
        }
    }

    /** 峰表模型：NaN 显示为 —，数值按列语义格式化。 */
    private static final class PeakTableModel extends AbstractTableModel {

        private static final String[] COLS = {
                "峰", "tR", "峰高", "峰面积", "面积 %", "塔板数 N", "拖尾因子", "W½", "Rs"};

        private final double[][] pk;

        PeakTableModel(double[][] pk) {
            this.pk = pk;
        }

        @Override
        public int getRowCount() {
            return pk.length;
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLS[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            double[] p = pk[r];
            switch (c) {
                case 0:
                    return String.valueOf(r + 1);
                case 1:
                    return fmt(p[0], 3);
                case 2:
                    return fmt(p[1], 4);
                case 3:
                    return fmt(p[2], 4);
                case 4:
                    return fmt(p[3], 2);
                case 5:
                    return fmt(p[4], 0);
                case 6:
                    return fmt(p[5], 2);
                case 7:
                    return fmt(p[6], 3);
                default:
                    return fmt(p[7], 2);
            }
        }

        private static String fmt(double v, int digits) {
            if (Double.isNaN(v)) {
                return "—";
            }
            return DataParse.fmt(v, digits);
        }
    }
}
