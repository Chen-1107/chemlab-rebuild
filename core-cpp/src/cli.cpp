/*
 * cli.cpp —— 内核自检与脚本化调用入口。
 *
 * 用法：
 *   chemlab_cli --selftest      运行内置算法自检，返回 0 表示全部通过
 *   chemlab_cli --version       打印内核版本
 *
 * 自检覆盖各模块的解析解/已知值，用于构建后快速验证内核正确性。
 */
#include "chemlab_core.h"
#include <cstdio>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

namespace {

int g_pass = 0;
int g_fail = 0;

bool nearly(double a, double b, double tol) {
    return std::fabs(a - b) <= tol;
}

void check(const char* name, double got, double want, double tol) {
    bool ok = nearly(got, want, tol);
    if (ok) {
        ++g_pass;
        std::printf("  [PASS] %-42s got=%.10g\n", name, got);
    } else {
        ++g_fail;
        std::printf("  [FAIL] %-42s got=%.10g want=%.10g\n", name, got, want);
    }
}

void check_true(const char* name, bool cond) {
    if (cond) {
        ++g_pass;
        std::printf("  [PASS] %s\n", name);
    } else {
        ++g_fail;
        std::printf("  [FAIL] %s\n", name);
    }
}

/* 用于 cl_unc_general 的示例函数：y = x0 * x1 / x2 */
double f_ratio(const double* x, int n, void* ctx) {
    (void)n; (void)ctx;
    return x[0] * x[1] / x[2];
}

int selftest() {
    std::printf("chemlab-core 自检 —— %s\n\n", cl_version());

    /* ---- 1. 描述统计 ---- */
    std::printf("[1] 描述统计\n");
    {
        double d[5] = {1, 2, 3, 4, 5};
        double o[8];
        int rc = cl_describe(d, 5, o);
        check_true("cl_describe 返回成功", rc == CL_OK);
        check("mean{1..5}", o[0], 3.0, 1e-12);
        check("median{1..5}", o[1], 3.0, 1e-12);
        check("sample stddev{1..5}", o[2], std::sqrt(2.5), 1e-12);
        check("rsd%{1..5}", o[3], std::sqrt(2.5) / 3.0 * 100.0, 1e-10);
        check("variance{1..5}", o[4], 2.5, 1e-12);
        check("sem{1..5}", o[5], std::sqrt(2.5) / std::sqrt(5.0), 1e-12);
        check("min", o[6], 1.0, 1e-12);
        check("max", o[7], 5.0, 1e-12);
    }
    {
        double d[6] = {10, 20, 30, 40, 50, 60};
        double o[8];
        cl_describe(d, 6, o);
        check("median 偶数样本", o[1], 35.0, 1e-12);
    }

    /* ---- 2. 线性回归 ---- */
    std::printf("\n[2] 线性回归\n");
    {
        double x[5] = {1, 2, 3, 4, 5};
        double y[5] = {3, 5, 7, 9, 11};   // y = 2x + 1，完美线性
        double o[8];
        int rc = cl_linreg(x, y, 5, o);
        check_true("cl_linreg 返回成功", rc == CL_OK);
        check("slope (期望 2)", o[0], 2.0, 1e-10);
        check("intercept (期望 1)", o[1], 1.0, 1e-10);
        check("r² (期望 1)", o[2], 1.0, 1e-10);
        check("sy/x (期望 0)", o[4], 0.0, 1e-9);
    }
    {
        // 带噪声的标准曲线，检验 r² 与 LOD/LOQ 量级
        double x[6] = {0.0, 1.0, 2.0, 3.0, 4.0, 5.0};
        double y[6] = {0.002, 0.201, 0.399, 0.602, 0.798, 1.001};
        double o[8];
        cl_linreg(x, y, 6, o);
        check_true("含噪回归 slope≈0.2", nearly(o[0], 0.2, 0.002));
        check_true("含噪回归 r²>0.999", o[2] > 0.999);
        double lq[2];
        cl_lod_loq(o[0], o[4], 3.0, 10.0, lq);
        check_true("LOD < LOQ", lq[0] < lq[1]);
        double inv;
        cl_calib_inverse(0.5, o[0], o[1], &inv);
        check_true("反算浓度 ≈ 2.5", nearly(inv, 2.5, 0.05));
    }

    /* ---- 3. 异常值检验 ---- */
    std::printf("\n[3] 异常值检验\n");
    {
        double d[8] = {10.2, 10.1, 10.3, 10.2, 10.4, 10.1, 10.3, 18.0};
        double o[4];
        int rc = cl_grubbs_max(d, 8, 0.05, o);
        check_true("cl_grubbs_max 返回成功", rc == CL_OK);
        check_true("Grubbs 判定末位为离群点", (int)o[3] == 7);
        check_true("G > G_crit", o[0] > o[1]);
    }
    {
        double d[6] = {10.2, 10.1, 10.3, 10.2, 10.4, 10.1};
        double o[4];
        cl_grubbs_max(d, 6, 0.05, o);
        check_true("无离群点数据判定为 -1", (int)o[3] == -1);
    }

    /* ---- 4. 显著性检验 ---- */
    std::printf("\n[4] 显著性检验\n");
    {
        // 与总体均值 3 比较，样本均值恰为 3 → t=0, p=1
        double d[5] = {1, 2, 3, 4, 5};
        double o[4];
        cl_t_test_one(d, 5, 3.0, o);
        check("单样本 t (期望 0)", o[0], 0.0, 1e-12);
        check("单样本 p (期望 1)", o[1], 1.0, 1e-10);
        check("自由度 (期望 4)", o[2], 4.0, 1e-12);
    }
    {
        // 两组明显不同 → p 应很小
        double a[5] = {1, 2, 3, 4, 5};
        double b[5] = {11, 12, 13, 14, 15};
        double o[4];
        cl_t_test_two(a, 5, b, 5, 1, o);
        check_true("双样本 t 显著 (p<0.001)", o[1] < 0.001);
        check("均值差 (期望 -10)", o[3], -10.0, 1e-10);
    }
    {
        // 置信区间应包含样本均值
        double d[5] = {1, 2, 3, 4, 5};
        double o[4];
        cl_ci_mean(d, 5, 0.95, o);
        check_true("95% CI 包含均值", o[0] < 3.0 && 3.0 < o[1]);
        check("CI 半宽对称", (3.0 - o[0]) - (o[1] - 3.0), 0.0, 1e-9);
    }

    /* ---- 5. 化学计算 ---- */
    std::printf("\n[5] 化学计算\n");
    {
        double v;
        cl_molarity(5.844, 58.44, 1.0, &v);          // 5.844 g NaCl / 1 L
        check("c(NaCl) 0.1 mol/L", v, 0.1, 1e-9);

        cl_dilution(1.0, 10.0, 100.0, &v);           // 1 M 取 10 mL 定容 100 mL
        check("稀释后浓度 0.1", v, 0.1, 1e-12);

        cl_ph_strong_acid(0.1, &v);
        check("pH 强酸 0.1 M", v, 1.0, 1e-4);

        cl_ph_strong_base(0.1, &v);
        check("pH 强碱 0.1 M", v, 13.0, 1e-4);

        double w[3];
        cl_ph_weak_acid(0.1, 1.8e-5, w);             // 醋酸
        check("pH 醋酸 0.1 M (期望 2.872)", w[0], 2.872, 0.01);
        check_true("解离度 α 合理 (0<α<0.05)", w[1] > 0.0 && w[1] < 0.05);

        double buf[2];
        cl_ph_buffer(1.8e-5, 0.1, 0.1, buf);         // 等浓度缓冲
        check("缓冲 pH = pKa", buf[0], -std::log10(1.8e-5), 1e-9);

        cl_titration(0.1000, 22.50, 25.00, 1.0, &v);
        check("滴定待测浓度 0.09", v, 0.09, 1e-9);

        cl_beer_lambert_conc(0.450, 1.5e4, 1.0, &v);
        check("Beer-Lambert 浓度 3e-5", v, 3e-5, 1e-12);

        /* 直接法配制：0.1 mol/L × 250 mL KHP (M=204.22)，纯度 100% */
        double sp[4];
        cl_std_prep_mass(0.1, 0.250, 204.22, 1.0, 1.0, sp);
        check("KHP 应称质量 5.1055 g", sp[0], 5.1055, 1e-4);
        check("KHP 物质的量 0.025 mol", sp[2], 0.025, 1e-12);
        check("KHP 质量浓度 20.422 g/L", sp[3], 20.422, 1e-9);

        /* 纯度 99.95% 时需多称 (1/0.9995 倍) */
        cl_std_prep_mass(0.1, 0.250, 204.22, 0.9995, 1.0, sp);
        check("纯度校正应称质量", sp[0], 5.1055 / 0.9995, 1e-6);

        /* 称大样 4 倍：质量与体积同步放大，浓度不变 */
        cl_std_prep_mass(0.1, 0.250, 204.22, 1.0, 4.0, sp);
        check("称大样 4 倍质量", sp[0], 4.0 * 5.1055, 1e-4);
        check("称大样后质量浓度不变", sp[3], 20.422, 1e-9);

        /* 标定法反算：称 5.1055 g KHP 定容 250 mL → 0.10000 mol/L */
        double sc[4];
        cl_std_prep_conc(5.1055, 204.22, 1.0, 0.250, sc);
        check("标定法浓度 0.1 mol/L", sc[0], 0.1, 1e-9);
        check("标定法物质的量 0.025 mol", sc[1], 0.025, 1e-12);

        /* 标定法与直接法互逆 */
        double back[4];
        cl_std_prep_mass(sc[0], 0.250, 204.22, 1.0, 1.0, back);
        check("两法互逆 (质量回代)", back[0], 5.1055, 1e-9);

        /* 参数校验 */
        check_true("浓度非正 → 拒绝", cl_std_prep_mass(0.0, 0.25, 204.22, 1.0, 1.0, sp) != CL_OK);
        check_true("纯度 > 1 → 拒绝", cl_std_prep_mass(0.1, 0.25, 204.22, 1.2, 1.0, sp) != CL_OK);
        check_true("倍数非正 → 拒绝", cl_std_prep_mass(0.1, 0.25, 204.22, 1.0, 0.0, sp) != CL_OK);
        check_true("标定法质量为 0 → 拒绝", cl_std_prep_conc(0.0, 204.22, 1.0, 0.25, sc) != CL_OK);
    }

    /* ---- 6. 误差传递 ---- */
    std::printf("\n[6] 误差传递\n");
    {
        double vals[2] = {10.0, 5.0};
        double uncs[2] = {0.1, 0.2};
        double o[3];
        cl_unc_add(vals, uncs, 2, o);
        check("加减法 y = 15", o[0], 15.0, 1e-12);
        check("加减法 u = √(0.1²+0.2²)", o[1], std::sqrt(0.05), 1e-12);

        cl_unc_mul(vals, uncs, 2, o);
        check("乘除法 y = 50", o[0], 50.0, 1e-12);
        double rel = std::sqrt(std::pow(0.1 / 10.0, 2) + std::pow(0.2 / 5.0, 2));
        check("乘除法相对不确定度", o[2], rel, 1e-12);

        double p[2];
        cl_unc_power(2.0, 0.02, 3.0, p);
        check("幂运算 y = 8", p[0], 8.0, 1e-12);
        check("幂运算 u = 3·8·0.02/2", p[1], 3.0 * 8.0 * 0.02 / 2.0, 1e-12);

        double x[3] = {10.0, 5.0, 2.0};
        double ux[3] = {0.1, 0.05, 0.01};
        double g[2];
        cl_unc_general(f_ratio, x, ux, 3, nullptr, g);
        check("一般函数 y = 25", g[0], 25.0, 1e-9);
        check_true("一般函数 u > 0", g[1] > 0.0);
    }

    /* ---- 7. 信号处理 ---- */
    std::printf("\n[7] 信号处理\n");
    {
        // 常数信号经平滑应保持不变
        double y[21];
        for (int i = 0; i < 21; ++i) y[i] = 7.0;
        double o[21];
        cl_savgol(y, 21, 7, 2, o);
        check("SG 平滑保持常数", o[10], 7.0, 1e-9);
        cl_moving_average(y, 21, 5, o);
        check("滑动平均保持常数", o[10], 7.0, 1e-12);

        // 合成双峰信号
        double s[200];
        for (int i = 0; i < 200; ++i) {
            double t = (double)i;
            s[i] = 5.0 * std::exp(-std::pow(t - 50, 2) / 100.0)
                 + 8.0 * std::exp(-std::pow(t - 140, 2) / 200.0);
        }
        int idx[10];
        double h[10];
        int nf = 0;
        int rc = cl_find_peaks(s, 200, 0.2, 5, idx, h, 10, &nf);
        check_true("寻峰返回成功", rc == CL_OK);
        check_true("找到 2 个峰", nf == 2);
        if (nf == 2) {
            check_true("峰1 位于 50 附近", std::abs(idx[0] - 50) <= 2);
            check_true("峰2 位于 140 附近", std::abs(idx[1] - 140) <= 2);
        }

        // 归一化
        double raw[4] = {0.0, 5.0, 10.0, 2.0};
        double nrm[4];
        cl_normalize(raw, 4, nrm);
        check("归一化最小值 0", nrm[0], 0.0, 1e-12);
        check("归一化最大值 1", nrm[2], 1.0, 1e-12);

        // 梯形积分：常值 2 在 0..9 上积分应为 18
        double c[10];
        for (int i = 0; i < 10; ++i) c[i] = 2.0;
        double area;
        cl_trapz(nullptr, c, 10, &area);
        check("梯形积分 (期望 18)", area, 18.0, 1e-12);

        // 基线校正：线性倾斜信号校正后应接近 0
        double tilt[50];
        for (int i = 0; i < 50; ++i) tilt[i] = 1.0 + 0.02 * i;
        double corr[50];
        cl_baseline_linear(tilt, 50, 5, corr);
        check("线性基线校正后中点 ≈ 0", corr[25], 0.0, 1e-9);

        // 多项式基线：二次弯曲背景 + 高斯峰（占 20% 时序），校正后
        // 基线区应归零且峰高保留（旧掩码实现会削峰肩、压低峰高）
        double bg[200];
        for (int i = 0; i < 200; ++i) {
            double u = (double)i / 199.0;
            bg[i] = 0.1 + 0.4 * u * (1.0 - u)
                  + std::exp(-0.5 * std::pow((i - 120) / 8.0, 2));
        }
        double corr2[200];
        int rcB = cl_baseline_poly(bg, 200, 2, 8, corr2);
        check_true("cl_baseline_poly 返回成功", rcB == CL_OK);
        check("二次基线校正后基线区≈0", corr2[20], 0.0, 0.02);
        check("二次基线校正后峰高保留", corr2[120], 1.0, 0.05);
    }

    /* ---- 8. 滴定曲线 ---- */
    std::printf("\n[8] 滴定曲线\n");
    {
        // 强碱滴强酸：0.1 M NaOH 滴 25 mL 0.1 M HCl，Veq = 25 mL
        double v[] = {0.0, 12.5, 24.9, 25.0, 25.1, 50.0};
        double ph[6];
        int rc = cl_titration_curve(0, 0.1, 25.0, 0.1, 0.0, 6, v, ph);
        check_true("强碱滴强酸返回成功", rc == CL_OK);
        check("V=0 pH=1.0", ph[0], 1.0, 0.005);
        check("V=12.5 pH≈1.477", ph[1], 1.477, 0.01);
        check("V=24.9 pH≈3.70", ph[2], 3.70, 0.03);
        check("V=25 等当点 pH=7.0", ph[3], 7.0, 0.02);
        check("V=25.1 pH≈10.30", ph[4], 10.30, 0.03);
        check("V=50 pH≈12.52", ph[5], 12.52, 0.02);

        // 强碱滴弱酸：0.1 M NaOH 滴 25 mL 0.1 M 醋酸（Ka=1.8e-5）
        double v2[] = {0.0, 12.5, 25.0, 50.0};
        double ph2[4];
        rc = cl_titration_curve(1, 0.1, 25.0, 0.1, 1.8e-5, 4, v2, ph2);
        check_true("强碱滴弱酸返回成功", rc == CL_OK);
        check("V=0 pH≈2.872", ph2[0], 2.872, 0.01);
        check("V=12.5 半中和 pH=pKa", ph2[1], 4.745, 0.02);
        check("V=25 等当点 pH≈8.72", ph2[2], 8.722, 0.03);
        check("V=50 pH≈12.52", ph2[3], 12.52, 0.02);

        // 强酸滴弱碱：等当点前对称性检查（V=0 与镜像体系）
        double v3[] = {25.0};
        double ph3[1];
        rc = cl_titration_curve(3, 0.1, 25.0, 0.1, 1.8e-5, 1, v3, ph3);
        check_true("强酸滴弱碱返回成功", rc == CL_OK);
        check("NH3 等当点 pH≈5.28", ph3[0], 5.278, 0.03);
    }

    /* ---- 9. 色谱峰综合分析 ---- */
    std::printf("\n[9] 色谱峰分析\n");
    {
        // 双窄高斯峰 + 平缓线性漂移（真实色谱形态：峰仅占总时程约 3%，
        // 基线点占主导，迭代基线扣除才能准确恢复）
        const int n = 501;                      // t = 0..10 步长 0.02
        std::vector<double> t(n), s(n), out(CL_CHROM_OUT_CAP);
        for (int i = 0; i < n; ++i) {
            double tt = 0.02 * i;
            t[i] = tt;
            s[i] = 0.05 + 0.01 * tt                                  // 缓漂移基线
                 + 1.0 * std::exp(-0.5 * std::pow((tt - 2.0) / 0.06, 2))
                 + 0.6 * std::exp(-0.5 * std::pow((tt - 3.5) / 0.08, 2));
        }
        // 主验证：不平滑，严格对齐理论值（平滑会轻微压低峰高/面积，
        // 属信号处理固有效应，下面单独做鲁棒性检查）
        int rc = cl_chrom_peaks(t.data(), s.data(), n, 0.05, 5, 0,
                                out.data(), CL_CHROM_OUT_CAP);
        check_true("cl_chrom_peaks 返回成功", rc == CL_OK);
        int nPk = (int)out[0];
        check_true("找到 2 个峰", nPk == 2);
        if (nPk == 2) {
            // 峰1：h=1, σ=0.06 → A=σh√(2π)=0.1504, W½=0.141, N=5.54(tR/W½)²≈1110
            check("峰1 tR≈2.0", out[1], 2.0, 0.02);
            check("峰1 峰高≈1.0", out[2], 1.0, 0.03);
            // 面积比 A1:A2 = 0.1504:0.1203 → 55.56% / 44.44%
            check("峰1 面积%", out[4], 55.56, 0.8);
            check("峰1 拖尾因子≈1", out[6], 1.0, 0.1);
            check("峰1 W½≈0.141", out[7], 0.141, 0.015);
            check("峰1 塔板数 N≈1110", out[5], 1110.0, 120.0);

            check("峰2 tR≈3.5", out[9], 3.5, 0.02);
            check("峰2 峰高≈0.6", out[10], 0.6, 0.03);
            check("峰2 面积%", out[12], 44.44, 0.8);
            // Rs = 2Δt / (1.699(W½1+W½2)) = 3.0/0.560 ≈ 5.36
            check("分离度 Rs≈5.36", out[16], 5.36, 0.4);
        }

        // 鲁棒性：带平滑窗口时峰数与保留时间仍应正确
        int rcS = cl_chrom_peaks(t.data(), s.data(), n, 0.05, 5, 5,
                                 out.data(), CL_CHROM_OUT_CAP);
        check_true("带平滑返回成功", rcS == CL_OK);
        check_true("带平滑仍找到 2 个峰", (int)out[0] == 2);
        if ((int)out[0] == 2) {
            check_true("带平滑峰1 tR 仍≈2.0", std::fabs(out[1] - 2.0) < 0.05);
            check_true("带平滑峰2 tR 仍≈3.5", std::fabs(out[9] - 3.5) < 0.05);
        }

        // NULL 时间轴：tR 应回退为采样索引
        double flat[100];
        for (int i = 0; i < 100; ++i)
            flat[i] = std::exp(-0.5 * std::pow((i - 50) / 6.0, 2));
        int rc2 = cl_chrom_peaks(nullptr, flat, 100, 0.05, 5, 0,
                                 out.data(), CL_CHROM_OUT_CAP);
        check_true("NULL 时间轴返回成功", rc2 == CL_OK);
        check_true("NULL 时间轴 tR≈索引 50", (int)out[0] == 1 && std::fabs(out[1] - 50.0) < 2.0);

        // 平坦信号：应报 0 峰且成功
        std::vector<double> zero(100, 1.0);
        int rc3 = cl_chrom_peaks(nullptr, zero.data(), 100, 0.05, 5, 0,
                                 out.data(), CL_CHROM_OUT_CAP);
        check_true("平坦信号返回成功", rc3 == CL_OK);
        check("平坦信号峰数 0", out[0], 0.0, 1e-12);
    }

    std::printf("\n---------------------------------------------\n");
    std::printf("自检结果：通过 %d 项，失败 %d 项\n", g_pass, g_fail);
    return g_fail == 0 ? 0 : 1;
}

} // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::printf("chemlab_cli —— 分析化学计算内核命令行\n");
        std::printf("用法: chemlab_cli --selftest | --version\n");
        return 0;
    }
    std::string cmd = argv[1];
    if (cmd == "--selftest") return selftest();
    if (cmd == "--version") { std::printf("%s\n", cl_version()); return 0; }
    std::printf("未知参数: %s\n", argv[1]);
    return 2;
}