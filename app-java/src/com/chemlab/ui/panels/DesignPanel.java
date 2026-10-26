package com.chemlab.ui.panels;

import com.chemlab.ui.FormUtil;
import com.chemlab.ui.Theme;
import com.chemlab.util.DataParse;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 实验设计辅助：正交试验表生成、误差分配与重复次数估算。
 *
 * <p>正交表按素数与指数构造（GF(p^k) 的射影点列），覆盖 L4/L8/L16（2 水平）、
 * L9/L27（3 水平）与 L25（5 水平）等常用表，列序按教科书惯例规范化，
 * 因此生成的表与常见教材一致。
 */
public class DesignPanel extends JPanel {

    /** 表型：显示名 → {素数 p, 指数 k}。 */
    private static final String[] TABLE_NAMES = {
            "L4(2^3)", "L8(2^7)", "L16(2^15)", "L9(3^4)", "L27(3^13)", "L25(5^6)"
    };
    private static final int[][] TABLE_PARAMS = {
            {2, 2}, {2, 3}, {2, 4}, {3, 2}, {3, 3}, {5, 2}
    };

    private final JComboBox<String> tableBox = new JComboBox<>(TABLE_NAMES);
    private final JTextField factorCountField = Theme.field(5);
    private final JTextArea factorNamesArea = new JTextArea(4, 20);
    private final DefaultTableModel designModel = new DefaultTableModel();
    private final JTable designTable = new JTable(designModel);
    private final JTextArea designNote = FormUtil.output(5);

    private final JTextField totalErrorField = Theme.field(10);
    private final JTextField stepCountField = Theme.field(10);
    private final JTextArea allocOut = FormUtil.output(12);

    private final JTextField sdField = Theme.field(10);
    private final JTextField targetSemField = Theme.field(10);
    private final JTextArea repeatOut = FormUtil.output(12);

    public DesignPanel() {
        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Theme.base());
        tabs.addTab("正交试验设计", buildOrthogonal());
        tabs.addTab("误差分配", buildAllocation());
        tabs.addTab("重复次数估算", buildRepeat());
        add(tabs, BorderLayout.CENTER);

        tableBox.setSelectedIndex(3); // 默认 L9(3^4)
        generate();
    }

    // ------------------------------------------------------------------
    // 正交试验设计
    // ------------------------------------------------------------------

    private JPanel buildOrthogonal() {
        factorCountField.setText("4");
        factorNamesArea.setFont(Theme.base());
        factorNamesArea.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "正交表", tableBox, "选择标准正交表");
        FormUtil.row(form, 1, "使用因子数", factorCountField, "不超过表的列数");
        FormUtil.row(form, 2, "因子名称", new JScrollPane(factorNamesArea), "每行一个，可留空");
        FormUtil.note(form, 3, "因子数超过表列数时自动截断；不足则按名称或 A、B、C… 命名。");

        JButton run = Theme.primary("生成正交表");
        run.addActionListener(e -> generate());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(run);

        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.setOpaque(false);
        left.setPreferredSize(new Dimension(360, 100));
        JPanel inputCard = Theme.titledCard("参数");
        inputCard.add(form, BorderLayout.CENTER);
        inputCard.add(buttons, BorderLayout.SOUTH);
        left.add(inputCard, BorderLayout.NORTH);
        left.add(FormUtil.resultCard("说明", designNote), BorderLayout.CENTER);

        designTable.setFont(Theme.base());
        designTable.setRowHeight(24);
        designTable.getTableHeader().setFont(Theme.bold(13));
        designTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(JLabel.CENTER);
        designTable.setDefaultRenderer(Object.class, center);

        JPanel tableCard = Theme.titledCard("试验方案");
        tableCard.add(new JScrollPane(designTable), BorderLayout.CENTER);

        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);
        p.add(left, BorderLayout.WEST);
        p.add(tableCard, BorderLayout.CENTER);
        return p;
    }

    private void generate() {
        int idx = Math.max(0, tableBox.getSelectedIndex());
        int p = TABLE_PARAMS[idx][0];
        int k = TABLE_PARAMS[idx][1];

        int[][] arr = orthogonalArray(p, k);
        int maxCols = arr[0].length;

        int factors = parseInt(factorCountField.getText(), maxCols);
        if (factors < 1) {
            factors = 1;
        }
        if (factors > maxCols) {
            factors = maxCols;
        }

        String[] names = factorNames(factors);

        // 重建表格列
        designModel.setRowCount(0);
        designModel.setColumnCount(0);
        designModel.addColumn("试验号");
        for (String n : names) {
            designModel.addColumn(n);
        }
        for (int r = 0; r < arr.length; r++) {
            Object[] row = new Object[factors + 1];
            row[0] = r + 1;
            for (int c = 0; c < factors; c++) {
                row[c + 1] = arr[r][c];
            }
            designModel.addRow(row);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("正交表       = ").append(TABLE_NAMES[idx]).append('\n');
        sb.append("试验次数     = ").append(arr.length).append('\n');
        sb.append("可用列数     = ").append(maxCols).append('\n');
        sb.append("使用因子数   = ").append(factors).append('\n');
        sb.append("每因子水平数 = ").append(p).append('\n');
        sb.append("说明：任意两列的水平组合出现次数相同，可均衡考察各因子主效应。");
        designNote.setForeground(Theme.TEXT);
        designNote.setText(sb.toString());

        // 列宽
        for (int c = 0; c < designModel.getColumnCount(); c++) {
            designTable.getColumnModel().getColumn(c).setPreferredWidth(c == 0 ? 70 : 90);
        }
    }

    private String[] factorNames(int factors) {
        List<String> lines = new ArrayList<>();
        for (String line : factorNamesArea.getText().split("\\r?\\n")) {
            String s = line.trim();
            if (!s.isEmpty()) {
                lines.add(s);
            }
        }
        String[] names = new String[factors];
        for (int i = 0; i < factors; i++) {
            names[i] = i < lines.size() ? lines.get(i) : String.valueOf((char) ('A' + i));
        }
        return names;
    }

    /**
     * 构造 L_{p^k}(p^{(p^k-1)/(p-1)}) 型正交表。
     *
     * <p>行取遍 GF(p)^k 的全部向量，列取射影点（一维子空间的代表元），
     * 表元为点积模 p。列按水平序列字典序排序，使输出与教科书表格一致。
     */
    static int[][] orthogonalArray(int p, int k) {
        int n = 1;
        for (int i = 0; i < k; i++) {
            n *= p;
        }

        List<int[]> cols = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int[] v = new int[k];
        for (int code = 0; code < n; code++) {
            int t = code;
            boolean allZero = true;
            for (int i = 0; i < k; i++) {
                v[i] = t % p;
                t /= p;
                if (v[i] != 0) {
                    allZero = false;
                }
            }
            if (allZero) {
                continue;
            }
            int first = 0;
            while (v[first] == 0) {
                first++;
            }
            int inv = modInverse(v[first], p);
            int[] norm = new int[k];
            for (int i = 0; i < k; i++) {
                norm[i] = (v[i] * inv) % p;
            }
            if (seen.add(Arrays.toString(norm))) {
                cols.add(norm);
            }
        }

        // 生成原始表
        int[][] raw = new int[n][cols.size()];
        for (int r = 0; r < n; r++) {
            int t = r;
            int[] a = new int[k];
            for (int i = 0; i < k; i++) {
                a[i] = t % p;
                t /= p;
            }
            for (int c = 0; c < cols.size(); c++) {
                int[] col = cols.get(c);
                int dot = 0;
                for (int i = 0; i < k; i++) {
                    dot = (dot + a[i] * col[i]) % p;
                }
                raw[r][c] = dot + 1;
            }
        }

        // 按列的水平序列字典序重排，匹配教科书列序
        Integer[] order = new Integer[cols.size()];
        for (int c = 0; c < order.length; c++) {
            order[c] = c;
        }
        Arrays.sort(order, (c1, c2) -> sequence(raw, c1).compareTo(sequence(raw, c2)));

        int[][] out = new int[n][cols.size()];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < order.length; c++) {
                out[r][c] = raw[r][order[c]];
            }
        }
        return out;
    }

    private static String sequence(int[][] arr, int col) {
        StringBuilder sb = new StringBuilder(arr.length);
        for (int[] row : arr) {
            sb.append((char) ('0' + row[col]));
        }
        return sb.toString();
    }

    private static int modInverse(int a, int p) {
        for (int i = 1; i < p; i++) {
            if ((a * i) % p == 1) {
                return i;
            }
        }
        return 1;
    }

    // ------------------------------------------------------------------
    // 误差分配
    // ------------------------------------------------------------------

    private JPanel buildAllocation() {
        totalErrorField.setText("0.5");
        stepCountField.setText("4");

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "总允许误差", totalErrorField, "%（相对）");
        FormUtil.row(form, 1, "独立环节数 n", stepCountField, "个");
        FormUtil.note(form, 2, "按「等影响原则」分配：各环节允许相对误差 = 总误差 / √n。");
        FormUtil.note(form, 3, "适用于各环节相互独立、误差以随机误差为主的情形。");

        JButton run = Theme.primary("分配");
        run.addActionListener(e -> allocate());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(run);

        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.setOpaque(false);
        left.setPreferredSize(new Dimension(360, 100));
        JPanel inputCard = Theme.titledCard("参数");
        inputCard.add(form, BorderLayout.CENTER);
        inputCard.add(buttons, BorderLayout.SOUTH);
        left.add(inputCard, BorderLayout.NORTH);

        JPanel right = FormUtil.resultCard("分配结果", allocOut);

        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);
        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.CENTER);
        return p;
    }

    private void allocate() {
        try {
            double total = Double.parseDouble(totalErrorField.getText().trim());
            int n = Integer.parseInt(stepCountField.getText().trim());
            if (total <= 0) {
                throw new IllegalArgumentException("总允许误差需为正数");
            }
            if (n < 1) {
                throw new IllegalArgumentException("环节数至少为 1");
            }
            double each = total / Math.sqrt(n);
            double absEach = total / Math.sqrt(n);

            StringBuilder sb = new StringBuilder();
            sb.append("总允许相对误差 = ").append(DataParse.fmt(total)).append(" %\n");
            sb.append("独立环节数     = ").append(n).append("\n\n");
            sb.append("等影响分配（各环节允许相对误差）：\n");
            for (int i = 1; i <= n; i++) {
                sb.append(String.format("  环节 %d：± %.4f %%\n", i, each));
            }
            sb.append(String.format("%n合成校验：√(Σ eᵢ²) = %.4f %% ≈ 总误差 %.4f %%%n",
                    each * Math.sqrt(n), total));
            sb.append("\n提示：若某环节实际可达误差小于分配值，可将余量转移给较难控制的环节。");
            allocOut.setForeground(Theme.TEXT);
            allocOut.setText(sb.toString());
        } catch (NumberFormatException e) {
            allocOut.setForeground(Theme.DANGER);
            allocOut.setText("输入有误：请填写有效的数值。");
        } catch (IllegalArgumentException e) {
            allocOut.setForeground(Theme.DANGER);
            allocOut.setText("输入有误：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 重复次数估算
    // ------------------------------------------------------------------

    private JPanel buildRepeat() {
        sdField.setText("0.02");
        targetSemField.setText("0.01");

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "测定标准差 s", sdField, "预实验得到");
        FormUtil.row(form, 1, "目标标准误 SEM", targetSemField, "希望达到的均值标准误");
        FormUtil.note(form, 2, "n = (t·s / SEM)²，取 95% 置信、t≈2 估算，结果向上取整。");

        JButton run = Theme.primary("估算");
        run.addActionListener(e -> estimateRepeat());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(run);

        JPanel left = new JPanel(new BorderLayout(0, 10));
        left.setOpaque(false);
        left.setPreferredSize(new Dimension(360, 100));
        JPanel inputCard = Theme.titledCard("参数");
        inputCard.add(form, BorderLayout.CENTER);
        inputCard.add(buttons, BorderLayout.SOUTH);
        left.add(inputCard, BorderLayout.NORTH);

        JPanel right = FormUtil.resultCard("估算结果", repeatOut);

        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);
        p.add(left, BorderLayout.WEST);
        p.add(right, BorderLayout.CENTER);
        return p;
    }

    private void estimateRepeat() {
        try {
            double s = Double.parseDouble(sdField.getText().trim());
            double sem = Double.parseDouble(targetSemField.getText().trim());
            if (s <= 0 || sem <= 0) {
                throw new IllegalArgumentException("标准差与目标标准误需为正数");
            }
            double t = 2.0;
            double nRaw = Math.pow(t * s / sem, 2);
            int n = (int) Math.ceil(nRaw);
            if (n < 2) {
                n = 2;
            }
            double achieved = t * s / Math.sqrt(n);

            StringBuilder sb = new StringBuilder();
            sb.append("预实验标准差 s   = ").append(DataParse.fmt(s)).append('\n');
            sb.append("目标标准误 SEM   = ").append(DataParse.fmt(sem)).append('\n');
            sb.append("估算公式         = (t·s / SEM)²，t 取 2（95%）\n\n");
            sb.append("理论所需次数     = ").append(DataParse.fmt(nRaw, 2)).append('\n');
            sb.append("建议重复次数 n   = ").append(n).append('\n');
            sb.append("实际可达 SEM     = ").append(DataParse.fmt(achieved, 4)).append('\n');
            sb.append("\n提示：n 为最少次数，实际应结合测量时间与成本适当增加；");
            sb.append("若 s 来自小样本，建议先做 6~10 次预实验再估算。");
            repeatOut.setForeground(Theme.TEXT);
            repeatOut.setText(sb.toString());
        } catch (NumberFormatException e) {
            repeatOut.setForeground(Theme.DANGER);
            repeatOut.setText("输入有误：请填写有效的数值。");
        } catch (IllegalArgumentException e) {
            repeatOut.setForeground(Theme.DANGER);
            repeatOut.setText("输入有误：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(1100, 700);
    }
}