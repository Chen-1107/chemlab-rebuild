/*
 * chemlab_core.h —— 分析化学计算内核（C ABI）
 *
 * 该头文件是 C++ 内核对外暴露的唯一契约。上层有三类调用方：
 *   1. JNI 桥接层（Java 桌面应用）—— 见 core-cpp/jni/chemlab_jni.cpp
 *   2. ctypes 桥接层（Python 分析服务）—— 见 analysis-python/cbridge.py
 *   3. CLI 入口（命令行/脚本/自检）—— 见 core-cpp/src/cli.cpp
 *
 * 约定：
 *   - 所有函数均为纯函数，不持有全局可变状态，可重入。
 *   - 返回值 int 统一表示状态码：0 = 成功，负数 = 失败（见 CL_ERR_*）。
 *   - 数组参数一律为「指针 + 长度」，长度单位为元素个数，而非字节数。
 *   - 调用方负责提供输出缓冲；内核不做任何内存分配后移交所有权。
 */
#ifndef CHEMLAB_CORE_H
#define CHEMLAB_CORE_H

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  define CL_API __declspec(dllexport)
#else
#  define CL_API __attribute__((visibility("default")))
#endif

/* ---- 状态码 ---- */
#define CL_OK            0
#define CL_ERR_NULL     -1   /* 传入空指针 */
#define CL_ERR_SIZE     -2   /* 样本量不足或长度非法 */
#define CL_ERR_RANGE    -3   /* 参数超出定义域 */
#define CL_ERR_DEGEN    -4   /* 退化输入（全等值、零方差等） */
#define CL_ERR_CONVERGE -5   /* 数值迭代未收敛 */
#define CL_ERR_CAPACITY -6   /* 输出缓冲不足 */

/* =======================================================================
 * 版本信息
 * ===================================================================== */
CL_API const char* cl_version(void);

/* =======================================================================
 * 一、描述统计
 * ===================================================================== */

/** 算术平均值。 */
CL_API int cl_mean(const double* data, int n, double* out);

/** 中位数。 */
CL_API int cl_median(const double* data, int n, double* out);

/** 极差（max - min）。 */
CL_API int cl_range(const double* data, int n, double* out);

/**
 * 标准差。
 * @param ddof 自由度修正：0 = 总体标准差(÷n)，1 = 样本标准差(÷n-1)。
 */
CL_API int cl_stddev(const double* data, int n, int ddof, double* out);

/** 相对标准偏差 RSD(%)，基于样本标准差。 */
CL_API int cl_rsd(const double* data, int n, double* out);

/** 方差。ddof 含义同 cl_stddev。 */
CL_API int cl_variance(const double* data, int n, int ddof, double* out);

/** 平均值的标准误差 SEM = s/√n。 */
CL_API int cl_sem(const double* data, int n, double* out);

/**
 * 相对误差(%)：(measured - true) / true * 100。
 */
CL_API int cl_rel_error(double measured, double trueValue, double* out);

/**
 * 一次性输出全部描述统计量，避免多次跨界调用。
 * @param out 至少 8 个 double，顺序为：
 *            [0]=mean [1]=median [2]=stddev_sample [3]=rsd%
 *            [4]=variance_sample [5]=sem [6]=min [7]=max
 */
CL_API int cl_describe(const double* data, int n, double* out);

/* =======================================================================
 * 二、异常值检验
 * ===================================================================== */

/**
 * Grubbs 检验（单侧，检验最大值是否为离群点）。
 * @param alpha  显著性水平，如 0.05
 * @param out    至少 4 个 double：
 *               [0]=G 统计量 [1]=临界值 G_crit
 *               [2]=被检验值 [3]=离群点下标（-1 表示无）
 * @return CL_ERR_SIZE 当 n < 3；CL_ERR_DEGEN 当标准差为 0。
 */
CL_API int cl_grubbs_max(const double* data, int n, double alpha, double* out);

/**
 * Dixon Q 检验（用于 3~10 个小样本）。
 * 自动按 n 选择 Q10/Q11/Q21/Q22 统计量。
 * @param out 至少 3 个 double：[0]=Q [1]=Q_crit [2]=离群点下标(-1 无)
 */
CL_API int cl_dixon_q(const double* data, int n, double alpha, double* out);

/* =======================================================================
 * 三、显著性检验
 * ===================================================================== */

/**
 * 单样本 t 检验（双侧）。
 * @param out 至少 4 个 double：[0]=t [1]=p(双侧) [2]=df [3]=标准误
 */
CL_API int cl_t_test_one(const double* data, int n, double mu0, double* out);

/**
 * 双样本 t 检验。
 * @param pooled 1 = 合并方差（等方差假设）；0 = Welch 校正。
 * @param out 至少 4 个 double：[0]=t [1]=p(双侧) [2]=df [3]=均值差
 */
CL_API int cl_t_test_two(const double* a, int na, const double* b, int nb,
                         int pooled, double* out);

/** F 检验（方差齐性，双侧 p）。out 至少 3 个：[0]=F [1]=p [2]=df */
CL_API int cl_f_test(const double* a, int na, const double* b, int nb, double* out);

/**
 * 均值的置信区间。
 * @param conf 置信水平，如 0.95
 * @param out  至少 4 个：[0]=下限 [1]=上限 [2]=半宽 [3]=t 临界值
 */
CL_API int cl_ci_mean(const double* data, int n, double conf, double* out);

/* =======================================================================
 * 四、回归与标定曲线
 * ===================================================================== */

/**
 * 一元线性最小二乘 y = a + b x。
 * @param out 至少 8 个 double：
 *            [0]=slope b [1]=intercept a [2]=r² [3]=r
 *            [4]=s_y/x 剩余标准差 [5]=s_b 斜率标准误
 *            [6]=s_a 截距标准误 [7]=n
 */
CL_API int cl_linreg(const double* x, const double* y, int n, double* out);

/**
 * 由回归参数计算检出限与定量限。
 * LOD = k1 * s_y/x / b，LOQ = k2 * s_y/x / b（通常 k1=3, k2=10）。
 * @param out 至少 2 个：[0]=LOD [1]=LOQ
 */
CL_API int cl_lod_loq(double slope, double syx, double kLod, double kLoq, double* out);

/**
 * 加权线性回归（权重 w = 1/s²），用于异方差标定数据。
 * @param out 布局与 cl_linreg 相同（[4]~[6] 为加权形式）
 */
CL_API int cl_linreg_weighted(const double* x, const double* y, const double* sd,
                              int n, double* out);

/** 用回归系数反算浓度：x = (y - a) / b。 */
CL_API int cl_calib_inverse(double y, double slope, double intercept, double* out);

/**
 * 回归残差数组，用于残差图。
 * @param out 长度 >= n，out[i] = y[i] - (a + b*x[i])
 */
CL_API int cl_linreg_residuals(const double* x, const double* y, int n,
                               double slope, double intercept, double* out);

/* =======================================================================
 * 五、化学计算
 * ===================================================================== */

/** 由质量、摩尔质量、体积求物质的量浓度：c = m / (M · V)。 */
CL_API int cl_molarity(double massG, double molarMass, double volumeL, double* out);

/** 稀释定律 c1·V1 = c2·V2，求稀释后浓度 c2。 */
CL_API int cl_dilution(double c1, double v1, double v2, double* out);

/** 由浓度与摩尔质量求质量浓度 ρ = c · M (g/L)。 */
CL_API int cl_mass_conc(double molarity, double molarMass, double* out);

/**
 * 直接法配制标准溶液：由目标浓度反算应称取的基准物质质量。
 *
 * 设目标物质的量浓度 c (mol/L)、定容体积 V (L)、基准物质摩尔质量 M (g/mol)、
 * 纯度 w（质量分数，0 < w ≤ 1）、称大样倍数 k（> 0，为减小称量相对误差而放大
 * 称样量与定容体积的倍数），则
 *   n = c·V·k（纯物质物质的量），m_pure = n·M（纯物质质量），
 *   m_sample = m_pure / w（实际应称取的含杂质样品质量），ρ = c·M（质量浓度）。
 *
 * @param out 至少 4 个：[0]=m_sample(g) [1]=m_pure(g) [2]=n(mol) [3]=ρ(g/L)
 */
CL_API int cl_std_prep_mass(double c, double volumeL, double molarMass,
                            double purity, double factor, double* out);

/**
 * 标定法：由称取质量与定容体积计算所配标准溶液的实际浓度。
 *
 * n = m·w / M，c = n / V。
 *
 * @param out 至少 4 个：[0]=c(mol/L) [1]=n(mol) [2]=m_pure(g) [3]=ρ(g/L)
 */
CL_API int cl_std_prep_conc(double massG, double molarMass, double purity,
                            double volumeL, double* out);

/**
 * 强酸溶液 pH：pH = -log10(c)。
 */
CL_API int cl_ph_strong_acid(double c, double* out);

/** 强碱溶液 pH：pOH = -log10(c)，pH = 14 - pOH。 */
CL_API int cl_ph_strong_base(double c, double* out);

/**
 * 一元弱酸 pH（解离常数 Ka，初始浓度 c）。
 * 精确解：Ka = x²/(c - x)  →  x = (-Ka + √(Ka² + 4·Ka·c)) / 2
 * @param out 至少 3 个：[0]=pH [1]=解离度 α [2]=[H+]
 */
CL_API int cl_ph_weak_acid(double c, double ka, double* out);

/** 一元弱碱 pH（Kb）。out 布局同 cl_ph_weak_acid，[1]=解离度。 */
CL_API int cl_ph_weak_base(double c, double kb, double* out);

/**
 * 缓冲溶液 pH（Henderson–Hasselbalch）：pH = pKa + log10(cb / ca)。
 * @param out 至少 2 个：[0]=pH [1]=缓冲比 cb/ca
 */
CL_API int cl_ph_buffer(double ka, double ca, double cb, double* out);

/**
 * 滴定计算：由标准液浓度、体积、化学计量比求待测液浓度。
 * c_sample = c_std · V_std · ratio / V_sample
 * @param ratio 计量比 n(sample)/n(std)
 */
CL_API int cl_titration(double cStd, double vStd, double vSample, double ratio, double* out);

/**
 * 滴定曲线模拟：对每个滴定剂体积解电荷平衡方程，求平衡 pH。
 *
 * @param type 体系类型：0=强碱滴强酸 1=强碱滴弱酸 2=强酸滴强碱 3=强酸滴弱碱
 * @param cAnal 被滴定液浓度 (mol/L)
 * @param vAnalMl 被滴定液体积 (mL)
 * @param cTitr 滴定剂浓度 (mol/L)
 * @param kAnal 弱酸 Ka（type=1）或弱碱 Kb（type=3），其余类型忽略
 * @param volMl 输入：滴定剂体积序列 (mL)，需非负
 * @param ph 输出：与 volMl 一一对应的平衡 pH
 *
 * 一元体系等当点体积 Veq = cAnal·vAnalMl / cTitr。
 */
CL_API int cl_titration_curve(int type, double cAnal, double vAnalMl,
                              double cTitr, double kAnal,
                              int nPoints, const double* volMl, double* ph);

/** Beer–Lambert：A = ε·b·c，由 ε、b、c 求吸光度。 */
CL_API int cl_beer_lambert_abs(double epsilon, double pathCm, double conc, double* out);

/** Beer–Lambert 反算浓度：c = A / (ε·b)。 */
CL_API int cl_beer_lambert_conc(double abs, double epsilon, double pathCm, double* out);

/** 摩尔吸光系数：ε = A / (b·c)。 */
CL_API int cl_beer_lambert_epsilon(double abs, double pathCm, double conc, double* out);

/* =======================================================================
 * 六、误差传递与不确定度合成
 * ===================================================================== */

/**
 * 加减法不确定度合成：y = Σ(±x_i)，u(y) = √Σ u(x_i)²
 * @param vals 各分量值（符号通过 sign 表达，此处只累加）
 * @param uncs 各分量标准不确定度
 * @param out  至少 2 个：[0]=y [1]=u(y)
 */
CL_API int cl_unc_add(const double* vals, const double* uncs, int n, double* out);

/**
 * 乘除法相对不确定度合成：y = Π x_i，u_rel(y) = √Σ (u(x_i)/x_i)²
 * @param out 至少 3 个：[0]=y [1]=u(y) [2]=相对不确定度
 */
CL_API int cl_unc_mul(const double* vals, const double* uncs, int n, double* out);

/**
 * 幂函数 y = x^a 的不确定度：u(y) = |a| · y · u(x)/x
 * @param out 至少 2 个：[0]=y [1]=u(y)
 */
CL_API int cl_unc_power(double x, double ux, double a, double* out);

/**
 * 一般函数式不确定度（数值微分法）。
 * 对 y = f(x1..xn) 以中心差分估计各偏导，再按 u²=Σ(∂f/∂xi · u_i)² 合成。
 * 由调用方提供函数指针，便于扩展任意公式。
 * @param f    目标函数，接收 (x[], n, void* ctx) 返回 f 值
 * @param out  至少 2 个：[0]=f(x) [1]=u(y)
 */
typedef double (*cl_func1)(const double* x, int n, void* ctx);
CL_API int cl_unc_general(cl_func1 f, const double* x, const double* ux, int n,
                          void* ctx, double* out);

/* =======================================================================
 * 七、信号处理（光谱/色谱曲线）
 * ===================================================================== */

/**
 * 滑动平均平滑。
 * @param win 窗口宽度（奇数，>=3）；边界采用收缩窗口处理
 * @param out 长度 >= n
 */
CL_API int cl_moving_average(const double* y, int n, int win, double* out);

/**
 * Savitzky–Golay 平滑（最小二乘多项式拟合卷积）。
 * 相比滑动平均能更好保留峰高与峰形。
 * @param win   窗口宽度（奇数），需满足 win > order
 * @param order 多项式阶数（2~5）
 * @param out   长度 >= n
 */
CL_API int cl_savgol(const double* y, int n, int win, int order, double* out);

/**
 * 线性基线校正（两点法）：以首尾 win 个点的均值确定基线并扣除。
 * @param out 长度 >= n，为扣基线后的信号
 */
CL_API int cl_baseline_linear(const double* y, int n, int win, double* out);

/**
 * 多项式基线（迭代拟合）校正，适用于弯曲基线。
 * @param out 长度 >= n
 */
CL_API int cl_baseline_poly(const double* y, int n, int order, int iters, double* out);

/**
 * 寻峰（局部极大值 + 高度/间距/峰宽过滤）。
 * @param minHeight 相对高度阈值（相对于全局最大值，0~1）
 * @param minDist   相邻峰最小间隔（采样点数，>=1）
 * @param idx       输出缓冲，容量 >= maxPeaks
 * @param heights   输出缓冲，容量 >= maxPeaks，可为 NULL
 * @param nFound    输出实际找到的峰数
 */
CL_API int cl_find_peaks(const double* y, int n, double minHeight, int minDist,
                         int* idx, double* heights, int maxPeaks, int* nFound);

/**
 * 曲线下面积（梯形法）。
 * @param x 横坐标（可为 NULL，此时按等间距 1 处理）
 */
CL_API int cl_trapz(const double* x, const double* y, int n, double* out);

/**
 * 归一化到 [0,1]。
 * @param out 长度 >= n
 */
CL_API int cl_normalize(const double* y, int n, double* out);

/** cl_chrom_peaks 单次最多分析的峰数。 */
#define CL_CHROM_MAX_PEAKS 32

/** cl_chrom_peaks 输出缓冲容量：1 + CL_CHROM_MAX_PEAKS × 8。 */
#define CL_CHROM_OUT_CAP (1 + CL_CHROM_MAX_PEAKS * 8)

/**
 * 色谱峰综合分析：Savitzky–Golay 平滑 → 迭代多项式基线扣除 → 寻峰，
 * 逐峰给出保留时间、峰高、峰面积、面积百分比、理论塔板数、USP 拖尾因子、
 * 半峰宽及与前峰的分离度。
 *
 * @param x 时间轴（严格递增；可为 NULL，此时按等间距索引处理）
 * @param y 检测器信号
 * @param minHeight 相对峰高阈值（相对全局最大信号，0~1，常用 0.02~0.1）
 * @param minDist   相邻峰最小间隔（采样点数）
 * @param smoothWin 平滑窗口（奇数，<=0 表示不平滑）
 * @param out 输出缓冲，容量 >= CL_CHROM_OUT_CAP：
 *            out[0] = 峰数 P；其后每峰 8 个值依次为
 *            [保留时间, 峰高, 峰面积, 面积%, 理论塔板数, 拖尾因子, 半峰宽, 与前峰分离度]
 *            （首个峰的分离度为 NaN）
 */
CL_API int cl_chrom_peaks(const double* x, const double* y, int n,
                          double minHeight, int minDist, int smoothWin,
                          double* out, int outCap);

#ifdef __cplusplus
}
#endif

#endif /* CHEMLAB_CORE_H */