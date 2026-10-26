package com.chemlab.ui;

import com.chemlab.util.TableImport;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据导入对话框：选择 CSV/TXT/XLSX 文件 → 预览 → 指定列映射 → 取出数值列。
 *
 * <p>供统计面板、标定面板等复用：单列场景用 {@link #pickNumbers}，
 * 成对数据用 {@link #pickPairs}，自定义角色（如分量值 / 不确定度）用
 * {@link #pickColumns}。返回 null 表示用户取消；行中含非数值单元格时
 * 该行被跳过并在结束后提示。
 */
public final class ImportDialog extends JDialog {

    private static File lastDir;

    private final TableImport.Table table;
    private final String[] roles;
    private final JComboBox<Integer>[] combos;

    private PreviewModel model;
    private JCheckBox headerBox;

    /** 用户确认后的结果；取消时保持 null。 */
    private double[][] result;
    /** 因非数值单元格被跳过的行数（含表头行之外被跳过的数据行）。 */
    private int skippedRows;

    private ImportDialog(Window owner, TableImport.Table table, String[] roles) {
        super(owner, "导入数据 · " + table.name, ModalityType.APPLICATION_MODAL);
        this.table = table;
        this.roles = roles;
        this.combos = createCombos();

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout(0, 10));
        ((JComponent) getContentPane()).setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        getContentPane().setBackground(Theme.CARD);

        add(buildNorth(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildSouth(), BorderLayout.SOUTH);

        setSize(680, 460);
        setMinimumSize(new Dimension(560, 380));
        setLocationRelativeTo(owner);
    }

    // ------------------------------------------------------------------
    // 静态入口
    // ------------------------------------------------------------------

    /** 选一列数值（描述统计、置信区间等单变量场景）。 */
    public static double[] pickNumbers(Component parent) {
        double[][] cols = pickColumns(parent, new String[]{"数据列"});
        return cols == null ? null : cols[0];
    }

    /** 选两列成对数值（标定曲线等 x/y 场景）。 */
    public static double[][] pickPairs(Component parent) {
        return pickColumns(parent, new String[]{"X 列", "Y 列"});
    }

    /** 完整流程：文件选择 → 解析 → 列映射对话框。返回 roles.length 个数值列。 */
    public static double[][] pickColumns(Component parent, String[] roles) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        if (owner == null) {
            owner = JOptionPane.getRootFrame();
        }
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setFileFilter(new FileNameExtensionFilter(
                "表格文件（CSV / TXT / XLSX）", "csv", "txt", "tsv", "xlsx"));
        fc.setDialogTitle("选择要导入的数据文件");
        if (fc.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File file = fc.getSelectedFile();
        lastDir = file.getParentFile();

        TableImport.Table table;
        try {
            table = TableImport.read(file);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(owner,
                    "读取失败：" + e.getMessage(), "导入", JOptionPane.ERROR_MESSAGE);
            return null;
        }
        if (table.columns < roles.length) {
            JOptionPane.showMessageDialog(owner,
                    "文件只有 " + table.columns + " 列，本功能需要至少 "
                            + roles.length + " 列。", "导入", JOptionPane.WARNING_MESSAGE);
            return null;
        }

        ImportDialog dlg = new ImportDialog(owner, table, roles);
        dlg.setVisible(true);
        double[][] r = dlg.result;
        if (r != null && dlg.skippedRows > 0) {
            int n = r[0].length;
            JOptionPane.showMessageDialog(owner,
                    "已导入 " + n + " 行，跳过 " + dlg.skippedRows + " 行（含非数值单元格或空行）。",
                    "导入", JOptionPane.INFORMATION_MESSAGE);
        }
        return r;
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private JComponent buildNorth() {
        JLabel info = Theme.label(table.name + " · " + table.rowCount()
                + " 行 × " + table.columns + " 列");
        headerBox = new JCheckBox("首行为表头（不作为数据导入）", detectHeader());
        headerBox.setOpaque(false);
        headerBox.setFont(Theme.base());
        headerBox.setForeground(Theme.TEXT);
        headerBox.addActionListener(e -> model.fireTableStructureChanged());

        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        p.add(info, BorderLayout.NORTH);
        p.add(headerBox, BorderLayout.SOUTH);
        return p;
    }

    private JComponent buildCenter() {
        model = new PreviewModel();
        JTable grid = new JTable(model);
        grid.setFont(Theme.mono(12));
        grid.setRowHeight(24);
        grid.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        JScrollPane sp = Theme.scroll(grid);
        sp.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        return sp;
    }

    private JComponent buildSouth() {
        JPanel mapping = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        mapping.setOpaque(false);
        for (int i = 0; i < roles.length; i++) {
            mapping.add(Theme.label(roles[i]));
            JComboBox<Integer> combo = combos[i];
            combo.setFont(Theme.base());
            combo.setRenderer(columnRenderer());
            combo.setSelectedIndex(Math.min(i, table.columns - 1));
            mapping.add(combo);
        }

        JButton ok = Theme.primary("导入");
        JButton cancel = Theme.secondary("取消");
        ok.addActionListener(e -> confirm());
        cancel.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(ok);
        buttons.add(cancel);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(mapping, BorderLayout.WEST);
        south.add(buttons, BorderLayout.EAST);
        return south;
    }

    @SuppressWarnings("unchecked")
    private JComboBox<Integer>[] createCombos() {
        JComboBox<Integer>[] arr = new JComboBox[roles.length];
        for (int i = 0; i < arr.length; i++) {
            Integer[] cols = new Integer[table.columns];
            for (int c = 0; c < cols.length; c++) {
                cols[c] = c;
            }
            arr[i] = new JComboBox<>(cols);
        }
        return arr;
    }

    private ListCellRenderer<? super Integer> columnRenderer() {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index,
                        isSelected, cellHasFocus);
                if (value instanceof Integer) {
                    setText(model.getColumnName((Integer) value));
                }
                return c;
            }
        };
    }

    // ------------------------------------------------------------------
    // 确认与解析
    // ------------------------------------------------------------------

    private void confirm() {
        int n = roles.length;
        int[] sel = new int[n];
        for (int i = 0; i < n; i++) {
            sel[i] = combos[i].getSelectedIndex();
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (sel[i] == sel[j]) {
                    warn("「" + roles[i] + "」与「" + roles[j] + "」选择了同一列，请重新选择。");
                    return;
                }
            }
        }

        int start = headerBox.isSelected() ? 1 : 0;
        List<double[]> parsed = new ArrayList<>();
        skippedRows = 0;
        for (int r = start; r < table.rows.size(); r++) {
            String[] row = table.rows.get(r);
            double[] vals = new double[n];
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                String cell = sel[i] < row.length ? row[sel[i]] : "";
                try {
                    vals[i] = Double.parseDouble(cell.trim());
                } catch (NumberFormatException e) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                parsed.add(vals);
            } else {
                skippedRows++;
            }
        }
        if (parsed.isEmpty()) {
            warn("所选列没有可解析的数值行，请检查列选择或表头设置。");
            return;
        }
        result = new double[n][parsed.size()];
        for (int i = 0; i < n; i++) {
            for (int r = 0; r < parsed.size(); r++) {
                result[i][r] = parsed.get(r)[i];
            }
        }
        dispose();
    }

    private void warn(String msg) {
        JOptionPane.showMessageDialog(this, msg, "导入", JOptionPane.WARNING_MESSAGE);
    }

    /** 表头启发式：首行任意单元格不是数字而其余行能读出数字，则视为表头。 */
    private boolean detectHeader() {
        if (table.rows.size() < 2) {
            return false;
        }
        String[] first = table.rows.get(0);
        for (String cell : first) {
            String s = cell.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------

    private final class PreviewModel extends AbstractTableModel {

        @Override
        public int getRowCount() {
            return table.rows.size();
        }

        @Override
        public int getColumnCount() {
            return table.columns;
        }

        @Override
        public String getColumnName(int c) {
            if (headerBox != null && headerBox.isSelected() && !table.rows.isEmpty()) {
                String[] first = table.rows.get(0);
                String name = c < first.length ? first[c].trim() : "";
                if (!name.isEmpty()) {
                    return name;
                }
            }
            return "第 " + (c + 1) + " 列";
        }

        @Override
        public Object getValueAt(int r, int c) {
            String[] row = table.rows.get(r);
            return c < row.length ? row[c] : "";
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    }
}
