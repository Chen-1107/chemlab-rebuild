/*
 * mathutil.cpp —— 分布函数与线性代数工具的实现。
 */
#include "internal.h"
#include <stdexcept>

namespace cl {

double lgamma_fn(double x) {
    // Lanczos 系数（g=7, n=9），适用于 x > 0
    static const double cof[9] = {
        0.99999999999980993, 676.5203681218851,   -1259.1392167224028,
        771.32342877765313,  -176.61502916214059, 12.507343278686905,
        -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7
    };
    if (x < 0.5) {
        // 反射公式 Γ(x)Γ(1-x) = π / sin(πx)
        return std::log(kPi / std::sin(kPi * x)) - lgamma_fn(1.0 - x);
    }
    x -= 1.0;
    double a = cof[0];
    double t = x + 7.5;
    for (int i = 1; i < 9; ++i) a += cof[i] / (x + i);
    return 0.5 * std::log(2.0 * kPi) + (x + 0.5) * std::log(t) - t + std::log(a);
}

// 连分式展开，用于不完全贝塔函数（Numerical Recipes 方案）
static double betacf(double a, double b, double x) {
    const int kMaxIter = 300;
    const double kEps = 3.0e-16;
    const double kFpMin = 1.0e-300;

    double qab = a + b, qap = a + 1.0, qam = a - 1.0;
    double c = 1.0;
    double d = 1.0 - qab * x / qap;
    if (std::fabs(d) < kFpMin) d = kFpMin;
    d = 1.0 / d;
    double h = d;

    for (int m = 1; m <= kMaxIter; ++m) {
        double m2 = 2.0 * m;
        double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
        d = 1.0 + aa * d;
        if (std::fabs(d) < kFpMin) d = kFpMin;
        c = 1.0 + aa / c;
        if (std::fabs(c) < kFpMin) c = kFpMin;
        d = 1.0 / d;
        h *= d * c;

        aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
        d = 1.0 + aa * d;
        if (std::fabs(d) < kFpMin) d = kFpMin;
        c = 1.0 + aa / c;
        if (std::fabs(c) < kFpMin) c = kFpMin;
        d = 1.0 / d;
        double del = d * c;
        h *= del;

        if (std::fabs(del - 1.0) <= kEps) break;
    }
    return h;
}

double betai(double a, double b, double x) {
    if (x <= 0.0) return 0.0;
    if (x >= 1.0) return 1.0;
    double bt = std::exp(lgamma_fn(a + b) - lgamma_fn(a) - lgamma_fn(b)
                         + a * std::log(x) + b * std::log(1.0 - x));
    if (x < (a + 1.0) / (a + b + 2.0))
        return bt * betacf(a, b, x) / a;
    return 1.0 - bt * betacf(b, a, 1.0 - x) / b;
}

double t_cdf(double t, double df) {
    if (df <= 0.0) return 0.5;
    double x = df / (df + t * t);
    double p = 0.5 * betai(df / 2.0, 0.5, x);
    return (t > 0.0) ? 1.0 - p : p;
}

double t_two_tail_p(double t, double df) {
    return 2.0 * (1.0 - t_cdf(std::fabs(t), df));
}

double t_quantile(double p, double df) {
    // 双侧：求使 P(|T| <= t) = p 的 t；p 为置信水平（如 0.95）
    double target = 0.5 + p / 2.0; // 单侧累积概率
    double lo = 0.0, hi = 1.0e3;
    for (int i = 0; i < 200; ++i) {
        double mid = 0.5 * (lo + hi);
        if (t_cdf(mid, df) < target) lo = mid; else hi = mid;
    }
    return 0.5 * (lo + hi);
}

double f_cdf(double f, double df1, double df2) {
    if (f <= 0.0) return 0.0;
    double x = df1 * f / (df1 * f + df2);
    return betai(df1 / 2.0, df2 / 2.0, x);
}

double norm_cdf(double z) {
    return 0.5 * std::erfc(-z / std::sqrt(2.0));
}

double mean(const double* d, int n) {
    if (n <= 0) return 0.0;
    double s = 0.0;
    for (int i = 0; i < n; ++i) s += d[i];
    return s / n;
}

double stddev(const double* d, int n, int ddof) {
    if (n - ddof <= 0) return 0.0;
    double m = mean(d, n);
    double s = 0.0;
    for (int i = 0; i < n; ++i) {
        double t = d[i] - m;
        s += t * t;
    }
    return std::sqrt(s / (n - ddof));
}

double table_lookup(const double* xs, const double* ys, int n, double xq) {
    if (n <= 0) return 0.0;
    if (xq <= xs[0]) return ys[0];
    if (xq >= xs[n - 1]) return ys[n - 1];
    for (int i = 1; i < n; ++i) {
        if (xq <= xs[i]) {
            double t = (xq - xs[i - 1]) / (xs[i] - xs[i - 1]);
            return ys[i - 1] + t * (ys[i] - ys[i - 1]);
        }
    }
    return ys[n - 1];
}

bool solve_linear(std::vector<double>& A, std::vector<double>& b, int n) {
    for (int col = 0; col < n; ++col) {
        // 部分主元
        int piv = col;
        double best = std::fabs(A[col * n + col]);
        for (int r = col + 1; r < n; ++r) {
            double v = std::fabs(A[r * n + col]);
            if (v > best) { best = v; piv = r; }
        }
        if (best < 1e-14) return false;
        if (piv != col) {
            for (int c = 0; c < n; ++c) std::swap(A[col * n + c], A[piv * n + c]);
            std::swap(b[col], b[piv]);
        }
        double diag = A[col * n + col];
        for (int r = col + 1; r < n; ++r) {
            double f = A[r * n + col] / diag;
            if (f == 0.0) continue;
            for (int c = col; c < n; ++c) A[r * n + c] -= f * A[col * n + c];
            b[r] -= f * b[col];
        }
    }
    // 回代
    for (int r = n - 1; r >= 0; --r) {
        double s = b[r];
        for (int c = r + 1; c < n; ++c) s -= A[r * n + c] * b[c];
        b[r] = s / A[r * n + r];
    }
    return true;
}

} // namespace cl