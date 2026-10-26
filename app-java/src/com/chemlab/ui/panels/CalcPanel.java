package com.chemlab.ui.panels;

import com.chemlab.core.NativeCore;
import com.chemlab.ui.ChartPanel;
import com.chemlab.ui.FormUtil;
import com.chemlab.ui.Theme;
import com.chemlab.util.DataParse;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.function.Supplier;

/** 基础化学计算：浓度、pH、滴定、光度。全部数值由 C++ 内核计算。 */
public class CalcPanel extends JPanel {

    public CalcPanel() {
        setLayout(new BorderLayout(0, 10));
        setBackground(Theme.BG);
        setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Theme.base());
        tabs.addTab("浓度计算", buildConcentration());
        tabs.addTab("标准溶液配制", buildStdSolution());
        tabs.addTab("pH 计算", buildPh());
        tabs.addTab("滴定分析", buildTitration());
        tabs.addTab("滴定曲线", buildTitrationCurve());
        tabs.addTab("光度分析", buildSpectro());
        add(tabs, BorderLayout.CENTER);
    }

    // ------------------------------------------------------------------
    // 浓度
    // ------------------------------------------------------------------

    private JPanel buildConcentration() {
        JTextField mass = Theme.field(12);
        JTextField molar = Theme.field(12);
        JTextField vol = Theme.field(12);
        JTextField c1 = Theme.field(12);
        JTextField v1 = Theme.field(12);
        JTextField v2 = Theme.field(12);
        JTextArea out = FormUtil.output(8);

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "质量 m", mass, "g");
        FormUtil.row(form, 1, "摩尔质量 M", molar, "g/mol");
        FormUtil.row(form, 2, "溶液体积 V", vol, "L");
        FormUtil.note(form, 3, "由 m、M、V 求物质的量浓度 c = m /(M·V)，并给出质量浓度 ρ = c·M。");
        FormUtil.row(form, 4, "稀释前浓度 c₁", c1, "mol/L");
        FormUtil.row(form, 5, "稀释前体积 V₁", v1, "mL");
        FormUtil.row(form, 6, "稀释后体积 V₂", v2, "mL");
        FormUtil.note(form, 7, "稀释定律 c₁V₁ = c₂V₂，体积单位一致即可。");

        JButton run = Theme.primary("计算");
        run.addActionListener(e -> compute(out, () -> {
            StringBuilder sb = new StringBuilder();
            if (!mass.getText().isBlank() && !molar.getText().isBlank() && !vol.getText().isBlank()) {
                double m = val(mass, "质量 m");
                double M = val(molar, "摩尔质量 M");
                double V = val(vol, "体积 V");
                double c = NativeCore.molarity(m, M, V);
                double rho = NativeCore.massConc(c, M);
                sb.append(String.format("物质的量浓度 c = %s mol/L%n", DataParse.fmt(c)));
                sb.append(String.format("质量浓度 ρ = %s g/L%n", DataParse.fmt(rho)));
                sb.append(String.format("换算：%s mg/mL%n", DataParse.fmt(rho)));
            }
            if (!c1.getText().isBlank() && !v1.getText().isBlank() && !v2.getText().isBlank()) {
                double a = val(c1, "稀释前浓度 c₁");
                double b = val(v1, "稀释前体积 V₁");
                double d = val(v2, "稀释后体积 V₂");
                double c2v = NativeCore.dilution(a, b, d);
                sb.append(String.format("稀释后浓度 c₂ = %s mol/L%n", DataParse.fmt(c2v)));
                sb.append(String.format("稀释倍数 = %s 倍%n", DataParse.fmt(d / b)));
            }
            if (sb.length() == 0) {
                throw new IllegalArgumentException("请至少填写一组完整输入");
            }
            return sb.toString();
        }));

        return wrap(form, run, out, "输入");
    }

    // ------------------------------------------------------------------
    // 标准溶液配制
    // ------------------------------------------------------------------

    /** 常用基准物质，末项为自定义。摩尔质量见 {@link #STD_MOLAR}。 */
    private static final String[] STD_SUBSTANCES = {
            "邻苯二甲酸氢钾 KHC8H4O4（标定碱）",
            "无水碳酸钠 Na2CO3（标定酸）",
            "硼砂 Na2B4O7·10H2O（标定酸）",
            "碳酸钙 CaCO3（标定 EDTA）",
            "氧化锌 ZnO（标定 EDTA）",
            "重铬酸钾 K2Cr2O7（氧化还原）",
            "草酸钠 Na2C2O4（标定 KMnO4）",
            "二水合草酸 H2C2O4·2H2O",
            "氯化钠 NaCl（标定 AgNO3）",
            "硝酸银 AgNO3（沉淀滴定）",
            "碘酸钾 KIO3（碘量法）",
            "溴酸钾 KBrO3（溴量法）",
            "苯甲酸 C6H5COOH（标定碱）",
            "氨基磺酸 H3NO3S（标定碱）",
            "三羟甲基氨基甲烷 C4H11NO3（TRIS）",
            "五水合硫酸铜 CuSO4·5H2O",
            "自定义（手工输入摩尔质量）",
    };
    private static final double[] STD_MOLAR = {
            204.22, 105.99, 381.37, 100.09, 81.38, 294.18, 134.00, 126.07,
            58.44, 169.87, 214.00, 167.00, 122.12, 97.09, 121.14, 249.68, 0.0,
    };

    private JPanel buildStdSolution() {
        JComboBox<String> substance = new JComboBox<>(STD_SUBSTANCES);
        substance.setFont(Theme.base());
        JComboBox<String> mode = new JComboBox<>(new String[]{
                "直接法：反算应称质量", "标定法：由称样量求实际浓度"});
        mode.setFont(Theme.base());
        JTextField molar = Theme.field(12);
        JTextField conc = Theme.field(12);
        JTextField vol = Theme.field(12);
        JTextField purity = Theme.field(12);
        JTextField factor = Theme.field(12);
        JTextField mass = Theme.field(12);
        JTextArea out = FormUtil.output(12);

        substance.setSelectedIndex(0);
        molar.setText(DataParse.fmt(STD_MOLAR[0], 4));
        conc.setText("0.1");
        vol.setText("250");
        purity.setText("100");
        factor.setText("1");
        mass.setText(DataParse.fmt(0.1 * 0.25 * 204.22, 4));

        substance.addActionListener(e -> {
            int i = substance.getSelectedIndex();
            if (i >= 0 && STD_MOLAR[i] > 0) {
                molar.setText(DataParse.fmt(STD_MOLAR[i], 4));
            }
        });
        mode.addActionListener(e -> {
            boolean direct = mode.getSelectedIndex() == 0;
            conc.setEnabled(direct);
            factor.setEnabled(direct);
            mass.setEnabled(!direct);
        });

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "基准物质", substance);
        FormUtil.row(form, 1, "摩尔质量 M", molar, "g/mol，随物质自动填入");
        FormUtil.row(form, 2, "计算模式", mode);
        FormUtil.row(form, 3, "目标浓度 c", conc, "mol/L（直接法）");
        FormUtil.row(form, 4, "定容体积 V", vol, "mL（两法共用）");
        FormUtil.row(form, 5, "纯度 w", purity, "%（两法共用）");
        FormUtil.row(form, 6, "称大样倍数 k", factor, "直接法，默认 1");
        FormUtil.row(form, 7, "称取质量 m", mass, "g（标定法）");
        FormUtil.note(form, 8, "直接法 m = c·V·k·M / w；标定法 c = m·w /(M·V)。");

        JButton run = Theme.primary("计算");
        run.addActionListener(e -> compute(out, () -> {
            double M = val(molar, "摩尔质量 M");
            double w = val(purity, "纯度 w") / 100.0;
            double V = val(vol, "定容体积 V") / 1000.0;
            if (mode.getSelectedIndex() == 0) {
                double c = val(conc, "目标浓度 c");
                double k = factor.getText().isBlank() ? 1.0 : val(factor, "称大样倍数 k");
                double[] r = NativeCore.stdPrepMass(c, V, M, w, k);
                if (r == null) {
                    throw new IllegalStateException("内核计算失败，请检查参数（浓度、体积、摩尔质量、纯度、倍数需合法）");
                }
                double mSample = r[0];
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("应称取基准物质 m = %s g%n", DataParse.fmt(mSample, 4)));
                sb.append(String.format("其中纯物质质量 = %s g%n", DataParse.fmt(r[1], 4)));
                sb.append(String.format("物质的量 n = %s mmol%n", DataParse.fmt(r[2] * 1000, 4)));
                sb.append(String.format("质量浓度 ρ = %s g/L%n", DataParse.fmt(r[3], 4)));
                sb.append(String.format("定容体积 = %s mL（称大样 %s 倍）%n",
                        DataParse.fmt(V * k * 1000.0, 1), DataParse.fmt(k, 2)));
                sb.append(String.format("称量相对不确定度 ≈ %s%%（按万分之一天平 ±0.1 mg 估）%n",
                        DataParse.fmt(weighRelUnc(mSample), 4)));
                sb.append("建议：").append(weighAdvice(mSample));
                return sb.toString();
            }
            double mG = val(mass, "称取质量 m");
            double[] r = NativeCore.stdPrepConc(mG, M, w, V);
            if (r == null) {
                throw new IllegalStateException("内核计算失败，请检查参数（称样量、体积、摩尔质量、纯度需合法）");
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("所配标准溶液浓度 c = %s mol/L%n", DataParse.fmt(r[0], 6)));
            sb.append(String.format("物质的量 n = %s mmol%n", DataParse.fmt(r[1] * 1000, 4)));
            sb.append(String.format("纯物质质量 = %s g%n", DataParse.fmt(r[2], 4)));
            sb.append(String.format("质量浓度 ρ = %s g/L%n", DataParse.fmt(r[3], 4)));
            sb.append(String.format("称量相对不确定度 ≈ %s%%（按万分之一天平 ±0.1 mg 估）%n",
                    DataParse.fmt(weighRelUnc(mG), 4)));
            sb.append("提示：用该溶液标定其他溶液，请在「滴定分析」页以上述浓度为 c_std 代入。");
            return sb.toString();
        }));

        return wrap(form, run, out, "配制参数");
    }

    /** 按万分之一天平单次读数 ±0.1 mg 估算称量相对不确定度 (%)。 */
    private static double weighRelUnc(double massG) {
        return 0.0001 / massG * 100.0;
    }

    private static String weighAdvice(double massG) {
        if (massG < 0.2) {
            return "称样量偏小，建议增大称大样倍数 k（同步放大定容体积）或改用微量天平。";
        }
        if (massG > 6.0) {
            return "称样量偏大，注意天平量程；可减小倍数 k 或分次称量。";
        }
        return "称样量适中，常规万分之一分析天平即可满足要求。";
    }

    // ------------------------------------------------------------------
    // pH
    // ------------------------------------------------------------------

    private JPanel buildPh() {
        JComboBox<String> type = new JComboBox<>(new String[]{
                "强酸溶液", "强碱溶液", "一元弱酸", "一元弱碱", "缓冲溶液"});
        type.setFont(Theme.base());
        JTextField conc = Theme.field(12);
        JTextField ka = Theme.field(12);
        JTextField kb = Theme.field(12);
        JTextField ca = Theme.field(12);
        JTextField cb = Theme.field(12);
        JTextArea out = FormUtil.output(9);

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "溶液类型", type);
        FormUtil.row(form, 1, "浓度 c", conc, "mol/L");
        FormUtil.row(form, 2, "酸常数 Ka", ka, "弱酸用");
        FormUtil.row(form, 3, "碱常数 Kb", kb, "弱碱用");
        FormUtil.row(form, 4, "共轭酸浓度 cₐ", ca, "缓冲溶液用");
        FormUtil.row(form, 5, "共轭碱浓度 c_b", cb, "缓冲溶液用");
        FormUtil.note(form, 6, "缓冲溶液 pH = pKa + log(c_b/cₐ)，Ka 为共轭酸的解离常数。");

        JButton run = Theme.primary("计算 pH");
        run.addActionListener(e -> compute(out, () -> {
            String t = (String) type.getSelectedItem();
            StringBuilder sb = new StringBuilder();
            switch (t) {
                case "强酸溶液": {
                    double c = val(conc, "浓度 c");
                    sb.append(String.format("pH = %s%n", DataParse.fmt(NativeCore.pHStrongAcid(c))));
                    sb.append("完全电离：[H⁺] = c");
                    break;
                }
                case "强碱溶液": {
                    double c = val(conc, "浓度 c");
                    sb.append(String.format("pH = %s%n", DataParse.fmt(NativeCore.pHStrongBase(c))));
                    sb.append("完全电离：[OH⁻] = c，pH = 14 - pOH");
                    break;
                }
                case "一元弱酸": {
                    double c = val(conc, "浓度 c");
                    double k = val(ka, "酸常数 Ka");
                    double[] r = NativeCore.pHWeakAcid(c, k);
                    if (r == null) {
                        throw new IllegalStateException("内核计算失败，请检查输入");
                    }
                    sb.append(String.format("pH = %s%n", DataParse.fmt(r[0])));
                    sb.append(String.format("解离度 α = %s（%s%%）%n",
                            DataParse.fmt(r[1]), DataParse.fmt(r[1] * 100, 3)));
                    sb.append(String.format("[H⁺] = %s mol/L%n", DataParse.fmt(r[2])));
                    sb.append(String.format("pKa = %s", DataParse.fmt(-Math.log10(k), 3)));
                    break;
                }
                case "一元弱碱": {
                    double c = val(conc, "浓度 c");
                    double k = val(kb, "碱常数 Kb");
                    double[] r = NativeCore.pHWeakBase(c, k);
                    if (r == null) {
                        throw new IllegalStateException("内核计算失败，请检查输入");
                    }
                    sb.append(String.format("pH = %s%n", DataParse.fmt(r[0])));
                    sb.append(String.format("解离度 α = %s（%s%%）%n",
                            DataParse.fmt(r[1]), DataParse.fmt(r[1] * 100, 3)));
                    sb.append(String.format("[OH⁻] = %s mol/L%n", DataParse.fmt(r[2])));
                    sb.append(String.format("pKb = %s", DataParse.fmt(-Math.log10(k), 3)));
                    break;
                }
                default: {
                    double k = val(ka, "酸常数 Ka");
                    double a = val(ca, "共轭酸浓度 cₐ");
                    double b = val(cb, "共轭碱浓度 c_b");
                    double[] r = NativeCore.pHBuffer(k, a, b);
                    if (r == null) {
                        throw new IllegalStateException("内核计算失败，请检查输入");
                    }
                    sb.append(String.format("pH = %s%n", DataParse.fmt(r[0])));
                    sb.append(String.format("缓冲比 c_b/cₐ = %s%n", DataParse.fmt(r[1])));
                    sb.append(String.format("pKa = %s", DataParse.fmt(-Math.log10(k), 3)));
                }
            }
            return sb.toString();
        }));

        return wrap(form, run, out, "输入");
    }

    // ------------------------------------------------------------------
    // 滴定
    // ------------------------------------------------------------------

    private JPanel buildTitration() {
        JTextField cStd = Theme.field(12);
        JTextField vStd = Theme.field(12);
        JTextField vSample = Theme.field(12);
        JTextField ratio = Theme.field(12);
        JTextField molar = Theme.field(12);
        JTextArea out = FormUtil.output(9);

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "标准液浓度 c_std", cStd, "mol/L");
        FormUtil.row(form, 1, "标准液体积 V_std", vStd, "mL");
        FormUtil.row(form, 2, "待测液体积 V_sample", vSample, "mL");
        FormUtil.row(form, 3, "计量比 ratio", ratio, "n(待测)/n(标准)，默认 1");
        FormUtil.row(form, 4, "待测物摩尔质量 M", molar, "选填，用于求质量");
        FormUtil.note(form, 5, "c_待测 = c_std · V_std · ratio / V_sample");

        JButton run = Theme.primary("计算");
        run.addActionListener(e -> compute(out, () -> {
            double cs = val(cStd, "标准液浓度");
            double vs = val(vStd, "标准液体积");
            double vp = val(vSample, "待测液体积");
            double r = ratio.getText().isBlank() ? 1.0 : val(ratio, "计量比");
            double c = NativeCore.titration(cs, vs, vp, r);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("待测液浓度 c = %s mol/L%n", DataParse.fmt(c)));
            double nSample = c * (vp / 1000.0);
            sb.append(String.format("待测物物质的量 n = %s mmol%n", DataParse.fmt(nSample * 1000)));
            if (!molar.getText().isBlank()) {
                double M = val(molar, "摩尔质量 M");
                sb.append(String.format("待测物质量 m = %s mg%n",
                        DataParse.fmt(nSample * M * 1000)));
            }
            sb.append(String.format("标准液物质的量 n_std = %s mmol",
                    DataParse.fmt(cs * (vs / 1000.0) * 1000)));
            return sb.toString();
        }));

        return wrap(form, run, out, "输入");
    }

    // ------------------------------------------------------------------
    // 滴定曲线模拟
    // ------------------------------------------------------------------

    private static final String[] CURVE_SYSTEMS = {
            "强碱滴定强酸（NaOH 滴 HCl）",
            "强碱滴定弱酸（NaOH 滴 HA）",
            "强酸滴定强碱（HCl 滴 NaOH）",
            "强酸滴定弱碱（HCl 滴 B）",
    };

    private JPanel buildTitrationCurve() {
        JComboBox<String> type = new JComboBox<>(CURVE_SYSTEMS);
        type.setFont(Theme.base());
        JTextField cAnal = Theme.field(12);
        JTextField vAnal = Theme.field(12);
        JTextField cTitr = Theme.field(12);
        JTextField kAnal = Theme.field(12);
        JTextField vFactor = Theme.field(12);
        vFactor.setText("2.0");
        JTextArea out = FormUtil.output(7);
        ChartPanel chart = new ChartPanel();

        // 经典示例：0.1 mol/L NaOH 滴定 25 mL 0.1 mol/L HAc
        type.setSelectedIndex(1);
        cAnal.setText("0.1");
        vAnal.setText("25");
        cTitr.setText("0.1");
        kAnal.setText("1.8e-5");

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "滴定体系", type);
        FormUtil.row(form, 1, "被滴定液浓度 c", cAnal, "mol/L");
        FormUtil.row(form, 2, "被滴定液体积 V", vAnal, "mL");
        FormUtil.row(form, 3, "滴定剂浓度 c′", cTitr, "mol/L");
        FormUtil.row(form, 4, "Ka / Kb", kAnal, "弱酸弱碱体系必填");
        FormUtil.row(form, 5, "横轴范围", vFactor, "×Veq，默认 2.0");
        FormUtil.note(form, 6, "对每个滴定体积解电荷平衡方程求平衡 pH，"
                + "虚线标注等当点，ΔpH 为 ±0.1% 误差范围内的突跃。");

        JButton run = Theme.primary("绘制曲线");
        run.addActionListener(e -> simulateCurve(type.getSelectedIndex(),
                cAnal, vAnal, cTitr, kAnal, vFactor, chart, out));

        JPanel left = Theme.titledCard("体系参数");
        left.add(form, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(run, BorderLayout.WEST);
        left.add(south, BorderLayout.SOUTH);

        JPanel right = Theme.titledCard("滴定曲线");
        right.add(chart, BorderLayout.CENTER);
        right.add(out, BorderLayout.SOUTH);

        JPanel body = new JPanel(new GridLayout(1, 2, 12, 0));
        body.setOpaque(false);
        body.add(left);
        body.add(right);

        JPanel root = new JPanel(new BorderLayout());
        root.setOpaque(false);
        root.add(coreBanner(), BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        return root;
    }

    private void simulateCurve(int type, JTextField cAnalF, JTextField vAnalF,
                               JTextField cTitrF, JTextField kAnalF, JTextField vFactorF,
                               ChartPanel chart, JTextArea out) {
        try {
            double cA = val(cAnalF, "被滴定液浓度 c");
            double vA = val(vAnalF, "被滴定液体积 V");
            double cT = val(cTitrF, "滴定剂浓度 c′");
            String kText = kAnalF.getText().trim();
            if ((type == 1 || type == 3) && kText.isEmpty()) {
                throw new IllegalArgumentException("弱酸 / 弱碱体系必须填写 Ka / Kb");
            }
            double k = kText.isEmpty() ? 1.0 : val(kAnalF, "Ka / Kb");
            double factor = vFactorF.getText().isBlank() ? 2.0 : val(vFactorF, "横轴范围");
            if (factor <= 1.02) {
                throw new IllegalArgumentException("横轴范围需明显大于 1×Veq，否则看不到等当点后的曲线");
            }

            double vEq = cA * vA / cT;
            double vMax = factor * vEq;
            int n = 241;
            double[] vols = new double[n];
            for (int i = 0; i < n; i++) {
                vols[i] = vMax * i / (n - 1.0);
            }
            double[] ph = NativeCore.titrationCurve(type, cA, vA, cT, k, vols);
            double[] keyVols = {0.0, 0.999 * vEq, vEq, 1.001 * vEq};
            double[] keyPh = NativeCore.titrationCurve(type, cA, vA, cT, k, keyVols);
            if (ph == null || keyPh == null) {
                throw new IllegalStateException("内核计算失败，请检查参数（浓度需为正、Ka/Kb 需合法）");
            }

            chart.clear();
            chart.setLabels(CURVE_SYSTEMS[type], "滴定剂体积 V (mL)", "pH");
            chart.addSeries(new ChartPanel.Series("pH–V 曲线", Theme.ACCENT,
                    ChartPanel.Type.LINE, vols, ph));
            chart.addMarker(vEq, "Veq=" + DataParse.fmt(vEq, 2) + "mL");

            double jumpLow = Math.min(keyPh[1], keyPh[3]);
            double jumpHigh = Math.max(keyPh[1], keyPh[3]);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("等当点体积 Veq = %s mL%n", DataParse.fmt(vEq, 3)));
            sb.append(String.format("起始 pH（V=0）= %s%n", DataParse.fmt(keyPh[0], 2)));
            sb.append(String.format("等当点 pH = %s%n", DataParse.fmt(keyPh[2], 2)));
            sb.append(String.format("突跃范围（±0.1%%）：pH %s ~ %s（ΔpH = %s）%n",
                    DataParse.fmt(jumpLow, 2), DataParse.fmt(jumpHigh, 2),
                    DataParse.fmt(jumpHigh - jumpLow, 2)));
            sb.append(String.format("指示剂建议：%s", suggestIndicator(keyPh[2], jumpLow, jumpHigh)));
            out.setForeground(Theme.TEXT);
            out.setText(sb.toString());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            out.setForeground(Theme.DANGER);
            out.setText(ex.getMessage());
        }
    }

    /** 按突跃范围与等当点 pH 挑选变色区间落在突跃内的常用指示剂。 */
    private static String suggestIndicator(double eqPh, double jumpLow, double jumpHigh) {
        StringBuilder sb = new StringBuilder();
        if (jumpLow <= 3.1 && jumpHigh >= 4.4) {
            sb.append("甲基橙（3.1–4.4） ");
        }
        if (jumpLow <= 4.4 && jumpHigh >= 6.2) {
            sb.append("甲基红（4.4–6.2） ");
        }
        if (jumpLow <= 6.0 && jumpHigh >= 7.6) {
            sb.append("溴百里酚蓝（6.0–7.6） ");
        }
        if (jumpLow <= 8.0 && jumpHigh >= 10.0) {
            sb.append("酚酞（8.0–10.0） ");
        }
        if (jumpLow <= 9.4 && jumpHigh >= 10.6) {
            sb.append("百里酚酞（9.4–10.6）");
        }
        if (sb.length() == 0) {
            return "突跃过窄（ΔpH < 0.4），建议电位滴定法确定终点";
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // 光度
    // ------------------------------------------------------------------

    private JPanel buildSpectro() {
        JComboBox<String> mode = new JComboBox<>(new String[]{
                "求吸光度 A", "求浓度 c", "求摩尔吸光系数 ε"});
        mode.setFont(Theme.base());
        JTextField eps = Theme.field(12);
        JTextField path = Theme.field(12);
        JTextField conc = Theme.field(12);
        JTextField abs = Theme.field(12);
        JTextArea out = FormUtil.output(9);

        JPanel form = FormUtil.grid();
        FormUtil.row(form, 0, "求解目标", mode);
        FormUtil.row(form, 1, "摩尔吸光系数 ε", eps, "L/(mol·cm)");
        FormUtil.row(form, 2, "光程 b", path, "cm");
        FormUtil.row(form, 3, "浓度 c", conc, "mol/L");
        FormUtil.row(form, 4, "吸光度 A", abs, "—");
        FormUtil.note(form, 5, "Beer–Lambert 定律 A = ε·b·c，按所选目标忽略对应输入项。");

        JButton run = Theme.primary("计算");
        run.addActionListener(e -> compute(out, () -> {
            String m = (String) mode.getSelectedItem();
            StringBuilder sb = new StringBuilder();
            if ("求吸光度 A".equals(m)) {
                double epsV = val(eps, "摩尔吸光系数 ε");
                double b = val(path, "光程 b");
                double c = val(conc, "浓度 c");
                double a = NativeCore.beerLambertAbs(epsV, b, c);
                sb.append(String.format("吸光度 A = %s%n", DataParse.fmt(a)));
                sb.append(String.format("透光率 T = %s%%%n", DataParse.fmt(Math.pow(10, -a) * 100, 3)));
                if (a > 1.0) {
                    sb.append("提示：A > 1.0，偏离线性范围，建议稀释后重测。");
                } else {
                    sb.append("提示：A 在常规线性范围内。");
                }
            } else if ("求浓度 c".equals(m)) {
                double a = val(abs, "吸光度 A");
                double epsV = val(eps, "摩尔吸光系数 ε");
                double b = val(path, "光程 b");
                double c = NativeCore.beerLambertConc(a, epsV, b);
                sb.append(String.format("浓度 c = %s mol/L%n", DataParse.fmt(c)));
                sb.append(String.format("            = %s mmol/L", DataParse.fmt(c * 1000)));
            } else {
                double a = val(abs, "吸光度 A");
                double b = val(path, "光程 b");
                double c = val(conc, "浓度 c");
                double epsV = NativeCore.beerLambertEpsilon(a, b, c);
                sb.append(String.format("摩尔吸光系数 ε = %s L/(mol·cm)%n", DataParse.fmt(epsV)));
                if (epsV > 1e4) {
                    sb.append("提示：ε 较大，属强吸收体系，方法灵敏度高。");
                }
            }
            return sb.toString();
        }));

        return wrap(form, run, out, "输入");
    }

    // ------------------------------------------------------------------
    // 通用
    // ------------------------------------------------------------------

    private JPanel wrap(JPanel form, JButton run, JTextArea out, String formTitle) {
        JPanel left = Theme.titledCard(formTitle);
        left.add(form, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(run, BorderLayout.WEST);
        left.add(south, BorderLayout.SOUTH);

        JPanel right = FormUtil.resultCard("计算结果", out);

        JPanel body = new JPanel(new GridLayout(1, 2, 12, 0));
        body.setOpaque(false);
        body.add(left);
        body.add(right);

        JPanel root = new JPanel(new BorderLayout());
        root.setOpaque(false);
        root.add(coreBanner(), BorderLayout.NORTH);
        root.add(body, BorderLayout.CENTER);
        return root;
    }

    static JComponent coreBanner() {
        if (NativeCore.isAvailable()) {
            return new JPanel() {{
                setOpaque(false);
            }};
        }
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new java.awt.Color(0xFE, 0xF3, 0xC7));
        p.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        p.add(Theme.muted("计算内核未加载，数值计算不可用：" + NativeCore.loadError()),
                BorderLayout.WEST);
        return p;
    }

    private static double val(JTextField f, String name) {
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
}