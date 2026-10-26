package com.chemlab.ui.panels;

import com.chemlab.core.NativeCore;
import com.chemlab.ui.ChartPanel;
import com.chemlab.ui.FormUtil;
import com.chemlab.ui.ImportDialog;
import com.chemlab.ui.Theme;
import com.chemlab.util.DataParse;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.function.Supplier;

/** 误差统计、误差传递、置信区间、异常值检验、显著性检验。 */
public class StatsPanel extends JPanel {

    public StatsPanel() {
        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Theme.base());
        tabs.addTab("描述统计", buildDescribe());
        tabs.addTab("误差传递", buildUncertainty());
        tabs.addTab("置信区间", buildCi());
        tabs.addTab("异常值检验", buildOutlier());
        tabs.addTab("显著性检验", buildSigTest());
        add(tabs, BorderLayout.CENTER);
    }

    // ------------------------------------------------------------------

    private JPanel buildDescribe() {
        JTextArea data = textArea(5);
        JTextField trueVal = Theme.field(12);
        JTextArea out = FormUtil.output(12);
        ChartPanel chart = new ChartPanel();

        JPanel form = FormUtil.grid();
        FormUtil.note(form, 0, "输入测量值，可用空格、逗号或换行分隔。");
        FormUtil.row(form, 1, "测量数据", new JScrollPane(data), "至少 2 个");
        FormUtil.row(form, 2, "标准值（选填）", trueVal, "用于计算相对误差");
        FormUtil.note(form, 3, "均值、中位数、标准差、RSD、方差、SEM、极值。");

        JButton run = Theme.primary("计算");
        run.addActionListener(e -> compute(out, () -> {
            double[] d = DataParse.numbers(data.getText());
            if (d.length < 2) {
                throw new IllegalArgumentException("至少需要 2 个数据点");
            }
            double[] r = NativeCore.describe(d);
            if (r == null) {
                throw new IllegalStateException("内核计算失败");
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("样本量 n        = %d%n", d.length));
            sb.append(String.format("平均值 mean     = %s%n", DataParse.fmt(r[0])));
            sb.append(String.format("中位数 median   = %s%n", DataParse.fmt(r[1])));
            sb.append(String.format("样本标准差 s    = %s%n", DataParse.fmt(r[2])));
            sb.append(String.format("相对标准偏差 RSD= %s %%%n", DataParse.fmt(r[3], 3)));
            sb.append(String.format("方差 s²         = %s%n", DataParse.fmt(r[4])));
            sb.append(String.format("标准误 SEM      = %s%n", DataParse.fmt(r[5])));
            sb.append(String.format("最小值 / 最大值 = %s / %s%n",
                    DataParse.fmt(r[6]), DataParse.fmt(r[7])));
            sb.append(String.format("极差 R          = %s%n", DataParse.fmt(r[7] - r[6])));
            if (!trueVal.getText().isBlank()) {
                double tv = Double.parseDouble(trueVal.getText().trim());
                double re = NativeCore.relativeError(r[0], tv);
                sb.append(String.format("相对误差 RE     = %s %%%n", DataParse.fmt(re, 3)));
                sb.append(String.format("绝对误差        = %s", DataParse.fmt(r[0] - tv)));
            }
            chart.clear();
            chart.setLabels("测量值分布", "序号", "数值");
            double[] xs = new double[d.length];
            for (int i = 0; i < d.length; i++) {
                xs[i] = i + 1;
            }
            chart.addSeries(new ChartPanel.Series("测量值", Theme.SERIES[0],
                    ChartPanel.Type.SCATTER, xs, d));
            double[] lineY = new double[d.length];
            for (int i = 0; i < d.length; i++) {
                lineY[i] = r[0];
            }
            chart.addSeries(new ChartPanel.Series("平均值", Theme.SERIES[1],
                    ChartPanel.Type.LINE, xs, lineY));
            return sb.toString();
        }));

        return twoColumn(form, run, out, chart, "输入数据", "统计结果",
                importSingle("导入数据…", data));
    }

    // ------------------------------------------------------------------

    private JPanel buildUncertainty() {
        JComboBox<String> mode = new JComboBox<>(new String[]{
                "加减法 y = Σxᵢ", "乘除法 y = Πxᵢ", "幂函数 y = x^a"});
        mode.setFont(Theme.base());
        JTextArea vals = textArea(4);
        JTextArea uncs = textArea(4);
        JTextField px = Theme.field(12);
        JTextField pux = Theme.field(12);
        JTextField pa = Theme.field(12);
        JTextArea out = FormUtil.output(11);

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "合成方式", mode);
        FormUtil.note(form, 1, "加减法 / 乘除法：填写各分量值与标准不确定度，一一对应。");
        FormUtil.row(form, 2, "分量值", new JScrollPane(vals));
        FormUtil.row(form, 3, "标准不确定度 u", new JScrollPane(uncs));
        FormUtil.note(form, 4, "幂函数：单独填写 x、u(x) 与指数 a。");
        FormUtil.row(form, 5, "x", px);
        FormUtil.row(form, 6, "u(x)", pux);
        FormUtil.row(form, 7, "指数 a", pa);

        JButton run = Theme.primary("合成不确定度");
        run.addActionListener(e -> compute(out, () -> {
            String m = (String) mode.getSelectedItem();
            StringBuilder sb = new StringBuilder();
            if (m.startsWith("幂函数")) {
                double x = num(px, "x");
                double ux = num(pux, "u(x)");
                double a = num(pa, "指数 a");
                double[] r = NativeCore.uncPower(x, ux, a);
                if (r == null) {
                    throw new IllegalStateException("内核计算失败");
                }
                sb.append(String.format("y = x^a = %s%n", DataParse.fmt(r[0])));
                sb.append(String.format("u(y) = %s%n", DataParse.fmt(r[1])));
                sb.append(String.format("相对不确定度 u_rel = %s %%%n",
                        DataParse.fmt(Math.abs(r[1] / r[0]) * 100, 3)));
                sb.append("u(y) = |a|·y·u(x)/x");
                return sb.toString();
            }
            double[] v = DataParse.numbers(vals.getText());
            double[] u = DataParse.numbers(uncs.getText());
            if (v.length == 0 || v.length != u.length) {
                throw new IllegalArgumentException("分量值与不确定度个数必须相同且非空");
            }
            if (m.startsWith("加减法")) {
                double[] r = NativeCore.uncAdd(v, u);
                if (r == null) {
                    throw new IllegalStateException("内核计算失败");
                }
                sb.append(String.format("y = Σxᵢ = %s%n", DataParse.fmt(r[0])));
                sb.append(String.format("u(y) = √Σu(xᵢ)² = %s%n", DataParse.fmt(r[1])));
                sb.append(String.format("相对不确定度 = %s %%%n",
                        DataParse.fmt(Math.abs(r[1] / r[0]) * 100, 3)));
                sb.append(String.format("扩展不确定度 U(k=2) = %s", DataParse.fmt(r[1] * 2)));
            } else {
                double[] r = NativeCore.uncMul(v, u);
                if (r == null) {
                    throw new IllegalStateException("内核计算失败");
                }
                sb.append(String.format("y = Πxᵢ = %s%n", DataParse.fmt(r[0])));
                sb.append(String.format("u(y) = %s%n", DataParse.fmt(r[1])));
                sb.append(String.format("相对不确定度 = %s %%%n", DataParse.fmt(r[2] * 100, 3)));
                sb.append(String.format("扩展不确定度 U(k=2) = %s", DataParse.fmt(r[1] * 2)));
            }
            return sb.toString();
        }));

        return twoColumn(form, run, out, null, "输入", "合成结果",
                importUncertainty(vals, uncs));
    }

    // ------------------------------------------------------------------

    private JPanel buildCi() {
        JTextArea data = textArea(5);
        JTextField conf = Theme.field(12);
        conf.setText("0.95");
        JTextArea out = FormUtil.output(9);

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "测量数据", new JScrollPane(data));
        FormUtil.row(form, 1, "置信水平", conf, "如 0.95 / 0.99");
        FormUtil.note(form, 2, "基于 t 分布：x̄ ± t(α, n-1)·s/√n");

        JButton run = Theme.primary("计算区间");
        run.addActionListener(e -> compute(out, () -> {
            double[] d = DataParse.numbers(data.getText());
            if (d.length < 2) {
                throw new IllegalArgumentException("至少需要 2 个数据点");
            }
            double c = num(conf, "置信水平");
            if (c <= 0 || c >= 1) {
                throw new IllegalArgumentException("置信水平应在 0 与 1 之间");
            }
            double[] r = NativeCore.confidenceInterval(d, c);
            if (r == null) {
                throw new IllegalStateException("内核计算失败");
            }
            double mean = NativeCore.mean(d);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("置信水平        = %s%%%n", DataParse.fmt(c * 100, 1)));
            sb.append(String.format("样本均值 x̄      = %s%n", DataParse.fmt(mean)));
            sb.append(String.format("置信区间        = [%s, %s]%n",
                    DataParse.fmt(r[0]), DataParse.fmt(r[1])));
            sb.append(String.format("半宽            = ± %s%n", DataParse.fmt(r[2])));
            sb.append(String.format("t 临界值        = %s%n", DataParse.fmt(r[3], 4)));
            sb.append(String.format("自由度 df       = %d%n", d.length - 1));
            sb.append(String.format("相对扩展不确定度= %s %%",
                    DataParse.fmt(Math.abs(r[2] / mean) * 100, 3)));
            return sb.toString();
        }));

        return twoColumn(form, run, out, null, "输入", "区间结果",
                importSingle("导入数据…", data));
    }

    // ------------------------------------------------------------------

    private JPanel buildOutlier() {
        JComboBox<String> method = new JComboBox<>(new String[]{"Grubbs 检验", "Dixon Q 检验"});
        method.setFont(Theme.base());
        JTextArea data = textArea(5);
        JTextField alpha = Theme.field(12);
        alpha.setText("0.05");
        JTextArea out = FormUtil.output(11);
        ChartPanel chart = new ChartPanel();

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "检验方法", method);
        FormUtil.row(form, 1, "测量数据", new JScrollPane(data));
        FormUtil.row(form, 2, "显著性水平 α", alpha, "常用 0.05 / 0.01");
        FormUtil.note(form, 3, "Grubbs 检验要求 n ≥ 3；Dixon Q 检验适用于 3~10 个小样本。");

        JButton run = Theme.primary("检验");
        run.addActionListener(e -> compute(out, () -> {
            double[] d = DataParse.numbers(data.getText());
            double a = num(alpha, "显著性水平");
            StringBuilder sb = new StringBuilder();
            chart.clear();
            chart.setLabels("数据与离群点", "序号", "数值");
            double[] xs = new double[d.length];
            for (int i = 0; i < d.length; i++) {
                xs[i] = i + 1;
            }

            String m = (String) method.getSelectedItem();
            if (m.startsWith("Grubbs")) {
                if (d.length < 3) {
                    throw new IllegalArgumentException("Grubbs 检验至少需要 3 个数据点");
                }
                double[] r = NativeCore.grubbs(d, a);
                if (r == null) {
                    throw new IllegalStateException("内核计算失败（可能数据全等导致零方差）");
                }
                sb.append(String.format("G 统计量        = %s%n", DataParse.fmt(r[0], 4)));
                sb.append(String.format("临界值 G_crit   = %s%n", DataParse.fmt(r[1], 4)));
                sb.append(String.format("被检验值        = %s%n", DataParse.fmt(r[2])));
                int idx = (int) Math.round(r[3]);
                sb.append(String.format("离群点下标      = %s%n", idx < 0 ? "无" : (idx + 1) + "（第 " + (idx + 1) + " 个）"));
                sb.append(idx >= 0 ? "结论：G > G_crit，判定为离群值，建议剔除或复核。"
                        : "结论：G ≤ G_crit，无离群值。");
                chart.addSeries(new ChartPanel.Series("数据", Theme.SERIES[0],
                        ChartPanel.Type.SCATTER, xs, d));
                if (idx >= 0) {
                    chart.addMarker(xs[idx], "离群");
                }
            } else {
                if (d.length < 3 || d.length > 10) {
                    throw new IllegalArgumentException("Dixon Q 检验适用于 3~10 个数据点");
                }
                double[] r = NativeCore.dixonQ(d, a);
                if (r == null) {
                    throw new IllegalStateException("内核计算失败");
                }
                sb.append(String.format("Q 统计量        = %s%n", DataParse.fmt(r[0], 4)));
                sb.append(String.format("临界值 Q_crit   = %s%n", DataParse.fmt(r[1], 4)));
                int idx = (int) Math.round(r[2]);
                sb.append(String.format("离群点下标      = %s%n", idx < 0 ? "无" : (idx + 1) + "（第 " + (idx + 1) + " 个）"));
                sb.append(idx >= 0 ? "结论：Q > Q_crit，判定为离群值，建议剔除或复核。"
                        : "结论：Q ≤ Q_crit，无离群值。");
                chart.addSeries(new ChartPanel.Series("数据", Theme.SERIES[0],
                        ChartPanel.Type.SCATTER, xs, d));
                if (idx >= 0) {
                    chart.addMarker(xs[idx], "离群");
                }
            }
            return sb.toString();
        }));

        return twoColumn(form, run, out, chart, "输入", "检验结果",
                importSingle("导入数据…", data));
    }

    // ------------------------------------------------------------------

    private JPanel buildSigTest() {
        JComboBox<String> mode = new JComboBox<>(new String[]{
                "单样本 t 检验", "双样本 t 检验", "F 检验（方差齐性）"});
        mode.setFont(Theme.base());
        JTextArea dataA = textArea(4);
        JTextArea dataB = textArea(4);
        JTextField mu0 = Theme.field(12);
        JCheckBox pooled = new JCheckBox("假设等方差（合并方差）");
        pooled.setOpaque(false);
        pooled.setFont(Theme.base());
        pooled.setSelected(true);
        JTextArea out = FormUtil.output(12);

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "检验类型", mode);
        FormUtil.row(form, 1, "样本 A", new JScrollPane(dataA));
        FormUtil.row(form, 2, "样本 B", new JScrollPane(dataB), "双样本 / F 检验用");
        FormUtil.row(form, 3, "已知均值 μ₀", mu0, "单样本 t 检验用");
        FormUtil.row(form, 4, "等方差假设", pooled);

        JButton run = Theme.primary("检验");
        run.addActionListener(e -> compute(out, () -> {
            String m = (String) mode.getSelectedItem();
            StringBuilder sb = new StringBuilder();
            if (m.startsWith("单样本")) {
                double[] d = DataParse.numbers(dataA.getText());
                if (d.length < 2) {
                    throw new IllegalArgumentException("至少需要 2 个数据点");
                }
                double mu = num(mu0, "已知均值 μ₀");
                double[] r = NativeCore.tTestOne(d, mu);
                if (r == null) {
                    throw new IllegalStateException("内核计算失败");
                }
                double mean = NativeCore.mean(d);
                sb.append(String.format("样本均值 x̄   = %s%n", DataParse.fmt(mean)));
                sb.append(String.format("对照值 μ₀    = %s%n", DataParse.fmt(mu)));
                sb.append(String.format("t 统计量     = %s%n", DataParse.fmt(r[0], 4)));
                sb.append(String.format("自由度 df    = %s%n", DataParse.fmt(r[2])));
                sb.append(String.format("标准误 SEM   = %s%n", DataParse.fmt(r[3])));
                sb.append(String.format("p 值（双侧） = %s%n", DataParse.fmt(r[1], 4)));
                sb.append(r[1] < 0.05 ? "结论：p < 0.05，均值与对照值存在显著差异。"
                        : "结论：p ≥ 0.05，均值与对照值无显著差异。");
            } else if (m.startsWith("双样本")) {
                double[] a = DataParse.numbers(dataA.getText());
                double[] b = DataParse.numbers(dataB.getText());
                if (a.length < 2 || b.length < 2) {
                    throw new IllegalArgumentException("两组样本各需至少 2 个数据点");
                }
                double[] r = NativeCore.tTestTwo(a, b, pooled.isSelected());
                if (r == null) {
                    throw new IllegalStateException("内核计算失败");
                }
                sb.append(String.format("A 组均值     = %s (n=%d)%n", DataParse.fmt(NativeCore.mean(a)), a.length));
                sb.append(String.format("B 组均值     = %s (n=%d)%n", DataParse.fmt(NativeCore.mean(b)), b.length));
                sb.append(String.format("均值差       = %s%n", DataParse.fmt(r[3])));
                sb.append(String.format("t 统计量     = %s%n", DataParse.fmt(r[0], 4)));
                sb.append(String.format("自由度 df    = %s%n", DataParse.fmt(r[2])));
                sb.append(String.format("p 值（双侧） = %s%n", DataParse.fmt(r[1], 4)));
                sb.append("方法：").append(pooled.isSelected() ? "合并方差 t 检验" : "Welch 校正 t 检验").append("\n");
                sb.append(r[1] < 0.05 ? "结论：p < 0.05，两组均值存在显著差异。"
                        : "结论：p ≥ 0.05，两组均值无显著差异。");
            } else {
                double[] a = DataParse.numbers(dataA.getText());
                double[] b = DataParse.numbers(dataB.getText());
                if (a.length < 2 || b.length < 2) {
                    throw new IllegalArgumentException("两组样本各需至少 2 个数据点");
                }
                double[] r = NativeCore.fTest(a, b);
                if (r == null) {
                    throw new IllegalStateException("内核计算失败");
                }
                sb.append(String.format("A 组标准差   = %s%n", DataParse.fmt(NativeCore.stddev(a, 1))));
                sb.append(String.format("B 组标准差   = %s%n", DataParse.fmt(NativeCore.stddev(b, 1))));
                sb.append(String.format("F 统计量     = %s%n", DataParse.fmt(r[0], 4)));
                sb.append(String.format("自由度 df    = %s%n", DataParse.fmt(r[2])));
                sb.append(String.format("p 值（双侧） = %s%n", DataParse.fmt(r[1], 4)));
                sb.append(r[1] < 0.05 ? "结论：p < 0.05，两组方差存在显著差异（不满足等方差）。"
                        : "结论：p ≥ 0.05，两组方差无显著差异（可认为等方差）。");
            }
            return sb.toString();
        }));

        return twoColumn(form, run, out, null, "输入", "检验结果",
                importSingle("导入样本 A…", dataA), importSingle("导入样本 B…", dataB));
    }

    // ------------------------------------------------------------------

    private static JTextArea textArea(int rows) {
        JTextArea ta = new JTextArea(rows, 24);
        ta.setFont(Theme.mono(13));
        ta.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        return ta;
    }

    /** 单列导入按钮：从文件选一列数值写入目标文本区。 */
    private JButton importSingle(String label, JTextArea target) {
        JButton b = Theme.secondary(label);
        b.addActionListener(e -> {
            double[] vals = ImportDialog.pickNumbers(this);
            if (vals == null) {
                return;
            }
            fillNumbers(target, vals);
        });
        return b;
    }

    /** 双列导入按钮（误差传递）：分量值与不确定度分别写入两个文本区。 */
    private JButton importUncertainty(JTextArea vals, JTextArea uncs) {
        JButton b = Theme.secondary("导入数据…");
        b.addActionListener(e -> {
            double[][] p = ImportDialog.pickColumns(this,
                    new String[]{"分量值列", "不确定度列"});
            if (p == null) {
                return;
            }
            fillNumbers(vals, p[0]);
            fillNumbers(uncs, p[1]);
        });
        return b;
    }

    private static void fillNumbers(JTextArea area, double[] vals) {
        StringBuilder sb = new StringBuilder();
        for (double v : vals) {
            sb.append(DataParse.fmt(v)).append('\n');
        }
        area.setText(sb.toString());
    }

    private static double num(JTextField f, String name) {
        String s = f.getText().trim();
        if (s.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 不是合法数值：" + s);
        }
    }

    private void compute(JTextArea out, Supplier<String> task) {
        try {
            out.setForeground(Theme.TEXT);
            out.setText(task.get());
        } catch (IllegalArgumentException | IllegalStateException e) {
            out.setForeground(Theme.DANGER);
            out.setText("输入有误：" + e.getMessage());
        } catch (Throwable t) {
            out.setForeground(Theme.DANGER);
            out.setText("计算失败：" + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }

    private JPanel twoColumn(JPanel form, JButton run, JTextArea out,
                             ChartPanel chart, String leftTitle, String rightTitle,
                             JButton... extras) {
        JPanel left = Theme.titledCard(leftTitle);
        left.add(form, BorderLayout.CENTER);
        JPanel south = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        south.setOpaque(false);
        for (JButton b : extras) {
            south.add(b);
        }
        south.add(run);
        left.add(south, BorderLayout.SOUTH);

        JPanel right = FormUtil.resultCard(rightTitle, out);

        JPanel body = new JPanel(new GridLayout(1, 2, 12, 0));
        body.setOpaque(false);
        body.add(left);
        body.add(right);

        JPanel root = new JPanel(new BorderLayout());
        root.setOpaque(false);
        root.add(CalcPanel.coreBanner(), BorderLayout.NORTH);
        if (chart != null) {
            JPanel chartCard = Theme.titledCard("图形");
            chartCard.add(chart, BorderLayout.CENTER);
            JPanel grid = new JPanel(new GridLayout(1, 2, 12, 0));
            grid.setOpaque(false);
            grid.add(body);
            grid.add(chartCard);
            root.add(grid, BorderLayout.CENTER);
        } else {
            root.add(body, BorderLayout.CENTER);
        }
        return root;
    }
}