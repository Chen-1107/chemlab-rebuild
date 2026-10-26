package com.chemlab.ui.panels;

import com.chemlab.service.PythonBridge;
import com.chemlab.ui.ChartPanel;
import com.chemlab.ui.FormUtil;
import com.chemlab.ui.Theme;
import com.chemlab.util.DataParse;
import com.chemlab.util.Json;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 光谱图像分析：从谱图截图中提取曲线并做定量处理。
 *
 * <p>图像解析（定位绘图区、分离曲线、寻峰、积分）全部由 Python 分析服务完成，
 * 服务内部再调用 C++ 内核做平滑、基线校正与寻峰。本面板只负责参数收集、
 * 结果渲染与异常提示；Python 服务不可用时优雅降级为提示信息。
 */
public class SpectrumPanel extends JPanel {

    private static final String[] MODE_KEYS = {"auto", "dark", "color"};
    private static final String[] MODE_LABELS = {"自动识别", "深色曲线", "彩色曲线"};

    private final PythonBridge bridge;

    private final JTextField pathField = Theme.field(26);
    private final JComboBox<String> modeBox = new JComboBox<>(MODE_LABELS);
    private final JTextField curvesField = Theme.field(4);
    private final JTextField smoothField = Theme.field(4);
    private final JTextField minHeightField = Theme.field(4);
    private final JTextField minDistField = Theme.field(4);
    private final JTextField xStartField = Theme.field(8);
    private final JTextField xEndField = Theme.field(8);
    private final JCheckBox baselineBox = new JCheckBox("启用线性基线校正", true);

    private final JLabel previewLabel = new JLabel("尚未载入图像", JLabel.CENTER);
    private final JTextArea statsArea = FormUtil.output(12);
    private final ChartPanel chart = new ChartPanel();
    private final DefaultTableModel peakModel = new DefaultTableModel(
            new Object[]{"序号", "峰位 x", "峰高（相对强度）"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final JTable peakTable = new JTable(peakModel);
    private final JLabel statusLabel = Theme.muted("就绪");

    private File imageFile;

    public SpectrumPanel(PythonBridge bridge) {
        this.bridge = bridge;
        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildBody(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);
    }

    // ------------------------------------------------------------------

    private JPanel buildToolbar() {
        pathField.setEditable(false);
        curvesField.setText("1");
        smoothField.setText("7");
        minHeightField.setText("0.08");
        minDistField.setText("8");

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);

        JButton choose = Theme.secondary("选择图像");
        JButton analyze = Theme.primary("开始分析");
        choose.addActionListener(e -> chooseImage());
        analyze.addActionListener(e -> analyze());

        bar.add(choose);
        bar.add(pathField);
        bar.add(new JLabel("曲线数"));
        bar.add(curvesField);
        bar.add(new JLabel("模式"));
        bar.add(modeBox);
        bar.add(new JLabel("平滑窗口"));
        bar.add(smoothField);
        bar.add(new JLabel("峰高阈值"));
        bar.add(minHeightField);
        bar.add(new JLabel("最小峰距"));
        bar.add(minDistField);
        bar.add(new JLabel("横轴范围"));
        bar.add(xStartField);
        bar.add(new JLabel("~"));
        bar.add(xEndField);
        bar.add(baselineBox);
        bar.add(analyze);
        return bar;
    }

    private JPanel buildBody() {
        JPanel options = Theme.titledCard("分析参数");
        options.add(buildOptionsNote(), BorderLayout.CENTER);

        JPanel previewCard = Theme.titledCard("图像预览");
        previewLabel.setVerticalAlignment(JLabel.TOP);
        JScrollPane previewScroll = new JScrollPane(previewLabel);
        previewScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        previewScroll.getVerticalScrollBar().setUnitIncrement(16);
        previewCard.add(previewScroll, BorderLayout.CENTER);

        JPanel chartCard = Theme.titledCard("提取曲线");
        chartCard.add(chart, BorderLayout.CENTER);

        JTabbedPane rightTabs = new JTabbedPane();
        rightTabs.setFont(Theme.base());
        rightTabs.addTab("曲线图", chartCard);
        rightTabs.addTab("图像预览", previewCard);

        peakTable.setFont(Theme.base());
        peakTable.setRowHeight(24);
        peakTable.getTableHeader().setFont(Theme.bold(13));
        JPanel peakCard = Theme.titledCard("峰列表");
        peakCard.add(new JScrollPane(peakTable), BorderLayout.CENTER);

        JPanel statsCard = FormUtil.resultCard("曲线统计", statsArea);

        JPanel bottom = new JPanel(new GridLayout(1, 2, 10, 0));
        bottom.setOpaque(false);
        bottom.add(peakCard);
        bottom.add(statsCard);

        JPanel right = new JPanel(new BorderLayout(0, 10));
        right.setOpaque(false);
        right.add(rightTabs, BorderLayout.CENTER);
        right.add(bottom, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, options, right);
        split.setResizeWeight(0.0);
        split.setDividerLocation(300);
        split.setBorder(null);
        split.setOpaque(false);

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(split, BorderLayout.CENTER);
        return p;
    }

    private JPanel buildOptionsNote() {
        JPanel p = FormUtil.grid();
        FormUtil.note(p, 0, "从光谱仪导出图或手机拍摄的谱图中自动定位绘图区、分离曲线，");
        FormUtil.note(p, 1, "并完成平滑、基线校正、寻峰与面积积分。");
        FormUtil.note(p, 2, "");
        FormUtil.note(p, 3, "· 模式：深色曲线适合黑白谱图，彩色曲线适合多条叠加谱图；");
        FormUtil.note(p, 4, "  自动识别会依据彩色像素占比自动选择。");
        FormUtil.note(p, 5, "· 曲线数：同一张图叠加多个谱图时可设为 2~5，按颜色聚类分离。");
        FormUtil.note(p, 6, "· 横轴范围：填入波长/波数起止值后，横坐标将换算为物理量；");
        FormUtil.note(p, 7, "  留空则使用像素列号。");
        return p;
    }

    // ------------------------------------------------------------------

    private void chooseImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择光谱图像");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "图像文件 (png, jpg, jpeg, bmp, tif)", "png", "jpg", "jpeg", "bmp", "tif", "tiff"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        imageFile = chooser.getSelectedFile();
        pathField.setText(imageFile.getAbsolutePath());
        statusLabel.setText("已选择：" + imageFile.getName());
    }

    private void analyze() {
        if (bridge == null || !bridge.isAvailable()) {
            statsArea.setForeground(Theme.DANGER);
            statsArea.setText("Python 分析服务不可用，无法进行光谱图像分析。\n"
                    + "请确认 Python 环境可用，并已安装 OpenCV / NumPy。\n"
                    + (bridge == null ? "" : "诊断：" + bridge.stderrSummary()));
            return;
        }
        if (imageFile == null) {
            statsArea.setForeground(Theme.WARN);
            statsArea.setText("请先选择一张光谱图像。");
            return;
        }

        final Map<String, Object> params = new LinkedHashMap<>();
        params.put("src", imageFile.getAbsolutePath());
        params.put("maxCurves", parseInt(curvesField.getText(), 1));
        params.put("mode", MODE_KEYS[Math.max(0, modeBox.getSelectedIndex())]);
        params.put("smoothWin", parseInt(smoothField.getText(), 7));
        params.put("baseline", baselineBox.isSelected());
        params.put("minHeight", parseDouble(minHeightField.getText(), 0.08));
        params.put("minDist", parseInt(minDistField.getText(), 8));
        double xs = parseDouble(xStartField.getText(), Double.NaN);
        double xe = parseDouble(xEndField.getText(), Double.NaN);
        if (!Double.isNaN(xs) && !Double.isNaN(xe)) {
            List<Object> range = new ArrayList<>();
            range.add(xs);
            range.add(xe);
            params.put("xRange", range);
        }

        statsArea.setForeground(Theme.MUTED);
        statsArea.setText("正在分析图像…");
        statusLabel.setText("分析中…");
        chart.clear();
        peakModel.setRowCount(0);

        new SwingWorker<Map<String, Object>, Void>() {
            @Override
            protected Map<String, Object> doInBackground() {
                return bridge.call("spectrum.analyze", params, 120_000L);
            }

            @Override
            protected void done() {
                try {
                    render(get());
                    statusLabel.setText("分析完成");
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    statsArea.setForeground(Theme.DANGER);
                    statsArea.setText("分析失败：" + cause.getMessage());
                    statusLabel.setText("分析失败");
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------------

    private void render(Map<String, Object> res) {
        renderPreview(Json.str(res.get("preview"), ""));
        renderChart(res);
        renderStats(res);
    }

    private void renderPreview(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:image")) {
            previewLabel.setIcon(null);
            previewLabel.setText("无预览图");
            return;
        }
        try {
            String b64 = dataUrl.substring(dataUrl.indexOf(',') + 1);
            byte[] bytes = Base64.getDecoder().decode(b64);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                previewLabel.setText("预览图解码失败");
                return;
            }
            previewLabel.setText(null);
            previewLabel.setIcon(new ImageIcon(img));
        } catch (Exception e) {
            previewLabel.setIcon(null);
            previewLabel.setText("预览图解码失败：" + e.getMessage());
        }
    }

    private void renderChart(Map<String, Object> res) {
        List<Object> curves = Json.asArray(res.get("curves"));
        chart.clear();
        peakModel.setRowCount(0);
        if (curves.isEmpty()) {
            return;
        }

        String xLabel = "像素列";
        boolean physical = false;
        // 若首个曲线的 x 不呈整数递增，视为已换算为物理量
        Map<String, Object> first = Json.asObject(curves.get(0));
        List<Object> fx = Json.asArray(first.get("x"));
        if (fx.size() > 1) {
            double a = Json.num(fx.get(0), 0);
            double b = Json.num(fx.get(1), 0);
            if (Math.abs(b - a) < 0.5 && Math.abs(a - Math.rint(a)) > 1e-9) {
                physical = true;
                xLabel = "波长 / 波数";
            }
        }

        int idx = 0;
        for (Object o : curves) {
            Map<String, Object> c = Json.asObject(o);
            List<Object> xs = Json.asArray(c.get("x"));
            List<Object> yRaw = Json.asArray(c.get("yRaw"));
            List<Object> yProc = Json.asArray(c.get("y"));
            double[] x = toArray(xs);
            double[] y = toArray(yProc);
            double[] yr = toArray(yRaw);
            String label = Json.str(c.get("label"), "曲线 " + (idx + 1));

            if (x.length > 0 && yr.length == x.length) {
                chart.addSeries(new ChartPanel.Series(label + "（原始）",
                        Theme.SERIES[(idx * 2) % Theme.SERIES.length],
                        ChartPanel.Type.LINE, x, yr));
            }
            if (x.length > 0 && y.length == x.length) {
                chart.addSeries(new ChartPanel.Series(label + "（处理后）",
                        Theme.SERIES[(idx * 2 + 1) % Theme.SERIES.length],
                        ChartPanel.Type.LINE, x, y));
            }

            for (Object po : Json.asArray(c.get("peaks"))) {
                Map<String, Object> pk = Json.asObject(po);
                int pi = Json.intOf(pk.get("index"), -1);
                double h = Json.num(pk.get("height"), 0);
                double px = (pi >= 0 && pi < x.length) ? x[pi] : pi;
                chart.addMarker(px, physical ? DataParse.fmt(px, 1) : null);
                peakModel.addRow(new Object[]{
                        peakModel.getRowCount() + 1,
                        physical ? DataParse.fmt(px) : DataParse.fmt(px),
                        DataParse.fmt(h, 4),
                });
            }
            idx++;
        }
        chart.setLabels("光谱曲线", xLabel, "相对强度");
    }

    private void renderStats(Map<String, Object> res) {
        List<Object> curves = Json.asArray(res.get("curves"));
        StringBuilder sb = new StringBuilder();
        sb.append("图像尺寸   = ").append(Json.intOf(res.get("imageWidth"), 0))
                .append(" × ").append(Json.intOf(res.get("imageHeight"), 0)).append('\n');
        List<Object> roi = Json.asArray(res.get("plotArea"));
        if (roi.size() == 4) {
            sb.append("绘图区 ROI = [").append(Json.intOf(roi.get(0), 0)).append(", ")
                    .append(Json.intOf(roi.get(1), 0)).append(", ")
                    .append(Json.intOf(roi.get(2), 0)).append(", ")
                    .append(Json.intOf(roi.get(3), 0)).append("]\n");
        }
        sb.append('\n');

        int idx = 0;
        for (Object o : curves) {
            Map<String, Object> c = Json.asObject(o);
            sb.append("【").append(Json.str(c.get("label"), "曲线 " + (idx + 1))).append("】\n");
            sb.append("  提取点数   = ").append(Json.intOf(c.get("pointCount"), 0)).append('\n');
            sb.append("  峰数量     = ").append(Json.asArray(c.get("peaks")).size()).append('\n');
            sb.append("  曲线面积   = ").append(DataParse.fmt(Json.num(c.get("area"), 0))).append('\n');
            Map<String, Object> st = Json.asObject(c.get("stats"));
            if (!st.isEmpty()) {
                sb.append("  均值       = ").append(DataParse.fmt(Json.num(st.get("mean"), 0))).append('\n');
                sb.append("  标准差     = ").append(DataParse.fmt(Json.num(st.get("stddev"), 0))).append('\n');
                sb.append("  RSD %      = ").append(DataParse.fmt(Json.num(st.get("rsd"), 0), 3)).append('\n');
                sb.append("  最大/最小  = ").append(DataParse.fmt(Json.num(st.get("max"), 0)))
                        .append(" / ").append(DataParse.fmt(Json.num(st.get("min"), 0))).append('\n');
            }
            sb.append('\n');
            idx++;
        }
        if (sb.length() == 0) {
            sb.append("未得到有效曲线。");
        }
        statsArea.setForeground(Theme.TEXT);
        statsArea.setText(sb.toString());
        statsArea.setCaretPosition(0);
    }

    // ------------------------------------------------------------------

    private static double[] toArray(List<Object> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = Json.num(list.get(i), Double.NaN);
        }
        return a;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    /** 供主窗口在启动时填充默认提示。 */
    public void setStatus(String text) {
        statusLabel.setText(text);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(1100, 700);
    }
}