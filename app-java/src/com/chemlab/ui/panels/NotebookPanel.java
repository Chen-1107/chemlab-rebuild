package com.chemlab.ui.panels;

import com.chemlab.data.RecordDao;
import com.chemlab.model.Record;
import com.chemlab.service.PythonBridge;
import com.chemlab.ui.FormUtil;
import com.chemlab.ui.Theme;
import com.chemlab.util.DataParse;
import com.chemlab.util.Json;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 实验记录本：记录增删改查、关键字检索、CSV 导出，以及 Python 归纳分析。 */
public class NotebookPanel extends JPanel {

    private final RecordDao dao;
    private final PythonBridge bridge;

    private final RecordTableModel tableModel = new RecordTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextField searchField = Theme.field(18);
    private final JTextArea summaryArea = FormUtil.output(16);
    private final DefaultTableModel groupModel = new DefaultTableModel(
            new Object[]{"实验类型", "次数", "均值", "RSD %", "离群值"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable groupTable = new JTable(groupModel);
    private final JLabel statusLabel = Theme.muted("就绪");

    private List<Record> current = new ArrayList<>();

    public NotebookPanel(RecordDao dao, PythonBridge bridge) {
        this.dao = dao;
        this.bridge = bridge;
        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        refresh();
    }

    // ------------------------------------------------------------------

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);

        JButton add = Theme.primary("新建记录");
        JButton edit = Theme.secondary("编辑");
        JButton del = Theme.danger("删除");
        JButton refresh = Theme.secondary("刷新");
        JButton search = Theme.secondary("检索");
        JButton clear = Theme.secondary("显示全部");
        JButton summarize = Theme.primary("归纳分析");
        JButton export = Theme.secondary("导出 CSV");
        JButton report = Theme.primary("导出报告");

        add.addActionListener(e -> openEditor(null));
        edit.addActionListener(e -> {
            Record r = selected();
            if (r != null) {
                openEditor(r);
            }
        });
        del.addActionListener(e -> deleteSelected());
        refresh.addActionListener(e -> refresh());
        search.addActionListener(e -> doSearch());
        clear.addActionListener(e -> {
            searchField.setText("");
            refresh();
        });
        summarize.addActionListener(e -> runSummarize());
        export.addActionListener(e -> exportCsv());
        report.addActionListener(e -> exportReport());

        searchField.addActionListener(e -> doSearch());

        bar.add(add);
        bar.add(edit);
        bar.add(del);
        bar.add(refresh);
        bar.add(new JLabel("  检索："));
        bar.add(searchField);
        bar.add(search);
        bar.add(clear);
        bar.add(new JLabel("  "));
        bar.add(summarize);
        bar.add(export);
        bar.add(report);
        return bar;
    }

    private JPanel buildBody() {
        table.setFont(Theme.base());
        table.setRowHeight(26);
        table.getTableHeader().setFont(Theme.bold(13));
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    Record r = selected();
                    if (r != null) {
                        openEditor(r);
                    }
                }
            }
        });
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(JLabel.CENTER);
        table.getColumnModel().getColumn(0).setCellRenderer(center);
        table.getColumnModel().getColumn(1).setCellRenderer(center);
        table.getColumnModel().getColumn(2).setCellRenderer(center);

        JPanel tableCard = Theme.titledCard("记录列表");
        tableCard.add(new JScrollPane(table), BorderLayout.CENTER);

        groupTable.setFont(Theme.base());
        groupTable.setRowHeight(24);
        groupTable.getTableHeader().setFont(Theme.bold(13));
        JPanel groupCard = Theme.titledCard("分组统计");
        groupCard.add(new JScrollPane(groupTable), BorderLayout.CENTER);

        JPanel summaryCard = Theme.titledCard("归纳结论");
        summaryCard.add(new JScrollPane(summaryArea), BorderLayout.CENTER);

        JTabbedPane analysis = new JTabbedPane();
        analysis.setFont(Theme.base());
        analysis.addTab("归纳结论", summaryCard);
        analysis.addTab("分组统计", groupCard);
        analysis.setPreferredSize(new Dimension(100, 260));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableCard, analysis);
        split.setResizeWeight(0.62);
        split.setBorder(null);
        split.setOpaque(false);

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------------
    // 数据操作
    // ------------------------------------------------------------------

    public void refresh() {
        try {
            current = dao.listAll();
            tableModel.setRecords(current);
            statusLabel.setText("共 " + current.size() + " 条记录");
        } catch (SQLException e) {
            error("读取记录失败：" + e.getMessage());
        }
    }

    private void doSearch() {
        try {
            current = dao.search(searchField.getText());
            tableModel.setRecords(current);
            statusLabel.setText("检索到 " + current.size() + " 条记录");
        } catch (SQLException e) {
            error("检索失败：" + e.getMessage());
        }
    }

    private Record selected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "请先在列表中选择一条记录。", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return tableModel.get(table.convertRowIndexToModel(row));
    }

    private void deleteSelected() {
        Record r = selected();
        if (r == null) {
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this,
                "确认删除记录「" + r.title + "」？此操作不可撤销。", "确认删除",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            dao.delete(r.id);
            refresh();
        } catch (SQLException e) {
            error("删除失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 编辑器
    // ------------------------------------------------------------------

    private void openEditor(Record existing) {
        boolean isNew = existing == null;
        Record draft = isNew ? new Record() : existing;

        JDialog dialog = new JDialog(
                (java.awt.Frame) javax.swing.SwingUtilities.getWindowAncestor(this),
                isNew ? "新建实验记录" : "编辑实验记录", true);
        dialog.setLayout(new BorderLayout(0, 10));

        JTextField title = Theme.field(28);
        JComboBox<String> type = new JComboBox<>(Record.TYPE_LABELS);
        JTextField date = Theme.field(28);
        JTextField tags = Theme.field(28);
        JTextField result = Theme.field(28);
        JTextArea purpose = new JTextArea(3, 28);
        JTextArea dataText = new JTextArea(6, 28);
        JTextArea conclusion = new JTextArea(3, 28);
        for (JTextArea ta : new JTextArea[]{purpose, dataText, conclusion}) {
            ta.setFont(Theme.base());
            ta.setLineWrap(true);
            ta.setWrapStyleWord(true);
            ta.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        }

        title.setText(draft.title);
        type.setSelectedIndex(indexOfType(draft.type));
        date.setText(draft.date.isEmpty()
                ? java.time.LocalDate.now().toString() : draft.date);
        tags.setText(draft.tags);
        result.setText(draft.resultValue == null ? "" : DataParse.fmt(draft.resultValue));
        purpose.setText(draft.purpose);
        dataText.setText(draft.dataText);
        conclusion.setText(draft.conclusion);

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "标题", title);
        FormUtil.row(form, 1, "实验类型", type);
        FormUtil.row(form, 2, "日期", date, "yyyy-MM-dd");
        FormUtil.row(form, 3, "目的", new JScrollPane(purpose));
        FormUtil.row(form, 4, "数据记录", new JScrollPane(dataText), "可写测量值与条件");
        FormUtil.row(form, 5, "结论", new JScrollPane(conclusion));
        FormUtil.row(form, 6, "标签", tags, "逗号分隔");
        FormUtil.row(form, 7, "代表值", result, "如测得浓度，选填");

        JButton save = Theme.primary(isNew ? "创建" : "保存");
        JButton cancel = Theme.secondary("取消");
        save.addActionListener(e -> {
            try {
                draft.title = title.getText().trim();
                if (draft.title.isEmpty()) {
                    throw new IllegalArgumentException("标题不能为空");
                }
                draft.type = Record.TYPE_KEYS[type.getSelectedIndex()];
                draft.date = date.getText().trim();
                draft.purpose = purpose.getText();
                draft.dataText = dataText.getText();
                draft.conclusion = conclusion.getText();
                draft.tags = tags.getText().trim();
                String rv = result.getText().trim();
                draft.resultValue = rv.isEmpty() ? null : Double.parseDouble(rv);

                if (isNew) {
                    dao.insert(draft);
                } else {
                    dao.update(draft);
                }
                dialog.dispose();
                refresh();
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(dialog, ex.getMessage(), "输入有误",
                        JOptionPane.WARNING_MESSAGE);
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(dialog, "保存失败：" + ex.getMessage(), "错误",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
        cancel.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(save);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.CARD);
        root.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        root.add(form, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(root);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private static int indexOfType(String type) {
        for (int i = 0; i < Record.TYPE_KEYS.length; i++) {
            if (Record.TYPE_KEYS[i].equals(type)) {
                return i;
            }
        }
        return Record.TYPE_KEYS.length - 1;
    }

    // ------------------------------------------------------------------
    // 归纳分析
    // ------------------------------------------------------------------

    private void runSummarize() {
        if (bridge == null || !bridge.isAvailable()) {
            summaryArea.setForeground(Theme.DANGER);
            summaryArea.setText("Python 分析服务不可用，无法进行归纳分析。\n"
                    + "请确认 Python 环境可用，并已安装 OpenCV / NumPy。");
            return;
        }
        if (current.isEmpty()) {
            summaryArea.setForeground(Theme.WARN);
            summaryArea.setText("当前列表没有记录，请先录入数据。");
            return;
        }

        summaryArea.setForeground(Theme.MUTED);
        summaryArea.setText("正在分析 " + current.size() + " 条记录…");
        statusLabel.setText("归纳分析中…");

        List<Object> payload = new ArrayList<>();
        for (Record r : current) {
            payload.add(r.toMap());
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("records", payload);

        new SwingWorker<Map<String, Object>, Void>() {
            @Override
            protected Map<String, Object> doInBackground() {
                return bridge.call("notebook.summarize", params);
            }

            @Override
            protected void done() {
                try {
                    renderSummary(get());
                    statusLabel.setText("归纳分析完成");
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    summaryArea.setForeground(Theme.DANGER);
                    summaryArea.setText("归纳分析失败：" + cause.getMessage());
                    statusLabel.setText("归纳分析失败");
                }
            }
        }.execute();
    }

    private void renderSummary(Map<String, Object> res) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> overview = Json.asObject(res.get("overview"));
        sb.append("【概览】\n");
        sb.append("  记录总数：").append(Json.intOf(overview.get("total"), 0)).append('\n');
        Map<String, Object> typeCounts = Json.asObject(overview.get("typeCounts"));
        if (!typeCounts.isEmpty()) {
            sb.append("  类型分布：");
            boolean first = true;
            for (Map.Entry<String, Object> e : typeCounts.entrySet()) {
                if (!first) {
                    sb.append("、");
                }
                first = false;
                sb.append(e.getKey()).append(' ').append(Json.intOf(e.getValue(), 0)).append(" 条");
            }
            sb.append('\n');
        }
        Object span = overview.get("dateSpan");
        if (span instanceof Map) {
            Map<String, Object> sp = Json.asObject(span);
            sb.append("  时间跨度：").append(Json.str(sp.get("start"), "-"))
                    .append(" 至 ").append(Json.str(sp.get("end"), "-"))
                    .append("（").append(Json.intOf(sp.get("days"), 0)).append(" 天）\n");
        }

        sb.append("\n【结论与建议】\n");
        for (Object o : Json.asArray(res.get("insights"))) {
            sb.append("  • ").append(String.valueOf(o)).append('\n');
        }

        Object trend = res.get("trend");
        if (trend instanceof Map) {
            Map<String, Object> tr = Json.asObject(trend);
            if (tr.containsKey("judgment")) {
                sb.append("\n【趋势回归】\n");
                sb.append("  判定：").append(Json.str(tr.get("judgment"), "-")).append('\n');
                sb.append("  斜率：").append(DataParse.fmt(Json.num(tr.get("slopePerDay"), 0)))
                        .append(" /天\n");
                sb.append("  R²：").append(DataParse.fmt(Json.num(tr.get("r2"), 0), 4)).append('\n');
            } else if (tr.containsKey("note")) {
                sb.append("\n【趋势回归】\n  ").append(Json.str(tr.get("note"), "")).append('\n');
            }
        }

        sb.append("\n生成时间：").append(Json.str(res.get("generatedAt"), "-"));
        if (!Json.bool(res.get("coreAvailable"), false)) {
            sb.append("\n注意：C++ 计算内核不可用，部分统计量缺失。");
        }

        summaryArea.setForeground(Theme.TEXT);
        summaryArea.setText(sb.toString());

        groupModel.setRowCount(0);
        for (Object o : Json.asArray(res.get("groups"))) {
            Map<String, Object> g = Json.asObject(o);
            Map<String, Object> st = Json.asObject(g.get("stats"));
            Object outlier = g.get("outlier");
            String outlierText = "-";
            if (outlier instanceof Map) {
                Map<String, Object> od = Json.asObject(outlier);
                if (Json.bool(od.get("isOutlier"), false)) {
                    outlierText = "第 " + (Json.intOf(od.get("index"), 0) + 1) + " 个："
                            + DataParse.fmt(Json.num(od.get("value"), 0));
                }
            }
            groupModel.addRow(new Object[]{
                    Json.str(g.get("typeLabel"), "-"),
                    Json.intOf(g.get("count"), 0),
                    st.isEmpty() ? "-" : DataParse.fmt(Json.num(st.get("mean"), 0)),
                    st.isEmpty() ? "-" : DataParse.fmt(Json.num(st.get("rsd"), 0), 3),
                    outlierText,
            });
        }
    }

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    private void exportCsv() {
        if (current.isEmpty()) {
            JOptionPane.showMessageDialog(this, "没有可导出的记录。", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导出记录为 CSV");
        chooser.setSelectedFile(new File("实验记录_" + java.time.LocalDate.now() + ".csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = chooser.getSelectedFile();
        try (BufferedWriter w = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
            w.write('\uFEFF'); // BOM，便于 Excel 正确识别中文
            w.write("编号,日期,类型,标题,目的,数据记录,结论,标签,代表值\n");
            for (Record r : current) {
                w.write(String.join(",",
                        csv(String.valueOf(r.id)),
                        csv(r.date),
                        csv(Record.labelOf(r.type)),
                        csv(r.title),
                        csv(r.purpose),
                        csv(r.dataText),
                        csv(r.conclusion),
                        csv(r.tags),
                        csv(r.resultValue == null ? "" : DataParse.fmt(r.resultValue))));
                w.write('\n');
            }
            JOptionPane.showMessageDialog(this, "已导出 " + current.size() + " 条记录到：\n" + f.getAbsolutePath(),
                    "导出完成", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            error("导出失败：" + e.getMessage());
        }
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
        if (v.contains(",") || v.contains("\"") || v.contains(" ")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Word 实验报告导出
    // ------------------------------------------------------------------

    private void exportReport() {
        if (current.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前列表没有记录，无法生成报告。", "提示",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JTextField title = Theme.field(24);
        title.setText("分析化学实验报告");
        JTextField author = Theme.field(24);
        JTextField course = Theme.field(24);

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "报告标题", title);
        FormUtil.row(form, 1, "实验人", author);
        FormUtil.row(form, 2, "课程 / 单位", course, "选填");

        int ok = JOptionPane.showConfirmDialog(this, form, "生成实验报告（Word）",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) {
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存实验报告");
        chooser.setSelectedFile(new File("实验报告_" + java.time.LocalDate.now() + ".docx"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = chooser.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".docx")) {
            f = new File(f.getParentFile(), f.getName() + ".docx");
        }

        String rptTitle = title.getText().trim();
        String rptAuthor = author.getText().trim();
        String rptCourse = course.getText().trim();
        try (com.chemlab.util.DocxWriter w = com.chemlab.util.DocxWriter.open(f.toPath())) {
            writeReport(w, rptTitle, rptAuthor, rptCourse, current);
            JOptionPane.showMessageDialog(this,
                    "报告已生成：\n" + f.getAbsolutePath() + "\n（共 "
                            + current.size() + " 条记录）",
                    "导出完成", JOptionPane.INFORMATION_MESSAGE);
            statusLabel.setText("报告已导出到 " + f.getName());
        } catch (IOException e) {
            error("报告生成失败：" + e.getMessage());
        }
    }

    /** 组装报告正文：封面信息 → 概览表 → 逐条记录明细 → 尾注。 */
    private static void writeReport(com.chemlab.util.DocxWriter w, String title,
                                    String author, String course, List<Record> records)
            throws IOException {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        w.title(title)
                .subtitle("生成时间：" + now.format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        + "  ·  共 " + records.size() + " 条实验记录");

        if (!author.isEmpty() || !course.isEmpty()) {
            w.labeled("实验人", author.isEmpty() ? "—" : author)
                    .labeled("课程 / 单位", course.isEmpty() ? "—" : course);
        }
        String first = records.get(records.size() - 1).date;
        String last = records.get(0).date;
        w.labeled("记录日期范围", first + " 至 " + last);

        w.heading("一、实验概览");
        w.table(new String[]{"实验类型", "记录数", "代表值均值"},
                overviewRows(records));
        w.paragraph("说明：代表值均值为各记录「代表值」字段的算术平均，未填代表值的记录不计入。");

        w.heading("二、实验记录明细");
        int idx = 1;
        for (Record r : records) {
            w.heading(idx + ". " + r.title);
            w.labeled("日期", r.date)
                    .labeled("类型", Record.labelOf(r.type));
            if (!r.tags.isEmpty()) {
                w.labeled("标签", r.tags);
            }
            if (!r.purpose.isEmpty()) {
                w.labeled("实验目的", "");
                w.dataBlock(r.purpose);
            }
            if (!r.dataText.isEmpty()) {
                w.labeled("数据记录", "");
                w.dataBlock(r.dataText);
            }
            if (r.resultValue != null) {
                w.labeled("代表值", DataParse.fmt(r.resultValue));
            }
            if (!r.conclusion.isEmpty()) {
                w.labeled("结论", "");
                w.dataBlock(r.conclusion);
            }
            idx++;
        }

        w.paragraph("—— 报告结束 ——");
        w.subtitle("本报告由 ChemLab Analyzer 依据实验记录本自动生成");
    }

    /** 按实验类型分组统计：记录数与代表值均值。 */
    private static List<String[]> overviewRows(List<Record> records) {
        java.util.Map<String, int[]> counts = new LinkedHashMap<>();
        java.util.Map<String, double[]> sums = new LinkedHashMap<>();
        java.util.Map<String, List<Double>> vals = new LinkedHashMap<>();
        for (Record r : records) {
            String key = Record.labelOf(r.type);
            counts.computeIfAbsent(key, k -> new int[1])[0]++;
            if (r.resultValue != null) {
                sums.computeIfAbsent(key, k -> new double[1])[0] += r.resultValue;
                vals.computeIfAbsent(key, k -> new ArrayList<>()).add(r.resultValue);
            }
        }
        List<String[]> rows = new ArrayList<>();
        for (java.util.Map.Entry<String, int[]> e : counts.entrySet()) {
            String type = e.getKey();
            int n = e.getValue()[0];
            List<Double> v = vals.get(type);
            String mean = (v == null || v.isEmpty()) ? "—"
                    : DataParse.fmt(sums.get(type)[0] / v.size());
            rows.add(new String[]{type, String.valueOf(n), mean});
        }
        return rows;
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(this, msg, "错误", JOptionPane.ERROR_MESSAGE);
    }

    // ------------------------------------------------------------------

    private static class RecordTableModel extends AbstractTableModel {
        private final String[] cols = {"编号", "日期", "类型", "标题", "标签", "代表值"};
        private List<Record> records = new ArrayList<>();

        void setRecords(List<Record> r) {
            this.records = r;
            fireTableDataChanged();
        }

        Record get(int row) {
            return records.get(row);
        }

        @Override
        public int getRowCount() {
            return records.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Record rec = records.get(r);
            switch (c) {
                case 0: return rec.id;
                case 1: return rec.date;
                case 2: return Record.labelOf(rec.type);
                case 3: return rec.title;
                case 4: return rec.tags;
                case 5: return rec.resultValue == null ? "" : DataParse.fmt(rec.resultValue);
                default: return "";
            }
        }
    }
}