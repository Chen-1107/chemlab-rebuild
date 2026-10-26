/*
 * stats.cpp —— 描述统计、异常值检验、t/F 检验、置信区间。
 */
#include "chemlab_core.h"
#include "internal.h"

using namespace cl;

extern "C" {

const char* cl_version(void) {
    return "chemlab-core 4.0.0 (C++17)";
}

/* ---------------- 描述统计 ---------------- */

int cl_mean(const double* data, int n, double* out) {
    if (!data || !out) return CL_ERR_NULL;
    if (n <= 0) return CL_ERR_SIZE;
    *out = mean(data, n);
    return CL_OK;
}

int cl_median(const double* data, int n, double* out) {
    if (!data || !out) return CL_ERR_NULL;
    if (n <= 0) return CL_ERR_SIZE;
    std::vector<double> v(data, data + n);
    std::sort(v.begin(), v.end());
    *out = (n % 2) ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
    return CL_OK;
}

int cl_range(const double* data, int n, double* out) {
    if (!data || !out) return CL_ERR_NULL;
    if (n <= 0) return CL_ERR_SIZE;
    double lo = data[0], hi = data[0];
    for (int i = 1; i < n; ++i) {
        if (data[i] < lo) lo = data[i];
        if (data[i] > hi) hi = data[i];
    }
    *out = hi - lo;
    return CL_OK;
}

int cl_variance(const double* data, int n, int ddof, double* out) {
    if (!data || !out) return CL_ERR_NULL;
    if (n - ddof <= 0) return CL_ERR_SIZE;
    double s = stddev(data, n, ddof);
    *out = s * s;
    return CL_OK;
}

int cl_stddev(const double* data, int n, int ddof, double* out) {
    if (!data || !out) return CL_ERR_NULL;
    if (n - ddof <= 0) return CL_ERR_SIZE;
    *out = stddev(data, n, ddof);
    return CL_OK;
}

int cl_rsd(const double* data, int n, double* out) {
    if (!data || !out) return CL_ERR_NULL;
    if (n <= 1) return CL_ERR_SIZE;
    double m = mean(data, n);
    if (std::fabs(m) < 1e-300) return CL_ERR_DEGEN;
    *out = stddev(data, n, 1) / std::fabs(m) * 100.0;
    return CL_OK;
}

int cl_sem(const double* data, int n, double* out) {
    if (!data || !out) return CL_ERR_NULL;
    if (n <= 1) return CL_ERR_SIZE;
    *out = stddev(data, n, 1) / std::sqrt((double)n);
    return CL_OK;
}

int cl_rel_error(double measured, double trueValue, double* out) {
    if (!out) return CL_ERR_NULL;
    if (std::fabs(trueValue) < 1e-300) return CL_ERR_DEGEN;
    *out = (measured - trueValue) / trueValue * 100.0;
    return CL_OK;
}

int cl_describe(const double* data, int n, double* out) {
    if (!data || !out) return CL_ERR_NULL;
    if (n <= 0) return CL_ERR_SIZE;

    double lo = data[0], hi = data[0], s = 0.0;
    for (int i = 0; i < n; ++i) {
        if (data[i] < lo) lo = data[i];
        if (data[i] > hi) hi = data[i];
        s += data[i];
    }
    double m = s / n;
    double ss = 0.0;
    for (int i = 0; i < n; ++i) { double t = data[i] - m; ss += t * t; }

    std::vector<double> v(data, data + n);
    std::sort(v.begin(), v.end());
    double med = (n % 2) ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);

    double var = (n > 1) ? ss / (n - 1) : 0.0;
    double sd = std::sqrt(var);

    out[0] = m;
    out[1] = med;
    out[2] = sd;
    out[3] = (std::fabs(m) > 1e-300) ? sd / std::fabs(m) * 100.0 : 0.0;
    out[4] = var;
    out[5] = (n > 0) ? sd / std::sqrt((double)n) : 0.0;
    out[6] = lo;
    out[7] = hi;
    return CL_OK;
}

/* ---------------- 异常值检验 ---------------- */

int cl_grubbs_max(const double* data, int n, double alpha, double* out) {
    if (!data || !out) return CL_ERR_NULL;
    if (n < 3) return CL_ERR_SIZE;
    if (alpha <= 0.0 || alpha >= 1.0) return CL_ERR_RANGE;

    double m = mean(data, n);
    double s = stddev(data, n, 1);
    if (s < 1e-300) return CL_ERR_DEGEN;

    // 找出偏离均值最远的点
    int idx = 0;
    double g = 0.0;
    for (int i = 0; i < n; ++i) {
        double v = std::fabs(data[i] - m) / s;
        if (v > g) { g = v; idx = i; }
    }

    // G_crit = ((N-1)/√N) · √(t²/(N-2+t²))，t 为 t_{α/(2N), N-2}
    double p = 1.0 - alpha / n;   // 单侧累积概率
    double t = t_quantile(2.0 * p - 1.0, n - 2);
    double gcrit = ((double)(n - 1) / std::sqrt((double)n))
                 * std::sqrt(t * t / ((double)(n - 2) + t * t));

    out[0] = g;
    out[1] = gcrit;
    out[2] = data[idx];
    out[3] = (g > gcrit) ? (double)idx : -1.0;
    return CL_OK;
}

int cl_dixon_q(const double* data, int n, double alpha, double* out) {
    if (!data || !out) return CL_ERR_NULL;
    if (n < 3 || n > 30) return CL_ERR_SIZE;
    if (alpha <= 0.0 || alpha >= 1.0) return CL_ERR_RANGE;

    std::vector<double> v(data, data + n);
    std::sort(v.begin(), v.end());

    // 取排序后数组，检验极值点：比较首尾两个候选的 Q，选较大者
    double qLow, qHigh;
    double range = v[n - 1] - v[0];
    if (range < 1e-300) return CL_ERR_DEGEN;

    if (n <= 7) {                       // Q10
        qLow  = (v[1] - v[0]) / range;
        qHigh = (v[n - 1] - v[n - 2]) / range;
    } else if (n <= 10) {               // Q11
        qLow  = (v[1] - v[0]) / (v[n - 1] - v[0]);
        qHigh = (v[n - 1] - v[n - 2]) / (v[n - 1] - v[0]);
    } else if (n <= 13) {               // Q21
        qLow  = (v[2] - v[0]) / (v[n - 1] - v[0]);
        qHigh = (v[n - 1] - v[n - 3]) / (v[n - 1] - v[0]);
    } else {                            // Q22
        qLow  = (v[2] - v[0]) / (v[n - 1] - v[0]);
        qHigh = (v[n - 1] - v[n - 3]) / (v[n - 1] - v[0]);
    }

    int useHigh = (qHigh >= qLow) ? 1 : 0;
    double q = useHigh ? qHigh : qLow;

    // Dixon 临界值表（双侧 0.05 / 0.01）
    static const double nTab[]  = {3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30};
    static const double q05[]   = {0.970,0.829,0.710,0.625,0.568,0.526,0.493,0.466,0.444,0.426,0.410,0.396,0.384,0.374,0.365,0.356,0.349,0.342,0.337,0.331,0.326,0.321,0.317,0.312,0.308,0.305,0.301,0.290};
    static const double q01[]   = {0.994,0.926,0.821,0.740,0.680,0.634,0.598,0.568,0.544,0.525,0.507,0.490,0.475,0.462,0.450,0.440,0.430,0.421,0.413,0.406,0.399,0.391,0.384,0.378,0.372,0.367,0.362,0.357};

    const int tabN = 28;
    double qcrit = (std::fabs(alpha - 0.01) < 1e-9)
                 ? table_lookup(nTab, q01, tabN, (double)n)
                 : table_lookup(nTab, q05, tabN, (double)n);

    out[0] = q;
    out[1] = qcrit;
    out[2] = (q > qcrit) ? (useHigh ? (double)(n - 1) : 0.0) : -1.0;
    return CL_OK;
}

/* ---------------- 显著性检验 ---------------- */

int cl_t_test_one(const double* data, int n, double mu0, double* out) {
    if (!data || !out) return CL_ERR_NULL;
    if (n < 2) return CL_ERR_SIZE;
    double m = mean(data, n);
    double sd = stddev(data, n, 1);
    if (sd < 1e-300) return CL_ERR_DEGEN;
    double sem = sd / std::sqrt((double)n);
    double t = (m - mu0) / sem;
    double df = n - 1;
    out[0] = t;
    out[1] = t_two_tail_p(t, df);
    out[2] = df;
    out[3] = sem;
    return CL_OK;
}

int cl_t_test_two(const double* a, int na, const double* b, int nb,
                  int pooled, double* out) {
    if (!a || !b || !out) return CL_ERR_NULL;
    if (na < 2 || nb < 2) return CL_ERR_SIZE;

    double ma = mean(a, na), mb = mean(b, nb);
    double va = stddev(a, na, 1), vb = stddev(b, nb, 1);
    va *= va; vb *= vb;

    double t, df;
    if (pooled) {
        df = na + nb - 2;
        double sp2 = ((na - 1) * va + (nb - 1) * vb) / df;
        double denom = std::sqrt(sp2 * (1.0 / na + 1.0 / nb));
        if (denom < 1e-300) return CL_ERR_DEGEN;
        t = (ma - mb) / denom;
    } else {
        double sa = va / na, sb = vb / nb;
        double denom = std::sqrt(sa + sb);
        if (denom < 1e-300) return CL_ERR_DEGEN;
        t = (ma - mb) / denom;
        df = (sa + sb) * (sa + sb) / (sa * sa / (na - 1) + sb * sb / (nb - 1));
    }

    out[0] = t;
    out[1] = t_two_tail_p(t, df);
    out[2] = df;
    out[3] = ma - mb;
    return CL_OK;
}

int cl_f_test(const double* a, int na, const double* b, int nb, double* out) {
    if (!a || !b || !out) return CL_ERR_NULL;
    if (na < 2 || nb < 2) return CL_ERR_SIZE;

    double va = stddev(a, na, 1), vb = stddev(b, nb, 1);
    va *= va; vb *= vb;
    if (va < 1e-300 || vb < 1e-300) return CL_ERR_DEGEN;

    double f = va / vb;
    double df1 = na - 1, df2 = nb - 1;
    double cdf = f_cdf(f, df1, df2);
    double p = 2.0 * std::min(cdf, 1.0 - cdf);

    out[0] = f;
    out[1] = std::min(p, 1.0);
    out[2] = df1;
    return CL_OK;
}

int cl_ci_mean(const double* data, int n, double conf, double* out) {
    if (!data || !out) return CL_ERR_NULL;
    if (n < 2) return CL_ERR_SIZE;
    if (conf <= 0.0 || conf >= 1.0) return CL_ERR_RANGE;

    double m = mean(data, n);
    double sem = stddev(data, n, 1) / std::sqrt((double)n);
    double tc = t_quantile(conf, n - 1);
    double hw = tc * sem;

    out[0] = m - hw;
    out[1] = m + hw;
    out[2] = hw;
    out[3] = tc;
    return CL_OK;
}

} // extern "C"