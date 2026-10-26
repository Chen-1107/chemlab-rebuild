/*
 * regression.cpp —— 线性回归、加权回归、标定曲线、LOD/LOQ、残差。
 */
#include "chemlab_core.h"
#include "internal.h"

using namespace cl;

extern "C" {

int cl_linreg(const double* x, const double* y, int n, double* out) {
    if (!x || !y || !out) return CL_ERR_NULL;
    if (n < 3) return CL_ERR_SIZE;

    double sx = 0.0, sy = 0.0;
    for (int i = 0; i < n; ++i) { sx += x[i]; sy += y[i]; }
    double mx = sx / n, my = sy / n;

    double sxx = 0.0, sxy = 0.0, syy = 0.0;
    for (int i = 0; i < n; ++i) {
        double dx = x[i] - mx, dy = y[i] - my;
        sxx += dx * dx;
        sxy += dx * dy;
        syy += dy * dy;
    }
    if (sxx < 1e-300) return CL_ERR_DEGEN;

    double b = sxy / sxx;          // 斜率
    double a = my - b * mx;        // 截距

    double ssRes = 0.0, ssTot = syy;
    for (int i = 0; i < n; ++i) {
        double r = y[i] - (a + b * x[i]);
        ssRes += r * r;
    }
    double r2 = (ssTot > 1e-300) ? 1.0 - ssRes / ssTot : 0.0;
    if (r2 < 0.0) r2 = 0.0;
    double r = (b >= 0.0) ? std::sqrt(r2) : -std::sqrt(r2);

    double df = n - 2;
    double syx = (df > 0) ? std::sqrt(ssRes / df) : 0.0;
    double sb = (df > 0) ? syx / std::sqrt(sxx) : 0.0;
    double sa = (df > 0) ? syx * std::sqrt(1.0 / n + mx * mx / sxx) : 0.0;

    out[0] = b;
    out[1] = a;
    out[2] = r2;
    out[3] = r;
    out[4] = syx;
    out[5] = sb;
    out[6] = sa;
    out[7] = (double)n;
    return CL_OK;
}

int cl_linreg_weighted(const double* x, const double* y, const double* sd,
                       int n, double* out) {
    if (!x || !y || !sd || !out) return CL_ERR_NULL;
    if (n < 3) return CL_ERR_SIZE;

    // 权重 w = 1/s²；对零标准差做保护
    double sw = 0.0, swx = 0.0, swy = 0.0, swxx = 0.0, swxy = 0.0;
    for (int i = 0; i < n; ++i) {
        double s = std::fabs(sd[i]);
        if (s < 1e-300) s = 1e-300;
        double w = 1.0 / (s * s);
        sw   += w;
        swx  += w * x[i];
        swy  += w * y[i];
        swxx += w * x[i] * x[i];
        swxy += w * x[i] * y[i];
    }
    double delta = sw * swxx - swx * swx;
    if (std::fabs(delta) < 1e-300) return CL_ERR_DEGEN;

    double b = (sw * swxy - swx * swy) / delta;
    double a = (swxx * swy - swx * swxy) / delta;

    // 加权残差与决定系数
    double ssRes = 0.0, ssTot = 0.0;
    double mw = swy / sw;
    for (int i = 0; i < n; ++i) {
        double s = std::fabs(sd[i]);
        if (s < 1e-300) s = 1e-300;
        double w = 1.0 / (s * s);
        double r = y[i] - (a + b * x[i]);
        ssRes += w * r * r;
        double d = y[i] - mw;
        ssTot += w * d * d;
    }
    double df = n - 2;
    double r2 = (ssTot > 1e-300) ? 1.0 - ssRes / ssTot : 0.0;
    if (r2 < 0.0) r2 = 0.0;
    double r = (b >= 0.0) ? std::sqrt(r2) : -std::sqrt(r2);
    double syx = (df > 0) ? std::sqrt(ssRes / df) : 0.0;
    double sb = (df > 0) ? std::sqrt(sw / delta) * syx : 0.0;
    double sa = (df > 0) ? std::sqrt(swxx / delta) * syx : 0.0;

    out[0] = b; out[1] = a; out[2] = r2; out[3] = r;
    out[4] = syx; out[5] = sb; out[6] = sa; out[7] = (double)n;
    return CL_OK;
}

int cl_lod_loq(double slope, double syx, double kLod, double kLoq, double* out) {
    if (!out) return CL_ERR_NULL;
    if (std::fabs(slope) < 1e-300) return CL_ERR_DEGEN;
    if (kLod <= 0.0 || kLoq <= 0.0) return CL_ERR_RANGE;
    out[0] = kLod * syx / std::fabs(slope);
    out[1] = kLoq * syx / std::fabs(slope);
    return CL_OK;
}

int cl_calib_inverse(double y, double slope, double intercept, double* out) {
    if (!out) return CL_ERR_NULL;
    if (std::fabs(slope) < 1e-300) return CL_ERR_DEGEN;
    *out = (y - intercept) / slope;
    return CL_OK;
}

int cl_linreg_residuals(const double* x, const double* y, int n,
                        double slope, double intercept, double* out) {
    if (!x || !y || !out) return CL_ERR_NULL;
    if (n <= 0) return CL_ERR_SIZE;
    for (int i = 0; i < n; ++i)
        out[i] = y[i] - (intercept + slope * x[i]);
    return CL_OK;
}

} // extern "C"