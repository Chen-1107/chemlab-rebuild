package com.chemlab.ui.panels;

import com.chemlab.core.NativeCore;
import com.chemlab.data.CalibrationDao;
import com.chemlab.model.Calibration;
import com.chemlab.ui.ChartPanel;
import com.chemlab.ui.FormUtil;
import com.chemlab.ui.ImportDialog;
import com.chemlab.ui.Theme;
import com.chemlab.util.DataParse;
import com.chemlab.util.Json;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 标定曲线：线性/加权回归、LOD/LOQ、残差图、反算浓度、曲线保存与调用。 */
public class CalibrationPanel extends JPanel {

    private final CalibrationDao dao;

    private final JTextArea xyArea = new JTextArea(9, 22);
    private final JTextArea sdArea = new JTextArea(4, 22);
    private final JTextArea out = FormUtil.output(13);
    private final ChartPanel fitChart = new ChartPanel();
    private final ChartPanel resChart = new ChartPanel();
    private final DefaultListModel<Calibration> savedModel = new DefaultListModel<>();
    private final JList<Calibration> savedList = new JList<>(savedModel);
    private final JTextField inverseY = Theme.field(10);

    private double lastSlope = Double.NaN;
    private double lastIntercept = Double.NaN;
    private double lastR2 = Double.NaN;
    private double lastSyx = Double.NaN;
    private double[] lastX = new double[0];
    private double[] lastY = new double[0];

    public CalibrationPanel(CalibrationDao dao) {
        this.dao = dao;
        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        add(buildLeft(), BorderLayout.WEST);
        add(buildRight(), BorderLayout.CENTER);
        reloadSaved();
    }

    // ------------------------------------------------------------------

    private JPanel buildLeft() {
        xyArea.setFont(Theme.mono(13));
        xyArea.setText("0.0\t0.002\n0.2\t0.078\n0.4\t0.155\n0.6\t0.232\n0.8\t0.309\n1.0\t0.386");
        sdArea.setFont(Theme.mono(13));

        JPanel form = FormUtil.grid();
        FormUtil.note(form, 0, "每行一对：浓度 x 与响应 y（空格/Tab 分隔）。");
        FormUtil.row(form, 1, "标定数据", new JScrollPane(xyArea));
        FormUtil.note(form, 2, "选填：各点标准差 s，用于加权回归（权重 1/s²）。");
        FormUtil.row(form, 3, "标准差 s", new JScrollPane(sdArea));

        JButton run = Theme.primary("回归");
        JButton save = Theme.secondary("保存曲线");
        JButton imp = Theme.secondary("导入数据…");
        run.addActionListener(e -> compute());
        save.addActionListener(e -> saveCurve());
        imp.addActionListener(e -> importData());

        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(imp);
        buttons.add(run);
        buttons.add(save);

        JPanel inputCard = Theme.titledCard("标定数据");
        inputCard.add(form, BorderLayout.CENTER);
        inputCard.add(buttons, BorderLayout.SOUTH);

        // 反算浓度
        JButton inverse = Theme.secondary("反算浓度");
        inverse.addActionListener(e -> inverseCalc());
        JPanel invForm = FormUtil.grid();
        FormUtil.row(invForm, 0, "测得响应 y", inverseY, "由回归方程反算 x");
        JPanel invCard = Theme.titledCard("未知样反算");
        invCard.add(invForm, BorderLayout.CENTER);
        invCard.add(inverse, BorderLayout.SOUTH);

        // 已保存曲线
        savedList.setFont(Theme.base());
        savedList.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        JButton load = Theme.secondary("载入");
        JButton del = Theme.danger("删除");
        load.addActionListener(e -> loadCurve());
        del.addActionListener(e -> deleteCurve());
        JPanel savedBtns = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        savedBtns.setOpaque(false);
        savedBtns.add(load);
        savedBtns.add(del);
        JPanel savedCard = Theme.titledCard("已保存曲线");
        savedCard.add(new JScrollPane(savedList), BorderLayout.CENTER);
        savedCard.add(savedBtns, BorderLayout.SOUTH);

        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.setOpaque(false);
        left.setPreferredSize(new java.awt.Dimension(430, 100));
        left.add(inputCard, BorderLayout.NORTH);
        left.add(invCard, BorderLayout.CENTER);
        left.add(savedCard, BorderLayout.SOUTH);
        return left;
    }

    private JPanel buildRight() {
        JPanel results = FormUtil.resultCard("回归结果", out);

        JPanel charts = new JPanel(new GridLayout(2, 1, 0, 10));
        charts.setOpaque(false);
        JPanel fitCard = Theme.titledCard("标定曲线");
        fitCard.add(fitChart, BorderLayout.CENTER);
        JPanel resCard = Theme.titledCard("残差分布");
        resCard.add(resChart, BorderLayout.CENTER);
        charts.add(fitCard);
        charts.add(resCard);

        JPanel right = new JPanel(new BorderLayout(0, 10));
        right.setOpaque(false);
        right.add(results, BorderLayout.NORTH);
        right.add(charts, BorderLayout.CENTER);
        return right;
    }

    // ------------------------------------------------------------------

    private void compute() {
        try {
            out.setForeground(Theme.TEXT);
            double[][] p = DataParse.pairs(xyArea.getText());
            double[] x = p[0];
            double[] y = p[1];
            if (x.length < 2) {
                throw new IllegalArgumentException("至少需要 2 个标定点");
            }

            double[] sd = DataParse.numbers(sdArea.getText());
            boolean weighted = sd.length == x.length && sd.length > 0;

            double[] r = weighted
                    ? NativeCore.weightedRegression(x, y, sd)
                    : NativeCore.linearRegression(x, y);
            if (r == null) {
                throw new IllegalStateException("内核回归失败（可能数据退化，如全部 x 相同）");
            }

            lastSlope = r[0];
            lastIntercept = r[1];
            lastR2 = r[2];
            lastSyx = r[4];
            lastX = x;
            lastY = y;

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("回归方式        = %s%n", weighted ? "加权最小二乘（w=1/s²）" : "普通最小二乘"));
            sb.append(String.format("标定点数 n      = %s%n", DataParse.fmt(r[7])));
            sb.append(String.format("斜率 b          = %s%n", DataParse.fmt(r[0])));
            sb.append(String.format("截距 a          = %s%n", DataParse.fmt(r[1])));
            sb.append(String.format("相关系数 r      = %s%n", DataParse.fmt(r[3])));
            sb.append(String.format("决定系数 R²     = %s%n", DataParse.fmt(r[2])));
            sb.append(String.format("剩余标准差 s_y/x= %s%n", DataParse.fmt(r[4])));
            sb.append(String.format("斜率标准误 s_b  = %s%n", DataParse.fmt(r[5])));
            sb.append(String.format("截距标准误 s_a  = %s%n", DataParse.fmt(r[6])));
            sb.append(String.format("回归方程        = y = %s·x + %s%n", DataParse.fmt(r[0]), DataParse.fmt(r[1])));

            double[] ll = NativeCore.lodLoq(r[0], r[4], 3.0, 10.0);
            if (ll != null) {
                sb.append(String.format("检出限 LOD      = %s（k=3）%n", DataParse.fmt(ll[0])));
                sb.append(String.format("定量限 LOQ      = %s（k=10）%n", DataParse.fmt(ll[1])));
            }
            if (r[2] < 0.995) {
                sb.append("提示：R² < 0.995，线性欠佳，建议检查标定点或改用加权回归。");
            } else {
                sb.append("提示：线性良好（R² ≥ 0.995）。");
            }
            out.setText(sb.toString());

            // 拟合图：散点 + 拟合直线
            fitChart.clear();
            fitChart.setLabels("标定曲线", "浓度 x", "响应 y");
            fitChart.addSeries(new ChartPanel.Series("标定点", Theme.SERIES[0],
                    ChartPanel.Type.SCATTER, x, y));
            double xmin = min(x), xmax = max(x);
            double[] lx = {xmin, xmax};
            double[] ly = {r[1] + r[0] * xmin, r[1] + r[0] * xmax};
            fitChart.addSeries(new ChartPanel.Series("拟合直线", Theme.SERIES[1],
                    ChartPanel.Type.LINE, lx, ly));

            // 残差图
            double[] res = NativeCore.residuals(x, y, r[0], r[1]);
            if (res != null) {
                resChart.clear();
                resChart.setLabels("残差分布", "浓度 x", "残差 y - ŷ");
                resChart.addSeries(new ChartPanel.Series("残差", Theme.SERIES[2],
                        ChartPanel.Type.SCATTER, x, res));
                double[] zeroX = {xmin, xmax};
                double[] zeroY = {0, 0};
                resChart.addSeries(new ChartPanel.Series("零线", Theme.MUTED,
                        ChartPanel.Type.LINE, zeroX, zeroY));
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            out.setForeground(Theme.DANGER);
            out.setText("输入有误：" + e.getMessage());
        } catch (Throwable t) {
            out.setForeground(Theme.DANGER);
            out.setText("计算失败：" + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }

    private void importData() {
        double[][] p = ImportDialog.pickColumns(this, new String[]{"浓度 X 列", "响应 Y 列"});
        if (p == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p[0].length; i++) {
            sb.append(DataParse.fmt(p[0][i])).append('\t')
                    .append(DataParse.fmt(p[1][i])).append('\n');
        }
        xyArea.setText(sb.toString());
        compute();
    }

    private void inverseCalc() {
        try {
            if (Double.isNaN(lastSlope)) {
                throw new IllegalStateException("请先完成一次回归");
            }
            double y = Double.parseDouble(inverseY.getText().trim());
            double x = NativeCore.calibInverse(y, lastSlope, lastIntercept);
            out.setForeground(Theme.TEXT);
            out.setText(String.format("由 y = %s 反算：%n", DataParse.fmt(y))
                    + String.format("浓度 x = (y - a) / b = %s%n", DataParse.fmt(x))
                    + String.format("使用方程：y = %s·x + %s",
                    DataParse.fmt(lastSlope), DataParse.fmt(lastIntercept)));
        } catch (Exception e) {
            out.setForeground(Theme.DANGER);
            out.setText("反算失败：" + e.getMessage());
        }
    }

    private void saveCurve() {
        if (Double.isNaN(lastSlope)) {
            JOptionPane.showMessageDialog(this, "请先完成一次回归再保存。", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String name = JOptionPane.showInputDialog(this, "曲线名称：", "标定曲线 " + lastX.length + " 点");
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        try {
            Calibration c = new Calibration();
            c.name = name.trim();
            c.xJson = Json.write(DataParse.toList(lastX));
            c.yJson = Json.write(DataParse.toList(lastY));
            c.slope = lastSlope;
            c.intercept = lastIntercept;
            c.r2 = lastR2;
            c.syx = lastSyx;
            double[] ll = NativeCore.lodLoq(lastSlope, lastSyx, 3.0, 10.0);
            if (ll != null) {
                c.lod = ll[0];
                c.loq = ll[1];
            }
            dao.insert(c);
            reloadSaved();
            JOptionPane.showMessageDialog(this, "已保存标定曲线：" + c.name, "成功",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "保存失败：" + e.getMessage(), "错误",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadCurve() {
        Calibration c = savedList.getSelectedValue();
        if (c == null) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            List<Double> xs = doubles(Json.parse(c.xJson));
            List<Double> ys = doubles(Json.parse(c.yJson));
            for (int i = 0; i < xs.size() && i < ys.size(); i++) {
                sb.append(DataParse.fmt(xs.get(i))).append('\t').append(DataParse.fmt(ys.get(i))).append('\n');
            }
            xyArea.setText(sb.toString());
            compute();
            out.setForeground(Theme.TEXT);
            out.setText(out.getText() + String.format("%n%n[已载入曲线：%s]", c.name));
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(this, "载入失败：" + e.getMessage(), "错误",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteCurve() {
        Calibration c = savedList.getSelectedValue();
        if (c == null) {
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this, "确认删除曲线「" + c.name + "」？", "确认",
                JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            dao.delete(c.id);
            reloadSaved();
        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "删除失败：" + e.getMessage(), "错误",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void reloadSaved() {
        savedModel.clear();
        try {
            for (Calibration c : dao.listAll()) {
                savedModel.addElement(c);
            }
        } catch (SQLException e) {
            System.err.println("[chemlab] 读取标定曲线失败：" + e.getMessage());
        }
    }

    private static List<Double> doubles(Object parsed) {
        List<Double> out = new ArrayList<>();
        for (Object o : Json.asArray(parsed)) {
            out.add(Json.num(o, 0));
        }
        return out;
    }

    private static double min(double[] a) {
        double m = a[0];
        for (double v : a) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static double max(double[] a) {
        double m = a[0];
        for (double v : a) {
            m = Math.max(m, v);
        }
        return m;
    }
}