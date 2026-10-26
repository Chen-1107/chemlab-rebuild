/*
 * signal.cpp —— 光谱/色谱信号处理：平滑、基线校正、寻峰、积分。
 */
#include "chemlab_core.h"
#include "internal.h"

using namespace cl;

namespace {

/* 反射填充，避免边界处卷积失真。 */
std::vector<double> reflect_pad(const double* y, int n, int m) {
    std::vector<double> p(n + 2 * m);
    for (int i = 0; i < m; ++i) {
        int src = m - i;
        if (src >= n) src = n - 1;
        p[i] = y[src];
    }
    for (int i = 0; i < n; ++i) p[m + i] = y[i];
    for (int i = 0; i < m; ++i) {
        int src = n - 2 - i;
        if (src < 0) src = 0;
        p[m + n + i] = y[src];
    }
    return p;
}

/*
 * 计算 Savitzky–Golay 平滑系数。
 * 设 A 为 (w × (p+1)) 的范德蒙矩阵，A[i][k] = i^k（i = -m..m）。
 * 中心点拟合值 ŷ₀ = e₀ᵀ(AᵀA)⁻¹Aᵀy，故系数向量 c = A·u，其中 u = (AᵀA)⁻¹e₀。
 */
std::vector<double> savgol_coeffs(int m, int order) {
    int w = 2 * m + 1;
    int p = order + 1;

    // 构造 AᵀA（元素为幂和 S_{k+l}）与单位右端项 e₀
    std::vector<double> M(p * p, 0.0), b(p, 0.0);
    for (int k = 0; k < p; ++k) {
        for (int l = 0; l < p; ++l) {
            double s = 0.0;
            for (int i = -m; i <= m; ++i) s += std::pow((double)i, k + l);
            M[k * p + l] = s;
        }
    }
    b[0] = 1.0;

    if (!solve_linear(M, b, p))
        return std::vector<double>(w, 1.0 / w);  // 退化时退化为均值平滑

    std::vector<double> c(w);
    for (int i = -m; i <= m; ++i) {
        double v = 0.0;
        for (int k = 0; k < p; ++k) v += std::pow((double)i, k) * b[k];
        c[i + m] = v;
    }
    return c;
}

} // namespace

extern "C" {

int cl_moving_average(const double* y, int n, int win, double* out) {
    if (!y || !out) return CL_ERR_NULL;
    if (n <= 0) return CL_ERR_SIZE;
    if (win < 3) return CL_ERR_RANGE;
    if (win % 2 == 0) win += 1;
    int m = win / 2;

    std::vector<double> p = reflect_pad(y, n, m);
    for (int i = 0; i < n; ++i) {
        double s = 0.0;
        for (int k = -m; k <= m; ++k) s += p[i + m + k];
        out[i] = s / win;
    }
    return CL_OK;
}

int cl_savgol(const double* y, int n, int win, int order, double* out) {
    if (!y || !out) return CL_ERR_NULL;
    if (n <= 0) return CL_ERR_SIZE;
    if (order < 1 || order > 6) return CL_ERR_RANGE;
    if (win % 2 == 0) win += 1;
    if (win <= order) return CL_ERR_RANGE;
    if (win > n) win = (n % 2) ? n : n - 1;
    if (win < 3) return CL_ERR_SIZE;

    int m = win / 2;
    std::vector<double> c = savgol_coeffs(m, order);
    std::vector<double> p = reflect_pad(y, n, m);

    for (int i = 0; i < n; ++i) {
        double s = 0.0;
        for (int k = 0; k < win; ++k) s += c[k] * p[i + k];
        out[i] = s;
    }
    return CL_OK;
}

int cl_baseline_linear(const double* y, int n, int win, double* out) {
    if (!y || !out) return CL_ERR_NULL;
    if (n <= 0) return CL_ERR_SIZE;
    if (win < 1) return CL_ERR_RANGE;
    if (win > n / 2) win = std::max(1, n / 2);

    double s0 = 0.0, s1 = 0.0;
    for (int i = 0; i < win; ++i) s0 += y[i];
    for (int i = n - win; i < n; ++i) s1 += y[i];
    double b0 = s0 / win;
    double b1 = s1 / win;

    // 窗口均值代表的是窗口中心处的基线，而非首尾端点，
    // 因此以两个窗口中心为锚点连线，避免基线整体平移。
    int m = (win - 1) / 2;
    double x0 = (double)m;
    double x1 = (double)(n - 1 - m);
    double dx = x1 - x0;

    for (int i = 0; i < n; ++i) {
        double t = (dx > 0.0) ? ((double)i - x0) / dx : 0.0;
        out[i] = y[i] - (b0 + t * (b1 - b0));
    }
    return CL_OK;
}

int cl_baseline_poly(const double* y, int n, int order, int iters, double* out) {
    if (!y || !out) return CL_ERR_NULL;
    if (n <= 0) return CL_ERR_SIZE;
    if (order < 1 || order > 6) return CL_ERR_RANGE;
    if (iters < 1) iters = 1;

    // 鲁棒迭代筛选：每轮只对「残差 ≤ MAD 容差」的点做多项式最小二乘，
    // 点集每轮从原始数据重新筛选（峰点被排除、基线点被保留），
    // 因此拟合线可以逐轮回升贴住真实基线，不存在下拉式压制的锁死偏置。
    const int p = order + 1;
    std::vector<char> inS(n, 1);
    std::vector<double> fit(n, 0.0);

    for (int it = 0; it < iters; ++it) {
        std::vector<double> M(p * p, 0.0), rhs(p, 0.0);
        int cnt = 0;
        for (int i = 0; i < n; ++i) {
            if (!inS[i]) continue;
            double x = (n > 1) ? (double)i / (n - 1) : 0.0;
            for (int k = 0; k < p; ++k) {
                rhs[k] += std::pow(x, k) * y[i];
                for (int l = 0; l < p; ++l)
                    M[k * p + l] += std::pow(x, k + l);
            }
            ++cnt;
        }
        if (cnt < p) break;
        if (!solve_linear(M, rhs, p)) break;

        for (int i = 0; i < n; ++i) {
            double x = (n > 1) ? (double)i / (n - 1) : 0.0;
            double v = 0.0;
            for (int k = 0; k < p; ++k) v += rhs[k] * std::pow(x, k);
            fit[i] = v;
        }

        // MAD 鲁棒容差：中位数绝对残差 × 1.4826 ≈ 噪声标准差。
        // 基线点占多数时中位数由基线主导，峰不会撑大容差。
        std::vector<double> absres;
        absres.reserve(n);
        for (int i = 0; i < n; ++i) {
            if (!inS[i]) continue;
            absres.push_back(std::fabs(y[i] - fit[i]));
        }
        size_t mid = absres.size() / 2;
        std::nth_element(absres.begin(), absres.begin() + mid, absres.end());
        double med = absres[mid];
        if (absres.size() % 2 == 0) {
            std::nth_element(absres.begin(), absres.begin() + mid - 1, absres.end());
            med = 0.5 * (med + absres[mid - 1]);
        }
        double tol = 1.4826 * med;

        std::vector<char> next(n, 0);
        int kept = 0;
        for (int i = 0; i < n; ++i) {
            if (y[i] - fit[i] <= tol) {
                next[i] = 1;
                ++kept;
            }
        }
        if (kept < p) break;                 // 收缩过度，保持当前拟合
        if (next == inS) break;              // 已收敛
        inS = next;
    }

    for (int i = 0; i < n; ++i) out[i] = y[i] - fit[i];
    return CL_OK;
}

int cl_find_peaks(const double* y, int n, double minHeight, int minDist,
                  int* idx, double* heights, int maxPeaks, int* nFound) {
    if (!y || !idx || !nFound) return CL_ERR_NULL;
    if (n < 3) return CL_ERR_SIZE;
    if (maxPeaks <= 0) return CL_ERR_RANGE;
    if (minDist < 1) minDist = 1;

    double hi = y[0];
    for (int i = 1; i < n; ++i) if (y[i] > hi) hi = y[i];
    if (hi <= 0.0) return CL_ERR_DEGEN;
    double thr = minHeight * hi;

    std::vector<int> cand;
    for (int i = 1; i < n - 1; ++i) {
        if (y[i] >= y[i - 1] && y[i] >= y[i + 1] && y[i] >= thr)
            cand.push_back(i);
    }

    // 按高度降序，逐个接纳并抑制邻近峰
    std::sort(cand.begin(), cand.end(),
              [&](int a, int b) { return y[a] > y[b]; });

    std::vector<int> picked;
    for (int c : cand) {
        bool ok = true;
        for (int p : picked) {
            if (std::abs(c - p) < minDist) { ok = false; break; }
        }
        if (ok) {
            picked.push_back(c);
            if ((int)picked.size() >= maxPeaks) break;
        }
    }

    std::sort(picked.begin(), picked.end());
    for (size_t i = 0; i < picked.size(); ++i) {
        idx[i] = picked[i];
        if (heights) heights[i] = y[picked[i]];
    }
    *nFound = (int)picked.size();
    return CL_OK;
}

int cl_trapz(const double* x, const double* y, int n, double* out) {
    if (!y || !out) return CL_ERR_NULL;
    if (n < 2) return CL_ERR_SIZE;
    double s = 0.0;
    for (int i = 1; i < n; ++i) {
        double dx = x ? (x[i] - x[i - 1]) : 1.0;
        s += 0.5 * dx * (y[i] + y[i - 1]);
    }
    *out = s;
    return CL_OK;
}

int cl_normalize(const double* y, int n, double* out) {
    if (!y || !out) return CL_ERR_NULL;
    if (n <= 0) return CL_ERR_SIZE;
    double lo = y[0], hi = y[0];
    for (int i = 1; i < n; ++i) {
        if (y[i] < lo) lo = y[i];
        if (y[i] > hi) hi = y[i];
    }
    double d = hi - lo;
    if (d < 1e-300) {
        for (int i = 0; i < n; ++i) out[i] = 0.0;
        return CL_OK;
    }
    for (int i = 0; i < n; ++i) out[i] = (y[i] - lo) / d;
    return CL_OK;
}

/* ---------------- 色谱峰分析 ---------------- */

/*
 * 色谱峰综合分析流程：Savitzky–Golay 平滑 → 迭代多项式基线扣除 →
 * 局部极大寻峰 → 逐峰求保留时间、峰高、峰面积、面积归一化百分比、
 * 理论塔板数、USP 拖尾因子、半峰宽与相邻峰分离度。
 */

namespace {

const double kNaNChrom = std::nan("");

// 在两点 (x0,y0)-(x1,y1) 之间线性插值求 y=target 对应的 x
double interp_x(double x0, double y0, double x1, double y1, double target) {
    if (y1 == y0) return 0.5 * (x0 + x1);
    return x0 + (target - y0) * (x1 - x0) / (y1 - y0);
}

// 从峰顶 ip 向 dir 方向找 yc 首次降至 frac·hp 以下的索引；
// 先抵达 limit（不含）或数组边界则返回当前索引
int cross_idx(const double* yc, int n, int ip, double frac, int dir, int limit) {
    double thr = frac * yc[ip];
    int j = ip;
    while (j + dir != limit) {
        int k = j + dir;
        if (k < 0) return 0;
        if (k >= n) return n - 1;
        if (yc[k] <= thr) return k;
        j = k;
    }
    return j;
}

// 从峰顶 ip 出发向 dir 方向找 y 首次降至 frac·hp 以下的位置，返回插值交点时间。
// limit 为该方向的搜索边界（不含），穿过即返回边界处时间
double cross_time(const double* yc, const double* x, int n, int ip,
                  double hp, double frac, int dir, int limit) {
    double thr = frac * hp;
    int j = ip;
    while (j + dir != limit && j + dir >= 0 && j + dir < n) {
        int k = j + dir;
        if (yc[k] <= thr) {
            return interp_x(x[j], yc[j], x[k], yc[k], thr);
        }
        j = k;
    }
    return x[j];
}

} // namespace

int cl_chrom_peaks(const double* x, const double* y, int n,
                   double minHeight, int minDist, int smoothWin,
                   double* out, int outCap) {
    if (!y || !out) return CL_ERR_NULL;
    if (n < 5) return CL_ERR_SIZE;
    if (outCap < CL_CHROM_OUT_CAP) return CL_ERR_CAPACITY;
    if (minHeight <= 0.0 || minHeight >= 1.0) return CL_ERR_RANGE;
    if (minDist < 1) minDist = 1;

    // 1) 平滑（窗口 <=0 表示跳过）
    std::vector<double> s(n);
    if (smoothWin >= 3) {
        int rc = cl_savgol(y, n, smoothWin, 2, s.data());
        if (rc != CL_OK) return rc;
    } else {
        std::copy(y, y + n, s.begin());
    }

    // 2) 迭代多项式基线扣除
    std::vector<double> yc(n);
    int rc = cl_baseline_poly(s.data(), n, 2, 8, yc.data());
    if (rc != CL_OK) return rc;

    // 3) 寻峰
    int idx[CL_CHROM_MAX_PEAKS];
    int nPk = 0;
    rc = cl_find_peaks(yc.data(), n, minHeight, minDist,
                       idx, nullptr, CL_CHROM_MAX_PEAKS, &nPk);
    if (rc == CL_ERR_DEGEN) {  // 信号平坦，无峰
        out[0] = 0.0;
        return CL_OK;
    }
    if (rc != CL_OK) return rc;

    std::vector<double> tx(n);
    for (int i = 0; i < n; ++i) tx[i] = x ? x[i] : (double)i;

    // 4) 峰区间边界：相邻峰之间取谷底，首末峰用 5% 峰高阈值或数组边界
    std::vector<int> bLeft(nPk), bRight(nPk);
    for (int p = 0; p < nPk; ++p) {
        if (p == 0) {
            bLeft[0] = cross_idx(yc.data(), n, idx[0], 0.05, -1, -1);
        } else {
            int lo = idx[p - 1], hi = idx[p];
            int v = lo;
            for (int i = lo; i <= hi; ++i) if (yc[i] < yc[v]) v = i;
            bLeft[p] = v;
            bRight[p - 1] = v;
        }
    }
    bRight[nPk - 1] = cross_idx(yc.data(), n, idx[nPk - 1], 0.05, 1, n);

    // 5) 逐峰参数
    struct Peak {
        double tR, height, area, nPlates, tailing, whh, rs;
    };
    std::vector<Peak> pk(nPk);
    double totalArea = 0.0;
    for (int p = 0; p < nPk; ++p) {
        int ip = idx[p];
        double hp = yc[ip];
        pk[p].tR = tx[ip];
        pk[p].height = hp;

        // 面积（梯形积分，区间含边界）
        double area = 0.0;
        for (int i = bLeft[p]; i < bRight[p]; ++i)
            area += 0.5 * (tx[i + 1] - tx[i]) * (yc[i] + yc[i + 1]);
        pk[p].area = area;
        totalArea += area;

        // 半峰宽：50% 高度交点
        double hl = cross_time(yc.data(), tx.data(), n, ip, hp, 0.5, -1, bLeft[p]);
        double hr = cross_time(yc.data(), tx.data(), n, ip, hp, 0.5, 1, bRight[p]);
        pk[p].whh = hr - hl;

        // USP 拖尾因子（10% 峰高）：Tf = b / a
        double a10 = cross_time(yc.data(), tx.data(), n, ip, hp, 0.1, -1, bLeft[p]);
        double b10 = cross_time(yc.data(), tx.data(), n, ip, hp, 0.1, 1, bRight[p]);
        pk[p].tailing = (a10 > 0.0) ? (b10 - pk[p].tR) / (pk[p].tR - a10) : kNaNChrom;

        // 理论塔板数 N = 5.54 (tR / W½)²
        pk[p].nPlates = (pk[p].whh > 0.0 && pk[p].tR > 0.0)
                ? 5.54 * (pk[p].tR / pk[p].whh) * (pk[p].tR / pk[p].whh)
                : kNaNChrom;
        pk[p].rs = kNaNChrom;
    }

    // 6) 相邻峰分离度 Rs = 2Δt/(W1+W2)，W ≈ 1.699·W½（高斯形）
    for (int p = 1; p < nPk; ++p) {
        double w1 = pk[p - 1].whh, w2 = pk[p].whh;
        if (w1 > 0.0 && w2 > 0.0)
            pk[p].rs = 2.0 * (pk[p].tR - pk[p - 1].tR) / (1.699 * (w1 + w2));
    }

    // 7) 写出：out[0]=峰数，其后每峰 8 值
    out[0] = (double)nPk;
    for (int p = 0; p < nPk; ++p) {
        double* o = out + 1 + p * 8;
        o[0] = pk[p].tR;
        o[1] = pk[p].height;
        o[2] = pk[p].area;
        o[3] = (totalArea > 0.0) ? pk[p].area / totalArea * 100.0 : 0.0;
        o[4] = pk[p].nPlates;
        o[5] = pk[p].tailing;
        o[6] = pk[p].whh;
        o[7] = pk[p].rs;
    }
    return CL_OK;
}

} // extern "C"