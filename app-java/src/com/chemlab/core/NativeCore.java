package com.chemlab.core;

/**
 * C++ 计算内核的 JNI 绑定。
 *
 * <p>本类只做「Java ↔ 原生」的类型搬运，不含业务逻辑。内核算法位于
 * {@code core-cpp/}，编译产物为 {@code chemlab_jni.dll}。
 *
 * <p>返回约定：标量失败返回 {@code NaN}，数组失败返回 {@code null}；
 * 数组各元素含义见方法注释。调用方应先经 {@link #isAvailable()} 判断内核是否加载成功。
 */
public final class NativeCore {

    private static final boolean AVAILABLE;
    private static final String LOAD_ERROR;

    static {
        boolean ok = false;
        String err = null;
        try {
            System.loadLibrary("chemlab_jni");
            ok = true;
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        AVAILABLE = ok;
        LOAD_ERROR = err;
    }

    private NativeCore() {
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static String loadError() {
        return LOAD_ERROR;
    }

    /** 内核版本字符串。 */
    public static native String version();

    // ---------------- 描述统计 ----------------

    /** @return [mean, median, stddev(样本), rsd%, variance(样本), sem, min, max] */
    public static native double[] describe(double[] data);

    public static native double mean(double[] data);

    /** @param ddof 0=总体, 1=样本 */
    public static native double stddev(double[] data, int ddof);

    public static native double rsd(double[] data);

    public static native double relativeError(double measured, double trueValue);

    // ---------------- 异常值检验 ----------------

    /** @return [G, G临界值, 被检验值, 离群点下标(-1 表示无)] */
    public static native double[] grubbs(double[] data, double alpha);

    /** @return [Q, Q临界值, 离群点下标(-1 表示无)] */
    public static native double[] dixonQ(double[] data, double alpha);

    // ---------------- 显著性检验 ----------------

    /** @return [t, p(双侧), 自由度, 标准误] */
    public static native double[] tTestOne(double[] data, double mu0);

    /** @return [t, p(双侧), 自由度, 均值差] */
    public static native double[] tTestTwo(double[] a, double[] b, boolean pooled);

    /** @return [F, p, 自由度] */
    public static native double[] fTest(double[] a, double[] b);

    /** @return [下限, 上限, 半宽, t 临界值] */
    public static native double[] confidenceInterval(double[] data, double conf);

    // ---------------- 回归与标定 ----------------

    /** @return [斜率, 截距, r², r, s_y/x, s_b, s_a, n] */
    public static native double[] linearRegression(double[] x, double[] y);

    /** 加权回归（权重 = 1/s²），返回布局同 {@link #linearRegression}。 */
    public static native double[] weightedRegression(double[] x, double[] y, double[] sd);

    /** @return [LOD, LOQ] */
    public static native double[] lodLoq(double slope, double syx, double kLod, double kLoq);

    public static native double[] residuals(double[] x, double[] y, double slope, double intercept);

    public static native double calibInverse(double y, double slope, double intercept);

    // ---------------- 化学计算 ----------------

    public static native double molarity(double massG, double molarMass, double volumeL);

    public static native double dilution(double c1, double v1, double v2);

    public static native double massConc(double molarity, double molarMass);

    /**
     * 直接法配制标准溶液：由目标浓度反算应称取的基准物质质量。
     *
     * @param c 目标物质的量浓度 (mol/L)
     * @param volumeL 定容体积 (L)
     * @param molarMass 基准物质摩尔质量 (g/mol)
     * @param purity 纯度（质量分数，0~1）
     * @param factor 称大样倍数（>0）
     * @return [应称样品质量(g), 纯物质质量(g), 物质的量(mol), 质量浓度(g/L)]；失败返回 null
     */
    public static native double[] stdPrepMass(double c, double volumeL, double molarMass,
                                              double purity, double factor);

    /**
     * 标定法：由称取质量与定容体积计算所配标准溶液的实际浓度。
     *
     * @return [浓度(mol/L), 物质的量(mol), 纯物质质量(g), 质量浓度(g/L)]；失败返回 null
     */
    public static native double[] stdPrepConc(double massG, double molarMass,
                                              double purity, double volumeL);

    public static native double pHStrongAcid(double c);

    public static native double pHStrongBase(double c);

    /** @return [pH, 解离度, [H+]] */
    public static native double[] pHWeakAcid(double c, double ka);

    /** @return [pH, 解离度, [OH-]] */
    public static native double[] pHWeakBase(double c, double kb);

    /** @return [pH, 缓冲比] */
    public static native double[] pHBuffer(double ka, double ca, double cb);

    public static native double titration(double cStd, double vStdMl, double vSampleMl, double ratio);

    /**
     * 滴定曲线模拟：对每个滴定剂体积解电荷平衡方程求平衡 pH。
     *
     * @param type 0=强碱滴强酸 1=强碱滴弱酸 2=强酸滴强碱 3=强酸滴弱碱
     * @param kAnal 弱酸 Ka（type=1）或弱碱 Kb（type=3），其余类型忽略
     * @param volumesMl 滴定剂体积序列 (mL)
     * @return 与 volumesMl 一一对应的 pH 数组；失败返回 null
     */
    public static native double[] titrationCurve(int type, double cAnal, double vAnalMl,
                                                 double cTitr, double kAnal,
                                                 double[] volumesMl);

    public static native double beerLambertAbs(double epsilon, double pathCm, double conc);

    public static native double beerLambertConc(double abs, double epsilon, double pathCm);

    public static native double beerLambertEpsilon(double abs, double pathCm, double conc);

    // ---------------- 误差传递 ----------------

    /** @return [y, u(y)] */
    public static native double[] uncAdd(double[] vals, double[] uncs);

    /** @return [y, u(y), 相对不确定度] */
    public static native double[] uncMul(double[] vals, double[] uncs);

    /** @return [y, u(y)] */
    public static native double[] uncPower(double x, double ux, double a);

    // ---------------- 信号处理 ----------------

    public static native double[] movingAverage(double[] y, int win);

    public static native double[] savgol(double[] y, int win, int order);

    public static native double[] baselineLinear(double[] y, int win);

    public static native double[] baselinePoly(double[] y, int order, int iters);

    /** @return [峰数, idx0, h0, idx1, h1, ...] */
    public static native double[] findPeaks(double[] y, double minHeight, int minDist);

    public static native double trapz(double[] x, double[] y);

    public static native double[] normalize(double[] y);

    /**
     * 色谱峰综合分析：平滑 → 基线扣除 → 寻峰，逐峰给出
     * 保留时间 / 峰高 / 峰面积 / 面积% / 理论塔板数 / USP 拖尾因子 / 半峰宽 / 与前峰分离度。
     *
     * @param minHeight 相对峰高阈值（0~1，常用 0.02~0.1）
     * @param smoothWin 平滑窗口（奇数，<=0 不平滑）
     * @return [峰数, 每峰 8 值...]；首个峰的分离度为 NaN；失败返回 null
     */
    public static native double[] chromPeaks(double[] x, double[] y,
                                             double minHeight, int minDist, int smoothWin);
}