/*
 * chemistry.cpp —— 浓度换算、pH、滴定、Beer-Lambert、误差传递。
 */
#include "chemlab_core.h"
#include "internal.h"

using namespace cl;

extern "C" {

/* ---------------- 浓度换算 ---------------- */

int cl_molarity(double massG, double molarMass, double volumeL, double* out) {
    if (!out) return CL_ERR_NULL;
    if (molarMass <= 0.0 || volumeL <= 0.0) return CL_ERR_RANGE;
    *out = massG / (molarMass * volumeL);
    return CL_OK;
}

int cl_dilution(double c1, double v1, double v2, double* out) {
    if (!out) return CL_ERR_NULL;
    if (v2 <= 0.0) return CL_ERR_RANGE;
    *out = c1 * v1 / v2;
    return CL_OK;
}

int cl_mass_conc(double molarity, double molarMass, double* out) {
    if (!out) return CL_ERR_NULL;
    if (molarMass <= 0.0) return CL_ERR_RANGE;
    *out = molarity * molarMass;
    return CL_OK;
}

/* ---------------- 标准溶液配制 ---------------- */

int cl_std_prep_mass(double c, double volumeL, double molarMass,
                     double purity, double factor, double* out) {
    if (!out) return CL_ERR_NULL;
    if (c <= 0.0 || volumeL <= 0.0 || molarMass <= 0.0) return CL_ERR_RANGE;
    if (purity <= 0.0 || purity > 1.0) return CL_ERR_RANGE;
    if (factor <= 0.0) return CL_ERR_RANGE;
    double n = c * volumeL * factor;      // 纯物质物质的量 (mol)
    double mPure = n * molarMass;         // 纯物质质量 (g)
    out[0] = mPure / purity;              // 含杂质样品的应称质量 (g)
    out[1] = mPure;
    out[2] = n;
    out[3] = c * molarMass;               // 质量浓度 (g/L)
    return CL_OK;
}

int cl_std_prep_conc(double massG, double molarMass, double purity,
                     double volumeL, double* out) {
    if (!out) return CL_ERR_NULL;
    if (massG <= 0.0 || molarMass <= 0.0 || volumeL <= 0.0) return CL_ERR_RANGE;
    if (purity <= 0.0 || purity > 1.0) return CL_ERR_RANGE;
    double mPure = massG * purity;        // 纯物质质量 (g)
    double n = mPure / molarMass;         // 物质的量 (mol)
    double c = n / volumeL;               // 物质的量浓度 (mol/L)
    out[0] = c;
    out[1] = n;
    out[2] = mPure;
    out[3] = c * molarMass;               // 质量浓度 (g/L)
    return CL_OK;
}

/* ---------------- pH 计算 ---------------- */

int cl_ph_strong_acid(double c, double* out) {
    if (!out) return CL_ERR_NULL;
    if (c <= 0.0) return CL_ERR_RANGE;
    // 极稀溶液需考虑水的自解离，解 [H+]² + Ka_w·... 简化为二次方程
    // [H+] = (c + √(c² + 4Kw)) / 2
    const double Kw = 1.0e-14;
    double h = 0.5 * (c + std::sqrt(c * c + 4.0 * Kw));
    *out = -std::log10(h);
    return CL_OK;
}

int cl_ph_strong_base(double c, double* out) {
    if (!out) return CL_ERR_NULL;
    if (c <= 0.0) return CL_ERR_RANGE;
    const double Kw = 1.0e-14;
    double oh = 0.5 * (c + std::sqrt(c * c + 4.0 * Kw));
    *out = 14.0 + std::log10(oh);
    return CL_OK;
}

int cl_ph_weak_acid(double c, double ka, double* out) {
    if (!out) return CL_ERR_NULL;
    if (c <= 0.0 || ka <= 0.0) return CL_ERR_RANGE;
    // 解 x² + Ka·x - Ka·c = 0
    double x = (-ka + std::sqrt(ka * ka + 4.0 * ka * c)) / 2.0;
    out[0] = -std::log10(x);
    out[1] = x / c;          // 解离度
    out[2] = x;              // [H+]
    return CL_OK;
}

int cl_ph_weak_base(double c, double kb, double* out) {
    if (!out) return CL_ERR_NULL;
    if (c <= 0.0 || kb <= 0.0) return CL_ERR_RANGE;
    double x = (-kb + std::sqrt(kb * kb + 4.0 * kb * c)) / 2.0;
    double poh = -std::log10(x);
    out[0] = 14.0 - poh;
    out[1] = x / c;
    out[2] = x;
    return CL_OK;
}

int cl_ph_buffer(double ka, double ca, double cb, double* out) {
    if (!out) return CL_ERR_NULL;
    if (ka <= 0.0 || ca <= 0.0 || cb <= 0.0) return CL_ERR_RANGE;
    double ratio = cb / ca;
    out[0] = -std::log10(ka) + std::log10(ratio);
    out[1] = ratio;
    return CL_OK;
}

/* ---------------- 滴定与光度 ---------------- */

int cl_titration(double cStd, double vStd, double vSample, double ratio, double* out) {
    if (!out) return CL_ERR_NULL;
    if (vSample <= 0.0) return CL_ERR_RANGE;
    *out = cStd * vStd * ratio / vSample;
    return CL_OK;
}

int cl_beer_lambert_abs(double epsilon, double pathCm, double conc, double* out) {
    if (!out) return CL_ERR_NULL;
    *out = epsilon * pathCm * conc;
    return CL_OK;
}

int cl_beer_lambert_conc(double abs, double epsilon, double pathCm, double* out) {
    if (!out) return CL_ERR_NULL;
    double d = epsilon * pathCm;
    if (std::fabs(d) < 1e-300) return CL_ERR_DEGEN;
    *out = abs / d;
    return CL_OK;
}

int cl_beer_lambert_epsilon(double abs, double pathCm, double conc, double* out) {
    if (!out) return CL_ERR_NULL;
    double d = pathCm * conc;
    if (std::fabs(d) < 1e-300) return CL_ERR_DEGEN;
    *out = abs / d;
    return CL_OK;
}

/* ---------------- 滴定曲线模拟 ---------------- */

namespace {

/**
 * 电荷平衡残差 f(h)，h = [H+]。四种体系统一写成「f 随 h 单调递增」的形式，
 * 保证 pH 域上的二分求解无条件收敛。
 *   ca  被滴定物质的分析浓度（稀释后）
 *   cb  滴定剂的分析浓度（稀释后）
 *   k   弱酸 Ka（type 1）或弱碱 Kb（type 3）
 */
double charge_balance(int type, double h, double ca, double cb, double k, double kw) {
    switch (type) {
        case 0:  // 强碱滴强酸：[Na+] = cb, [Cl-] = ca
            return h + cb - ca - kw / h;
        case 1:  // 强碱滴弱酸 HA：[A-] = ca·Ka/(h+Ka)
            return h + cb - ca * k / (h + k) - kw / h;
        case 2:  // 强酸滴强碱：[Na+] = ca, [Cl-] = cb
            return h + ca - cb - kw / h;
        default: // 强酸滴弱碱 B：[BH+] = ca·h/(h+Kw/Kb)
            return h + ca * h / (h + kw / k) - cb - kw / h;
    }
}

} // namespace

int cl_titration_curve(int type, double cAnal, double vAnalMl,
                       double cTitr, double kAnal,
                       int nPoints, const double* volMl, double* ph) {
    if (!volMl || !ph) return CL_ERR_NULL;
    if (nPoints <= 0) return CL_ERR_SIZE;
    if (type < 0 || type > 3) return CL_ERR_RANGE;
    if (cAnal <= 0.0 || vAnalMl <= 0.0 || cTitr <= 0.0) return CL_ERR_RANGE;
    if ((type == 1 || type == 3) && kAnal <= 0.0) return CL_ERR_RANGE;

    const double kw = 1.0e-14;
    for (int i = 0; i < nPoints; ++i) {
        if (volMl[i] < 0.0) return CL_ERR_RANGE;
        double vt = vAnalMl + volMl[i];
        double ca = cAnal * vAnalMl / vt;
        double cb = cTitr * volMl[i] / vt;

        double lo = 0.0, hi = 14.0;
        while (hi - lo > 1e-10) {
            double mid = 0.5 * (lo + hi);
            double h = std::pow(10.0, -mid);
            // f 随 pH 单调递减：f>0 说明酸性过剩，解在更高 pH 一侧
            if (charge_balance(type, h, ca, cb, kAnal, kw) > 0.0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        ph[i] = 0.5 * (lo + hi);
    }
    return CL_OK;
}

/* ---------------- 误差传递 ---------------- */

int cl_unc_add(const double* vals, const double* uncs, int n, double* out) {
    if (!vals || !uncs || !out) return CL_ERR_NULL;
    if (n <= 0) return CL_ERR_SIZE;
    double y = 0.0, u2 = 0.0;
    for (int i = 0; i < n; ++i) {
        y += vals[i];
        u2 += uncs[i] * uncs[i];
    }
    out[0] = y;
    out[1] = std::sqrt(u2);
    return CL_OK;
}

int cl_unc_mul(const double* vals, const double* uncs, int n, double* out) {
    if (!vals || !uncs || !out) return CL_ERR_NULL;
    if (n <= 0) return CL_ERR_SIZE;
    double y = 1.0, rel2 = 0.0;
    for (int i = 0; i < n; ++i) {
        if (std::fabs(vals[i]) < 1e-300) return CL_ERR_DEGEN;
        y *= vals[i];
        double r = uncs[i] / vals[i];
        rel2 += r * r;
    }
    double rel = std::sqrt(rel2);
    out[0] = y;
    out[1] = std::fabs(y) * rel;
    out[2] = rel;
    return CL_OK;
}

int cl_unc_power(double x, double ux, double a, double* out) {
    if (!out) return CL_ERR_NULL;
    if (std::fabs(x) < 1e-300) return CL_ERR_DEGEN;
    double y = std::pow(x, a);
    out[0] = y;
    out[1] = std::fabs(a) * std::fabs(y) * ux / std::fabs(x);
    return CL_OK;
}

int cl_unc_general(cl_func1 f, const double* x, const double* ux, int n,
                   void* ctx, double* out) {
    if (!f || !x || !ux || !out) return CL_ERR_NULL;
    if (n <= 0) return CL_ERR_SIZE;

    double y0 = f(x, n, ctx);
    std::vector<double> xw(x, x + n);
    double u2 = 0.0;

    for (int i = 0; i < n; ++i) {
        // 中心差分步长：兼顾量级与精度
        double h = 1e-6 * std::max(1.0, std::fabs(x[i]));
        xw[i] = x[i] + h;
        double yp = f(xw.data(), n, ctx);
        xw[i] = x[i] - h;
        double ym = f(xw.data(), n, ctx);
        xw[i] = x[i];
        double d = (yp - ym) / (2.0 * h);
        u2 += d * d * ux[i] * ux[i];
    }

    out[0] = y0;
    out[1] = std::sqrt(u2);
    return CL_OK;
}

} // extern "C"